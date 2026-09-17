package com.jerara04.mimicvoice.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.jerara04.mimicvoice.PluginSettings;

class SpeechQualityTest {

    private static final PluginSettings.VoiceActivity SETTINGS =
            new PluginSettings.VoiceActivity(
                    -42.0, -32.0, 10.0, 5.0,
                    100, 1200, 500, 900, 0.25, 30, 1500);

    @Test
    void denseSpeechPassesAndSparseSpikesFail() {
        short[] dense = new short[PluginSettings.FRAME_SIZE * 30];
        short[] sparse = new short[PluginSettings.FRAME_SIZE * 150];
        for (int frame = 0; frame < 30; frame++) {
            writeSineFrame(dense, frame);
        }
        for (int frame = 0; frame < 150; frame += 5) {
            writeSineFrame(sparse, frame);
        }

        assertTrue(SpeechQuality.hasEnoughSpeech(dense, SETTINGS));
        assertFalse(SpeechQuality.hasEnoughSpeech(sparse, SETTINGS));
    }

    private static void writeSineFrame(short[] samples, int frame) {
        int offset = frame * PluginSettings.FRAME_SIZE;
        for (int index = 0; index < PluginSettings.FRAME_SIZE; index++) {
            double angle = 2.0 * Math.PI * 220.0 * index / PluginSettings.SAMPLE_RATE;
            samples[offset + index] = (short) Math.round(Math.sin(angle) * 8_000.0);
        }
    }
}
