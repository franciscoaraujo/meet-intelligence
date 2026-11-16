package com.meetingintelligence.domain.enums;

public enum KnowledgeDocumentType {
    /** Currículo / perfil profissional: sempre enviado inteiro para a IA. */
    RESUME,
    /** Material de referência (arquitetura, documentação etc.): apenas os trechos relevantes são enviados. */
    REFERENCE
}
