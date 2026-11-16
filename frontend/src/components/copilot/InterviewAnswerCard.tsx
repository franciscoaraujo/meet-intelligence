import React, { useEffect, useState } from 'react';
import { Copy, Check, RefreshCw, XCircle, Loader2, ChevronRight, Languages, Sparkles, Split } from 'lucide-react';
import type { AIInsight, InterviewQuestion } from '../../types';
import { parseCopilotMetadata, SECTION_TITLES, type CopilotTranslation } from '../../features/copilot/copilotAnswer';
import { INTERVIEW_LANGUAGES } from '../../features/live/interviewLanguage';
import { isDraftActive, type CopilotDraft } from '../../features/copilot/copilotStream';

interface Props {
  question?: InterviewQuestion;
  /** Spec 016: partes da pergunta (ela + complementos "e mais uma coisa…"). */
  parts?: number;
  /** Spec 016: desfaz a junção dos complementos desta pergunta. */
  onSplit?: (questionId: string) => void;
  insight?: AIInsight;
  /** Rascunho em streaming (spec 008); enquanto ativo, substitui a resposta gravada. */
  draft?: CopilotDraft;
  /** 'focus' = destaque do modo entrevista (fonte maior). */
  variant?: 'normal' | 'focus';
  onRegenerate?: (questionId: string) => void;
  onDismiss?: (questionId: string) => void;
  onJump?: (timeMs: number) => void;
  /** Spec 012: traduz o card (pergunta, resposta falada e seções). */
  onTranslate?: (questionId: string, language: string) => Promise<CopilotTranslation>;
  formatMs: (ms: number) => string;
}

const STATUS_LABEL: Record<InterviewQuestion['status'], string> = {
  DETECTED: 'Pergunta detectada',
  ANSWERING: 'Gerando resposta…',
  ANSWERED: 'Respondida',
  FAILED: 'Não foi possível gerar a resposta',
  DISMISSED: 'Descartada',
  MERGED: 'Juntada à pergunta anterior',
};

/**
 * Card do copiloto (spec 004, RF-08): versão para falar em destaque, seções expansíveis e ações.
 * Também mostra perguntas ainda sem resposta (gerando, falha ou aguardando confirmação).
 */
