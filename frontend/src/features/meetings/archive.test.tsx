import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ConfirmDialog, typedConfirmationMatches } from '../../components/common/ConfirmDialog';

const get = vi.fn();
const post = vi.fn();
const del = vi.fn();
vi.mock('../../services/api', () => ({
  api: {
    get: (...a: unknown[]) => get(...a),
    post: (...a: unknown[]) => post(...a),
    delete: (...a: unknown[]) => del(...a),
  },
}));

const { MeetingsPage } = await import('../../pages/MeetingsPage');

const meeting = (id: string, title: string) => ({
  id, title, status: 'COMPLETED', createdAt: '2026-11-16T20:00:00Z', captureMechanism: 'BROWSER_CAPTURE',
});

describe('arquivar em vez de excluir (spec 020)', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    del.mockReset();
    get.mockImplementation(async (url: string) => {
      if (url.startsWith('/calendar')) return { data: [] };
      if (url.includes('archived=true')) return { data: { content: [meeting('a1', 'Entrevista antiga')] } };
      return { data: { content: [meeting('m1', 'Entrevista Java')] } };
    });
    post.mockResolvedValue({ data: {} });
    del.mockResolvedValue({});
  });

  const renderPage = () => render(<MemoryRouter><MeetingsPage /></MemoryRouter>);

  it('RF-09/RF-10: a lista pede só as ativas, e o card oferece "Arquivar" (sem excluir)', async () => {
    renderPage();
    await screen.findByText('Entrevista Java');
    expect(get).toHaveBeenCalledWith('/meetings?size=50&archived=false');
    expect(screen.getByTestId('archive-m1')).toBeTruthy();
    expect(screen.queryByTestId('delete-m1')).toBeNull();
  });

  it('CA-07: arquivar confirma, chama /archive e tira o card da lista', async () => {
    renderPage();
    fireEvent.click(await screen.findByTestId('archive-m1'));
    fireEvent.click(screen.getByRole('button', { name: 'Arquivar' }));
    await waitFor(() => expect(post).toHaveBeenCalledWith('/meetings/m1/archive'));
    await waitFor(() => expect(screen.queryByText('Entrevista Java')).toBeNull());
    expect(del).not.toHaveBeenCalled();
  });

  it('RF-09: a aba Arquivadas lista as arquivadas, com Restaurar e Excluir definitivamente', async () => {
    renderPage();
    await screen.findByText('Entrevista Java');
    fireEvent.click(screen.getByTestId('tab-archived'));
    await screen.findByText('Entrevista antiga');
    expect(get).toHaveBeenCalledWith('/meetings?size=50&archived=true');

    fireEvent.click(screen.getByTestId('restore-a1'));
    await waitFor(() => expect(post).toHaveBeenCalledWith('/meetings/a1/unarchive'));
  });

  it('CA-08: excluir definitivamente só confirma com o título ou EXCLUIR digitado', async () => {
    renderPage();
    await screen.findByText('Entrevista Java');
    fireEvent.click(screen.getByTestId('tab-archived'));
    fireEvent.click(await screen.findByTestId('delete-a1'));

    const confirm = screen.getByRole('button', { name: 'Excluir definitivamente' }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    fireEvent.change(screen.getByTestId('confirm-typing'), { target: { value: 'excluir tudo' } });
    expect(confirm.disabled).toBe(true);
    fireEvent.change(screen.getByTestId('confirm-typing'), { target: { value: 'excluir' } });
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    await waitFor(() => expect(del).toHaveBeenCalledWith('/meetings/a1'));
  });
});

describe('confirmação digitada', () => {
  it('aceita o título ou a palavra, sem diferenciar maiúsculas; vazio nunca', () => {
    expect(typedConfirmationMatches('  Entrevista Java ', ['Entrevista Java', 'EXCLUIR'])).toBe(true);
    expect(typedConfirmationMatches('EXCLUIR', ['Entrevista Java', 'EXCLUIR'])).toBe(true);
    expect(typedConfirmationMatches('entrevista', ['Entrevista Java', 'EXCLUIR'])).toBe(false);
    expect(typedConfirmationMatches('', ['', 'EXCLUIR'])).toBe(false);
  });

  it('sem requireTyping, o diálogo continua como antes', () => {
    const onConfirm = vi.fn();
    render(<ConfirmDialog open title="T" description="D" onConfirm={onConfirm} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar' }));
    expect(onConfirm).toHaveBeenCalled();
  });
});
