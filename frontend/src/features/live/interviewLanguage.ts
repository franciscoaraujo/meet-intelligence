/** Idioma da entrevista (spec 011): captura, detecção de perguntas e respostas do copiloto. */

export type InterviewLanguageTag = 'pt-BR' | 'en-US' | 'fr-FR';

export const INTERVIEW_LANGUAGES: Array<{ tag: InterviewLanguageTag; label: string; flag: string }> = [
  { tag: 'pt-BR', label: 'Português', flag: '🇧🇷' },
  { tag: 'en-US', label: 'English', flag: '🇺🇸' },
  { tag: 'fr-FR', label: 'Français', flag: '🇫🇷' },
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
