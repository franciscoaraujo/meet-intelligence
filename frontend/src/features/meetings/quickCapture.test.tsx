import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { buildQuickCaptureRequest, isQuickCapture, quickCaptureTitle } from './quickCapture';

const post = vi.fn();
vi.mock('../../services/api', () => ({ api: { post: (...args: unknown[]) => post(...args) } }));

// Importados depois do mock da API
const { QuickCaptureButton } = await import('../../components/meetings/QuickCaptureButton');
const { NewMeetingModal } = await import('../../components/modals/NewMeetingModal');

describe('captura avulsa (spec 010)', () => {
  beforeEach(() => {
    post.mockReset();
  });

  it('RF-01: título "Captura de dd/mm HH:mm", sem link do Meet, 1 h de duração', () => {
    const now = new Date(2026, 10, 15, 9, 5);
    const body = buildQuickCaptureRequest(now);
    expect(quickCaptureTitle(now)).toBe('Captura de 15/11 09:05');
    expect(body).toMatchObject({ title: 'Captura de 15/11 09:05', captureMechanism: 'BROWSER_CAPTURE', meetingType: 'GENERAL', language: 'pt-BR' });
    expect(body).not.toHaveProperty('googleMeetLink');
    expect(new Date(body.scheduledEnd).getTime() - new Date(body.scheduledStart).getTime()).toBe(3_600_000);
  });

  it('CA-04: reunião sem link (ou com link em branco) é captura avulsa', () => {
    expect(isQuickCapture({})).toBe(true);
    expect(isQuickCapture({ googleMeetLink: null })).toBe(true);
    expect(isQuickCapture({ googleMeetLink: '  ' })).toBe(true);
    expect(isQuickCapture({ googleMeetLink: 'https://meet.google.com/abc-defg-hij' })).toBe(false);
  });

  const renderButton = () =>
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<QuickCaptureButton variant="primary" />} />
          <Route path="/meetings/:id/live" element={<p>sala ao vivo</p>} />
        </Routes>
      </MemoryRouter>
    );

  it('CA-01: "Nova captura" cria a sessão sem link e abre a sala ao vivo', async () => {
    post.mockResolvedValue({ data: { id: 'm-123' } });
    renderButton();

    fireEvent.click(screen.getByTestId('quick-capture-primary'));

    await waitFor(() => expect(screen.getByText('sala ao vivo')).toBeTruthy());
    expect(post).toHaveBeenCalledWith('/meetings', expect.objectContaining({ title: expect.stringMatching(/^Captura de \d{2}\/\d{2} \d{2}:\d{2}$/) }));
    expect(post.mock.calls[0][1]).not.toHaveProperty('googleMeetLink');
  });

  it('CA-02: falha ao criar → mensagem de erro e nenhuma navegação', async () => {
    post.mockImplementation(async () => {
      throw Object.assign(new Error('500'), { response: { status: 500 } });
    });
    renderButton();

    fireEvent.click(screen.getByTestId('quick-capture-primary'));

    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Não foi possível abrir a captura'));
    expect(screen.queryByText('sala ao vivo')).toBeNull();
    expect(screen.getByText('Nova captura')).toBeTruthy();
  });

  describe('CA-03: modal "Nova reunião" com link opcional', () => {
    const fill = (title: string, link: string) => {
      fireEvent.change(screen.getByPlaceholderText('https://meet.google.com/xxx-yyyy-zzz'), { target: { value: link } });
      const titleInput = document.querySelector('input[type="text"]') as HTMLInputElement;
      fireEvent.change(titleInput, { target: { value: title } });
      fireEvent.submit(titleInput.closest('form')!);
    };

    it('sem link: cria a reunião sem googleMeetLink', async () => {
      post.mockResolvedValue({ data: { id: 'x' } });
      const onCreated = vi.fn();
      render(<NewMeetingModal isOpen onClose={vi.fn()} onCreated={onCreated} />);
      fill('Entrevista WhatsApp', '');
      await waitFor(() => expect(onCreated).toHaveBeenCalled());
      expect(post.mock.calls[0][1].googleMeetLink).toBeUndefined();
    });

    it('link que não é do Meet: erro de validação, sem POST', async () => {
      render(<NewMeetingModal isOpen onClose={vi.fn()} onCreated={vi.fn()} />);
      fill('Reunião', 'https://zoom.us/j/123');
      await waitFor(() => expect(screen.getByText(/não é do Google Meet/)).toBeTruthy());
      expect(post).not.toHaveBeenCalled();
    });
  });
});
