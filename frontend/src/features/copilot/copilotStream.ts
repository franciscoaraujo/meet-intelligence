/**
 * Estado do streaming do copiloto por pergunta (spec 008, RF-03..RF-05).
 * Reducer puro: recebe os eventos de /topic/meetings/{id}/copilot e devolve o rascunho de cada pergunta.
 */
export type CopilotStreamEventType = 'STARTED' | 'DELTA' | 'REJECTED' | 'COMPLETED' | 'FAILED';

export interface CopilotStreamEvent {
  type: CopilotStreamEventType;
  questionId: string;
  attempt: number;
  seq: number;
  text?: string;
  reason?: string;
  insightId?: string;
  /** Execução (spec 016): cresce a cada nova geração; eventos de uma execução anterior são ignorados. */
  run?: number;
}

/**
 * - generating: tentativa começou, nenhum texto ainda
 * - streaming: texto chegando (rascunho, ainda não validado)
 * - adjusting: a tentativa foi descartada e outra vai começar
 * - completed: resposta validada gravada (o card passa a usar o insight)
 * - failed: nenhuma resposta (o card usa o status da pergunta)
 */
export type CopilotDraftPhase = 'generating' | 'streaming' | 'adjusting' | 'completed' | 'failed';

export interface CopilotDraft {
  run?: number;
  attempt: number;
  lastSeq: number;
  text: string;
  phase: CopilotDraftPhase;
  reason?: string;
  insightId?: string;
}

export type CopilotDrafts = Record<string, CopilotDraft>;

export const copilotStreamReducer = (state: CopilotDrafts, event: CopilotStreamEvent): CopilotDrafts => {
  if (!event?.questionId) return state;
  const current = state[event.questionId];

  // Spec 016: evento de uma execução substituída (mais antiga que a da tela) não mexe no card
  if (current && event.run != null && current.run != null && event.run < current.run) return state;

  if (event.type === 'STARTED') {
    // Um STARTED de nova execução (gerar de novo, complemento) recomeça a sequência em 0
    const newRun = !current || event.seq === 0 || (event.run != null && event.run !== current.run);
    if (!newRun && current && event.seq <= current.lastSeq) return state;
    return {
      ...state,
      [event.questionId]: { run: event.run, attempt: event.attempt, lastSeq: event.seq, text: '', phase: 'generating' },
    };
  }

  // Evento de uma execução mais nova cujo STARTED ainda não chegou: espera o STARTED
  if (current && event.run != null && current.run != null && event.run > current.run) return state;

  // Sem STARTED visto (ex.: entrou no meio): só aceita eventos de fim, sem texto parcial
  if (!current) {
    if (event.type === 'COMPLETED' || event.type === 'FAILED') {
      return {
        ...state,
        [event.questionId]: {
          run: event.run,
          attempt: event.attempt,
          lastSeq: event.seq,
          text: '',
          phase: event.type === 'COMPLETED' ? 'completed' : 'failed',
          reason: event.reason,
          insightId: event.insightId,
        },
      };
    }
    return state;
  }

  // Fora de ordem ou repetido (RF: ignora seq menor ou igual ao último aplicado)
  if (event.seq <= current.lastSeq) return state;

  switch (event.type) {
    case 'DELTA':
      if (event.attempt !== current.attempt || current.phase === 'adjusting') {
        return { ...state, [event.questionId]: { ...current, lastSeq: event.seq } };
      }
      return {
        ...state,
        [event.questionId]: { ...current, lastSeq: event.seq, text: current.text + (event.text ?? ''), phase: 'streaming' },
      };
    case 'REJECTED':
      // RN-02: o texto da tentativa rejeitada some da tela
      return { ...state, [event.questionId]: { ...current, lastSeq: event.seq, text: '', phase: 'adjusting', reason: event.reason } };
    case 'COMPLETED':
      return { ...state, [event.questionId]: { ...current, lastSeq: event.seq, phase: 'completed', insightId: event.insightId } };
    case 'FAILED':
      return { ...state, [event.questionId]: { ...current, lastSeq: event.seq, text: '', phase: 'failed', reason: event.reason } };
    default:
      return state;
  }
};

/** O card deve mostrar o rascunho (em vez da resposta gravada) enquanto a geração está em andamento. */
export const isDraftActive = (draft?: CopilotDraft): boolean =>
  !!draft && (draft.phase === 'generating' || draft.phase === 'streaming' || draft.phase === 'adjusting');
