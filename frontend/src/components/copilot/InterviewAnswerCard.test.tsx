import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { InterviewAnswerCard } from './InterviewAnswerCard';
import type { AIInsight, InterviewQuestion } from '../../types';

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
});
