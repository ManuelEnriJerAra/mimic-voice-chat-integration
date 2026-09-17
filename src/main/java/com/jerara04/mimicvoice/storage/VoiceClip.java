package com.jerara04.mimicvoice.storage;

import java.nio.file.Path;
import java.util.UUID;

public record VoiceClip(String id, UUID speakerId, String speakerName, Path path,
                        short[] memoryAudio, int samples, long createdAt) {

    public double durationSeconds(int sampleRate) {
        return samples / (double) sampleRate;
    }
}
