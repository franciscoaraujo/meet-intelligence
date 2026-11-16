import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { InterviewAnswerCard } from './InterviewAnswerCard';
import type { AIInsight, InterviewQuestion } from '../../types';
import type { CopilotDraft } from '../../features/copilot/copilotStream';

const formatMs = (ms: number) => `${Math.floor(ms / 1000)}s`;

const question = (patch: Partial<InterviewQuestion> = {}): InterviewQuestion => ({
  id: 'q1', meetingId: 'm', status: 'ANSWERED', questionText: 'Como você garante consistência?',
  utteranceType: 'DIRECT_QUESTION', score: 0.9, autoAnswer: true, sourceSegmentIds: ['s1'], answerVersion: 1,
  captureStartMs: 120000, createdAt: '2026-11-15T10:00:00Z', ...patch,
});

const insight = (simulated = false): AIInsight => ({
  id: 'i1', meetingId: 'm', category: 'QUESTION', title: '❓ Como você garante consistência?', description: 'texto',
  createdAt: '2026-11-15T10:00:01Z', questionId: 'q1',
  metadata: JSON.stringify({
    simulated,
    copilotAnswer: {
      is_question_for_candidate: true, spoken_answer: 'Uso Transactional Outbox e consumidores idempotentes.',
      sections: { direct_answer: 'Outbox + Saga', trade_offs: 'Mais complexidade operacional' },
      assumptions: ['interpretação de termo'],
    },
  }),
});

