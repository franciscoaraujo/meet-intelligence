import { afterEach, describe, expect, it } from 'vitest';
import { LANGUAGE_STORAGE_KEY, loadLanguagePreference, saveLanguagePreference, toInterviewLanguage } from './interviewLanguage';
import { buildQuickCaptureRequest } from '../meetings/quickCapture';
import { WebSpeechMicSource, type SpeechRecognitionLike } from '../capture/webSpeechMicSource';

describe('idioma da entrevista (spec 011)', () => {
  afterEach(() => window.localStorage.clear());

  it('normaliza para pt-BR, en-US ou fr-FR (padrão português)', () => {
    expect(toInterviewLanguage('en-US')).toBe('en-US');
    expect(toInterviewLanguage('FR-fr')).toBe('fr-FR');
    expect(toInterviewLanguage('es-ES')).toBe('pt-BR');
    expect(toInterviewLanguage(undefined)).toBe('pt-BR');
  });

  it('CA-06: a última escolha fica no navegador e vira o idioma da próxima "Nova captura"', () => {
    expect(loadLanguagePreference()).toBe('pt-BR');
    expect(buildQuickCaptureRequest(new Date(2026, 10, 15, 22, 0)).language).toBe('pt-BR');

    saveLanguagePreference('fr-FR');
    expect(window.localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('fr-FR');
    expect(buildQuickCaptureRequest(new Date(2026, 10, 15, 22, 0)).language).toBe('fr-FR');
  });

  it('RF-05: o microfone em modo navegador reconhece no idioma da entrevista', () => {
    const recognition = {
      lang: '', continuous: false, interimResults: false, maxAlternatives: 1,
      onstart: null, onresult: null, onerror: null, onend: null, start() {}, stop() {},
    } as unknown as SpeechRecognitionLike;
    new WebSpeechMicSource(
      { onState: () => {}, onInterim: () => {}, onFinalText: () => {} },
      { createRecognition: () => recognition, language: 'en-US' },
    ).start();
    expect(recognition.lang).toBe('en-US');
  });
});
