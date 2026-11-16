import type { SessionClock } from './sessionClock';
import type { CaptureSurface, SourceKind, SourceState } from './types';

/**
 * Código do AudioWorkletProcessor: repassa frames mono à thread principal (spec 001, RNF-01).
 * Carregado por Blob URL para não depender de configuração de build para worklets.
 */
export const PCM_WORKLET_SOURCE = `
class PcmCaptureProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (channel && channel.length) this.port.postMessage(channel.slice(0));
    return true;
  }
}
registerProcessor('pcm-capture', PcmCaptureProcessor);
`;

export interface AudioSourceDeps {
  mediaDevices: Pick<MediaDevices, 'getUserMedia' | 'getDisplayMedia'>;
  createAudioContext: (options: AudioContextOptions) => AudioContext;
  createMediaStream: (tracks: MediaStreamTrack[]) => MediaStream;
  createWorkletNode?: (ctx: AudioContext, name: string) => AudioWorkletNode;
  createWorkletModuleUrl?: () => string;
  clock: SessionClock;
  setTimer?: (fn: () => void, ms: number) => unknown;
}

export interface AudioSourceCallbacks {
  onFrame: (frame: Float32Array, frameStartMs: number) => void;
  onState: (state: SourceState, detail?: string) => void;
  /** Superfície escolhida no seletor (só para a fonte do computador) — spec 009, RF-03. */
  onSurface?: (surface: CaptureSurface) => void;
}

/** Lê a superfície (aba, tela inteira, janela) informada pelo navegador na trilha de vídeo. */
export const surfaceOf = (stream: MediaStream): CaptureSurface => {
  const video = stream.getVideoTracks?.()[0];
  const surface = (video?.getSettings?.() as { displaySurface?: string } | undefined)?.displaySurface;
  return surface === 'browser' || surface === 'monitor' || surface === 'window' ? surface : 'unknown';
};

/** Orientação de "Sem áudio" conforme a superfície escolhida (spec 009, RF-04). */
export const noAudioHint = (surface: CaptureSurface): string => {
  switch (surface) {
    case 'window':
      return 'Janelas não compartilham áudio. Escolha uma aba (marcando "Compartilhar áudio da guia") ou a Tela inteira (marcando "Compartilhar áudio do sistema").';
    case 'monitor':
      return 'Marque "Compartilhar áudio do sistema" ao escolher a Tela inteira (Windows/ChromeOS). No macOS e no Linux, escolha uma aba.';
    case 'browser':
      return 'Marque "Compartilhar áudio da guia" ao escolher a aba (Meet, WhatsApp Web, YouTube…).';
    default:
      return 'Escolha uma aba marcando "Compartilhar áudio da guia" ou a Tela inteira marcando "Compartilhar áudio do sistema".';
  }
};

export const SAMPLE_RATE = 16000;
const MIC_RECOVERY_DELAYS_MS = [1000, 3000, 5000];

/** Mapeia erros de getUserMedia/getDisplayMedia para estados da fonte (spec 001, tabela de erros). */
export const stateForMediaError = (kind: SourceKind, err: unknown): { state: SourceState; detail: string } => {
  const name = (err as { name?: string })?.name ?? '';
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    // No Chrome, cancelar o seletor de aba também gera NotAllowedError
    return kind === 'TAB'
      ? { state: 'INATIVA', detail: 'Compartilhamento cancelado ou bloqueado.' }
      : { state: 'PERMISSAO_NEGADA', detail: 'Permissão de microfone negada. Libere no cadeado ao lado da URL.' };
  }
  if (name === 'AbortError') return { state: 'INATIVA', detail: 'Captura cancelada.' };
  if (name === 'NotFoundError' || name === 'OverconstrainedError') {
    return { state: 'DISPOSITIVO_INDISPONIVEL', detail: 'Nenhum microfone disponível.' };
  }
  if (name === 'NotReadableError') {
    return { state: 'ERRO', detail: 'O dispositivo está em uso por outro aplicativo.' };
  }
  return { state: 'ERRO', detail: 'Não foi possível iniciar a captura de áudio.' };
};

