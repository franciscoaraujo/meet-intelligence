import { concatPcm, rms } from './wavEncoder';
import type { AudioSegment, SourceKind } from './types';

export interface VadOptions {
  source: SourceKind;
  sampleRate: number;
  onSegment: (segment: AudioSegment) => void;
  idFactory?: () => string;
  /** Limiar de RMS para considerar fala (valor já usado na captura da aba). */
  threshold?: number;
  /** Áudio anterior ao início da fala incluído no trecho, para não cortar a primeira sílaba. */
  prerollMs?: number;
  /** Silêncio que encerra o trecho. */
  endSilenceMs?: number;
  /** Duração máxima de um trecho (fala contínua é cortada aqui). */
  maxSegmentMs?: number;
  /** Fala mínima para enviar o trecho (descarta estalos). */
  minSpeechMs?: number;
}

interface FrameAt {
  frame: Float32Array;
  startMs: number;
  durationMs: number;
}

/**
 * Segmentação por detecção de fala (VAD por energia) — spec 001, RF-09.
 * Recebe frames contínuos e emite um {@link AudioSegment} por trecho de fala.
 */
export class VadSegmenter {
  private readonly opts: Required<VadOptions>;
  private preroll: FrameAt[] = [];
  private current: { frames: FrameAt[]; startMs: number; lastVoiceEndMs: number; voicedMs: number } | null = null;

  constructor(options: VadOptions) {
    this.opts = {
      threshold: 0.008,
      prerollMs: 300,
      endSilenceMs: 1200,
      maxSegmentMs: 12000,
      minSpeechMs: 200,
      idFactory: () => crypto.randomUUID(),
      ...options,
    };
  }

  /** Processa um frame; devolve o RMS calculado (para o medidor de nível). */
  push(frame: Float32Array, frameStartMs: number): number {
    const level = rms(frame);
    const voiced = level > this.opts.threshold;
    const durationMs = (frame.length / this.opts.sampleRate) * 1000;
    const item: FrameAt = { frame, startMs: frameStartMs, durationMs };
    const frameEndMs = frameStartMs + durationMs;

    if (!this.current) {
      if (voiced) {
        const frames = [...this.preroll, item];
        this.current = {
          frames,
          startMs: frames[0].startMs,
          lastVoiceEndMs: frameEndMs,
          voicedMs: durationMs,
        };
        this.preroll = [];
      } else {
        this.preroll.push(item);
        while (this.preroll.length > 0 && frameEndMs - this.preroll[0].startMs > this.opts.prerollMs) {
          this.preroll.shift();
        }
      }
      return level;
    }

    this.current.frames.push(item);
    if (voiced) {
      this.current.lastVoiceEndMs = frameEndMs;
      this.current.voicedMs += durationMs;
    }
    const silenceMs = frameEndMs - this.current.lastVoiceEndMs;
    const lengthMs = frameEndMs - this.current.startMs;
    if (silenceMs >= this.opts.endSilenceMs || lengthMs >= this.opts.maxSegmentMs) {
      this.emit(frameEndMs);
    }
    return level;
  }

  /** Encerra o trecho em andamento (ex.: ao parar a captura). */
  flush(): void {
    if (!this.current) return;
    const last = this.current.frames[this.current.frames.length - 1];
    this.emit(last.startMs + last.durationMs);
  }

  get speaking(): boolean {
    return this.current !== null;
  }

  private emit(endMs: number): void {
    const current = this.current;
    this.current = null;
    if (!current || current.voicedMs < this.opts.minSpeechMs) return;
    this.opts.onSegment({
      id: this.opts.idFactory(),
      source: this.opts.source,
      startMs: Math.round(current.startMs),
      endMs: Math.round(endMs),
      pcm: concatPcm(current.frames.map((f) => f.frame)),
      sampleRate: this.opts.sampleRate,
    });
  }
}
