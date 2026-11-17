import { describe, expect, it, vi } from 'vitest';
import { createSessionActions, type SessionDeps } from './captureSession';

const fakeDeps = (over: Partial<{ shared: boolean; recording: boolean; tab: boolean; status: string; interview: boolean }> = {}) => {
  const state = { recording: over.recording ?? false, tab: over.tab ?? false, status: over.status ?? 'SCHEDULED' };
  const calls: string[] = [];
  const deps: SessionDeps = {
    startTab: vi.fn(async () => {
      calls.push('startTab');
      if (over.shared === false) return false;
      state.tab = true;
      return true;
    }),
    stopTab: vi.fn(async () => {
      calls.push('stopTab');
      state.tab = false;
    }),
    startRecording: vi.fn(async (opts: { withMic: boolean }) => {
      calls.push(`startRecording(mic=${opts.withMic})`);
      state.recording = true;
    }),
    stopRecording: vi.fn(async () => {
      calls.push('stopRecording');
      state.recording = false;
      state.tab = false;
    }),
    isRecording: () => state.recording,
    tabOn: () => state.tab,
    meetingStatus: () => state.status,
    interviewMode: () => over.interview ?? false,
    markStatus: vi.fn(async (s) => {
      calls.push(`status=${s}`);
      state.status = s;
    }),
    triggerAnalysis: vi.fn(() => calls.push('analysis')),
  };
  return { deps, calls, state, session: createSessionActions(deps) };
};

describe('ciclo da sessão de captura (spec 020)', () => {
  it('CA-01: "Iniciar" no modo entrevista compartilha o áudio, marca em andamento e inicia sem microfone', async () => {
    const { session, calls } = fakeDeps({ interview: true });
    await session.toggle();
    expect(calls).toEqual(['startTab', 'status=IN_PROGRESS', 'startRecording(mic=false)']);
  });

  it('RF-01: fora do modo entrevista, "Iniciar" também liga o microfone', async () => {
    const { session, calls } = fakeDeps();
    await session.toggle();
    expect(calls).toContain('startRecording(mic=true)');
  });

  it('CA-01: compartilhamento cancelado não inicia nada (status, cronômetro)', async () => {
    const { session, calls, state } = fakeDeps({ shared: false });
    expect(await session.start()).toBe(false);
    expect(calls).toEqual(['startTab']);
    expect(state.status).toBe('SCHEDULED');
    expect(state.recording).toBe(false);
  });

  it('CA-02: ligar só o "Áudio do computador" com a sessão parada inicia a sessão sem microfone', async () => {
    const { session, calls, state } = fakeDeps();
    await session.toggleComputerAudio();
    expect(calls).toEqual(['startTab', 'status=IN_PROGRESS', 'startRecording(mic=false)']);
    expect(state.recording).toBe(true);
  });

  it('RF-02: "Áudio do computador" com a sessão já rodando só liga a aba', async () => {
    const { session, calls } = fakeDeps({ recording: true, status: 'IN_PROGRESS' });
    await session.toggleComputerAudio();
    expect(calls).toEqual(['startTab']);
  });

  it('RF-04: desligar o "Áudio do computador" não encerra a sessão', async () => {
    const { session, calls, state } = fakeDeps({ recording: true, tab: true, status: 'IN_PROGRESS' });
    await session.toggleComputerAudio();
    expect(calls).toEqual(['stopTab']);
    expect(state.recording).toBe(true);
    expect(state.status).toBe('IN_PROGRESS');
  });

  it('CA-03: "Encerrar" para as fontes, marca concluída e chama a análise final uma vez', async () => {
    const { session, calls, deps } = fakeDeps({ recording: true, tab: true, status: 'IN_PROGRESS' });
    await session.toggle();
    expect(calls).toEqual(['stopRecording', 'status=COMPLETED', 'analysis']);
    expect(deps.triggerAnalysis).toHaveBeenCalledTimes(1);
  });

  it('RF-01: "Retomar" uma reunião já em andamento não remarca o status', async () => {
    const { session, calls } = fakeDeps({ status: 'IN_PROGRESS' });
    await session.start();
    expect(calls).toEqual(['startTab', 'startRecording(mic=true)']);
  });

  it('CA-05: "Encerrar sessão" sem captura marca concluída e gera a análise', async () => {
    const { session, calls } = fakeDeps({ status: 'IN_PROGRESS' });
    await session.endWithoutCapture();
    expect(calls).toEqual(['status=COMPLETED', 'analysis']);
  });
});
