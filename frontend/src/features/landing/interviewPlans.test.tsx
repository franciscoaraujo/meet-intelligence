import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { INTERVIEW_PLANS, contactHref, trialPath } from './interviewPlans';
import { InterviewPlansSection } from '../../components/landing/InterviewPlansSection';

const renderSection = (isAuthenticated: boolean, contactEmail: string | null = null) =>
  render(
    <MemoryRouter initialEntries={['/landing']}>
      <Routes>
        <Route
          path="/landing"
          element={<InterviewPlansSection isAuthenticated={isAuthenticated} contactEmail={contactEmail} />}
        />
        <Route path="/login" element={<p>tela de cadastro</p>} />
        <Route path="/" element={<p>painel</p>} />
      </Routes>
    </MemoryRouter>,
  );

describe('adesão ao copiloto de entrevistas (spec 019)', () => {
  afterEach(() => vi.restoreAllMocks());

  it('CA-01/CA-04: três planos, "Grátis" e duas vezes "Sob consulta", sem valor em R$', () => {
    renderSection(false);
    const section = screen.getByTestId('interview-plans');

    expect(section.id).toBe('planos-entrevista');
    expect(INTERVIEW_PLANS).toHaveLength(3);
    expect(screen.getByTestId('plan-price-trial').textContent).toBe('Grátis');
    expect(screen.getByTestId('plan-price-pro').textContent).toBe('Sob consulta');
    expect(screen.getByTestId('plan-price-intensive').textContent).toBe('Sob consulta');
    expect(section.textContent).not.toMatch(/R\$/);
    expect(within(section).getByText('Como aderir')).toBeTruthy();
  });

  it('CA-02: "Começar teste grátis" leva o visitante ao cadastro', () => {
    renderSection(false);
    fireEvent.click(screen.getByTestId('plan-cta-trial'));
    expect(screen.getByText('tela de cadastro')).toBeTruthy();
  });

  it('CA-02: usuário logado vai ao painel', () => {
    renderSection(true);
    expect(screen.getByTestId('plan-cta-trial').textContent).toContain('Ir para o painel');
    fireEvent.click(screen.getByTestId('plan-cta-trial'));
    expect(screen.getByText('painel')).toBeTruthy();
  });

  it('CA-03: sem e-mail de contato, "Fale conosco" leva ao cadastro', () => {
    renderSection(false, null);
    fireEvent.click(screen.getByTestId('plan-cta-pro'));
    expect(screen.getByText('tela de cadastro')).toBeTruthy();
  });

  it('CA-03: com e-mail configurado, gera mailto com o plano no assunto', () => {
    const pro = INTERVIEW_PLANS.find((p) => p.id === 'pro')!;
    expect(contactHref(pro, ' vendas@exemplo.com ')).toBe(
      'mailto:vendas@exemplo.com?subject=' + encodeURIComponent('Plano Profissional - copiloto de entrevistas'),
    );
    expect(contactHref(pro, '')).toBe('/login');
    expect(contactHref(pro, undefined)).toBe('/login');
    expect(trialPath(false)).toBe('/login');
    expect(trialPath(true)).toBe('/');
  });
});
