import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { SourceStatusBadge } from './SourceStatusBadge';
import { emptyStats } from '../../features/capture/types';
import type { SourceView } from '../../features/capture/useCaptureSession';

const view = (patch: Partial<SourceView>): SourceView => ({ state: 'INATIVA', level: 0, stats: emptyStats(), lastError: null, ...patch });

describe('SourceStatusBadge', () => {
  it('mostra o estado de cada fonte', () => {
    render(<SourceStatusBadge kind="MIC" view={view({ state: 'PERMISSAO_NEGADA', detail: 'Libere no cadeado' })} />);
    expect(screen.getByText(/Seu microfone: Permissão negada/)).toBeTruthy();
    expect(screen.getByText('Libere no cadeado')).toBeTruthy();
  });

  it('aba interrompida oferece compartilhar novamente', () => {
    const onRetry = vi.fn();
    render(<SourceStatusBadge kind="TAB" view={view({ state: 'INTERROMPIDA' })} onRetry={onRetry} retryLabel="Compartilhar aba novamente" />);
    fireEvent.click(screen.getByText('Compartilhar aba novamente'));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('exibe erro de transcrição e trechos perdidos', () => {
    const stats = { ...emptyStats(), sent: 3, transcribed: 2, dropped: 1 };
    render(<SourceStatusBadge kind="TAB" view={view({ state: 'ATIVA_FALANDO', stats, lastError: 'Falha ao transcrever' })} />);
    expect(screen.getByRole('alert').textContent).toBe('Falha ao transcrever');
    expect(screen.getByText('1 trecho(s) perdido(s)')).toBeTruthy();
    expect(screen.getByText('2/3')).toBeTruthy();
  });
});
