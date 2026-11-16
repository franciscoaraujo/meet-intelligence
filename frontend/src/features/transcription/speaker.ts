import type { TranscriptSegment } from '../../types';

export type SpeakerRole = 'SELF' | 'REMOTE' | 'UNKNOWN';

/** Abaixo disso a atribuição do falante é exibida como incerta (spec 003). */
export const LOW_SPEAKER_CONFIDENCE = 0.5;

export interface SegmentSpeakerView {
  label: string;
  origin: 'TAB' | 'MIC' | 'UPLOAD' | 'MANUAL' | 'UNKNOWN';
  uncertain: boolean;
  /** Horário exibido: relógio da sessão de captura quando disponível (spec 001/003). */
  timeMs: number;
  title: string;
}

const ORIGIN_TITLE: Record<SegmentSpeakerView['origin'], string> = {
  TAB: 'Áudio do computador (aba ou tela inteira)',
  MIC: 'Seu microfone',
  UPLOAD: 'Gravação enviada',
  MANUAL: 'Texto digitado',
  UNKNOWN: 'Origem não registrada',
};

export const segmentSpeakerView = (segment: TranscriptSegment): SegmentSpeakerView => {
  const origin = segment.audioSource ?? 'UNKNOWN';
  const confidence = segment.speakerConfidence;
  const uncertain = segment.speakerRole === 'UNKNOWN' || (confidence !== undefined && confidence !== null && confidence < LOW_SPEAKER_CONFIDENCE);
  return {
    label: segment.speakerLabel || 'Desconhecido',
    origin,
    uncertain,
    timeMs: segment.captureStartMs ?? segment.startTimeMs,
    title: `${ORIGIN_TITLE[origin]}${uncertain ? ' · falante incerto' : ''}`,
  };
};

/** Opções do falante para texto digitado: o usuário logado (SELF) e participantes remotos. */
export const manualSpeakerOptions = (userName?: string | null) => [
  { label: userName && userName.trim() ? userName.trim() : 'Você', role: 'SELF' as SpeakerRole },
  { label: 'Entrevistador', role: 'REMOTE' as SpeakerRole },
  { label: 'Participante convidado', role: 'REMOTE' as SpeakerRole },
];
