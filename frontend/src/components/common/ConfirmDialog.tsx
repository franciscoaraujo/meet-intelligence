import React, { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Loader2 } from 'lucide-react';

interface Props {
  open: boolean;
  title: string;
  /** Texto explicativo; pode incluir elementos (ex.: nome do item em negrito). */
  description: React.ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Ação destrutiva: botão vermelho e foco inicial em "Cancelar". */
  destructive?: boolean;
  busy?: boolean;
  busyLabel?: string;
  /** Erro da ação, mostrado dentro do diálogo (sem alert do navegador). */
  error?: string | null;
  onConfirm: () => void;
  onCancel: () => void;
  /**
   * Spec 020 RF-11: confirmação digitada. O botão só libera quando o texto digitado é igual (sem
   * diferenciar maiúsculas) a um dos valores aceitos.
   */
  requireTyping?: { accepted: string[]; label: React.ReactNode };
}

/** Texto digitado confere com um dos valores aceitos (ignora espaços nas pontas e maiúsculas). */
export const typedConfirmationMatches = (typed: string, accepted: string[]): boolean => {
  const t = typed.trim().toLowerCase();
  return t.length > 0 && accepted.some((a) => a.trim().toLowerCase() === t);
};

/** Diálogo de confirmação do app, no lugar de window.confirm (spec 013). Fecha com Esc ou clique fora. */
export const ConfirmDialog: React.FC<Props> = ({
  open, title, description, confirmLabel = 'Confirmar', cancelLabel = 'Cancelar', destructive = false,
  busy = false, busyLabel, error, onConfirm, onCancel, requireTyping,
}) => {
  const cancelRef = useRef<HTMLButtonElement | null>(null);
  const confirmRef = useRef<HTMLButtonElement | null>(null);
  const [typed, setTyped] = useState('');
  const typingOk = !requireTyping || typedConfirmationMatches(typed, requireTyping.accepted);

  useEffect(() => {
    if (open) setTyped('');
  }, [open]);

  useEffect(() => {
    if (!open) return;
    (destructive ? cancelRef : confirmRef).current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busy) onCancel();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, destructive, busy, onCancel]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#202124]/40 p-4"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !busy) onCancel();
      }}
      data-testid="confirm-overlay"
    >
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        aria-describedby="confirm-dialog-description"
        className="mi-enter w-full max-w-md rounded-2xl bg-white shadow-xl border border-[#e8eaed] p-6 space-y-4"
      >
        <div className="flex items-start gap-3">
          {destructive && (
            <span className="shrink-0 w-10 h-10 rounded-full bg-[#fce8e6] text-[#d93025] flex items-center justify-center">
              <AlertTriangle className="w-5 h-5" />
            </span>
          )}
          <div className="space-y-1.5 min-w-0">
            <h2 id="confirm-dialog-title" className="text-base font-semibold text-[#202124]">{title}</h2>
            <div id="confirm-dialog-description" className="text-sm text-[#5f6368] leading-relaxed">{description}</div>
          </div>
        </div>

        {requireTyping && (
          <label className="block space-y-1.5">
            <span className="text-xs text-[#3c4043]">{requireTyping.label}</span>
            <input
              type="text"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              disabled={busy}
              autoComplete="off"
              className="w-full h-9 px-3 rounded-lg border border-[#dadce0] text-sm focus:outline-none focus:border-[#d93025]"
              data-testid="confirm-typing"
            />
          </label>
        )}

        {error && (
          <p role="alert" className="text-xs text-[#d93025] bg-[#fce8e6] rounded-lg px-3 py-2">{error}</p>
        )}

        <div className="flex justify-end gap-2 pt-1">
          <button
            ref={cancelRef}
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="h-9 px-4 rounded-lg text-sm font-medium text-[#3c4043] border border-[#dadce0] bg-white hover:bg-[#f8f9fa] cursor-pointer disabled:opacity-60"
          >
            {cancelLabel}
          </button>
          <button
            ref={confirmRef}
            type="button"
            onClick={onConfirm}
            disabled={busy || !typingOk}
            className={`h-9 px-4 rounded-lg text-sm font-semibold text-white inline-flex items-center gap-2 cursor-pointer disabled:opacity-70 ${
              destructive ? 'bg-[#d93025] hover:bg-[#b3261e]' : 'bg-[#1a73e8] hover:bg-[#1765cc]'
            }`}
          >
            {busy && <Loader2 className="w-4 h-4 motion-safe:animate-spin" />}
            {busy ? busyLabel ?? confirmLabel : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
};
