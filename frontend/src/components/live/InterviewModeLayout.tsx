import React, { useEffect, useMemo, useState } from 'react';
import { CheckCircle2, Loader2, Target } from 'lucide-react';
import type { AIInsight, InterviewQuestion } from '../../types';
import { InterviewAnswerCard } from '../copilot/InterviewAnswerCard';
import type { CopilotDrafts } from '../../features/copilot/copilotStream';
import type { CopilotTranslation } from '../../features/copilot/copilotAnswer';
import { partsOf, visibleQuestions } from '../../features/live/interviewMode';

interface Props {
  questions: InterviewQuestion[];
  insights: AIInsight[];
  drafts: CopilotDrafts;
  formatMs: (ms: number) => string;
  onJump?: (timeMs: number) => void;
  onRegenerate?: (questionId: string) => void;
  onDismiss?: (questionId: string) => void;
  /** Spec 016: desfaz a junção dos complementos da pergunta. */
  onSplit?: (questionId: string) => void;
  onTranslate?: (questionId: string, language: string) => Promise<CopilotTranslation>;
  /** Botão "Pergunta terminou": o candidato sinaliza o fim da pergunta do entrevistador. */
  onAnswerLatest?: () => void;
  manualPending?: boolean;
}

const STATUS_CHIP: Record<InterviewQuestion['status'], string> = {
  DETECTED: 'detectada',
  ANSWERING: 'gerando',
  ANSWERED: 'respondida',
  FAILED: 'falhou',
  DISMISSED: 'descartada',
  MERGED: 'juntada',
};

/**
 * Modo entrevista (spec 008, RF-14/RF-16): a pergunta atual e a resposta em destaque,
 * com o histórico compacto ao lado. Uma pergunta nova passa a ser a do destaque.
 */
export const InterviewModeLayout: React.FC<Props> = ({
  questions, insights, drafts, formatMs, onJump, onRegenerate, onDismiss, onTranslate, onAnswerLatest, manualPending = false,
  onSplit,
}) => {
  const ordered = useMemo(() => visibleQuestions(questions), [questions]);
  const latestId = ordered.length > 0 ? ordered[ordered.length - 1].id : null;
  const [pinnedId, setPinnedId] = useState<string | null>(null);

  // RF-16: pergunta nova volta o destaque para a mais recente
  useEffect(() => {
    setPinnedId(null);
  }, [latestId]);

  const focusedId = pinnedId && ordered.some((q) => q.id === pinnedId) ? pinnedId : latestId;
  const focused = ordered.find((q) => q.id === focusedId);
  const focusedInsight = focused
    ? insights.find((i) => i.category === 'QUESTION' && (i.questionId === focused.id || i.id === focused.answerInsightId))
    : undefined;
  const history = ordered.slice().reverse();

  return (
    <div className="grid grid-cols-12 gap-4 h-full min-h-0 p-4" data-testid="interview-mode">
      <div className="col-span-8 min-h-0 overflow-y-auto space-y-3">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-sm font-medium text-[#1f1f1f]">
            <Target className="w-4 h-4 text-[#0b57d0]" /> Pergunta atual
          </div>
          {onAnswerLatest && (
            <button
              type="button"
              onClick={onAnswerLatest}
              disabled={manualPending}
              className="px-4 py-2 rounded-full bg-[#0b57d0] hover:bg-[#0842a0] text-white text-xs font-medium flex items-center gap-1.5 cursor-pointer disabled:opacity-70"
              title="Clique quando o entrevistador terminar de perguntar"
              data-testid="interview-question-finished"
            >
              {manualPending ? <Loader2 className="w-3.5 h-3.5 motion-safe:animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
              {manualPending ? 'Enviando pergunta…' : 'Pergunta terminou'}
            </button>
          )}
        </div>
        {focused ? (
          <InterviewAnswerCard
            key={focused.id}
            question={focused}
            insight={focusedInsight}
            draft={drafts[focused.id]}
            variant="focus"
            formatMs={formatMs}
            onJump={onJump}
            onRegenerate={onRegenerate}
            onDismiss={onDismiss}
            onTranslate={onTranslate}
            parts={partsOf(questions, focused.id)}
            onSplit={onSplit}
          />
        ) : (
          <div className="rounded-2xl border border-dashed border-[#c4c7c5] p-8 text-center text-sm text-[#444746] space-y-3" data-testid="interview-empty">
            <Loader2 className="w-5 h-5 mx-auto text-[#0b57d0] motion-safe:animate-spin" />
            <p>Aguardando a primeira pergunta do entrevistador…</p>
            <p className="text-xs">A resposta sugerida aparece aqui assim que a pergunta for detectada.</p>
            {onAnswerLatest && (
              <p className="text-xs">Se ela não aparecer sozinha, clique em <strong>Pergunta terminou</strong> quando o entrevistador acabar de falar.</p>
            )}
          </div>
        )}
      </div>

      <aside className="col-span-4 min-h-0 overflow-y-auto">
        <div className="text-xs font-semibold text-[#444746] uppercase tracking-wider mb-2">Perguntas ({ordered.length})</div>
        {history.length === 0 ? (
          <p className="text-xs text-[#747775]">Nenhuma pergunta ainda.</p>
        ) : (
          <ul className="space-y-1.5" data-testid="question-history">
            {history.map((q) => (
              <li key={q.id}>
                <button
                  type="button"
                  onClick={() => setPinnedId(q.id)}
                  aria-current={q.id === focusedId ? 'true' : undefined}
                  className={`mi-enter w-full text-left px-3 py-2 rounded-xl border text-xs transition-colors cursor-pointer ${
                    q.id === focusedId ? 'bg-[#e8f0fe] border-[#0b57d0]' : 'bg-white border-[#e0e3e7] hover:border-[#c4c7c5]'
                  }`}
                >
                  <span className="line-clamp-2 text-[#1f1f1f]">{q.questionText}</span>
                  <span className="mt-1 flex items-center justify-between text-[10px] text-[#747775]">
                    <span>{STATUS_CHIP[q.status]}</span>
                    {q.captureStartMs != null && <span className="font-mono">{formatMs(q.captureStartMs)}</span>}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </aside>
    </div>
  );
};
