package com.meetingintelligence.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Gera WAV PCM 16 kHz mono sintético (silêncio) com a duração pedida. Sem dados pessoais. */
public final class WavFixtures {

    private WavFixtures() {}

    public static byte[] wav(long durationMs) {
        int sampleRate = 16000;
        int samples = (int) (sampleRate * durationMs / 1000);
        int dataSize = samples * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataSize).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(dataSize);
        return b.array();
    }
}
