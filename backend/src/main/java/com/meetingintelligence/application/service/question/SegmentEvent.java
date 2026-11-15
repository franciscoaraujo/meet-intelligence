package com.meetingintelligence.application.service.question;

import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.speech.AudioSource;
import com.meetingintelligence.domain.speech.SpeakerRole;

import java.util.UUID;

/**
 * Fala nova (ou continuação fundida a um segmento) entregue à detecção de perguntas.
 *
 * @param text somente o texto novo (delta), nunca o segmento fundido inteiro (spec 005, RF-11)
 */
public record SegmentEvent(UUID meetingId, UUID segmentId, String text, AudioSource audioSource, SpeakerRole speakerRole,
                           InterviewRole interviewRole, Long captureStartMs, Long captureEndMs) {}
