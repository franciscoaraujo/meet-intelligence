import { describe, expect, it } from 'vitest';
import type { InterviewQuestion } from '../../types';
import { complementsOf, partsOf, visibleQuestions } from './interviewMode';

const q = (id: string, patch: Partial<InterviewQuestion> = {}): InterviewQuestion => ({
  id, meetingId: 'm', status: 'ANSWERED', questionText: id, utteranceType: 'DIRECT_QUESTION', score: 0.9,
  autoAnswer: true, sourceSegmentIds: [], answerVersion: 1, createdAt: `2026-11-16T10:00:0${id.length}Z`, ...patch,
});

describe('perguntas em várias partes (spec 016)', () => {
  const questions = [
    q('a'),
    q('bb', { status: 'MERGED', mergedIntoId: 'a' }),
    q('ccc', { status: 'MERGED', mergedIntoId: 'a' }),
    q('dddd'),
    q('eeeee', { status: 'DISMISSED' }),
  ];

  it('RF-08: complementos não aparecem como item próprio', () => {
    expect(visibleQuestions(questions).map((x) => x.id)).toEqual(['a', 'dddd']);
  });

  it('conta as partes e lista os complementos na ordem em que foram ditos', () => {
    expect(partsOf(questions, 'a')).toBe(3);
    expect(partsOf(questions, 'dddd')).toBe(1);
    expect(complementsOf(questions, 'a').map((x) => x.id)).toEqual(['bb', 'ccc']);
  });
});
