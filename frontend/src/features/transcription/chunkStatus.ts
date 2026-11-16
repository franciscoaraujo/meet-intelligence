import type { ChunkTranscriptionResponse } from '../../types';

/**
 * Mensagem para o usuário quando um trecho de áudio não pôde ser transcrito (spec 002).
 * Retorna null quando não há problema a mostrar (transcrito, sem fala ou em processamento).
 */
export const describeChunkFailure = (response: Pick<ChunkTranscriptionResponse, 'status' | 'failureReason'>): string | null => {
  if (response.status !== 'FAILED') return null;
  switch (response.failureReason) {
    case 'NO_PROVIDER_CONFIGURED':
      return 'Nenhum serviço de transcrição configurado. Cadastre uma chave do Gemini em Configurações.';
    case 'INVALID_AUDIO':
      return 'O áudio capturado não pôde ser lido. Tente compartilhar a aba novamente.';
    case 'TIMEOUT':
    case 'TIMEOUT_BUDGET':
      return 'O serviço de transcrição demorou demais para responder. Um trecho de fala pode ter sido perdido.';
    case 'HTTP_4XX':
      return 'O serviço de transcrição recusou a requisição. Verifique a chave de API em Configurações.';
    default:
      return 'Falha ao transcrever um trecho de fala. Os próximos trechos continuam sendo enviados.';
  }
};
