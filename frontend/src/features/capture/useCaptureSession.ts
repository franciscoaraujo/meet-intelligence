import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChunkTranscriptionResponse, TranscriptSegment } from '../../types';
import { describeChunkFailure } from '../transcription/chunkStatus';
import { AudioCaptureSource, browserAudioDeps, SAMPLE_RATE, type AudioSourceDeps } from './audioSource';
import { createSessionClock, type SessionClock } from './sessionClock';
import { SegmentUploader, type ChunkPayload } from './segmentUploader';
import { emptyStats, type MicMode, type SourceKind, type SourceState, type UploadStats, type CaptureSurface } from './types';
import { VadSegmenter } from './vadSegmenter';
import { browserSpeechRecognition, WebSpeechMicSource, type SpeechRecognitionLike } from './webSpeechMicSource';

export interface SourceView {
  state: SourceState;
  detail?: string;
  /** Nível de áudio 0-100 (atualizado no máximo a 10 Hz). */
  level: number;
  /** O VAD está dentro de um trecho de fala nesta fonte (spec 008, RF-18). */
  speaking: boolean;
  /** Fonte do computador: aba, tela inteira ou janela escolhida no seletor (spec 009). */
  surface?: CaptureSurface;
  stats: UploadStats;
  /** Última falha de transcrição/envio a mostrar, ou null. */
  lastError: string | null;
}

export interface CaptureSessionOptions {
  sendChunk: (payload: ChunkPayload) => Promise<ChunkTranscriptionResponse>;
  onTranscribed: (segments: TranscriptSegment[]) => void;
  /** Frase consolidada no modo Web Speech (vai para /live-text). */
  onWebSpeechText: (text: string) => void;
  micSpeakerLabel: () => string;
  /**
   * Microfone bloqueado (spec 008, RF-20: modo entrevista capta só o participante remoto).
   * Enquanto true, o microfone não liga em nenhum modo e é desligado na hora se estiver ligado.
   */
  micBlocked?: boolean;
  /** Idioma da entrevista (spec 011), usado pelo microfone em modo navegador. */
  language?: string;
  /** Substituíveis nos testes. */
  depsFactory?: (clock: SessionClock) => AudioSourceDeps;
  createRecognition?: () => SpeechRecognitionLike | null;
}

const initialView = (): SourceView => ({ state: 'INATIVA', level: 0, speaking: false, stats: emptyStats(), lastError: null });
const LEVEL_INTERVAL_MS = 100;
const STOP_FLUSH_TIMEOUT_MS = 5000;

