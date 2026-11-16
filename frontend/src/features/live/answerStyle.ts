/** Estilo das respostas do copiloto (spec 014): muda o tom, nunca os fatos. */

export type AnswerStyle = 'NEUTRAL' | 'INFORMAL' | 'FORMAL' | 'TECHNICAL';

export const ANSWER_STYLES: Array<{ value: AnswerStyle; label: string; hint: string }> = [
  { value: 'NEUTRAL', label: 'Padrão', hint: 'Tom profissional neutro' },
  { value: 'INFORMAL', label: 'Informal', hint: 'Tom de conversa, frases curtas, menos jargão' },
  { value: 'FORMAL', label: 'Formal', hint: 'Vocabulário cuidado: contexto, solução e resultado' },
  { value: 'TECHNICAL', label: 'Técnica', hint: 'Termos precisos, padrões, trade-offs e números do currículo' },
];

export const ANSWER_STYLE_STORAGE_KEY = 'meet-intelligence.answerStyle';

export const toAnswerStyle = (value?: string | null): AnswerStyle =>
  ANSWER_STYLES.find((s) => s.value === (value ?? '').toUpperCase())?.value ?? 'NEUTRAL';

/** Última escolha do usuário (vira o estilo da próxima "Nova captura"). */
export const loadAnswerStylePreference = (): AnswerStyle => {
  try {
    return toAnswerStyle(window.localStorage.getItem(ANSWER_STYLE_STORAGE_KEY));
  } catch {
    return 'NEUTRAL';
  }
};

export const saveAnswerStylePreference = (style: AnswerStyle) => {
  try {
    window.localStorage.setItem(ANSWER_STYLE_STORAGE_KEY, style);
  } catch {
    // ignora (localStorage indisponível)
  }
};
