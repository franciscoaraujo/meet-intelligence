import React, { useState } from 'react';
import { Copy, Check, RefreshCw, XCircle, Loader2, ChevronRight } from 'lucide-react';
import type { AIInsight, InterviewQuestion } from '../../types';
import { parseCopilotMetadata, SECTION_TITLES } from '../../features/copilot/copilotAnswer';

interface Props {
  question?: InterviewQuestion;
  insight?: AIInsight;
  onRegenerate?: (questionId: string) => void;
  onDismiss?: (questionId: string) => void;
  onJump?: (timeMs: number) => void;
  formatMs: (ms: number) => string;
}

const STATUS_LABEL: Record<InterviewQuestion['status'], string> = {
  DETECTED: 'Pergunta detectada',
  ANSWERING: 'Gerando resposta…',
  ANSWERED: 'Respondida',
  FAILED: 'Não foi possível gerar a resposta',
  DISMISSED: 'Descartada',
};

/**
 * Card do copiloto (spec 004, RF-08): versão para falar em destaque, seções expansíveis e ações.
 * Também mostra perguntas ainda sem resposta (gerando, falha ou aguardando confirmação).
 */
export const InterviewAnswerCard: React.FC<Props> = ({ question, insight, onRegenerate, onDismiss, onJump, formatMs }) => {
  const [copied, setCopied] = useState(false);
  const meta = parseCopilotMetadata(insight?.metadata);
  const questionId = question?.id ?? insight?.questionId;
  const title = question?.questionText ?? insight?.title?.replace(/^❓\s*/, '') ?? '';
  const status = question?.status ?? (insight ? 'ANSWERED' : 'DETECTED');
  const spoken = meta?.answer.spoken_answer ?? (meta ? '' : insight?.description ?? '');
  const timeMs = question?.captureStartMs ?? insight?.startTimeMs;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(spoken);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // área de transferência indisponível (ex.: página sem foco)
    }
  };

  return (
    <div className="p-4 rounded-2xl border space-y-2 shadow-2xs bg-[#f0f6ff] border-[#0b57d0]/40" data-testid="answer-card">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5">
          <span className="text-[10px] font-semibold uppercase tracking-wider px-2.5 py-0.5 rounded-full bg-[#0b57d0] text-white">
            🎯 Copiloto
          </span>
          {meta?.simulated && (
            <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-[#fef7e0] text-[#5f4300]" title="Gerada sem modelo de IA">
              Simulação
            </span>
          )}
          {status !== 'ANSWERED' && (
            <span className={`text-[10px] font-medium ${status === 'FAILED' ? 'text-[#b3261e]' : 'text-[#444746]'} flex items-center gap-1`}>
              {status === 'ANSWERING' && <Loader2 className="w-3 h-3 animate-spin" />}
              {STATUS_LABEL[status]}
            </span>
          )}
        </div>
        {timeMs !== undefined && timeMs !== null && onJump && (
          <button onClick={() => onJump(timeMs)} className="text-[11px] font-mono text-[#0b57d0] hover:underline flex items-center gap-0.5">
            {formatMs(timeMs)}
            <ChevronRight className="w-3 h-3" />
          </button>
        )}
      </div>

      <h3 className="text-xs font-semibold text-[#1f1f1f]">❓ {title}</h3>

      {spoken && (
        <p className="text-[13px] text-[#1f1f1f] leading-relaxed bg-white border border-[#d3e3fd] rounded-xl p-3 whitespace-pre-line" data-testid="spoken-answer">
          {spoken}
        </p>
      )}

      {meta && (
        <details className="text-xs text-[#1f1f1f]">
          <summary className="cursor-pointer text-[#0b57d0] font-medium">Detalhes da resposta</summary>
          <div className="mt-2 space-y-1.5">
            {SECTION_TITLES.map(([key, label]) => {
              const value = meta.answer.sections?.[key];
              return value ? (
                <p key={key}>
                  <span className="font-semibold">{label}:</span> {value}
                </p>
              ) : null;
            })}
            {meta.answer.assumptions && meta.answer.assumptions.length > 0 && (
              <p className="text-[11px] text-[#747775]">Observações: {meta.answer.assumptions.join(' · ')}</p>
            )}
          </div>
        </details>
      )}

      {questionId && (
        <div className="flex items-center gap-2 pt-1">
          {spoken && (
            <button onClick={copy} className="text-[11px] font-medium text-[#0b57d0] flex items-center gap-1 cursor-pointer">
              {copied ? <Check className="w-3 h-3" /> : <Copy className="w-3 h-3" />} {copied ? 'Copiado' : 'Copiar'}
            </button>
          )}
          {onRegenerate && status !== 'ANSWERING' && (
            <button onClick={() => onRegenerate(questionId)} className="text-[11px] font-medium text-[#0b57d0] flex items-center gap-1 cursor-pointer">
              <RefreshCw className="w-3 h-3" /> {status === 'ANSWERED' ? 'Gerar de novo' : status === 'FAILED' ? 'Tentar novamente' : 'Responder'}
            </button>
          )}
          {onDismiss && status !== 'DISMISSED' && (
            <button onClick={() => onDismiss(questionId)} className="text-[11px] font-medium text-[#444746] flex items-center gap-1 cursor-pointer">
              <XCircle className="w-3 h-3" /> Não era pergunta
            </button>
          )}
        </div>
      )}
    </div>
  );
};
