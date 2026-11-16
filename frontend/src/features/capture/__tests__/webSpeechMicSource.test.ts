import { describe, expect, it } from 'vitest';
import { WebSpeechMicSource, type SpeechRecognitionLike } from '../webSpeechMicSource';
import type { SourceState } from '../types';

class FakeRecognition implements SpeechRecognitionLike {
  lang = '';
  continuous = false;
  interimResults = false;
  maxAlternatives = 1;
  onstart: SpeechRecognitionLike['onstart'] = null;
  onresult: SpeechRecognitionLike['onresult'] = null;
  onerror: SpeechRecognitionLike['onerror'] = null;
  onend: SpeechRecognitionLike['onend'] = null;
  starts = 0;
  start() {
    this.starts++;
    this.onstart?.();
  }
  stop() {}
  say(text: string, isFinal: boolean) {
    this.onresult?.({ resultIndex: 0, results: [{ isFinal, 0: { transcript: text } }] });
  }
}

const setup = () => {
  const rec = new FakeRecognition();
  const timers: Array<{ id: number; fn: () => void; ms: number }> = [];
  let nextId = 1;
  let now = 0;
  const states: SourceState[] = [];
  const finals: string[] = [];
  const source = new WebSpeechMicSource(
    { onState: (s) => states.push(s), onInterim: () => {}, onFinalText: (t) => finals.push(t) },
    {
      createRecognition: () => rec,
      setTimer: (fn, ms) => {
        timers.push({ id: nextId, fn, ms });
        return nextId++;
      },
      clearTimer: (id) => {
        const i = timers.findIndex((t) => t.id === id);
        if (i >= 0) timers.splice(i, 1);
      },
      now: () => now,
    },
  );
  const runTimers = () => timers.splice(0).forEach((t) => t.fn());
  return { rec, source, states, finals, runTimers, advance: (ms: number) => (now += ms) };
};

describe('WebSpeechMicSource (modo alternativo)', () => {
  it('CA-07: consolida falas finais após a pausa e entrega uma frase', () => {
    const { rec, source, finals, runTimers } = setup();
    source.start();
    rec.say('explique o conceito', true);
    rec.say('de SOLID', true);
    runTimers();
    expect(finals).toEqual(['explique o conceito de SOLID']);
  });

  it('erro network é fatal: não reinicia em laço', () => {
    const { rec, source, states, runTimers } = setup();
    source.start();
    rec.onerror?.({ error: 'network' });
    rec.onend?.();
    runTimers();
    expect(states.at(-1)).toBe('ERRO');
    expect(rec.starts).toBe(1);
  });

  it('permissão negada → PERMISSAO_NEGADA', () => {
    const { rec, source, states } = setup();
    source.start();
    rec.onerror?.({ error: 'not-allowed' });
    expect(states.at(-1)).toBe('PERMISSAO_NEGADA');
  });

  it('reinicia após fim normal, mas limita reinícios em sequência', () => {
    const { rec, source, states, runTimers } = setup();
    source.start();
    for (let i = 0; i < 6; i++) {
      rec.onend?.();
      runTimers();
    }
    expect(rec.starts).toBe(6); // 1 início + 5 reinícios
    expect(states.at(-1)).toBe('ERRO');
  });

  it('stop devolve o texto pendente e para de reiniciar', () => {
    const { rec, source, runTimers } = setup();
    source.start();
    rec.say('pergunta sem pausa', false);
    const pending = source.stop();
    rec.onend?.();
    runTimers();
    expect(pending).toBe('pergunta sem pausa');
    expect(rec.starts).toBe(1);
  });

  it('spec 008 CA-10: o texto provisório só vai para a tela; ao consolidar, some e a frase é enviada uma vez', () => {
    const rec = new FakeRecognition();
    const timers: Array<() => void> = [];
    const interims: string[] = [];
    const finals: string[] = [];
    const source = new WebSpeechMicSource(
      { onState: () => {}, onInterim: (t) => interims.push(t), onFinalText: (t) => finals.push(t) },
      { createRecognition: () => rec, setTimer: (fn) => timers.push(fn), clearTimer: () => {}, now: () => 0 },
    );
    source.start();

    rec.say('eu trabalhei', false);
    rec.say('eu trabalhei com', false);
    expect(interims).toEqual(['eu trabalhei', 'eu trabalhei com']);
    expect(finals).toEqual([]);

    rec.say('eu trabalhei com Kafka', true);
    timers.splice(0).forEach((fn) => fn());

    expect(finals).toEqual(['eu trabalhei com Kafka']);
    expect(interims[interims.length - 1]).toBe('');
  });
});
