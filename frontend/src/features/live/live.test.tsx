import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import React, { useRef } from 'react';
import { mergeById, upsertById } from './mergeById';
import { useStickToBottom } from './useStickToBottom';
import { INTERVIEW_MODE_STORAGE_KEY, loadInterviewModePreference, saveInterviewModePreference } from './interviewMode';
import { TranscriptItem, type TranscriptItemActions } from '../../components/live/TranscriptItem';
import { InterviewModeLayout } from '../../components/live/InterviewModeLayout';
import type { AIInsight, InterviewQuestion, TranscriptSegment } from '../../types';

const seg = (i: number, patch: Partial<TranscriptSegment> = {}): TranscriptSegment => ({
  id: `s${i}`, meetingId: 'm', originalText: `fala ${i}`, startTimeMs: i * 1000, endTimeMs: i * 1000 + 900,
  createdAt: `2026-11-15T10:00:${String(i % 60).padStart(2, '0')}Z`, audioSource: 'TAB', speakerRole: 'REMOTE', ...patch,
} as TranscriptSegment);

describe('mergeById (spec 008, RF-10)', () => {
  it('mantém o mesmo objeto para itens sem mudança e devolve prev se nada mudou', () => {
    const prev = [seg(1), seg(2)];
    const sameFromServer = [seg(1), seg(2)];
    expect(mergeById(prev, sameFromServer)).toBe(prev);

    const next = mergeById(prev, [seg(1), seg(2, { correctedText: 'corrigida' }), seg(3)]);
    expect(next[0]).toBe(prev[0]);
    expect(next[1]).not.toBe(prev[1]);
    expect(next[1].correctedText).toBe('corrigida');
    expect(next.map((s) => s.id)).toEqual(['s1', 's2', 's3']);
  });

  it('a ordem e a remoção seguem o servidor', () => {
    const prev = [seg(1), seg(2), seg(3)];
    const next = mergeById(prev, [seg(3), seg(1)]);
    expect(next.map((s) => s.id)).toEqual(['s3', 's1']);
    expect(next[0]).toBe(prev[2]);
  });

  it('upsertById: igual não muda a lista; novo entra no início ou no fim', () => {
    const prev = [seg(1)];
    expect(upsertById(prev, seg(1))).toBe(prev);
    expect(upsertById(prev, seg(2)).map((s) => s.id)).toEqual(['s1', 's2']);
    expect(upsertById(prev, seg(2), 'start').map((s) => s.id)).toEqual(['s2', 's1']);
    expect(upsertById(prev, seg(1, { originalText: 'novo' }))[0].originalText).toBe('novo');
  });
});

describe('TranscriptItem + mergeById (CA-07)', () => {
  it('polling com as mesmas 50 falas + 1 nova: só a nova é renderizada', () => {
    const formatMs = vi.fn((ms: number) => `${ms}`);
    const actions: TranscriptItemActions = {
      askAi: vi.fn(), startEdit: vi.fn(), cancelEdit: vi.fn(), saveEdit: vi.fn(), setEditText: vi.fn(), formatMs,
    };
    const List = ({ items }: { items: TranscriptSegment[] }) => (
      <div>
        {items.map((s) => (
          <TranscriptItem key={s.id} segment={s} highlighted={false} isEditing={false} editText="" actions={actions} />
        ))}
      </div>
    );
    const first = Array.from({ length: 50 }, (_, i) => seg(i));
    const { rerender } = render(<List items={first} />);
    expect(screen.getAllByTestId('transcript-item')).toHaveLength(50);
    formatMs.mockClear();

    const fromServer = [...Array.from({ length: 50 }, (_, i) => seg(i)), seg(50)];
    rerender(<List items={mergeById(first, fromServer)} />);

    expect(screen.getAllByTestId('transcript-item')).toHaveLength(51);
    expect(formatMs).toHaveBeenCalledTimes(1);
    expect(formatMs).toHaveBeenCalledWith(50_000);
  });
});

describe('useStickToBottom (CA-08)', () => {
  const makeScrollable = (el: HTMLDivElement, scrollHeight: number, clientHeight: number) => {
    Object.defineProperty(el, 'scrollHeight', { configurable: true, get: () => scrollHeight });
    Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => clientHeight });
  };

  it('no fim: acompanha as falas novas; rolado para cima: mantém a posição e conta as novas', () => {
    const el = document.createElement('div');
    makeScrollable(el, 1000, 300);
    const { result, rerender } = renderHook(({ count }) => {
      const ref = useRef<HTMLDivElement | null>(el);
      return useStickToBottom(ref, count);
    }, { initialProps: { count: 10 } });

    expect(el.scrollTop).toBe(1000); // começou no fim

    act(() => {
      el.scrollTop = 200; // usuário sobe para ler
      el.dispatchEvent(new Event('scroll'));
    });
    rerender({ count: 11 });
    expect(el.scrollTop).toBe(200);
    expect(result.current.newCount).toBe(1);

    act(() => result.current.scrollToBottom());
    expect(result.current.newCount).toBe(0);
    rerender({ count: 12 });
    expect(el.scrollTop).toBe(1000);
  });

  it('até 80 px do fim ainda conta como fim', () => {
    const el = document.createElement('div');
    makeScrollable(el, 1000, 300);
    const { result, rerender } = renderHook(({ count }) => {
      const ref = useRef<HTMLDivElement | null>(el);
      return useStickToBottom(ref, count);
    }, { initialProps: { count: 1 } });
    act(() => {
      el.scrollTop = 640; // 1000 - 640 - 300 = 60 px
      el.dispatchEvent(new Event('scroll'));
    });
    rerender({ count: 2 });
    expect(result.current.newCount).toBe(0);
    expect(el.scrollTop).toBe(1000);
  });
});

