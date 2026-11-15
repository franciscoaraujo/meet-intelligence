import { describe, expect, it } from 'vitest';
import { describeChunkFailure } from './chunkStatus';

describe('describeChunkFailure', () => {
  it('não mostra nada para transcrição, sem fala ou em processamento', () => {
    expect(describeChunkFailure({ status: 'TRANSCRIBED' })).toBeNull();
    expect(describeChunkFailure({ status: 'NO_SPEECH' })).toBeNull();
    expect(describeChunkFailure({ status: 'PROCESSING' })).toBeNull();
  });

  it('orienta a configurar uma chave quando não há provedor', () => {
    expect(describeChunkFailure({ status: 'FAILED', failureReason: 'NO_PROVIDER_CONFIGURED' })).toContain('Configurações');
  });

  it('tem mensagem genérica para motivos não mapeados', () => {
    expect(describeChunkFailure({ status: 'FAILED', failureReason: 'HTTP_5XX' })).toContain('Falha ao transcrever');
  });
});
