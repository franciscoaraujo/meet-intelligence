import { describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useCaptureSession } from '../useCaptureSession';
import { createFakeMediaEnv } from '../../../test/fakes/media';
import type { ChunkPayload } from '../segmentUploader';
import type { ChunkTranscriptionResponse } from '../../../types';

const speechFrame = () => new Float32Array(320).fill(0.1);
const silenceFrame = () => new Float32Array(320);

const setup = (envOpts: { mic?: string; tab?: string } = {}) => {
  const env = createFakeMediaEnv(envOpts);
  const sent: ChunkPayload[] = [];
  const sendChunk = vi.fn(async (p: ChunkPayload): Promise<ChunkTranscriptionResponse> => {
    sent.push(p);
    return {
      status: 'TRANSCRIBED',
      duplicate: false,
      segments: [{ id: `srv-${sent.length}`, meetingId: 'm', originalText: 'texto', startTimeMs: p.captureStartMs, endTimeMs: p.captureEndMs }],
    };
  });
  const onTranscribed = vi.fn();
  const hook = renderHook(() =>
    useCaptureSession({
      sendChunk,
      onTranscribed,
      onWebSpeechText: vi.fn(),
      micSpeakerLabel: () => 'Francisco',
      depsFactory: () => env.deps,
    }),
  );
  /** Emite fala seguida de 1,3 s de silêncio no contexto de áudio indicado. */
  const speak = (ctxIndex: number, speechMs: number) => {
    const port = env.contexts[ctxIndex].port;
    for (let i = 0; i < speechMs / 20; i++) port.emit(speechFrame());
    for (let i = 0; i < 1300 / 20; i++) port.emit(silenceFrame());
  };
  return { env, sent, sendChunk, onTranscribed, hook, speak };
};

