package com.meetingintelligence.domain.speech;

/** Papel de quem falou, do ponto de vista do usuário da sessão (spec 003). */
public enum SpeakerRole {
    /** O próprio usuário (áudio do microfone dele). */
    SELF,
    /** Participante remoto (áudio da aba do Meet). */
    REMOTE,
    /** Sem evidência suficiente para afirmar quem falou. */
    UNKNOWN
}
