import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChunkTranscriptionResponse, TranscriptSegment } from '../../types';
import { describeChunkFailure } from '../transcription/chunkStatus';
import { AudioCaptureSource, browserAudioDeps, SAMPLE_RATE, type AudioSourceDeps } from './audioSource';
import { createSessionClock, type SessionClock } from './sessionClock';
import { SegmentUploader, type ChunkPayload } from './segmentUploader';
import { emptyStats, type MicMode, type SourceKind, type SourceState, type UploadStats } from './types';
import { VadSegmenter } from './vadSegmenter';
import { browserSpeechRecognition, WebSpeechMicSource, type SpeechRecognitionLike } from './webSpeechMicSource';

export interface SourceView {
  state: SourceState;
  detail?: string;
  /** Nível de áudio 0-100 (atualizado no máximo a 10 Hz). */
  level: number;
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
  /** Substituíveis nos testes. */
  depsFactory?: (clock: SessionClock) => AudioSourceDeps;
  createRecognition?: () => SpeechRecognitionLike | null;
}

const initialView = (): SourceView => ({ state: 'INATIVA', level: 0, stats: emptyStats(), lastError: null });
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
      onState: (state, detail) => setView(kind, { state, detail, ...(state === 'INATIVA' ? { level: 0 } : {}) }),
      onFrame: (frame, startMs) => {
        const level = vad.push(frame, startMs);
        const speaking = vad.speaking;
        if (speaking !== speakingRef.current[kind]) {
          speakingRef.current[kind] = speaking;
          source.markSpeaking(speaking);
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
    pipeline.vad.flush(); // envia a fala em andamento (RN-02)
    pipeline.source.stop();
    await pipeline.uploader.flush(STOP_FLUSH_TIMEOUT_MS);
  };

  const startTab = useCallback(async () => {
    await stopPipeline('TAB');
    setView('TAB', { lastError: null });
    await buildPipeline('TAB').source.start();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const stopTab = useCallback(() => stopPipeline('TAB'), []);

  const startRecording = useCallback(async () => {
    setIsRecording(true);
    setView('MIC', { lastError: null });
    if (micMode === 'WEB_SPEECH') {
      const ws = new WebSpeechMicSource(
        {
          onState: (state, detail) => setView('MIC', { state, detail }),
          onInterim: setInterim,
          onFinalText: (text) => optsRef.current.onWebSpeechText(text),
        },
        { createRecognition: optsRef.current.createRecognition ?? browserSpeechRecognition },
      );
      webSpeechRef.current = ws;
      ws.start();
      return;
    }
    await buildPipeline('MIC').source.start();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [micMode]);

  /** Para todas as fontes; envia os trechos pendentes antes (até 5 s). */
  const stopRecording = useCallback(async () => {
    setIsRecording(false);
    const ws = webSpeechRef.current;
    webSpeechRef.current = null;
    if (ws) {
      const pending = ws.stop();
      if (pending) optsRef.current.onWebSpeechText(pending);
    }
    await Promise.all([stopPipeline('MIC'), stopPipeline('TAB')]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    setMicMode,
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