describe('useCaptureSession', () => {
  it('CA-01/CA-08: fala no microfone vira POST /chunk com audioSource MIC e horários crescentes', async () => {
    const { hook, speak, sent, onTranscribed } = setup();

    await act(() => hook.result.current.startRecording());
    expect(hook.result.current.mic.state).toBe('ATIVA_SILENCIO');

    await act(async () => {
      speak(0, 1000);
      speak(0, 600);
      await new Promise((r) => setTimeout(r, 0));
    });

    expect(sent).toHaveLength(2);
    expect(sent.every((p) => p.audioSource === 'MIC')).toBe(true);
    expect(sent[0].speakerLabel).toBe('Francisco');
    expect(sent[1].captureStartMs).toBeGreaterThan(sent[0].captureEndMs - 1);
    expect(new Set(sent.map((p) => p.clientSegmentId)).size).toBe(2);
    expect(onTranscribed).toHaveBeenCalledTimes(2);
  });

  it('aba e microfone são fontes independentes, cada uma com sua origem', async () => {
    const { hook, speak, sent } = setup();

    await act(() => hook.result.current.startRecording());
    await act(() => hook.result.current.startTab());
    await act(async () => {
      speak(1, 800); // contexto 1 = aba
      await new Promise((r) => setTimeout(r, 0));
    });

    expect(sent.map((p) => p.audioSource)).toEqual(['TAB']);
    expect(hook.result.current.tab.state).toBe('ATIVA_SILENCIO');
    expect(hook.result.current.mic.state).toBe('ATIVA_SILENCIO');
  });

  it('spec 008 RF-18: speaking da aba acompanha o VAD (fala aberta) e volta a false no silêncio', async () => {
    const { hook, env } = setup();
    await act(() => hook.result.current.startRecording());
    await act(() => hook.result.current.startTab());
    expect(hook.result.current.tab.speaking).toBe(false);

    await act(async () => {
      for (let i = 0; i < 25; i++) env.contexts[1].port.emit(speechFrame());
    });
    expect(hook.result.current.tab.speaking).toBe(true);
    expect(hook.result.current.mic.speaking).toBe(false);

    await act(async () => {
      for (let i = 0; i < 1300 / 20; i++) env.contexts[1].port.emit(silenceFrame());
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(hook.result.current.tab.speaking).toBe(false);
  });

  it('spec 008 RF-20: microfone desligado não abre o microfone; só o áudio do computador é capturado', async () => {
    const { hook, env, speak, sent } = setup();
    await act(() => hook.result.current.setMicMode('OFF'));
    await act(() => hook.result.current.startRecording());
    await act(() => hook.result.current.startTab());

    expect(env.getUserMedia).not.toHaveBeenCalled();
    expect(hook.result.current.mic.state).toBe('INATIVA');
    await act(async () => {
      speak(0, 800); // único contexto de áudio = computador
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(sent.map((p) => p.audioSource)).toEqual(['TAB']);
  });

  it('spec 008 RF-20: trocar para desligado durante a captura fecha só o microfone; voltar religa', async () => {
    const { hook, env } = setup();
    await act(() => hook.result.current.startRecording());
    await act(() => hook.result.current.startTab());
    expect(hook.result.current.mic.state).toBe('ATIVA_SILENCIO');

    await act(() => hook.result.current.setMicMode('OFF'));
    expect(hook.result.current.mic.state).toBe('INATIVA');
    expect(env.streams[0].tracks.every((t) => t.readyState === 'ended')).toBe(true); // trilha do microfone
    expect(hook.result.current.tab.state).toBe('ATIVA_SILENCIO');
    expect(hook.result.current.isRecording).toBe(true);

    await act(() => hook.result.current.setMicMode('BACKEND'));
    expect(env.getUserMedia).toHaveBeenCalledTimes(2);
    expect(hook.result.current.mic.state).toBe('ATIVA_SILENCIO');
  });

  describe('spec 008 RF-20: microfone bloqueado no modo entrevista', () => {
    const setupBlocked = (initialBlocked: boolean) => {
      const env = createFakeMediaEnv();
      const hook = renderHook(
        ({ blocked }) =>
          useCaptureSession({
            sendChunk: vi.fn(async () => ({ status: 'NO_SPEECH', duplicate: false, segments: [] }) as ChunkTranscriptionResponse),
            onTranscribed: vi.fn(),
            onWebSpeechText: vi.fn(),
            micSpeakerLabel: () => 'Francisco',
            depsFactory: () => env.deps,
            micBlocked: blocked,
          }),
        { initialProps: { blocked: initialBlocked } },
      );
      return { env, hook };
    };

    it('bloqueado: iniciar a captura não abre o microfone, nem escolhendo outro modo no seletor', async () => {
      const { env, hook } = setupBlocked(true);
      await act(() => hook.result.current.startRecording());
      await act(() => hook.result.current.setMicMode('BACKEND'));
      await act(() => hook.result.current.setMicMode('WEB_SPEECH'));
      await act(() => hook.result.current.startTab());

      expect(env.getUserMedia).not.toHaveBeenCalled();
      expect(hook.result.current.mic.state).toBe('INATIVA');
      expect(hook.result.current.tab.state).toBe('ATIVA_SILENCIO');
    });

    it('entrar no modo entrevista com a captura rodando desliga o microfone na hora; sair religa', async () => {
      const { env, hook } = setupBlocked(false);
      await act(() => hook.result.current.startRecording());
      await act(() => hook.result.current.startTab());
      expect(hook.result.current.mic.state).toBe('ATIVA_SILENCIO');

      await act(async () => {
        hook.rerender({ blocked: true });
        await new Promise((r) => setTimeout(r, 0));
      });
      expect(hook.result.current.mic.state).toBe('INATIVA');
      expect(env.streams[0].tracks.every((t) => t.readyState === 'ended')).toBe(true);
      expect(hook.result.current.tab.state).toBe('ATIVA_SILENCIO');

      await act(async () => {
        hook.rerender({ blocked: false });
        await new Promise((r) => setTimeout(r, 0));
      });
      expect(env.getUserMedia).toHaveBeenCalledTimes(2);
      expect(hook.result.current.mic.state).toBe('ATIVA_SILENCIO');
    });
  });

  it('CA-06: parar envia a fala em andamento e encerra todas as trilhas', async () => {
    const { hook, env, sent } = setup();
    await act(() => hook.result.current.startRecording());

    await act(async () => {
      for (let i = 0; i < 40; i++) env.contexts[0].port.emit(speechFrame()); // fala sem pausa
    });
    expect(sent).toHaveLength(0);

    await act(() => hook.result.current.stopRecording());

    expect(sent).toHaveLength(1);
    expect(env.streams.flatMap((s) => s.tracks).every((t) => t.readyState === 'ended')).toBe(true);
    expect(hook.result.current.mic.state).toBe('INATIVA');
  });

  it('CA-04: microfone negado não impede a captura da aba', async () => {
    const { hook } = setup({ mic: 'NotAllowedError' });

    await act(() => hook.result.current.startRecording());
    await act(() => hook.result.current.startTab());

    expect(hook.result.current.mic.state).toBe('PERMISSAO_NEGADA');
    expect(hook.result.current.tab.state).toBe('ATIVA_SILENCIO');
  });

  it('falha de transcrição do backend aparece como erro da fonte', async () => {
    const { hook, speak, sendChunk } = setup();
    sendChunk.mockResolvedValueOnce({ status: 'FAILED', failureReason: 'NO_PROVIDER_CONFIGURED', segments: [], duplicate: false });

    await act(() => hook.result.current.startRecording());
    await act(async () => {
      speak(0, 800);
      await new Promise((r) => setTimeout(r, 0));
    });

    expect(hook.result.current.mic.lastError).toContain('Configurações');
  });

  it('modo Web Speech mantém o fluxo anterior (texto via callback, sem POST /chunk)', async () => {
    const env = createFakeMediaEnv();
    const sendChunk = vi.fn();
    const onWebSpeechText = vi.fn();
    type FakeRec = { onresult: ((e: unknown) => void) | null; onstart: (() => void) | null; start: () => void; stop: () => void };
    let recognition: FakeRec | null = null;
    const hook = renderHook(() =>
      useCaptureSession({
        sendChunk,
        onTranscribed: vi.fn(),
        onWebSpeechText,
        micSpeakerLabel: () => 'Francisco',
        depsFactory: () => env.deps,
        createRecognition: () => {
          const rec: FakeRec = { onresult: null, onstart: null, start() { rec.onstart?.(); }, stop() {} };
          recognition = rec;
          return recognition as never;
        },
      }),
    );

    await act(() => hook.result.current.setMicMode('WEB_SPEECH'));
    await act(() => hook.result.current.startRecording());
    act(() => recognition!.onresult!({ resultIndex: 0, results: [{ isFinal: false, 0: { transcript: 'pergunta pendente' } }] }));
    await act(() => hook.result.current.stopRecording());

    expect(onWebSpeechText).toHaveBeenCalledWith('pergunta pendente');
    expect(sendChunk).not.toHaveBeenCalled();
    expect(env.getUserMedia).not.toHaveBeenCalled();
  });
});