/** Orquestra a sessão de captura: aba e microfone como fontes independentes (spec 001). */
export const useCaptureSession = (options: CaptureSessionOptions) => {
  const optsRef = useRef(options);
  optsRef.current = options;

  const [tab, setTab] = useState<SourceView>(initialView);
  const [mic, setMic] = useState<SourceView>(initialView);
  const [micMode, setMicMode] = useState<MicMode>('BACKEND');
  const [interim, setInterim] = useState('');
  const [isRecording, setIsRecording] = useState(false);
  const isRecordingRef = useRef(false);
  isRecordingRef.current = isRecording;
  const [overlapWarning, setOverlapWarning] = useState(false);

  const clockRef = useRef<SessionClock | null>(null);
  const pipelines = useRef<Partial<Record<SourceKind, { source: AudioCaptureSource; vad: VadSegmenter; uploader: SegmentUploader }>>>({});
  const webSpeechRef = useRef<WebSpeechMicSource | null>(null);
  const speakingRef = useRef<Record<SourceKind, boolean>>({ TAB: false, MIC: false });
  const lastLevelAt = useRef<Record<SourceKind, number>>({ TAB: 0, MIC: 0 });

  const setView = useCallback((kind: SourceKind, patch: Partial<SourceView>) => {
    (kind === 'TAB' ? setTab : setMic)((prev) => ({ ...prev, ...patch }));
  }, []);

  const clock = () => {
    if (!clockRef.current) clockRef.current = createSessionClock();
    return clockRef.current;
  };

  const buildPipeline = (kind: SourceKind) => {
    const sessionClock = clock();
    const deps = (optsRef.current.depsFactory ?? browserAudioDeps)(sessionClock);

    const uploader = new SegmentUploader({
      send: (payload) => optsRef.current.sendChunk(payload),
      speakerLabel: kind === 'MIC' ? () => optsRef.current.micSpeakerLabel() : undefined,
      onStats: (stats) => setView(kind, { stats }),
      onResponse: (_segment, response) => {
        setView(kind, { lastError: describeChunkFailure(response) });
        if (response.segments?.length) optsRef.current.onTranscribed(response.segments);
      },
      onSendError: (_segment, message) => setView(kind, { lastError: message }),
    });

    const vad = new VadSegmenter({
      source: kind,
      sampleRate: SAMPLE_RATE,
      onSegment: (segment) => uploader.enqueue(segment),
    });

    const source = new AudioCaptureSource(kind, deps, {
      onState: (state, detail) => setView(kind, { state, detail, ...(state === 'INATIVA' ? { level: 0, speaking: false } : {}) }),
      onSurface: (surface) => setView(kind, { surface }),
      onFrame: (frame, startMs) => {
        const level = vad.push(frame, startMs);
        const speaking = vad.speaking;
        if (speaking !== speakingRef.current[kind]) {
          speakingRef.current[kind] = speaking;
          source.markSpeaking(speaking);
          setView(kind, { speaking });
          // Fala simultânea nas duas fontes sugere eco do alto-falante no microfone (RF-10)
          if (speakingRef.current.TAB && speakingRef.current.MIC) setOverlapWarning(true);
        }
        const now = sessionClock.now();
        if (now - lastLevelAt.current[kind] >= LEVEL_INTERVAL_MS) {
          lastLevelAt.current[kind] = now;
          setView(kind, { level: Math.min(100, Math.round(level * 350)) });
        }
      },
    });

    const pipeline = { source, vad, uploader };
    pipelines.current[kind] = pipeline;
    return pipeline;
  };

  const stopPipeline = async (kind: SourceKind) => {
    const pipeline = pipelines.current[kind];
    if (!pipeline) return;
    delete pipelines.current[kind];
    speakingRef.current[kind] = false;
    setView(kind, { speaking: false });
    pipeline.vad.flush(); // envia a fala em andamento (RN-02)
    pipeline.source.stop();
    await pipeline.uploader.flush(STOP_FLUSH_TIMEOUT_MS);
  };

  /**
   * Abre o compartilhamento de áudio. Devolve true se a fonte ficou ativa; false se a pessoa cancelou,
   * negou ou a fonte não tem áudio (spec 020, RF-01: nesse caso a sessão não é iniciada).
   */
  const startTab = useCallback(async (): Promise<boolean> => {
    await stopPipeline('TAB');
    setView('TAB', { lastError: null });
    const { source } = buildPipeline('TAB');
    await source.start();
    const active = source.currentState === 'ATIVA_SILENCIO' || source.currentState === 'ATIVA_FALANDO';
    if (!active && pipelines.current.TAB?.source === source) delete pipelines.current.TAB;
    return active;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const stopTab = useCallback(() => stopPipeline('TAB'), []);

  /** Liga o microfone no modo indicado (OFF ou microfone bloqueado não ligam nada). */
  const startMic = async (mode: MicMode) => {
    setView('MIC', { lastError: null });
    if (mode === 'OFF' || optsRef.current.micBlocked) return;
    if (mode === 'WEB_SPEECH') {
      const ws = new WebSpeechMicSource(
        {
          onState: (state, detail) => setView('MIC', { state, detail }),
          onInterim: setInterim,
          onFinalText: (text) => optsRef.current.onWebSpeechText(text),
        },
        {
          createRecognition: optsRef.current.createRecognition ?? browserSpeechRecognition,
          language: optsRef.current.language ?? 'pt-BR',
        },
      );
      webSpeechRef.current = ws;
      ws.start();
      return;
    }
    await buildPipeline('MIC').source.start();
  };

  /** Desliga só o microfone (Web Speech ou pipeline), enviando o que estiver pendente. */
  const stopMic = async () => {
    const ws = webSpeechRef.current;
    webSpeechRef.current = null;
    if (ws) {
      const pending = ws.stop();
      if (pending) optsRef.current.onWebSpeechText(pending);
    }
    await stopPipeline('MIC');
  };

  /**
   * Inicia a sessão (cronômetro, "Encerrar"). Com withMic=false a sessão começa sem ligar o microfone:
   * é o caso de quem começou pelo "Áudio do computador" (spec 020, RF-02).
   */
  const startRecording = useCallback(async (opts?: { withMic?: boolean }) => {
    setIsRecording(true);
    if (opts?.withMic === false) return;
    await startMic(micMode);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [micMode]);

  /**
   * Troca o modo do microfone; com a captura em andamento, desliga o microfone atual e liga o novo
   * (spec 008, RF-20: entrar no modo entrevista desliga o microfone sem parar o áudio do computador).
   */
  const changeMicMode = useCallback(async (mode: MicMode) => {
    setMicMode(mode);
    if (!isRecordingRef.current) return;
    await stopMic();
    await startMic(mode);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Para todas as fontes; envia os trechos pendentes antes (até 5 s). */
  const stopRecording = useCallback(async () => {
    setIsRecording(false);
    await Promise.all([stopMic(), stopPipeline('TAB')]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // RF-20: bloquear/desbloquear o microfone durante a captura desliga/religa só o microfone
  const micBlocked = !!options.micBlocked;
  const micModeRef = useRef(micMode);
  micModeRef.current = micMode;
  useEffect(() => {
    if (!isRecordingRef.current) return;
    if (micBlocked) {
      void stopMic();
    } else {
      void startMic(micModeRef.current);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [micBlocked]);

  // Libera dispositivos ao sair da página
  useEffect(() => {
    return () => {
      webSpeechRef.current?.stop();
      Object.values(pipelines.current).forEach((p) => p?.source.stop());
      pipelines.current = {};
    };
  }, []);

  return {
    tab,
    mic,
    micMode,
    setMicMode: changeMicMode,
    interim,
    isRecording,
    overlapWarning,
    dismissOverlapWarning: () => setOverlapWarning(false),
    startTab,
    stopTab,
    startRecording,
    stopRecording,
  };
};
