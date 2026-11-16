/** Idioma da entrevista (spec 011): captura, detecção de perguntas e respostas do copiloto. */

export type InterviewLanguageTag = 'pt-BR' | 'en-US' | 'fr-FR';

/** Sigla no lugar de bandeira: o Windows não desenha emoji de bandeira (aparecia "us English"). */
export const INTERVIEW_LANGUAGES: Array<{ tag: InterviewLanguageTag; label: string; short: string }> = [
  { tag: 'pt-BR', label: 'Português', short: 'PT' },
  { tag: 'en-US', label: 'English', short: 'EN' },
  { tag: 'fr-FR', label: 'Français', short: 'FR' },
];

export const LANGUAGE_STORAGE_KEY = 'meet-intelligence.interviewLanguage';

/** Normaliza qualquer valor para um idioma suportado (padrão: português). */
export const toInterviewLanguage = (value?: string | null): InterviewLanguageTag =>
  INTERVIEW_LANGUAGES.find((l) => l.tag.toLowerCase() === (value ?? '').toLowerCase())?.tag ?? 'pt-BR';

/** Última escolha do usuário (vira o idioma da próxima "Nova captura"). */
export const loadLanguagePreference = (): InterviewLanguageTag => {
  try {
    return toInterviewLanguage(window.localStorage.getItem(LANGUAGE_STORAGE_KEY));
  } catch {
    return 'pt-BR';
  }
};

export const saveLanguagePreference = (tag: InterviewLanguageTag) => {
  try {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, tag);
  } catch {
    // ignora (localStorage indisponível)
  }
};
