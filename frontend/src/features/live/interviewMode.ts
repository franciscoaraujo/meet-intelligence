import type { InterviewQuestion } from '../../types';

/** Preferência do modo entrevista salva no navegador (spec 008, RF-15). */
export const INTERVIEW_MODE_STORAGE_KEY = 'meet-intelligence.interviewMode';

/** Escolha salva; sem escolha, vem ativado quando o usuário é o candidato. */
export const loadInterviewModePreference = (userInterviewRole?: 'CANDIDATE' | 'INTERVIEWER'): boolean => {
  try {
    const saved = window.localStorage.getItem(INTERVIEW_MODE_STORAGE_KEY);
    if (saved === '1') return true;
    if (saved === '0') return false;
  } catch {
    // localStorage indisponível (modo privado restrito)
  }
  return (userInterviewRole ?? 'CANDIDATE') === 'CANDIDATE';
};

export const saveInterviewModePreference = (enabled: boolean) => {
  try {
    window.localStorage.setItem(INTERVIEW_MODE_STORAGE_KEY, enabled ? '1' : '0');
  } catch {
    // ignora
  }
};

/**
 * Perguntas visíveis no modo entrevista, da mais antiga para a mais nova. Descartadas ficam fora, e os
 * complementos (spec 016) aparecem dentro do card da pergunta principal.
 */
export const visibleQuestions = (questions: InterviewQuestion[]): InterviewQuestion[] =>
  questions
    .filter((q) => q.status !== 'DISMISSED' && q.status !== 'MERGED')
    .slice()
    .sort((a, b) => (a.createdAt ?? '').localeCompare(b.createdAt ?? ''));

/** Complementos juntados a uma pergunta (spec 016), na ordem em que foram ditos. */
export const complementsOf = (questions: InterviewQuestion[], questionId: string): InterviewQuestion[] =>
  questions
    .filter((q) => q.status === 'MERGED' && q.mergedIntoId === questionId)
    .slice()
    .sort((a, b) => (a.createdAt ?? '').localeCompare(b.createdAt ?? ''));

/** Número de partes da pergunta: ela mesma + os complementos. */
export const partsOf = (questions: InterviewQuestion[], questionId: string): number =>
  1 + complementsOf(questions, questionId).length;
