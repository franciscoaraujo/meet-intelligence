package com.meetingintelligence.domain.speech;

/** Origem do áudio de um segmento de transcrição. */
public enum AudioSource {
    /** Áudio da aba do Google Meet (participantes remotos). */
    TAB,
    /** Microfone do usuário. */
    MIC,
    /** Arquivo de gravação enviado. */
    UPLOAD,
    /** Texto digitado ou vindo de reconhecimento no navegador sem áudio no backend. */
    MANUAL
}
