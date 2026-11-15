/**
 * Dublês de MediaDevices, MediaStreamTrack e AudioContext para testar a captura sem navegador real
 * (spec 006, T-04).
 */
import { vi } from 'vitest';
import type { AudioSourceDeps } from '../../features/capture/audioSource';
import type { SessionClock } from '../../features/capture/sessionClock';

export class FakeTrack extends EventTarget {
  readyState: 'live' | 'ended' = 'live';
  readonly kind: 'audio' | 'video';
  constructor(kind: 'audio' | 'video') {
    super();
    this.kind = kind;
  }
  stop() {
    this.readyState = 'ended';
  }
  /** Simula o navegador/usuário encerrando a trilha. */
  end() {
    this.readyState = 'ended';
    this.dispatchEvent(new Event('ended'));
  }
}

export class FakeStream {
  readonly tracks: FakeTrack[];
  constructor(tracks: FakeTrack[]) {
    this.tracks = tracks;
  }
  getTracks() {
    return this.tracks as unknown as MediaStreamTrack[];
  }
  getAudioTracks() {
    return this.tracks.filter((t) => t.kind === 'audio') as unknown as MediaStreamTrack[];
  }
  getVideoTracks() {
    return this.tracks.filter((t) => t.kind === 'video') as unknown as MediaStreamTrack[];
  }
}

export class FakePort {
  onmessage: ((e: MessageEvent<Float32Array>) => void) | null = null;
  emit(frame: Float32Array) {
    this.onmessage?.({ data: frame } as MessageEvent<Float32Array>);
  }
}

export class FakeAudioContext {
  sampleRate: number;
  closed = false;
  readonly destination = {};
  readonly audioWorklet = { addModule: vi.fn(async () => {}) };
  readonly port = new FakePort();
  constructor(options: AudioContextOptions) {
    this.sampleRate = options.sampleRate ?? 48000;
  }
  createMediaStreamSource() {
    return { connect: vi.fn() };
  }
  createGain() {
    return { gain: { value: 1 }, connect: vi.fn() };
  }
  createScriptProcessor() {
    return { connect: vi.fn(), onaudioprocess: null };
  }
  close() {
    this.closed = true;
    return Promise.resolve();
  }
}

export interface FakeMediaEnv {
  deps: AudioSourceDeps;
  contexts: FakeAudioContext[];
  streams: FakeStream[];
  getUserMedia: ReturnType<typeof vi.fn>;
  getDisplayMedia: ReturnType<typeof vi.fn>;
  timers: Array<{ fn: () => void; ms: number }>;
  runTimers: () => Promise<void>;
}

const domError = (name: string) => Object.assign(new Error(name), { name });

/**
 * @param mic  'ok' | nome do erro (ex.: 'NotAllowedError')
 * @param tab  'ok' | 'sem-audio' | nome do erro
 */
export const createFakeMediaEnv = (opts: { mic?: string; tab?: string } = {}, clock?: SessionClock): FakeMediaEnv => {
  const contexts: FakeAudioContext[] = [];
  const streams: FakeStream[] = [];
  const timers: Array<{ fn: () => void; ms: number }> = [];
  const env = { mic: opts.mic ?? 'ok', tab: opts.tab ?? 'ok' };

  const getUserMedia = vi.fn(async () => {
    if (env.mic !== 'ok') throw domError(env.mic);
    const s = new FakeStream([new FakeTrack('audio')]);
    streams.push(s);
    return s as unknown as MediaStream;
  });
  const getDisplayMedia = vi.fn(async () => {
    if (env.tab !== 'ok' && env.tab !== 'sem-audio') throw domError(env.tab);
    const tracks = env.tab === 'sem-audio' ? [new FakeTrack('video')] : [new FakeTrack('video'), new FakeTrack('audio')];
    const s = new FakeStream(tracks);
    streams.push(s);
    return s as unknown as MediaStream;
  });

  const deps: AudioSourceDeps = {
    mediaDevices: { getUserMedia, getDisplayMedia } as unknown as AudioSourceDeps['mediaDevices'],
    createAudioContext: (o) => {
      const ctx = new FakeAudioContext(o);
      contexts.push(ctx);
      return ctx as unknown as AudioContext;
    },
    createMediaStream: (tracks) => new FakeStream(tracks as unknown as FakeTrack[]) as unknown as MediaStream,
    createWorkletNode: (ctx) => ({ port: (ctx as unknown as FakeAudioContext).port, connect: vi.fn() }) as unknown as AudioWorkletNode,
    createWorkletModuleUrl: () => 'blob:fake-worklet',
    clock: clock ?? { now: () => 0 },
    setTimer: (fn, ms) => timers.push({ fn, ms }),
  };

  return {
    deps,
    contexts,
    streams,
    getUserMedia,
    getDisplayMedia,
    timers,
    runTimers: async () => {
      const pending = timers.splice(0);
      for (const t of pending) await t.fn();
      await new Promise((r) => setTimeout(r, 0));
    },
    // permite trocar o comportamento do microfone no meio do teste
    ...{ setMic: (v: string) => (env.mic = v) },
  } as FakeMediaEnv & { setMic: (v: string) => void };
};
