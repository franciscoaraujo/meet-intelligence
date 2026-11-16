import { describe, expect, it, vi } from 'vitest';
import { SegmentUploader, type ChunkPayload } from '../segmentUploader';
import type { AudioSegment } from '../types';
import type { ChunkTranscriptionResponse } from '../../../types';

const segment = (id: string, startMs = 0): AudioSegment => ({
  id,
  source: 'MIC',
  startMs,
  endMs: startMs + 1000,
  pcm: new Float32Array(16000),
  sampleRate: 16000,
});

const transcribed = (): ChunkTranscriptionResponse => ({ status: 'TRANSCRIBED', segments: [], duplicate: false });
const networkError = () => Object.assign(new Error('Network Error'), { response: undefined });
const httpError = (status: number) => Object.assign(new Error(`HTTP ${status}`), { response: { status } });

describe('SegmentUploader', () => {
  it('CA-03: reenvia após erro de rede com o MESMO clientSegmentId', async () => {
    const payloads: ChunkPayload[] = [];
    const send = vi.fn(async (p: ChunkPayload) => {
      payloads.push(p);
      if (payloads.length === 1) throw networkError();
      return transcribed();
    });
    const uploader = new SegmentUploader({ send, sleep: async () => {} });

    uploader.enqueue(segment('seg-1'));
    await uploader.flush(1000);

    expect(send).toHaveBeenCalledTimes(2);
    expect(payloads[0].clientSegmentId).toBe('seg-1');
    expect(payloads[1].clientSegmentId).toBe('seg-1');
    expect(uploader.stats.transcribed).toBe(1);
  });

  it('envia origem, horário de captura e WAV em base64', async () => {
    const send = vi.fn(async (_p: ChunkPayload) => transcribed());
    const uploader = new SegmentUploader({ send, speakerLabel: () => 'Francisco' });

    uploader.enqueue(segment('seg-2', 5000));
    await uploader.flush(1000);

    const p = send.mock.calls[0][0];
    expect(p.audioSource).toBe('MIC');
    expect(p.captureStartMs).toBe(5000);
    expect(p.captureEndMs).toBe(6000);
    expect(p.speakerLabel).toBe('Francisco');
    expect(atob(p.base64Audio).slice(0, 4)).toBe('RIFF');
  });

  it('não reenvia erro 4xx e informa a falha de envio', async () => {
    const send = vi.fn(async () => {
      throw httpError(400);
    });
    const onSendError = vi.fn();
    const uploader = new SegmentUploader({ send, onSendError, sleep: async () => {} });

    uploader.enqueue(segment('seg-3'));
    await uploader.flush(1000);

    expect(send).toHaveBeenCalledTimes(1);
    expect(onSendError).toHaveBeenCalledOnce();
    expect(uploader.stats.failed).toBe(1);
  });

  it('desiste após 3 reenvios (backoff 1 s, 2 s, 4 s) em erro 5xx', async () => {
    const send = vi.fn(async () => {
      throw httpError(503);
    });
    const sleeps: number[] = [];
    const uploader = new SegmentUploader({ send, sleep: async (ms) => void sleeps.push(ms) });

    uploader.enqueue(segment('seg-4'));
    await uploader.flush(1000);

    expect(send).toHaveBeenCalledTimes(4);
    expect(sleeps).toEqual([1000, 2000, 4000]);
  });

  it('FAILED do backend (falha dos provedores) não é reenviado', async () => {
    const send = vi.fn(async (): Promise<ChunkTranscriptionResponse> => ({
      status: 'FAILED', failureReason: 'HTTP_5XX', segments: [], duplicate: false,
    }));
    const uploader = new SegmentUploader({ send });

    uploader.enqueue(segment('seg-5'));
    await uploader.flush(1000);

    expect(send).toHaveBeenCalledTimes(1);
    expect(uploader.stats.failed).toBe(1);
  });

  it('acima do limite da fila descarta o trecho mais antigo e conta a perda', async () => {
    let release!: () => void;
    const blocked = new Promise<void>((r) => (release = r));
    const sent: string[] = [];
    const send = vi.fn(async (p: ChunkPayload) => {
      sent.push(p.clientSegmentId);
      if (sent.length === 1) await blocked;
      return transcribed();
    });
    const uploader = new SegmentUploader({ send, maxQueue: 2 });

    uploader.enqueue(segment('a')); // em envio
    uploader.enqueue(segment('b'));
    uploader.enqueue(segment('c'));
    uploader.enqueue(segment('d')); // descarta 'b'
    release();
    await uploader.flush(1000);

    expect(sent).toEqual(['a', 'c', 'd']);
    expect(uploader.stats.dropped).toBe(1);
  });

  it('envia um trecho por vez, na ordem', async () => {
    const order: string[] = [];
    const send = vi.fn(async (p: ChunkPayload) => {
      order.push(`start-${p.clientSegmentId}`);
      await Promise.resolve();
      order.push(`end-${p.clientSegmentId}`);
      return transcribed();
    });
    const uploader = new SegmentUploader({ send });

    uploader.enqueue(segment('x'));
    uploader.enqueue(segment('y'));
    await uploader.flush(1000);

    expect(order).toEqual(['start-x', 'end-x', 'start-y', 'end-y']);
  });
});