describe('modo entrevista (RF-14..RF-16, CA-09)', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => window.localStorage.clear());

  it('RF-15: sem escolha salva, ativo para candidato e inativo para entrevistador; a escolha salva prevalece', () => {
    expect(loadInterviewModePreference('CANDIDATE')).toBe(true);
    expect(loadInterviewModePreference(undefined)).toBe(true);
    expect(loadInterviewModePreference('INTERVIEWER')).toBe(false);
    saveInterviewModePreference(false);
    expect(window.localStorage.getItem(INTERVIEW_MODE_STORAGE_KEY)).toBe('0');
    expect(loadInterviewModePreference('CANDIDATE')).toBe(false);
  });

  const question = (id: string, text: string, createdAt: string, patch: Partial<InterviewQuestion> = {}): InterviewQuestion => ({
    id, meetingId: 'm', status: 'ANSWERED', questionText: text, utteranceType: 'DIRECT_QUESTION', score: 0.9,
    autoAnswer: true, sourceSegmentIds: [], answerVersion: 1, createdAt, answerInsightId: `i-${id}`, ...patch,
  });
  const answer = (qid: string, spoken: string): AIInsight => ({
    id: `i-${qid}`, meetingId: 'm', category: 'QUESTION', title: '❓', description: spoken, createdAt: 'x', questionId: qid,
    metadata: JSON.stringify({ copilotAnswer: { is_question_for_candidate: true, spoken_answer: spoken } }),
  } as AIInsight);
  const formatMs = (ms: number) => `${ms}`;

  it('CA-09: pergunta nova fica em destaque; a anterior continua no histórico com o seu card', () => {
    const q1 = question('q1', 'O que é SOLID?', '2026-11-15T10:00:00Z');
    const insights = [answer('q1', 'SOLID são cinco princípios.')];
    const { rerender } = render(<InterviewModeLayout questions={[q1]} insights={insights} drafts={{}} formatMs={formatMs} />);
    expect(screen.getByTestId('spoken-answer').textContent).toBe('SOLID são cinco princípios.');

    const q2 = question('q2', 'Como funciona o Kafka?', '2026-11-15T10:01:00Z', { status: 'ANSWERING', answerInsightId: undefined });
    rerender(
      <InterviewModeLayout questions={[q1, q2]} insights={insights} formatMs={formatMs}
        drafts={{ q2: { attempt: 1, lastSeq: 2, text: 'O Kafka é um log', phase: 'streaming' } }} />
    );
    expect(screen.getByTestId('spoken-answer').textContent).toBe('O Kafka é um log');
    expect(screen.getByTestId('draft-badge')).toBeTruthy();

    fireEvent.click(screen.getByText('O que é SOLID?'));
    expect(screen.getByTestId('spoken-answer').textContent).toBe('SOLID são cinco princípios.');

    // Uma terceira pergunta volta o destaque para a mais recente
    const q3 = question('q3', 'E sobre Redis?', '2026-11-15T10:02:00Z', { status: 'ANSWERING', answerInsightId: undefined });
    rerender(<InterviewModeLayout questions={[q1, q2, q3]} insights={insights} drafts={{}} formatMs={formatMs} />);
    expect(screen.getAllByText(/E sobre Redis?/).length).toBeGreaterThan(1); // destaque + histórico
  });

  it('botão "Pergunta terminou" sempre visível; desabilitado enquanto a pergunta é enviada', () => {
    const onAnswerLatest = vi.fn();
    const { rerender } = render(<InterviewModeLayout questions={[]} insights={[]} drafts={{}} formatMs={formatMs} onAnswerLatest={onAnswerLatest} />);
    expect(screen.getByTestId('interview-empty')).toBeTruthy();
    fireEvent.click(screen.getByTestId('interview-question-finished'));
    expect(onAnswerLatest).toHaveBeenCalledTimes(1);

    rerender(<InterviewModeLayout questions={[]} insights={[]} drafts={{}} formatMs={formatMs} onAnswerLatest={onAnswerLatest} manualPending />);
    const button = screen.getByTestId('interview-question-finished') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Enviando pergunta');
  });

  it('perguntas descartadas não aparecem', () => {
    const q = question('q1', 'Era só um comentário', '2026-11-15T10:00:00Z', { status: 'DISMISSED' });
    render(<InterviewModeLayout questions={[q]} insights={[]} drafts={{}} formatMs={formatMs} />);
    expect(screen.getByTestId('interview-empty')).toBeTruthy();
  });
});

// Evita aviso de React não usado em alguns ambientes de JSX automático
void React;
