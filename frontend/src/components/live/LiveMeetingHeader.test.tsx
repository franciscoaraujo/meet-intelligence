import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { LiveMeetingHeader } from './LiveMeetingHeader';

const props = (patch: Partial<React.ComponentProps<typeof LiveMeetingHeader>> = {}) => ({
  title: 'Captura de 16/11 10:30',
  onBack: vi.fn(),
  language: 'en-US' as const,
  onLanguageChange: vi.fn(),
  answerStyle: 'NEUTRAL' as const,
  onAnswerStyleChange: vi.fn(),
  role: 'CANDIDATE' as const,
  onRoleChange: vi.fn(),
  connected: true,
  interviewMode: false,
  onToggleInterviewMode: vi.fn(),
  computerAudioOn: false,
  onToggleComputerAudio: vi.fn(),
  manualPending: false,
  onQuestionFinished: vi.fn(),
  analyzing: false,
  onConsolidate: vi.fn(),
  uploading: false,
  onUpload: vi.fn(),
  micMode: 'BACKEND' as const,
  onMicModeChange: vi.fn(),
  isRecording: false,
  elapsed: '00:00',
  onToggleRecording: vi.fn(),
  ...patch,
});

describe('LiveMeetingHeader (spec 013)', () => {
  it('CA-01: controles principais no cabeçalho; upload, consolidar e microfone no menu "⋯"', () => {
    const p = props();
    render(<LiveMeetingHeader {...p} />);

    expect(screen.getByTestId('computer-audio-toggle')).toBeTruthy();
    expect(screen.getByTestId('question-finished')).toBeTruthy();
    expect(screen.queryByText(/Enviar gravação/)).toBeNull();

    fireEvent.click(screen.getByTestId('more-actions'));
    fireEvent.click(screen.getByRole('menuitem', { name: /Consolidar análise/ }));
    expect(p.onConsolidate).toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('more-actions'));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /Reconhecimento do navegador/ }));
    expect(p.onMicModeChange).toHaveBeenCalledWith('WEB_SPEECH');
  });

  it('idiomas sem emoji de bandeira; papel e idioma mudam pelo cabeçalho', () => {
    const p = props();
    render(<LiveMeetingHeader {...p} />);

    const select = screen.getByTestId('interview-language') as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.textContent)).toEqual(['PT · Português', 'EN · English', 'FR · Français']);
    fireEvent.change(select, { target: { value: 'fr-FR' } });
    expect(p.onLanguageChange).toHaveBeenCalledWith('fr-FR');

    fireEvent.click(screen.getByRole('button', { name: 'Entrevistador' }));
    expect(p.onRoleChange).toHaveBeenCalledWith('INTERVIEWER');
  });

  it('CA-02: no modo entrevista não há "Pergunta terminou" no cabeçalho e o microfone fica travado em desligado', () => {
    render(<LiveMeetingHeader {...props({ interviewMode: true })} />);

    expect(screen.queryByTestId('question-finished')).toBeNull();
    fireEvent.click(screen.getByTestId('more-actions'));
    const off = screen.getByRole('menuitemradio', { name: /Desligado/ }) as HTMLButtonElement;
    expect(off.getAttribute('aria-checked')).toBe('true');
    expect(off.disabled).toBe(true);
    expect(screen.getByText(/só o participante remoto é captado/)).toBeTruthy();
  });

  it('CA-03: gravando, o botão mostra o cronômetro e "Encerrar"', () => {
    const p = props({ isRecording: true, elapsed: '07:14' });
    render(<LiveMeetingHeader {...p} />);

    const button = screen.getByTestId('recording-toggle');
    expect(button.textContent).toContain('07:14');
    expect(button.textContent).toContain('Encerrar');
    fireEvent.click(button);
    expect(p.onToggleRecording).toHaveBeenCalled();
  });

  it('spec 014: seletor de estilo com Padrão, Informal, Formal e Técnica', () => {
    const p = props();
    render(<LiveMeetingHeader {...p} />);
    const select = screen.getByTestId('answer-style') as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['NEUTRAL', 'INFORMAL', 'FORMAL', 'TECHNICAL']);
    fireEvent.change(select, { target: { value: 'TECHNICAL' } });
    expect(p.onAnswerStyleChange).toHaveBeenCalledWith('TECHNICAL');
  });
});
