/** Tipos da captura de áudio (spec 001). */

export type SourceKind = 'TAB' | 'MIC';

export type SourceState =
  | 'INATIVA'
  | 'SOLICITANDO_PERMISSAO'
  | 'ATIVA_FALANDO'
  | 'ATIVA_SILENCIO'
  | 'SEM_AUDIO'
  | 'PERMISSAO_NEGADA'
  | 'DISPOSITIVO_INDISPONIVEL'
  | 'INTERROMPIDA'
  | 'RECUPERANDO'
  | 'ERRO';

/** Estados em que a fonte está capturando. */
export const ACTIVE_STATES: ReadonlySet<SourceState> = new Set(['ATIVA_FALANDO', 'ATIVA_SILENCIO']);

/** Trecho de fala delimitado pelo VAD, com horário no relógio da sessão. */
export interface AudioSegment {
  id: string;
  source: SourceKind;
  startMs: number;
  endMs: number;
  pcm: Float32Array;
  sampleRate: number;
}

export interface UploadStats {
  sent: number;
  transcribed: number;
  noSpeech: number;
  failed: number;
  retrying: number;
  dropped: number;
}

export const emptyStats = (): UploadStats => ({
  sent: 0,
  transcribed: 0,
  noSpeech: 0,
  failed: 0,
  retrying: 0,
  dropped: 0,
});

/** Modo do microfone: pipeline do backend (padrão) ou Web Speech do navegador (modo anterior). */
export type MicMode = 'BACKEND' | 'WEB_SPEECH';
