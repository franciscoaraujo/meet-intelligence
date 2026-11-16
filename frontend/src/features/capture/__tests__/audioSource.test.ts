import { describe, expect, it, vi } from 'vitest';
import { AudioCaptureSource, noAudioHint, stateForMediaError } from '../audioSource';
import { createFakeMediaEnv } from '../../../test/fakes/media';
import type { SourceState } from '../types';

const setup = (kind: 'TAB' | 'MIC', env = createFakeMediaEnv()) => {
  const states: Array<[SourceState, string | undefined]> = [];
  const frames: Array<[number, number]> = [];
  const surfaces: string[] = [];
  const source = new AudioCaptureSource(kind, env.deps, {
    onFrame: (f, t) => frames.push([f.length, t]),
    onState: (s, d) => states.push([s, d]),
    onSurface: (s) => surfaces.push(s),
  });
  return { source, states, frames, surfaces, env };
};

describe('AudioCaptureSource', () => {
  it('CA-01: microfone com permissão fica ativo e o áudio do getUserMedia é consumido (frames chegam)', async () => {
    const { source, states, frames, env } = setup('MIC');

    await source.start();
    env.contexts[0].port.emit(new Float32Array(320));
    env.contexts[0].port.emit(new Float32Array(320));

    expect(env.getUserMedia).toHaveBeenCalledWith({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    expect(states.map((s) => s[0])).toEqual(['SOLICITANDO_PERMISSAO', 'ATIVA_SILENCIO']);
    expect(env.contexts[0].sampleRate).toBe(16000);
    expect(frames).toEqual([[320, 0], [320, 20]]);
  });

  it('CA-04: permissão de microfone negada → PERMISSAO_NEGADA', async () => {
    const { source, states } = setup('MIC', createFakeMediaEnv({ mic: 'NotAllowedError' }));

    await source.start();

    expect(states.at(-1)?.[0]).toBe('PERMISSAO_NEGADA');
  });

  it('microfone inexistente → DISPOSITIVO_INDISPONIVEL; em uso → ERRO', () => {
    expect(stateForMediaError('MIC', { name: 'NotFoundError' }).state).toBe('DISPOSITIVO_INDISPONIVEL');
    expect(stateForMediaError('MIC', { name: 'NotReadableError' }).state).toBe('ERRO');
    expect(stateForMediaError('TAB', { name: 'NotAllowedError' }).state).toBe('INATIVA');
  });

  it('CA-05: aba compartilhada sem áudio → SEM_AUDIO, sem alert, e trilhas liberadas', async () => {
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    const { source, states, env } = setup('TAB', createFakeMediaEnv({ tab: 'sem-audio' }));

    await source.start();

    expect(states.at(-1)?.[0]).toBe('SEM_AUDIO');
    expect(alertSpy).not.toHaveBeenCalled();
    expect(env.streams[0].tracks.every((t) => t.readyState === 'ended')).toBe(true);
  });

  it('CA-02: fim da trilha de áudio da aba → INTERROMPIDA, AudioContext fechado e trilhas paradas', async () => {
    const { source, states, env } = setup('TAB');
    await source.start();

    const audioTrack = env.streams[0].tracks.find((t) => t.kind === 'audio')!;
    audioTrack.end();

    expect(states.at(-1)?.[0]).toBe('INTERROMPIDA');
    expect(env.contexts[0].closed).toBe(true);
    expect(env.streams[0].tracks.every((t) => t.readyState === 'ended')).toBe(true);
  });

  it('RF-06: microfone desconectado tenta reconectar e volta a ficar ativo', async () => {
    const env = createFakeMediaEnv() as ReturnType<typeof createFakeMediaEnv> & { setMic: (v: string) => void };
    const { source, states } = setup('MIC', env);
    await source.start();

    env.streams[0].tracks[0].end();
    expect(states.at(-1)?.[0]).toBe('RECUPERANDO');
    expect(env.timers[0].ms).toBe(1000);

    await env.runTimers();
    expect(source.currentState).toBe('ATIVA_SILENCIO');
    expect(env.getUserMedia).toHaveBeenCalledTimes(2);
  });

  it('RF-06: após 3 tentativas sem microfone → DISPOSITIVO_INDISPONIVEL', async () => {
    const env = createFakeMediaEnv() as ReturnType<typeof createFakeMediaEnv> & { setMic: (v: string) => void };
    const { source } = setup('MIC', env);
    await source.start();

    env.setMic('NotFoundError');
    env.streams[0].tracks[0].end();
    for (let i = 0; i < 3; i++) await env.runTimers();

    expect(source.currentState).toBe('DISPOSITIVO_INDISPONIVEL');
    expect(env.getUserMedia).toHaveBeenCalledTimes(4);
  });

  it('RNF-03: stop() deixa todas as trilhas encerradas, fecha o contexto e não tenta recuperar', async () => {
    const { source, env } = setup('MIC');
    await source.start();

    source.stop();

    expect(env.streams[0].tracks.every((t) => t.readyState === 'ended')).toBe(true);
    expect(env.contexts[0].closed).toBe(true);
    expect(env.timers).toHaveLength(0);
    expect(source.currentState).toBe('INATIVA');
  });

  describe('qualquer fonte do computador (spec 009)', () => {
    it('CA-01: pede o áudio do sistema e esconde a aba do próprio app do seletor', async () => {
      const { source, env } = setup('TAB');
      await source.start();
      expect(env.getDisplayMedia).toHaveBeenCalledWith(
        expect.objectContaining({ video: true, systemAudio: 'include', selfBrowserSurface: 'exclude', surfaceSwitching: 'include' }),
      );
    });

    it('CA-02: janela sem áudio → SEM_AUDIO com a orientação de janela', async () => {
      const { source, states, surfaces } = setup('TAB', createFakeMediaEnv({ tab: 'sem-audio', surface: 'window' }));
      await source.start();
      expect(surfaces).toEqual(['window']);
      expect(states[states.length - 1]).toEqual(['SEM_AUDIO', noAudioHint('window')]);
      expect(noAudioHint('window')).toContain('Janelas não compartilham áudio');
    });

    it('CA-03: tela inteira com áudio do sistema → ativa e informa a superfície', async () => {
      const { source, states, surfaces } = setup('TAB', createFakeMediaEnv({ surface: 'monitor' }));
      await source.start();
      expect(surfaces).toEqual(['monitor']);
      expect(states[states.length - 1][0]).toBe('ATIVA_SILENCIO');
    });

    it('CA-04: tela inteira sem áudio → orienta a marcar "Compartilhar áudio do sistema"', async () => {
      const { source, states } = setup('TAB', createFakeMediaEnv({ tab: 'sem-audio', surface: 'monitor' }));
      await source.start();
      expect(states[states.length - 1][1]).toContain('Compartilhar áudio do sistema');
    });

    it('aba sem áudio → orienta a marcar "Compartilhar áudio da guia"; o microfone não informa superfície', async () => {
      const tab = setup('TAB', createFakeMediaEnv({ tab: 'sem-audio', surface: 'browser' }));
      await tab.source.start();
      expect(tab.states[tab.states.length - 1][1]).toContain('Compartilhar áudio da guia');

      const mic = setup('MIC');
      await mic.source.start();
      expect(mic.surfaces).toEqual([]);
    });
  });
});
