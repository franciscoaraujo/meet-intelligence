import { describe, expect, it } from 'vitest';
import { copilotStreamReducer, isDraftActive, type CopilotDrafts, type CopilotStreamEvent } from './copilotStream';

const ev = (type: CopilotStreamEvent['type'], seq: number, patch: Partial<CopilotStreamEvent> = {}): CopilotStreamEvent => ({
  type, questionId: 'q1', attempt: 1, seq, ...patch,
});

const run = (events: CopilotStreamEvent[], initial: CopilotDrafts = {}) => events.reduce(copilotStreamReducer, initial);

describe('copilotStreamReducer (spec 008)', () => {
  it('RF-03: STARTED → DELTAs concatenados → COMPLETED', () => {
    const state = run([ev('STARTED', 0), ev('DELTA', 1, { text: 'Eu uso ' }), ev('DELTA', 2, { text: 'Outbox.' })]);
    expect(state.q1).toMatchObject({ phase: 'streaming', text: 'Eu uso Outbox.' });
    expect(isDraftActive(state.q1)).toBe(true);

    const done = copilotStreamReducer(state, ev('COMPLETED', 3, { insightId: 'i1' }));
    expect(done.q1).toMatchObject({ phase: 'completed', insightId: 'i1' });
    expect(isDraftActive(done.q1)).toBe(false);
  });

  it('ignora eventos repetidos ou fora de ordem (seq menor ou igual ao último)', () => {
    const state = run([ev('STARTED', 0), ev('DELTA', 2, { text: 'B' }), ev('DELTA', 1, { text: 'A' }), ev('DELTA', 2, { text: 'B' })]);
    expect(state.q1.text).toBe('B');
  });

  it('RF-05/RN-02: REJECTED limpa o rascunho; a nova tentativa recomeça do zero', () => {
    const state = run([
      ev('STARTED', 0), ev('DELTA', 1, { text: 'trabalhei com Kafka' }), ev('REJECTED', 2, { reason: 'VALIDATION' }),
    ]);
    expect(state.q1).toMatchObject({ phase: 'adjusting', text: '' });

    const retry = run([ev('STARTED', 3, { attempt: 2 }), ev('DELTA', 4, { attempt: 2, text: 'Uso Outbox' })], state);
    expect(retry.q1).toMatchObject({ attempt: 2, phase: 'streaming', text: 'Uso Outbox' });
  });

  it('DELTA de tentativa antiga não entra no rascunho da tentativa atual', () => {
    const state = run([ev('STARTED', 0), ev('REJECTED', 1), ev('STARTED', 2, { attempt: 2 }), ev('DELTA', 3, { attempt: 1, text: 'velho' })]);
    expect(state.q1.text).toBe('');
  });

  it('FAILED remove o rascunho e encerra', () => {
    const state = run([ev('STARTED', 0), ev('DELTA', 1, { text: 'x' }), ev('FAILED', 2, { reason: 'LLM_UNAVAILABLE' })]);
    expect(state.q1).toMatchObject({ phase: 'failed', text: '', reason: 'LLM_UNAVAILABLE' });
  });

  it('"Gerar de novo" (novo STARTED com seq 0) recomeça uma resposta já concluída', () => {
    const state = run([ev('STARTED', 0), ev('DELTA', 1, { text: 'a' }), ev('COMPLETED', 2), ev('STARTED', 0), ev('DELTA', 1, { text: 'nova' })]);
    expect(state.q1).toMatchObject({ phase: 'streaming', text: 'nova' });
  });

  it('RF-08: entrou no meio (sem STARTED) → ignora DELTA, aceita só o fim', () => {
    expect(run([ev('DELTA', 5, { text: 'meio' })])).toEqual({});
    expect(run([ev('COMPLETED', 9, { insightId: 'i9' })]).q1).toMatchObject({ phase: 'completed', insightId: 'i9' });
  });

  it('perguntas diferentes evoluem de forma independente', () => {
    const state = run([
      ev('STARTED', 0), ev('STARTED', 0, { questionId: 'q2' }),
      ev('DELTA', 1, { text: 'um' }), ev('DELTA', 1, { questionId: 'q2', text: 'dois' }),
    ]);
    expect(state.q1.text).toBe('um');
    expect(state.q2.text).toBe('dois');
  });
});

describe('execuções substituídas (spec 016)', () => {
  it('CA-02: a nova execução assume o card; eventos atrasados da antiga (inclusive SUPERSEDED) são ignorados', () => {
    const state = run([
      ev('STARTED', 0, { run: 10 }),
      ev('DELTA', 1, { run: 10, text: 'Primeira versão' }),
      ev('STARTED', 0, { run: 11 }),
      ev('DELTA', 1, { run: 11, text: 'Com as duas partes' }),
      ev('DELTA', 2, { run: 10, text: ' atrasada' }),
      ev('REJECTED', 3, { run: 10, reason: 'SUPERSEDED' }),
    ]);
    expect(state.q1).toMatchObject({ run: 11, phase: 'streaming', text: 'Com as duas partes' });

    const done = copilotStreamReducer(state, ev('COMPLETED', 2, { run: 11, insightId: 'i2' }));
    expect(done.q1).toMatchObject({ phase: 'completed', insightId: 'i2' });
  });

  it('SUPERSEDED antes do STARTED da nova execução: o rascunho antigo some e o card espera a nova', () => {
    const state = run([
      ev('STARTED', 0, { run: 10 }),
      ev('DELTA', 1, { run: 10, text: 'Primeira versão' }),
      ev('REJECTED', 2, { run: 10, reason: 'SUPERSEDED' }),
    ]);
    expect(state.q1).toMatchObject({ phase: 'adjusting', text: '' });
    expect(isDraftActive(state.q1)).toBe(true);

    const next = run([ev('DELTA', 1, { run: 11, text: 'antes do STARTED' }), ev('STARTED', 0, { run: 11 })], state);
    expect(next.q1).toMatchObject({ run: 11, phase: 'generating', text: '' });
  });
});
