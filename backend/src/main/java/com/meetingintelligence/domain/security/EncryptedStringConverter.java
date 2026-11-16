package com.meetingintelligence.domain.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Colunas com segredo (chaves de IA, token do Google) gravadas protegidas (spec 007, RF-14). O Hibernate
 * obtém este conversor do contêiner do Spring, que injeta o {@link SecretProtector}.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final SecretProtector protector;

    public EncryptedStringConverter(SecretProtector protector) {
        this.protector = protector;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return protector.protect(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return protector.reveal(dbData);
    }
}