describe('InterviewAnswerCard', () => {
  it('RF-08: destaca a versão para falar e mostra as seções nos detalhes', () => {
    render(<InterviewAnswerCard question={question()} insight={insight()} formatMs={formatMs} />);
    expect(screen.getByTestId('spoken-answer').textContent).toBe('Uso Transactional Outbox e consumidores idempotentes.');
    expect(screen.getByText('Outbox + Saga')).toBeTruthy();
    expect(screen.getByText(/Mais complexidade operacional/)).toBeTruthy();
    expect(screen.queryByText('Simulação')).toBeNull();
  });

  it('CA-06: resposta simulada exibe o selo "Simulação"', () => {
    render(<InterviewAnswerCard question={question()} insight={insight(true)} formatMs={formatMs} />);
    expect(screen.getByText('Simulação')).toBeTruthy();
  });

  it('ações: gerar de novo e "não era pergunta" chamam com o id da pergunta', () => {
    const onRegenerate = vi.fn();
    const onDismiss = vi.fn();
    render(<InterviewAnswerCard question={question()} insight={insight()} formatMs={formatMs} onRegenerate={onRegenerate} onDismiss={onDismiss} />);
    fireEvent.click(screen.getByText(/Gerar de novo/));
    fireEvent.click(screen.getByText(/Não era pergunta/));
    expect(onRegenerate).toHaveBeenCalledWith('q1');
    expect(onDismiss).toHaveBeenCalledWith('q1');
  });

  it('pergunta gerando resposta mostra o estado e não oferece "gerar de novo"', () => {
    render(<InterviewAnswerCard question={question({ status: 'ANSWERING' })} formatMs={formatMs} onRegenerate={vi.fn()} />);
    expect(screen.getByText('Gerando resposta…')).toBeTruthy();
    expect(screen.queryByText(/Gerar de novo/)).toBeNull();
  });

  it('falha oferece "Tentar novamente"', () => {
    render(<InterviewAnswerCard question={question({ status: 'FAILED' })} formatMs={formatMs} onRegenerate={vi.fn()} />);
    expect(screen.getByText('Não foi possível gerar a resposta')).toBeTruthy();
    expect(screen.getByText(/Tentar novamente/)).toBeTruthy();
  });

  it('insight antigo (sem metadata do copiloto) ainda é exibido pela descrição', () => {
    const legacy: AIInsight = { id: 'old', meetingId: 'm', category: 'QUESTION', title: '❓ Pergunta: "SOLID"', description: 'resposta antiga', createdAt: 'x' };
    render(<InterviewAnswerCard insight={legacy} formatMs={formatMs} />);
    expect(screen.getByTestId('spoken-answer').textContent).toBe('resposta antiga');
  });

  describe('streaming (spec 008)', () => {
    const draft = (patch: Partial<CopilotDraft>): CopilotDraft => ({ attempt: 1, lastSeq: 1, text: '', phase: 'streaming', ...patch });

    it('RF-04: rascunho em streaming mostra o texto parcial com o selo "Rascunho", sem seções nem ações', () => {
      render(<InterviewAnswerCard question={question({ status: 'ANSWERING' })} draft={draft({ text: 'Eu uso Out' })}
        formatMs={formatMs} onRegenerate={vi.fn()} />);
      expect(screen.getByTestId('spoken-answer').textContent).toBe('Eu uso Out');
      expect(screen.getByTestId('draft-badge')).toBeTruthy();
      expect(screen.queryByText('Detalhes da resposta')).toBeNull();
      expect(screen.queryByText('Copiar')).toBeNull();
      expect(screen.queryByText(/Gerar de novo|Responder/)).toBeNull();
    });

    it('gerando sem texto ainda → esqueleto e "Gerando resposta…"', () => {
      render(<InterviewAnswerCard question={question({ status: 'ANSWERING' })} draft={draft({ phase: 'generating' })} formatMs={formatMs} />);
      expect(screen.getByTestId('answer-skeleton')).toBeTruthy();
      expect(screen.getByTestId('draft-status').textContent).toContain('Gerando resposta');
    });

    it('RF-05: tentativa rejeitada → "Ajustando a resposta…" e o texto anterior some', () => {
      render(<InterviewAnswerCard question={question({ status: 'ANSWERING' })} draft={draft({ phase: 'adjusting', text: '' })} formatMs={formatMs} />);
      expect(screen.getByTestId('draft-status').textContent).toContain('Ajustando a resposta');
      expect(screen.queryByTestId('spoken-answer')).toBeNull();
    });

    it('"Gerar de novo": enquanto o novo rascunho chega, ele substitui a resposta gravada', () => {
      render(<InterviewAnswerCard question={question()} insight={insight()} draft={draft({ text: 'versão nova' })} formatMs={formatMs} />);
      expect(screen.getByTestId('spoken-answer').textContent).toBe('versão nova');
    });

    it('rascunho concluído → volta a mostrar a resposta validada com as seções', () => {
      render(<InterviewAnswerCard question={question()} insight={insight()} draft={draft({ phase: 'completed', text: 'parcial' })} formatMs={formatMs} />);
      expect(screen.getByTestId('spoken-answer').textContent).toBe('Uso Transactional Outbox e consumidores idempotentes.');
      expect(screen.getByText('Detalhes da resposta')).toBeTruthy();
      expect(screen.queryByTestId('draft-badge')).toBeNull();
    });
  });

  describe('botão Traduzir (spec 012)', () => {
    const translation = {
      language: 'pt-BR',
      question: 'Como você garante consistência?',
      spokenAnswer: 'Eu uso o Transactional Outbox e consumidores idempotentes.',
      sections: { direct_answer: 'Outbox mais Saga' },
    };

    it('CA-05: traduz para português, mostra o selo e volta ao original', async () => {
      const onTranslate = vi.fn(async () => translation);
      render(<InterviewAnswerCard question={question()} insight={insight()} formatMs={formatMs} onTranslate={onTranslate} />);

      fireEvent.click(screen.getByTestId('translate-button'));
      fireEvent.click(screen.getByRole('menuitem', { name: /Português/ }));

      await waitFor(() => expect(screen.getByTestId('spoken-answer').textContent).toBe('Eu uso o Transactional Outbox e consumidores idempotentes.'));
      expect(onTranslate).toHaveBeenCalledWith('q1', 'pt-BR');
      expect(screen.getByTestId('translation-badge').textContent).toContain('Português');
      expect(screen.getByText('Outbox mais Saga')).toBeTruthy();

      fireEvent.click(screen.getByText('Ver original'));
      expect(screen.getByTestId('spoken-answer').textContent).toBe('Uso Transactional Outbox e consumidores idempotentes.');

      // De novo: usa a tradução já obtida, sem nova chamada
      fireEvent.click(screen.getByTestId('translate-button'));
      fireEvent.click(screen.getByRole('menuitem', { name: /Português/ }));
      await waitFor(() => expect(screen.getByTestId('translation-badge')).toBeTruthy());
      expect(onTranslate).toHaveBeenCalledTimes(1);
    });

    it('falha na tradução: mensagem e o original continua', async () => {
      const onTranslate = vi.fn(async () => {
        throw new Error('503');
      });
      render(<InterviewAnswerCard question={question()} insight={insight()} formatMs={formatMs} onTranslate={onTranslate} />);

      fireEvent.click(screen.getByTestId('translate-button'));
      fireEvent.click(screen.getByRole('menuitem', { name: /English/ }));

      await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Não foi possível traduzir'));
      expect(screen.getByTestId('spoken-answer').textContent).toBe('Uso Transactional Outbox e consumidores idempotentes.');
    });

    it('tradução já gravada no card aparece sem chamar a API; sem resposta concluída não há botão', () => {
      const onTranslate = vi.fn();
      const withCache = insight();
      const meta = JSON.parse(withCache.metadata as string);
      meta.translations = { 'fr-FR': { ...translation, language: 'fr-FR', spokenAnswer: "J'utilise le Transactional Outbox." } };
      withCache.metadata = JSON.stringify(meta);
      render(<InterviewAnswerCard question={question()} insight={withCache} formatMs={formatMs} onTranslate={onTranslate} />);

      fireEvent.click(screen.getByTestId('translate-button'));
      fireEvent.click(screen.getByRole('menuitem', { name: /Français/ }));
      expect(screen.getByTestId('spoken-answer').textContent).toBe("J'utilise le Transactional Outbox.");
      expect(onTranslate).not.toHaveBeenCalled();
    });

    it('rascunho em streaming não mostra o botão', () => {
      render(<InterviewAnswerCard question={question({ status: 'ANSWERING' })} insight={insight()} formatMs={formatMs} onTranslate={vi.fn()}
        draft={{ attempt: 1, lastSeq: 1, text: 'parcial', phase: 'streaming' }} />);
      expect(screen.queryByTestId('translate-button')).toBeNull();
    });
  });
});
