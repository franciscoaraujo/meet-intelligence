import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2, Radio } from 'lucide-react';
import { api } from '../../services/api';
import { buildQuickCaptureRequest } from '../../features/meetings/quickCapture';

interface Props {
  /** 'sidebar' = botão largo do menu lateral; 'primary' = botão de destaque nas páginas. */
  variant?: 'sidebar' | 'primary';
}

/**
 * "Nova captura" (spec 010): cria uma sessão sem Google Meet e abre direto a sala ao vivo, onde o
 * usuário escolhe a fonte de áudio (qualquer aba ou a tela inteira — spec 009).
 */
export const QuickCaptureButton: React.FC<Props> = ({ variant = 'primary' }) => {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await api.post<{ id: string }>('/meetings', buildQuickCaptureRequest());
      navigate(`/meetings/${res.data.id}/live`);
    } catch {
      setError('Não foi possível abrir a captura. Tente novamente.');
    } finally {
      setBusy(false);
    }
  };

  const base =
    variant === 'sidebar'
      ? 'w-full justify-center mb-3 px-4 py-3 rounded-2xl bg-[#c2e7ff] hover:bg-[#b3dbf5] text-[#001d35] text-sm font-medium shadow-sm'
      : 'px-4 py-2 rounded-full bg-[#0b57d0] hover:bg-[#0842a0] text-white text-xs font-medium';

  return (
    <div className={variant === 'sidebar' ? 'w-full' : 'inline-flex flex-col items-start'}>
      <button
        type="button"
        onClick={start}
        disabled={busy}
        className={`${base} flex items-center gap-2 transition-colors cursor-pointer disabled:opacity-60`}
        title="Abre a sala ao vivo sem Google Meet: capture uma aba (WhatsApp Web, YouTube…) ou a tela inteira"
        data-testid={`quick-capture-${variant}`}
      >
        {busy ? <Loader2 className="w-4 h-4 motion-safe:animate-spin" /> : <Radio className="w-4 h-4" />}
        <span>{busy ? 'Abrindo…' : 'Nova captura'}</span>
      </button>
      {error && (
        <p role="alert" className="mt-1 text-[11px] text-[#b3261e]">
          {error}
        </p>
      )}
    </div>
  );
};
