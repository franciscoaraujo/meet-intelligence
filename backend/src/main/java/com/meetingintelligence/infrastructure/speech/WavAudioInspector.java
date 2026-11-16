package com.meetingintelligence.infrastructure.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Leitura mínima de cabeçalho WAV (PCM) para validar o áudio e calcular sua duração. */
public final class WavAudioInspector {

    private WavAudioInspector() {}

    /** Duração em ms, ou -1 se o arquivo não for um WAV PCM válido. */
    public static long durationMs(byte[] wav) {
        if (wav == null || wav.length < 44) return -1;
        if (wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
                || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
            return -1;
        }
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int byteRate = buf.getInt(28);
        if (byteRate <= 0) return -1;

        // Procura o chunk "data" (o cabeçalho pode ter chunks extras antes dele)
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String id = new String(wav, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = buf.getInt(offset + 4);
            if ("data".equals(id)) {
                long available = Math.min(size, wav.length - (offset + 8L));
                if (available < 0) return -1;
                return available * 1000L / byteRate;
            }
            if (size < 0) return -1;
            offset += 8 + size + (size % 2);
        }
        return -1;
    }
}
