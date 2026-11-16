import { describe, expect, it } from 'vitest';
import { sortTranscriptsChronologically } from '../LiveMeetingPage';
import type { TranscriptSegment } from '../../types';

const seg = (id: string, createdAt: string | undefined, startTimeMs: number): TranscriptSegment => ({
  id,
  meetingId: 'm1',
  originalText: id,
  startTimeMs,
  endTimeMs: startTimeMs + 1000,
  createdAt,
});

describe('sortTranscriptsChronologically', () => {
  it('ordena pelo horário de registro, ignorando relógios inconsistentes de startTimeMs', () => {
    const result = sortTranscriptsChronologically([
      seg('c', '2026-11-15T10:00:03Z', 0),
      seg('a', '2026-11-15T10:00:01Z', 600000),
      seg('b', '2026-11-15T10:00:02Z', 5000),
    ]);
    expect(result.map((s) => s.id)).toEqual(['a', 'b', 'c']);
  });

  it('mantém itens ainda sem createdAt no fim, na ordem de chegada', () => {
    const result = sortTranscriptsChronologically([
      seg('temp-1', undefined, 0),
      seg('a', '2026-11-15T10:00:01Z', 0),
      seg('temp-2', undefined, 0),
    ]);
    expect(result.map((s) => s.id)).toEqual(['a', 'temp-1', 'temp-2']);
  });
});
