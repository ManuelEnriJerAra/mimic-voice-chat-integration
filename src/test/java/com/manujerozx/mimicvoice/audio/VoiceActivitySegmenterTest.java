package com.manujerozx.mimicvoice.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.manujerozx.mimicvoice.PluginSettings;

class VoiceActivitySegmenterTest {

    private static PluginSettings.VoiceActivity settings() {
        return new PluginSettings.VoiceActivity(
                -42.0, -32.0, 10.0, 5.0,
                60, 300, 300, 450, 0.25, 12, 750);
    }

    private static PluginSettings.VoiceActivity phraseSettings() {
        return new PluginSettings.VoiceActivity(
                -42.0, -32.0, 10.0, 5.0,
                100, 1200, 500, 900, 0.25, 30, 1500);
    }

    @Test
    void silenceAndLowNoiseNeverCreateAClip() {
        VoiceActivitySegmenter segmenter = new VoiceActivitySegmenter(settings());
        for (int index = 0; index < 100; index++) {
            assertNull(segmenter.accept(constantFrame((short) (index % 2 == 0 ? 70 : -70))));
        }
        assertNull(segmenter.flush());
    }

    @Test
    void shortImpulseIsRejected() {
        VoiceActivitySegmenter segmenter = new VoiceActivitySegmenter(settings());
        assertNull(segmenter.accept(sineFrame(12_000, 220.0)));
        for (int index = 0; index < settings().hangoverFrames(); index++) {
            assertNull(segmenter.accept(constantFrame((short) 0)));
        }
        assertNull(segmenter.flush());
    }

    @Test
    void speechIsAcceptedAndLongSilenceTailIsTrimmed() {
        VoiceActivitySegmenter segmenter = new VoiceActivitySegmenter(settings());
        for (int index = 0; index < 10; index++) {
            assertNull(segmenter.accept(constantFrame((short) 0)));
        }
        for (int index = 0; index < 25; index++) {
            assertNull(segmenter.accept(sineFrame(8_000, 180.0 + index)));
        }

        short[] completed = null;
        for (int index = 0; index < settings().hangoverFrames(); index++) {
            short[] result = segmenter.accept(constantFrame((short) 0));
            if (result != null) {
                completed = result;
            }
        }

        assertNotNull(completed);
        int frames = completed.length / PluginSettings.FRAME_SIZE;
        assertEquals(29, frames,
                "Configured pre-roll, speech, and one trailing boundary should remain");
    }

    @Test
    void configuredPreRollIsRetained() {
        VoiceActivitySegmenter segmenter = new VoiceActivitySegmenter(settings());
        for (int index = 0; index < 10; index++) {
            assertNull(segmenter.accept(constantFrame((short) 0)));
        }
        for (int index = 0; index < 25; index++) {
            assertNull(segmenter.accept(sineFrame(8_000, 200.0)));
        }

        short[] completed = segmenter.flush();

        assertNotNull(completed);
        assertEquals(28, completed.length / PluginSettings.FRAME_SIZE,
                "Three configured pre-roll frames should precede the speech");
        for (int index = 0; index < settings().preRollFrames() * PluginSettings.FRAME_SIZE; index++) {
            assertEquals(0, completed[index]);
        }
    }

    @Test
    void microphoneGapFlushesMeaningfulSpeech() {
        VoiceActivitySegmenter segmenter = new VoiceActivitySegmenter(settings());
        for (int index = 0; index < 24; index++) {
            assertNull(segmenter.accept(sineFrame(7_000, 200.0)));
        }
        short[] completed = segmenter.flush();
        assertNotNull(completed);
        assertTrue(completed.length >= 24 * PluginSettings.FRAME_SIZE);
    }

    @Test
    void naturalPauseStaysInsideOnePhrase() {
        PluginSettings.VoiceActivity phraseSettings = phraseSettings();
        VoiceActivitySegmenter segmenter = new VoiceActivitySegmenter(phraseSettings);

        for (int index = 0; index < 25; index++) {
            assertNull(segmenter.accept(sineFrame(8_000, 180.0 + index)));
        }
        for (int index = 0; index < 40; index++) {
            assertNull(segmenter.accept(constantFrame((short) 0)),
                    "An 800 ms pause must not end the phrase");
        }
        for (int index = 0; index < 25; index++) {
            assertNull(segmenter.accept(sineFrame(8_000, 220.0 + index)));
        }

        short[] completed = null;
        for (int index = 0; index < phraseSettings.hangoverFrames(); index++) {
            short[] result = segmenter.accept(constantFrame((short) 0));
            if (result != null) {
                completed = result;
            }
        }

        assertNotNull(completed);
        int frames = completed.length / PluginSettings.FRAME_SIZE;
        assertTrue(frames >= 90 && frames <= 92,
                "Both speech sections and their pause should remain in one phrase, got "
                        + frames + " frames");
    }

    @Test
    void scatteredNoiseSpikesAreRejectedAsAClip() {
        PluginSettings.VoiceActivity phraseSettings = phraseSettings();
        VoiceActivitySegmenter segmenter = new VoiceActivitySegmenter(phraseSettings);

        for (int index = 0; index < 150; index++) {
            short[] frame = index % 5 == 0
                    ? sineFrame(8_000, 180.0 + index)
                    : constantFrame((short) 0);
            assertNull(segmenter.accept(frame));
        }
        for (int index = 0; index < phraseSettings.hangoverFrames(); index++) {
            assertNull(segmenter.accept(constantFrame((short) 0)));
        }
        assertNull(segmenter.flush());
    }

    private static short[] constantFrame(short value) {
        short[] frame = new short[PluginSettings.FRAME_SIZE];
        java.util.Arrays.fill(frame, value);
        return frame;
    }

    private static short[] sineFrame(int amplitude, double frequency) {
        short[] frame = new short[PluginSettings.FRAME_SIZE];
        for (int index = 0; index < frame.length; index++) {
            double angle = 2.0 * Math.PI * frequency * index / PluginSettings.SAMPLE_RATE;
            frame[index] = (short) Math.round(Math.sin(angle) * amplitude);
        }
        return frame;
    }
}