export const InterviewAnswerCard: React.FC<Props> = ({
  question, insight, draft, variant = 'normal', onRegenerate, onDismiss, onJump, onTranslate, formatMs, parts = 1, onSplit,
}) => {
  const [copied, setCopied] = useState(false);
  const [contextOpen, setContextOpen] = useState(false);
  const meta = parseCopilotMetadata(insight?.metadata);
  const questionId = question?.id ?? insight?.questionId;
  const originalTitle = question?.questionText ?? insight?.title?.replace(/^❓\s*/, '') ?? '';
  const status = question?.status ?? (insight ? 'ANSWERED' : 'DETECTED');
  const streaming = isDraftActive(draft);
  const originalSpoken = streaming ? draft!.text : meta?.answer.spoken_answer ?? (meta ? '' : insight?.description ?? '');

  // Spec 012: tradução sob demanda; as já gravadas no card aparecem sem nova chamada
  const [shownLanguage, setShownLanguage] = useState<string | null>(null);
  const [localTranslations, setLocalTranslations] = useState<Record<string, CopilotTranslation>>({});
  const [translating, setTranslating] = useState<string | null>(null);
  const [translateError, setTranslateError] = useState<string | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  useEffect(() => {
    // Resposta nova (gerar de novo): as traduções antigas não valem mais
    setShownLanguage(null);
    setLocalTranslations({});
    setTranslateError(null);
  }, [meta?.answer.spoken_answer]);
  const translations = { ...(meta?.translations ?? {}), ...localTranslations };
  const shown = !streaming && shownLanguage ? translations[shownLanguage] : undefined;
  const canTranslate = !!onTranslate && !!meta && !streaming && !!questionId && !!originalSpoken;
  const languageLabel = (tag: string) => {
    const l = INTERVIEW_LANGUAGES.find((x) => x.tag === tag);
    return l ? l.label : tag;
  };

  const translateTo = async (tag: string) => {
    setMenuOpen(false);
    setTranslateError(null);
    if (translations[tag]) {
      setShownLanguage(tag);
      return;
    }
    if (!onTranslate || !questionId) return;
    setTranslating(tag);
    try {
      const result = await onTranslate(questionId, tag);
      setLocalTranslations((prev) => ({ ...prev, [tag]: result }));
      setShownLanguage(tag);
    } catch {
      setTranslateError('Não foi possível traduzir agora.');
    } finally {
      setTranslating(null);
    }
  };

  // Spec 015: a pergunta completa escrita pelo copiloto (com o cenário) vira o título
  const title = shown?.question || meta?.answer.question || originalTitle;
  const spoken = shown?.spokenAnswer || originalSpoken;
  const focus = variant === 'focus';
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
    <div className="p-4 rounded-xl border space-y-2.5 bg-white border-[#e8eaed] shadow-[0_1px_2px_rgba(60,64,67,0.08)]" data-testid="answer-card">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5">
          <span className="inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider px-2 py-0.5 rounded-md bg-[#e8f0fe] text-[#1a73e8]">
            <Sparkles className="w-3 h-3" /> Copiloto
          </span>
          {parts > 1 && (
            <span
              className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-[#f3e8fd] text-[#6a1b9a]"
              title="O entrevistador completou a pergunta; a resposta cobre todas as partes"
              data-testid="parts-badge"
            >
              {parts} partes
            </span>
          )}
          {meta?.simulated && (
            <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-[#fef7e0] text-[#5f4300]" title="Gerada sem modelo de IA">
              Simulação
            </span>
          )}
          {streaming && draft!.phase === 'streaming' && (
            <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-[#e8eaed] text-[#444746]" title="Ainda não conferida com o currículo" data-testid="draft-badge">
              Rascunho
            </span>
          )}
          {streaming && draft!.phase !== 'streaming' && (
            <span className="text-[10px] font-medium text-[#444746] flex items-center gap-1" data-testid="draft-status">
              <Loader2 className="w-3 h-3 animate-spin" />
              {draft!.phase === 'adjusting' ? 'Ajustando a resposta…' : 'Gerando resposta…'}
            </span>
          )}
          {!streaming && status !== 'ANSWERED' && (
            <span className={`text-[10px] font-medium ${status === 'FAILED' ? 'text-[#b3261e]' : 'text-[#444746]'} flex items-center gap-1`}>
              {status === 'ANSWERING' && <Loader2 className="w-3 h-3 animate-spin" />}
              {STATUS_LABEL[status]}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
        {canTranslate && (
          <div className="relative">
            <button
              type="button"
              onClick={() => setMenuOpen((v) => !v)}
              disabled={translating !== null}
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              className="text-[11px] font-medium text-[#0b57d0] flex items-center gap-1 px-2 py-0.5 rounded-full border border-[#a8c7fa] bg-white hover:bg-[#e8f0fe] cursor-pointer disabled:opacity-70"
              data-testid="translate-button"
            >
              {translating ? <Loader2 className="w-3 h-3 motion-safe:animate-spin" /> : <Languages className="w-3 h-3" />}
              {translating ? 'Traduzindo…' : 'Traduzir'}
            </button>
            {menuOpen && (
              <div role="menu" className="absolute right-0 z-10 mt-1 w-36 rounded-xl border border-[#e0e3e7] bg-white shadow-md py-1">
                {INTERVIEW_LANGUAGES.map((l) => (
                  <button
                    key={l.tag}
                    type="button"
                    role="menuitem"
                    onClick={() => void translateTo(l.tag)}
                    className={`w-full text-left px-3 py-1.5 text-xs hover:bg-[#f0f4f9] cursor-pointer ${shownLanguage === l.tag ? 'font-semibold text-[#0b57d0]' : 'text-[#1f1f1f]'}`}
                  >
                    <span className="inline-block w-6 text-[10px] font-semibold text-[#80868b]">{l.short}</span>{l.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
        {timeMs !== undefined && timeMs !== null && onJump && (
          <button onClick={() => onJump(timeMs)} className="text-[11px] font-mono text-[#0b57d0] hover:underline flex items-center gap-0.5">
            {formatMs(timeMs)}
            <ChevronRight className="w-3 h-3" />
          </button>
        )}
        </div>
      </div>

      {shown && (
        <div className="flex items-center gap-2 text-[11px]" data-testid="translation-badge">
          <span className="px-2 py-0.5 rounded-full bg-[#e6f4ea] text-[#137333] font-semibold">Traduzido: {languageLabel(shown.language)}</span>
          <button type="button" onClick={() => setShownLanguage(null)} className="font-medium text-[#0b57d0] hover:underline cursor-pointer">
            Ver original
          </button>
        </div>
      )}
      {translateError && (
        <p role="alert" className="text-[11px] text-[#b3261e]">
          {translateError}
        </p>
      )}

      <h3 className="text-[13px] font-semibold leading-snug text-[#202124]">{title}</h3>
      {question?.contextText && (
        <button
          type="button"
          onClick={() => setContextOpen((v) => !v)}
          aria-expanded={contextOpen}
          title={contextOpen ? 'Recolher o contexto' : 'Ver o contexto inteiro'}
          className="block w-full text-left text-[11px] leading-snug text-[#5f6368] cursor-pointer hover:text-[#3c4043]"
          data-testid="question-context"
        >
          <span className={`block ${contextOpen ? '' : 'line-clamp-2'}`}>
            <span className="font-semibold text-[#444746]">Contexto: </span>
            {question.contextText}
          </span>
        </button>
      )}

      {streaming && !spoken && (
        <div className="bg-white border border-[#d3e3fd] rounded-xl p-3 space-y-2" aria-hidden="true" data-testid="answer-skeleton">
          <div className="h-2.5 rounded bg-[#e8eaed] w-11/12 motion-safe:animate-pulse" />
          <div className="h-2.5 rounded bg-[#e8eaed] w-9/12 motion-safe:animate-pulse" />
          <div className="h-2.5 rounded bg-[#e8eaed] w-10/12 motion-safe:animate-pulse" />
        </div>
      )}

      {spoken && (
        <p
          className={`${focus ? 'text-base' : 'text-[13px]'} leading-relaxed bg-white border rounded-xl p-3 whitespace-pre-line ${
            streaming ? 'text-[#3c4043] border-dashed border-[#c6dafc] bg-[#fafbff]' : 'text-[#202124] border-[#e8eaed] bg-[#f8f9fa]'
          }`}
          data-testid="spoken-answer"
          aria-live="polite"
        >
          {spoken}
          {streaming && <span className="inline-block w-1.5 h-3.5 ml-0.5 align-middle bg-[#0b57d0] motion-safe:animate-pulse" aria-hidden="true" />}
        </p>
      )}

      {meta && !streaming && (
        <details className="text-xs text-[#1f1f1f]">
          <summary className="cursor-pointer text-[#0b57d0] font-medium">Detalhes da resposta</summary>
          <div className="mt-2 space-y-1.5">
            {SECTION_TITLES.map(([key, label]) => {
              const value = shown?.sections?.[key] || meta.answer.sections?.[key];
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
          {spoken && !streaming && (
            <button onClick={copy} className="text-[11px] font-medium text-[#0b57d0] flex items-center gap-1 cursor-pointer">
              {copied ? <Check className="w-3 h-3" /> : <Copy className="w-3 h-3" />} {copied ? 'Copiado' : 'Copiar'}
            </button>
          )}
          {onRegenerate && status !== 'ANSWERING' && !streaming && (
            <button onClick={() => onRegenerate(questionId)} className="text-[11px] font-medium text-[#0b57d0] flex items-center gap-1 cursor-pointer">
              <RefreshCw className="w-3 h-3" /> {status === 'ANSWERED' ? 'Gerar de novo' : status === 'FAILED' ? 'Tentar novamente' : 'Responder'}
            </button>
          )}
          {onSplit && parts > 1 && !streaming && (
            <button
              onClick={() => onSplit(questionId)}
              className="text-[11px] font-medium text-[#444746] flex items-center gap-1 cursor-pointer"
              title="Responder cada parte em um card separado"
              data-testid="split-button"
            >
              <Split className="w-3 h-3" /> Separar
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
