package com.meetingintelligence.domain.speech;

import java.io.File;
import java.io.InputStream;

public interface SpeechToTextProvider {

    String getProviderName();

    boolean isAvailable();

    TranscriptionResult transcribeAudio(byte[] audioBytes, String mimeType, String language);

    TranscriptionResult transcribeStream(InputStream audioStream, String mimeType, String language);
}

