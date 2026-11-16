import React from 'react';
import { Mic, MonitorSpeaker } from 'lucide-react';
import type { SourceView } from '../../features/capture/useCaptureSession';
import type { CaptureSurface, SourceKind, SourceState } from '../../features/capture/types';

/** Nome da fonte do computador conforme a superfície escolhida (spec 009, RF-03). */
const SURFACE_LABEL: Record<CaptureSurface, string> = {
  browser: 'Áudio do computador (aba)',
  monitor: 'Áudio do computador (tela inteira)',
  window: 'Áudio do computador (janela)',
  unknown: 'Áudio do computador',
};

const STATE_LABEL: Record<SourceState, string> = {
  INATIVA: 'Inativa',
  SOLICITANDO_PERMISSAO: 'Aguardando permissão…',
  ATIVA_FALANDO: 'Captando fala',
  ATIVA_SILENCIO: 'Ativa (silêncio)',
  SEM_AUDIO: 'Sem áudio',
  PERMISSAO_NEGADA: 'Permissão negada',
  DISPOSITIVO_INDISPONIVEL: 'Dispositivo indisponível',
  INTERROMPIDA: 'Interrompida',
  RECUPERANDO: 'Reconectando…',
  ERRO: 'Erro',
};

const tone = (state: SourceState) => {
  if (state === 'ATIVA_FALANDO' || state === 'ATIVA_SILENCIO') return { dot: 'bg-[#137333]', text: 'text-[#137333]', bg: 'bg-[#e6f4ea]' };
  if (state === 'SOLICITANDO_PERMISSAO' || state === 'RECUPERANDO') return { dot: 'bg-[#b06000]', text: 'text-[#b06000]', bg: 'bg-[#fef7e0]' };
  if (state === 'INATIVA') return { dot: 'bg-[#747775]', text: 'text-[#444746]', bg: 'bg-[#f8fafd]' };
  return { dot: 'bg-[#b3261e]', text: 'text-[#b3261e]', bg: 'bg-[#fce8e6]' };
};

interface Props {
  kind: SourceKind;
  view: SourceView;
  /** Ação de recuperação exibida em estados interrompidos (ex.: compartilhar a aba de novo). */
  onRetry?: () => void;
  retryLabel?: string;
}

/** Estado, nível e envios de uma fonte de áudio (spec 001, RF-04/RF-11). */
export const SourceStatusBadge: React.FC<Props> = ({ kind, view, onRetry, retryLabel }) => {
  const t = tone(view.state);
  const active = view.state === 'ATIVA_FALANDO' || view.state === 'ATIVA_SILENCIO';
  const Icon = kind === 'TAB' ? MonitorSpeaker : Mic;
  const showRetry = onRetry && ['INTERROMPIDA', 'SEM_AUDIO', 'ERRO', 'DISPOSITIVO_INDISPONIVEL'].includes(view.state);

  return (
    <div className={`px-3.5 py-1.5 border-b border-[#e0e3e7] ${t.bg} shrink-0`} data-testid={`source-${kind}`}>
      <div className="flex items-center justify-between gap-3">
        <span className={`text-[11px] font-semibold flex items-center gap-1.5 ${t.text}`}>
          <span className={`w-2 h-2 rounded-full ${t.dot} ${view.state === 'ATIVA_FALANDO' ? 'animate-pulse' : ''}`} />
          <Icon className="w-3.5 h-3.5" />
          {kind === 'TAB' ? SURFACE_LABEL[view.surface ?? 'unknown'] : 'Seu microfone'}: {STATE_LABEL[view.state]}
        </span>
        <div className="flex items-center gap-2">
          {active && (
            <div className="w-24 h-2 bg-white rounded-full overflow-hidden border border-[#c4eed0]" aria-label="nível de áudio">
              <div className="h-full bg-[#137333] transition-all duration-100" style={{ width: `${Math.max(4, view.level)}%` }} />
            </div>
          )}
          {view.stats.sent > 0 && (
            <span className="text-[10px] text-[#444746]" title="trechos transcritos / enviados">
              {view.stats.transcribed}/{view.stats.sent}
            </span>
          )}
          {view.stats.retrying > 0 && <span className="text-[10px] text-[#b06000]">reenviando…</span>}
          {view.stats.dropped > 0 && (
            <span className="text-[10px] font-semibold text-[#b3261e]">{view.stats.dropped} trecho(s) perdido(s)</span>
          )}
          {showRetry && (
            <button type="button" onClick={onRetry} className="text-[11px] font-semibold text-[#0b57d0] hover:underline cursor-pointer">
              {retryLabel ?? 'Tentar novamente'}
            </button>
          )}
        </div>
      </div>
      {view.detail && !active && <p className={`text-[11px] mt-0.5 ${t.text}`}>{view.detail}</p>}
      {view.lastError && (
        <p role="alert" className="text-[11px] mt-0.5 text-[#b3261e]">
          {view.lastError}
        </p>
      )}
    </div>
  );
};
