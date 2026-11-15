import type { ChunkTranscriptionResponse } from '../../types';
import { bytesToBase64, encodeWav } from './wavEncoder';
import { emptyStats, type AudioSegment, type UploadStats } from './types';

export interface ChunkPayload {
  base64Audio: string;
  mimeType: 'audio/wav';
  clientSegmentId: string;
  audioSource: AudioSegment['source'];
  captureStartMs: number;
  captureEndMs: number;
  speakerLabel?: string;
}

export interface UploaderOptions {
  /** Envia o trecho ao backend (POST /transcripts/chunk). */
  send: (payload: ChunkPayload) => Promise<ChunkTranscriptionResponse>;
  onResponse?: (segment: AudioSegment, response: ChunkTranscriptionResponse) => void;
  /** Falha definitiva do envio (não do provedor): esgotou as tentativas ou erro 4xx. */
  onSendError?: (segment: AudioSegment, message: string) => void;
  onStats?: (stats: UploadStats) => void;
  speakerLabel?: () => string | undefined;
  maxQueue?: number;
  backoffMs?: number[];
  sleep?: (ms: number) => Promise<void>;
}

/** Erros de transporte que justificam reenviar o mesmo trecho: rede ou HTTP 5xx. */
export const isRetryableSendError = (err: unknown): boolean => {
  const status = (err as { response?: { status?: number } })?.response?.status;
  return status === undefined || status >= 500;
};

/**
 * Fila de envio de trechos de uma fonte (spec 001, RF-08): um envio por vez, reenvio com o mesmo
 * clientSegmentId (idempotente no backend), limite de fila com contagem de perdas.
 */
export class SegmentUploader {
  private readonly queue: AudioSegment[] = [];
  private readonly opts: Required<Omit<UploaderOptions, 'onResponse' | 'onSendError' | 'onStats' | 'speakerLabel'>> &
    UploaderOptions;
  private running = false;
  private readonly idleWaiters: Array<() => void> = [];
  readonly stats: UploadStats = emptyStats();

  constructor(options: UploaderOptions) {
    this.opts = {
      maxQueue: 20,
      backoffMs: [1000, 2000, 4000],
      sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
      ...options,
    };
  }

  enqueue(segment: AudioSegment): void {
    if (this.queue.length >= this.opts.maxQueue) {
      this.queue.shift();
      this.stats.dropped++;
      this.emitStats();
    }
    this.queue.push(segment);
    void this.drain();
  }

  get pending(): number {
    return this.queue.length + (this.running ? 1 : 0);
  }

  /** Aguarda a fila esvaziar ou o tempo limite (usado ao parar a gravação, RN-02). */
  async flush(timeoutMs: number): Promise<boolean> {
    if (this.pending === 0) return true;
    return new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => resolve(false), timeoutMs);
      this.idleWaiters.push(() => {
        clearTimeout(timer);
        resolve(true);
      });
    });
  }

  private async drain(): Promise<void> {
    if (this.running) return;
    this.running = true;
    try {
      while (this.queue.length > 0) {
        const segment = this.queue.shift()!;
        await this.sendWithRetry(segment);
      }
    } finally {
      this.running = false;
      this.idleWaiters.splice(0).forEach((notify) => notify());
    }
  }

  private async sendWithRetry(segment: AudioSegment): Promise<void> {
    const payload: ChunkPayload = {
      base64Audio: bytesToBase64(encodeWav(segment.pcm, segment.sampleRate)),
      mimeType: 'audio/wav',
      clientSegmentId: segment.id,
      audioSource: segment.source,
      captureStartMs: segment.startMs,
      captureEndMs: segment.endMs,
      speakerLabel: this.opts.speakerLabel?.(),
    };

    for (let attempt = 0; ; attempt++) {
      try {
        const response = await this.opts.send(payload);
        this.stats.sent++;
        if (response.status === 'TRANSCRIBED') this.stats.transcribed++;
        else if (response.status === 'NO_SPEECH') this.stats.noSpeech++;
        else if (response.status === 'FAILED') this.stats.failed++;
        this.emitStats();
        this.opts.onResponse?.(segment, response);
        return;
      } catch (err) {
        const canRetry = isRetryableSendError(err) && attempt < this.opts.backoffMs.length;
        if (!canRetry) {
          this.stats.failed++;
          this.emitStats();
          this.opts.onSendError?.(segment, 'Não foi possível enviar um trecho de áudio ao servidor.');
          return;
        }
        this.stats.retrying++;
        this.emitStats();
        await this.opts.sleep(this.opts.backoffMs[attempt]);
        this.stats.retrying--;
      }
    }
  }

  private emitStats(): void {
    this.opts.onStats?.({ ...this.stats });
  }
}
