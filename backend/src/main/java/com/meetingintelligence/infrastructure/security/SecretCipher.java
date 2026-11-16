package com.meetingintelligence.infrastructure.security;

import com.meetingintelligence.domain.security.SecretProtector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Criptografia em repouso de segredos (spec 007, RF-14): AES-256-GCM, formato
 * {@code enc:v1:<base64(iv[12] + texto cifrado + tag)>}. A chave vem de {@code app.security.encryption-key}
 * (32 bytes em base64) e não tem valor padrão: sem ela o backend não sobe.
 * <p>
 * Valores sem o prefixo são legados em texto puro: são devolvidos como estão e cifrados pelo
 * {@link SecretEncryptionMigrator} na inicialização.
 */
@Component
public class SecretCipher implements SecretProtector {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.security.encryption-key:}") String base64Key) {
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
    }

    static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY não configurada: as chaves de IA são gravadas "
                    + "criptografadas (spec 007). Gere 32 bytes aleatórios em base64 e defina APP_ENCRYPTION_KEY "
                    + "(no backend/.env em desenvolvimento).");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY precisa estar em base64.");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY precisa ter 32 bytes (AES-256); tem " + raw.length + ".");
        }
        return raw;
    }

    @Override
    public String protect(String plain) {
        return encrypt(plain);
    }

    @Override
    public String reveal(String stored) {
        return decrypt(stored);
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) return plain;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao criptografar segredo", e);
        }
    }

    public String decrypt(String stored) {
        if (!isEncrypted(stored)) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Chave errada ou valor adulterado: não devolve lixo nem o valor cifrado como se fosse a chave
            throw new IllegalStateException("Não foi possível descriptografar um segredo: a APP_ENCRYPTION_KEY mudou?");
        }
    }
}
