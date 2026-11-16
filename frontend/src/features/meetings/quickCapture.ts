/** Captura avulsa (spec 010): sessão de captura sem Google Meet, criada em um clique. */
import { loadLanguagePreference } from '../live/interviewLanguage';

const pad = (n: number) => String(n).padStart(2, '0');

/** Título automático: "Captura de dd/mm HH:mm" (horário local). */
export const quickCaptureTitle = (now: Date): string =>
  `Captura de ${pad(now.getDate())}/${pad(now.getMonth() + 1)} ${pad(now.getHours())}:${pad(now.getMinutes())}`;

/** Corpo do POST /meetings para uma captura avulsa (sem googleMeetLink). */
export const buildQuickCaptureRequest = (now: Date = new Date(), language: string = loadLanguagePreference()) => ({
  title: quickCaptureTitle(now),
  description: 'Captura avulsa (sem Google Meet)',
  scheduledStart: now.toISOString(),
  scheduledEnd: new Date(now.getTime() + 60 * 60 * 1000).toISOString(),
  captureMechanism: 'BROWSER_CAPTURE' as const,
  meetingType: 'GENERAL' as const,
  // Spec 011: idioma da última escolha do usuário
  language,
});

/** Reunião sem link do Meet: exibida como "Captura avulsa" nas listas. */
export const isQuickCapture = (meeting: { googleMeetLink?: string | null }): boolean =>
  !meeting.googleMeetLink || !meeting.googleMeetLink.trim();
