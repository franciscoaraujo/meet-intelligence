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

/** Perguntas visíveis no modo entrevista, da mais antiga para a mais nova (descartadas ficam fora). */
export const visibleQuestions = (questions: InterviewQuestion[]): InterviewQuestion[] =>
  questions
    .filter((q) => q.status !== 'DISMISSED')
    .slice()
    .sort((a, b) => (a.createdAt ?? '').localeCompare(b.createdAt ?? ''));
