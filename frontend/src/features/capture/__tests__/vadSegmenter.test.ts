import { describe, expect, it } from 'vitest';
import { VadSegmenter } from '../vadSegmenter';
import { encodeWav, rms } from '../wavEncoder';
import { createSessionClock } from '../sessionClock';
import type { AudioSegment } from '../types';

const SR = 16000;
const FRAME_MS = 20;
const frame = (amplitude: number) => new Float32Array((SR * FRAME_MS) / 1000).fill(amplitude);
const speech = () => frame(0.1);
const silence = () => frame(0);

/** Alimenta o segmentador com uma sequência [tipo, duraçãoMs] e devolve os trechos emitidos. */
const run = (plan: Array<['fala' | 'silencio', number]>, opts: Partial<ConstructorParameters<typeof VadSegmenter>[0]> = {}) => {
  const out: AudioSegment[] = [];
  let n = 0;
  const vad = new VadSegmenter({ source: 'TAB', sampleRate: SR, onSegment: (s) => out.push(s), idFactory: () => `s${++n}`, ...opts });
  let t = 0;
  for (const [kind, ms] of plan) {
    for (let i = 0; i < ms / FRAME_MS; i++) {
      vad.push(kind === 'fala' ? speech() : silence(), t);
      t += FRAME_MS;
    }
  }
  return { out, vad };
};

describe('VadSegmenter', () => {
  it('não emite nada para silêncio ou ruído abaixo do limiar', () => {
    const { out } = run([['silencio', 3000]]);
    expect(out).toHaveLength(0);
  });

  it('emite um trecho após 1,2 s de silêncio, com 300 ms de pré-roll e horário da sessão', () => {
    const { out } = run([['silencio', 1000], ['fala', 2000], ['silencio', 1300]]);
    expect(out).toHaveLength(1);
    expect(out[0].startMs).toBe(700); // 1000 - 300 de pré-roll
    expect(out[0].endMs).toBeGreaterThanOrEqual(3000 + 1200);
    expect(out[0].source).toBe('TAB');
    expect(out[0].pcm.length).toBe(((out[0].endMs - out[0].startMs) * SR) / 1000);
  });

  it('corta fala contínua em trechos de no máximo 12 s', () => {
    const { out } = run([['fala', 30000], ['silencio', 1300]]);
    expect(out.length).toBe(3);
    for (const s of out) expect(s.endMs - s.startMs).toBeLessThanOrEqual(12000);
    // Trechos contíguos, sem sobreposição nem lacuna
    expect(out[1].startMs).toBe(out[0].endMs);
  });

  it('separa duas falas com pausa longa e mantém juntas falas com pausa curta', () => {
    const longPause = run([['fala', 1000], ['silencio', 1500], ['fala', 1000], ['silencio', 1300]]).out;
    const shortPause = run([['fala', 1000], ['silencio', 500], ['fala', 1000], ['silencio', 1300]]).out;
    expect(longPause).toHaveLength(2);
    expect(shortPause).toHaveLength(1);
  });

  it('descarta estalos menores que a fala mínima', () => {
    const { out } = run([['fala', 100], ['silencio', 1300]]);
    expect(out).toHaveLength(0);
  });

  it('flush entrega a fala em andamento (ao parar a captura)', () => {
    const { out, vad } = run([['fala', 800]]);
    expect(out).toHaveLength(0);
    vad.flush();
    expect(out).toHaveLength(1);
  });
});

describe('wavEncoder e sessionClock', () => {
  it('gera WAV PCM16 mono com cabeçalho e tamanho corretos', () => {
    const bytes = encodeWav(new Float32Array(16000), SR);
    const view = new DataView(bytes.buffer);
    expect(String.fromCharCode(...bytes.subarray(0, 4))).toBe('RIFF');
    expect(view.getUint32(24, true)).toBe(SR);
    expect(view.getUint32(40, true)).toBe(32000);
    expect(bytes.length).toBe(44 + 32000);
  });

  it('rms de frame silencioso é zero e de seno é positivo', () => {
    expect(rms(new Float32Array(10))).toBe(0);
    expect(rms(new Float32Array([0.5, -0.5]))).toBeCloseTo(0.5);
  });

  it('relógio da sessão começa em zero e é crescente', () => {
    let t = 1000;
    const clock = createSessionClock(() => t);
    expect(clock.now()).toBe(0);
    t = 1250.4;
    expect(clock.now()).toBe(250);
  });
});
