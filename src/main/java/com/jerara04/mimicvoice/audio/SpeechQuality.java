package com.jerara04.mimicvoice.audio;

import com.jerara04.mimicvoice.PluginSettings;

/** Applies the configured energy and hysteresis gates to a completed PCM clip. */
public final class SpeechQuality {

    private SpeechQuality() {
    }

    public static boolean hasEnoughSpeech(short[] samples, PluginSettings.VoiceActivity settings) {
        if (samples == null || samples.length == 0) {
            return false;
        }

        int frames = 0;
        int voicedFrames = 0;
        boolean sustaining = false;
        for (int offset = 0; offset < samples.length; offset += PluginSettings.FRAME_SIZE) {
            int length = Math.min(PluginSettings.FRAME_SIZE, samples.length - offset);
            double hysteresis = sustaining ? settings.releaseHysteresisDb() : 0.0;
            boolean speech = VoiceActivitySegmenter.rmsDb(samples, offset, length)
                    >= settings.activationDb() - hysteresis
                    && VoiceActivitySegmenter.peakDb(samples, offset, length) >= settings.peakDb() - hysteresis;
            frames++;
            if (speech) {
                voicedFrames++;
            }
            sustaining = speech;
        }
        return voicedFrames >= settings.minimumSpeechFrames()
                && voicedFrames / (double) frames >= settings.minimumSpeechRatio();
    }
}
