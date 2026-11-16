/** Resposta do copiloto gravada em AIInsight.metadata (spec 004). */
export interface CopilotSections {
  direct_answer?: string | null;
  technical_explanation?: string | null;
  practical_example?: string | null;
  best_practices?: string | null;
  trade_offs?: string | null;
}

export interface CopilotAnswer {
  is_question_for_candidate: boolean;
  depth?: 'BASIC' | 'INTERMEDIATE' | 'ADVANCED' | string;
  spoken_answer?: string;
  sections?: CopilotSections | null;
  resume_facts_used?: string[] | null;
  assumptions?: string[] | null;
  confidence?: number | null;
  simulated?: boolean;
  /** Pergunta completa escrita pelo copiloto (pergunta manual ou com contexto, spec 015). */
  question?: string | null;
}

/** Tradução do card (spec 012), gravada em metadata.translations[idioma]. */
export interface CopilotTranslation {
  language: string;
  question: string;
  spokenAnswer: string;
  sections: Partial<Record<keyof CopilotSections, string>>;
}

export interface CopilotMetadata {
  answer: CopilotAnswer;
  simulated: boolean;
  provider?: string;
  model?: string;
  translations: Record<string, CopilotTranslation>;
}

/** Lê a resposta estruturada do metadata do insight; null para insights antigos ou de outro tipo. */
export const parseCopilotMetadata = (metadata?: string | null): CopilotMetadata | null => {
  if (!metadata) return null;
  try {
    const raw = typeof metadata === 'string' ? JSON.parse(metadata) : metadata;
    if (!raw?.copilotAnswer) return null;
    return {
      answer: raw.copilotAnswer,
      simulated: Boolean(raw.simulated),
      provider: raw.provider,
      model: raw.model,
      translations: raw.translations && typeof raw.translations === 'object' ? raw.translations : {},
    };
  } catch {
    return null;
  }
};

export const SECTION_TITLES: Array<[keyof CopilotSections, string]> = [
  ['direct_answer', 'Resposta direta'],
  ['technical_explanation', 'Explicação técnica'],
  ['practical_example', 'Exemplo prático'],
  ['best_practices', 'Boas práticas'],
  ['trade_offs', 'Trade-offs'],
];
