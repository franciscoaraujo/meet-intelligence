import { describe, expect, it } from 'vitest';
import { manualSpeakerOptions, segmentSpeakerView } from './speaker';
import type { TranscriptSegment } from '../../types';

const seg = (patch: Partial<TranscriptSegment>): TranscriptSegment => ({
  id: '1', meetingId: 'm', originalText: 'x', startTimeMs: 5000, endTimeMs: 6000, ...patch,
});

describe('segmentSpeakerView', () => {
  it('CA-03: fala da aba aparece como participante remoto, com origem identificada', () => {
    const v = segmentSpeakerView(seg({ audioSource: 'TAB', speakerRole: 'REMOTE', speakerLabel: 'Participante remoto', speakerConfidence: 0.8 }));
    expect(v.label).toBe('Participante remoto');
    expect(v.origin).toBe('TAB');
    expect(v.uncertain).toBe(false);
  });

  it('falante desconhecido ou de baixa confiança é exibido como incerto', () => {
    expect(segmentSpeakerView(seg({ speakerRole: 'UNKNOWN' })).uncertain).toBe(true);
    expect(segmentSpeakerView(seg({ speakerRole: 'REMOTE', speakerConfidence: 0.3 })).uncertain).toBe(true);
  });

  it('usa o horário de captura quando existe; segmentos antigos usam startTimeMs', () => {
    expect(segmentSpeakerView(seg({ captureStartMs: 125000 })).timeMs).toBe(125000);
    expect(segmentSpeakerView(seg({})).timeMs).toBe(5000);
    expect(segmentSpeakerView(seg({})).origin).toBe('UNKNOWN');
  });
});

describe('manualSpeakerOptions', () => {
  it('usa o nome do usuário logado em vez de um nome fixo', () => {
    expect(manualSpeakerOptions('Maria Souza')[0]).toEqual({ label: 'Maria Souza', role: 'SELF' });
    expect(manualSpeakerOptions(undefined)[0].label).toBe('Você');
    expect(manualSpeakerOptions('x').slice(1).every((o) => o.role === 'REMOTE')).toBe(true);
  });
});