/**
 * Uma fonte de áudio (áudio do computador — aba ou tela inteira — ou microfone): aquisição, grafo de áudio a 16 kHz, frames com
 * horário da sessão, detecção de encerramento e recuperação controlada (spec 001, RF-01..RF-06).
 */
export class AudioCaptureSource {
  private stream: MediaStream | null = null;
  private ctx: AudioContext | null = null;
  private workletUrl: string | null = null;
  private stopping = false;
  private samplesSeen = 0;
  private captureStartMs = 0;
  private recoveryAttempt = 0;
  private state: SourceState = 'INATIVA';

  readonly kind: SourceKind;
  private readonly deps: AudioSourceDeps;
  private readonly callbacks: AudioSourceCallbacks;

  constructor(kind: SourceKind, deps: AudioSourceDeps, callbacks: AudioSourceCallbacks) {
    this.kind = kind;
    this.deps = deps;
    this.callbacks = callbacks;
  }

  get currentState(): SourceState {
    return this.state;
  }

  async start(): Promise<void> {
    this.stopping = false;
    this.setState('SOLICITANDO_PERMISSAO');
    let stream: MediaStream;
    try {
      stream = await this.acquire();
    } catch (err) {
      const { state, detail } = stateForMediaError(this.kind, err);
      this.setState(state, detail);
      return;
    }

    const surface = this.kind === 'TAB' ? surfaceOf(stream) : 'unknown';
    if (this.kind === 'TAB') this.callbacks.onSurface?.(surface);

    const audioTracks = stream.getAudioTracks();
    if (audioTracks.length === 0) {
      stream.getTracks().forEach((t) => t.stop());
      this.setState('SEM_AUDIO', noAudioHint(surface));
      return;
    }

    this.stream = stream;
    for (const track of stream.getTracks()) {
      track.addEventListener('ended', this.handleTrackEnded);
    }
    for (const track of audioTracks) {
      track.addEventListener('mute', () => this.setState('ATIVA_SILENCIO', 'Fonte sem sinal (mudo).'));
    }

    try {
      await this.buildGraph(audioTracks);
    } catch {
      this.teardown();
      this.setState('ERRO', 'Navegador sem suporte ao processamento de áudio necessário.');
      return;
    }
    this.recoveryAttempt = 0;
    this.setState('ATIVA_SILENCIO');
  }

  stop(): void {
    this.stopping = true;
    this.teardown();
    this.setState('INATIVA');
  }

  /** Atualizado pelo VAD (fala/silêncio), apenas enquanto a fonte estiver ativa. */
  markSpeaking(speaking: boolean): void {
    if (this.state === 'ATIVA_FALANDO' || this.state === 'ATIVA_SILENCIO') {
      this.setState(speaking ? 'ATIVA_FALANDO' : 'ATIVA_SILENCIO');
    }
  }

