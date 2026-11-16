import React from 'react';
import { Check, Edit2, Mic, MonitorSpeaker, Users } from 'lucide-react';
import type { TranscriptSegment } from '../../types';
import { segmentSpeakerView } from '../../features/transcription/speaker';

/** Ações estáveis (mesma referência entre renderizações) para não quebrar o React.memo. */
export interface TranscriptItemActions {
  askAi: (text: string) => void;
  startEdit: (segment: TranscriptSegment) => void;
  cancelEdit: () => void;
  saveEdit: (segmentId: string) => void;
  setEditText: (text: string) => void;
  formatMs: (ms: number) => string;
}

interface Props {
  segment: TranscriptSegment;
  highlighted: boolean;
  isEditing: boolean;
  /** Só é relevante durante a edição; fora dela, passe ''. */
  editText: string;
  actions: TranscriptItemActions;
}

/**
 * Uma fala da transcrição (spec 008, RF-10/RF-12). Memoizada: com a mesclagem por id, só a fala
 * nova ou alterada é renderizada de novo. Entra com uma transição curta (classe mi-enter).
 */
export const TranscriptItem = React.memo(function TranscriptItem({ segment, highlighted, isEditing, editText, actions }: Props) {
  const view = segmentSpeakerView(segment);
  const OriginIcon = view.origin === 'TAB' ? MonitorSpeaker : view.origin === 'MIC' ? Mic : Users;
  const text = segment.correctedText || segment.originalText;

  return (
    <div
      id={`segment-${segment.startTimeMs}`}
      data-testid="transcript-item"
      className={`mi-enter p-3.5 rounded-2xl border transition-colors space-y-1.5 group ${
        highlighted ? 'bg-[#e8f0fe] border-[#0b57d0] shadow-sm' : 'bg-[#f8fafd] border-[#e0e3e7] hover:border-[#c4c7c5]'
      }`}
    >
      <div className="flex items-center justify-between text-xs">
        <span className={`font-semibold flex items-center gap-1.5 ${view.uncertain ? 'text-[#747775] italic' : 'text-[#0b57d0]'}`} title={view.title}>
          <OriginIcon className="w-3.5 h-3.5" />
          {view.label}
          {view.uncertain && <span className="text-[10px] font-normal">(incerto)</span>}
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => actions.askAi(text)}
            className="px-2 py-0.5 rounded-full bg-[#e8f0fe] hover:bg-[#d3e3fd] text-[#0b57d0] text-[10px] font-semibold transition-colors cursor-pointer"
            title="Gerar resposta técnica para esta fala no Chat RAG"
          >
            💡 Responder com IA
          </button>
          <span className="text-[11px] font-mono text-[#444746]">{actions.formatMs(view.timeMs)}</span>
          {!isEditing && (
            <button
              type="button"
              onClick={() => actions.startEdit(segment)}
              className="opacity-0 group-hover:opacity-100 p-1 text-[#444746] hover:text-[#0b57d0] transition-opacity"
              aria-label="Corrigir texto"
            >
              <Edit2 className="w-3 h-3" />
            </button>
          )}
        </div>
      </div>

      {isEditing ? (
        <div className="mt-2 space-y-2">
          <textarea value={editText} onChange={(e) => actions.setEditText(e.target.value)} className="md-input bg-white text-xs" rows={2} />
          <div className="flex justify-end gap-2">
            <button type="button" onClick={actions.cancelEdit} className="px-2.5 py-1 text-xs text-[#444746] hover:bg-[#e0e3e7] rounded-full">
              Cancelar
            </button>
            <button
              type="button"
              onClick={() => actions.saveEdit(segment.id)}
              className="px-3 py-1 text-xs bg-[#0b57d0] text-white font-medium rounded-full flex items-center gap-1"
            >
              <Check className="w-3 h-3" /> Salvar
            </button>
          </div>
        </div>
      ) : (
        <div>
          <p className="text-sm text-[#1f1f1f] leading-relaxed">{text}</p>
          {segment.correctedText && <p className="text-[10px] text-[#747775] italic mt-0.5">Original: "{segment.originalText}"</p>}
        </div>
      )}
    </div>
  );
});
