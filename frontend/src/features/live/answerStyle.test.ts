import { afterEach, describe, expect, it } from 'vitest';
import { ANSWER_STYLE_STORAGE_KEY, loadAnswerStylePreference, saveAnswerStylePreference, toAnswerStyle } from './answerStyle';
import { buildQuickCaptureRequest } from '../meetings/quickCapture';

describe('estilo das respostas (spec 014)', () => {
  afterEach(() => window.localStorage.clear());

  it('normaliza valores (padrão NEUTRAL)', () => {
    expect(toAnswerStyle('formal')).toBe('FORMAL');
    expect(toAnswerStyle('TECHNICAL')).toBe('TECHNICAL');
    expect(toAnswerStyle('xyz')).toBe('NEUTRAL');
    expect(toAnswerStyle(undefined)).toBe('NEUTRAL');
  });

  it('CA-04: a última escolha fica no navegador e vai para a próxima "Nova captura"', () => {
    expect(loadAnswerStylePreference()).toBe('NEUTRAL');
    expect(buildQuickCaptureRequest(new Date(2026, 10, 16, 10, 0)).answerStyle).toBe('NEUTRAL');

    saveAnswerStylePreference('INFORMAL');
    expect(window.localStorage.getItem(ANSWER_STYLE_STORAGE_KEY)).toBe('INFORMAL');
    expect(buildQuickCaptureRequest(new Date(2026, 10, 16, 10, 0)).answerStyle).toBe('INFORMAL');
  });
});