  private acquire(): Promise<MediaStream> {
    if (this.kind === 'TAB') {
      // Spec 009 (RF-02): qualquer aba ou a tela inteira com o áudio do sistema; a aba do próprio app
      // fica fora do seletor (evita eco e a transcrição das respostas do copiloto). Navegadores que não
      // conhecem essas opções as ignoram.
      return this.deps.mediaDevices.getDisplayMedia({
        video: true,
        audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: true },
        systemAudio: 'include',
        selfBrowserSurface: 'exclude',
        surfaceSwitching: 'include',
      } as DisplayMediaStreamOptions);
    }
    // Com a aba capturada à parte, o cancelamento de eco evita que o microfone duplique a fala remota
    return this.deps.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
  }

  private async buildGraph(audioTracks: MediaStreamTrack[]): Promise<void> {
    const ctx = this.deps.createAudioContext({ sampleRate: SAMPLE_RATE });
    this.ctx = ctx;
    const source = ctx.createMediaStreamSource(this.deps.createMediaStream(audioTracks));
    const silentOut = ctx.createGain();
    silentOut.gain.value = 0;
    this.samplesSeen = 0;
    this.captureStartMs = this.deps.clock.now();

    if (ctx.audioWorklet && this.deps.createWorkletNode) {
      this.workletUrl = this.deps.createWorkletModuleUrl
        ? this.deps.createWorkletModuleUrl()
        : URL.createObjectURL(new Blob([PCM_WORKLET_SOURCE], { type: 'application/javascript' }));
      await ctx.audioWorklet.addModule(this.workletUrl);
      const node = this.deps.createWorkletNode(ctx, 'pcm-capture');
      node.port.onmessage = (e: MessageEvent<Float32Array>) => this.handleFrame(e.data);
      source.connect(node);
      node.connect(silentOut);
    } else {
      // Navegadores sem AudioWorklet: ScriptProcessorNode (API antiga, mantida como alternativa)
      const processor = ctx.createScriptProcessor(4096, 1, 1);
      processor.onaudioprocess = (e) => this.handleFrame(new Float32Array(e.inputBuffer.getChannelData(0)));
      source.connect(processor);
      processor.connect(silentOut);
    }
    silentOut.connect(ctx.destination);
  }

  private handleFrame(frame: Float32Array): void {
    const sampleRate = this.ctx?.sampleRate || SAMPLE_RATE;
    const frameStartMs = this.captureStartMs + (this.samplesSeen / sampleRate) * 1000;
    this.samplesSeen += frame.length;
    this.callbacks.onFrame(frame, frameStartMs);
  }

  private readonly handleTrackEnded = (): void => {
    if (this.stopping || !this.stream) return;
    this.teardown();
    if (this.kind === 'TAB') {
      // Reabrir exige gesto do usuário (getDisplayMedia): a UI oferece "Compartilhar de novo"
      this.setState('INTERROMPIDA', 'O compartilhamento da aba foi encerrado.');
      return;
    }
    this.recoverMic();
  };

  private recoverMic(): void {
    if (this.recoveryAttempt >= MIC_RECOVERY_DELAYS_MS.length) {
      this.setState('DISPOSITIVO_INDISPONIVEL', 'O microfone foi desconectado e não voltou.');
      return;
    }
    const delay = MIC_RECOVERY_DELAYS_MS[this.recoveryAttempt++];
    this.setState('RECUPERANDO', `Tentando reconectar o microfone (${this.recoveryAttempt}/${MIC_RECOVERY_DELAYS_MS.length})...`);
    const setTimer = this.deps.setTimer ?? ((fn: () => void, ms: number) => setTimeout(fn, ms));
    setTimer(async () => {
      if (this.stopping) return;
      const attemptBefore = this.recoveryAttempt;
      await this.start();
      if (this.state !== 'ATIVA_SILENCIO' && this.state !== 'ATIVA_FALANDO' && !this.stopping) {
        this.recoveryAttempt = attemptBefore;
        this.recoverMic();
      }
    }, delay);
  }

  private teardown(): void {
    if (this.stream) {
      for (const track of this.stream.getTracks()) {
        track.removeEventListener('ended', this.handleTrackEnded);
        track.stop();
      }
      this.stream = null;
    }
    if (this.ctx) {
      this.ctx.close().catch(() => {});
      this.ctx = null;
    }
    if (this.workletUrl && !this.deps.createWorkletModuleUrl) {
      URL.revokeObjectURL(this.workletUrl);
    }
    this.workletUrl = null;
  }

  private setState(state: SourceState, detail?: string): void {
    if (state === this.state && detail === undefined) return;
    this.state = state;
    this.callbacks.onState(state, detail);
  }
}

/** Dependências reais do navegador. */
export const browserAudioDeps = (clock: SessionClock): AudioSourceDeps => ({
  mediaDevices: navigator.mediaDevices,
  createAudioContext: (options) => {
    const Ctx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    return new Ctx(options);
  },
  createMediaStream: (tracks) => new MediaStream(tracks),
  createWorkletNode: typeof AudioWorkletNode !== 'undefined'
    ? (ctx, name) => new AudioWorkletNode(ctx, name)
    : undefined,
  clock,
});
