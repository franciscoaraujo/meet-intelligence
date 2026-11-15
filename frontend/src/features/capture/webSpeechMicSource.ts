import type { SourceState } from './types';

/** Mínimo da Web Speech API usado aqui (não há tipos oficiais no TypeScript). */
export interface SpeechRecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  maxAlternatives: number;
  onstart: (() => void) | null;
  onresult: ((event: { resultIndex: number; results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }> }) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
}

export interface WebSpeechCallbacks {
  onState: (state: SourceState, detail?: string) => void;
  onInterim: (text: string) => void;
  /** Frase consolidada (após pausa), pronta para POST /live-text. */
  onFinalText: (text: string) => void;
}

export interface WebSpeechOptions {
  createRecognition: () => SpeechRecognitionLike | null;
  setTimer?: (fn: () => void, ms: number) => number;
  clearTimer?: (id: number) => void;
  now?: () => number;
  /** Pausa que consolida a frase (comportamento atual: 2,2 s). */
  flushDelayMs?: number;
  /** Reinícios automáticos permitidos numa janela, para não entrar em laço infinito. */
  maxRestarts?: number;
  restartWindowMs?: number;
}

const FATAL_ERRORS: Record<string, { state: SourceState; detail: string }> = {
  'not-allowed': { state: 'PERMISSAO_NEGADA', detail: 'Permissão de microfone negada. Libere no cadeado ao lado da URL.' },
  'service-not-allowed': { state: 'PERMISSAO_NEGADA', detail: 'O navegador bloqueou o reconhecimento de voz.' },
  'audio-capture': { state: 'DISPOSITIVO_INDISPONIVEL', detail: 'Nenhum microfone disponível para o reconhecimento de voz.' },
  network: { state: 'ERRO', detail: 'O reconhecimento de voz do navegador está sem conexão com o serviço do Google.' },
};

/**
 * Microfone via Web Speech API (modo alternativo, comportamento anterior preservado — spec 001, RF-03).
 * A captura é feita pelo próprio navegador; o texto vai para /live-text.
 */
export class WebSpeechMicSource {
  private recognition: SpeechRecognitionLike | null = null;
  private active = false;
  private buffer = '';
  private interim = '';
  private flushTimer: number | null = null;
  private restarts: number[] = [];
  private readonly o: Required<WebSpeechOptions>;

  private readonly callbacks: WebSpeechCallbacks;

  constructor(callbacks: WebSpeechCallbacks, options: WebSpeechOptions) {
    this.callbacks = callbacks;
    this.o = {
      setTimer: (fn, ms) => window.setTimeout(fn, ms),
      clearTimer: (id) => window.clearTimeout(id),
      now: () => Date.now(),
      flushDelayMs: 2200,
      maxRestarts: 5,
      restartWindowMs: 10000,
      ...options,
    };
  }

  start(): void {
    const recognition = this.o.createRecognition();
    if (!recognition) {
      this.callbacks.onState('ERRO', 'Navegador sem suporte à Web Speech API (use Chrome ou Edge).');
      return;
    }
    this.active = true;
    this.restarts = [];
    this.recognition = recognition;
    recognition.lang = 'pt-BR';
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;
    recognition.onstart = () => this.callbacks.onState('ATIVA_SILENCIO');
    recognition.onresult = (event) => this.handleResult(event);
    recognition.onerror = (event) => this.handleError(event.error);
    recognition.onend = () => this.handleEnd();
    this.callbacks.onState('SOLICITANDO_PERMISSAO');
    recognition.start();
  }

  /** Encerra e devolve o texto pendente (fala ainda não consolidada). */
  stop(): string {
    this.active = false;
    if (this.flushTimer !== null) this.o.clearTimer(this.flushTimer);
    this.flushTimer = null;
    if (this.recognition) {
      this.recognition.onend = null;
      try {
        this.recognition.stop();
      } catch {
        // já parado
      }
      this.recognition = null;
    }
    const pending = [this.buffer, this.interim].filter(Boolean).join(' ').trim();
    this.buffer = '';
    this.interim = '';
    this.callbacks.onInterim('');
    this.callbacks.onState('INATIVA');
    return pending;
  }

  private handleResult(event: Parameters<NonNullable<SpeechRecognitionLike['onresult']>>[0]): void {
    let interim = '';
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const piece = event.results[i][0].transcript;
      if (event.results[i].isFinal) {
        const clean = piece.trim();
        if (clean) this.buffer = this.buffer ? `${this.buffer} ${clean}` : clean;
      } else {
        interim += piece;
      }
    }
    this.interim = interim.trim();
    const preview = [this.buffer, this.interim].filter(Boolean).join(' ').trim();
    this.callbacks.onInterim(preview);
    if (preview) {
      this.callbacks.onState('ATIVA_FALANDO');
      this.scheduleFlush(this.o.flushDelayMs);
    }
  }

  private scheduleFlush(delayMs: number): void {
    if (this.flushTimer !== null) this.o.clearTimer(this.flushTimer);
    this.flushTimer = this.o.setTimer(() => {
      this.flushTimer = null;
      // Ainda no meio de uma palavra: espera mais um pouco para não partir a frase
      if (this.interim) {
        this.scheduleFlush(1100);
        return;
      }
      const sentence = this.buffer.trim();
      this.buffer = '';
      this.callbacks.onInterim('');
      if (this.active) this.callbacks.onState('ATIVA_SILENCIO');
      if (sentence) this.callbacks.onFinalText(sentence);
    }, delayMs);
  }

  private handleError(error: string): void {
    const fatal = FATAL_ERRORS[error];
    if (fatal) {
      this.active = false;
      this.callbacks.onState(fatal.state, fatal.detail);
    }
    // 'no-speech' e 'aborted' não são fatais: o reinício automático continua escutando
  }

  private handleEnd(): void {
    if (this.interim) {
      this.buffer = this.buffer ? `${this.buffer} ${this.interim}` : this.interim;
      this.interim = '';
      this.scheduleFlush(1800);
    }
    if (!this.active || !this.recognition) return;

    const now = this.o.now();
    this.restarts = this.restarts.filter((t) => now - t < this.o.restartWindowMs);
    if (this.restarts.length >= this.o.maxRestarts) {
      this.active = false;
      this.callbacks.onState('ERRO', 'O reconhecimento de voz parou repetidamente. Clique em iniciar novamente.');
      return;
    }
    this.restarts.push(now);
    const recognition = this.recognition;
    this.o.setTimer(() => {
      if (this.active && this.recognition === recognition) {
        try {
          recognition.start();
        } catch {
          // start() em reconhecimento já ativo
        }
      }
    }, 150);
  }
}

/** Fábrica real: SpeechRecognition do Chrome/Edge, ou null se não suportado. */
export const browserSpeechRecognition = (): SpeechRecognitionLike | null => {
  const w = window as unknown as { SpeechRecognition?: new () => SpeechRecognitionLike; webkitSpeechRecognition?: new () => SpeechRecognitionLike };
  const Ctor = w.SpeechRecognition || w.webkitSpeechRecognition;
  return Ctor ? new Ctor() : null;
};
