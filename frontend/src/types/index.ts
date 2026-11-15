export type MeetingStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
export type CaptureMechanism = 'GOOGLE_DRIVE' | 'BOT' | 'BROWSER_CAPTURE' | 'FILE_UPLOAD';
export type MeetingType = 'INTERVIEW' | 'TECHNICAL' | 'SALES' | 'CORPORATE' | 'CLASS' | 'GENERAL';

export interface User {
  id: string;
  email: string;
  fullName: string;
  avatarUrl?: string;
  organizationId: string;
  organizationName: string;
  role: string;
}

export interface Meeting {
  id: string;
  organizationId: string;
  title: string;
  description?: string;
  scheduledStart?: string;
  scheduledEnd?: string;
  actualStart?: string;
  actualEnd?: string;
  status: MeetingStatus;
  googleMeetLink?: string;
  googleCalendarEventId?: string;
  captureMechanism: CaptureMechanism;
  language: string;
  createdAt: string;
}

export interface CalendarEvent {
  id: string;
  meetingId?: string;
  title: string;
  description?: string;
  startTime: string;
  endTime: string;
  googleMeetLink?: string;
  hasMeetLink: boolean;
  attendees?: string[];
}

export interface TranscriptSegment {
  id: string;
  meetingId: string;
  speakerLabel?: string;
  originalText: string;
  correctedText?: string;
  startTimeMs: number;
  endTimeMs: number;
  confidence?: number;
  /** Instante real em que a fala foi registrada no servidor; ausente em itens otimistas recém-criados. */
  createdAt?: string;
}

export interface AIInsight {
  id: string;
  meetingId: string;
  category: 'CONTEXT' | 'QUESTION' | 'DECISION' | 'TASK' | 'CONTENT_ANALYSIS' | 'ALERT';
  title: string;
  description: string;
  startTimeMs?: number;
  endTimeMs?: number;
  speakerReference?: string;
  createdAt: string;
}

export interface MeetingSummary {
  meetingId: string;
  version: number;
  executiveSummary?: string;
  keyTopics?: string[];
  currentContext?: string;
}

export interface ChatMessage {
  id: string;
  senderType: 'USER' | 'ASSISTANT' | 'SYSTEM';
  content: string;
  citations?: Array<{
    startTimeMs: number;
    endTimeMs: number;
    textSnippet: string;
  }>;
  createdAt: string;
}

