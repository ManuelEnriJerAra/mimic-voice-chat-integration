package com.manujerozx.mimicvoice.audio;

import java.util.Arrays;

import com.manujerozx.mimicvoice.PluginSettings;

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
            int end = Math.min(samples.length, offset + PluginSettings.FRAME_SIZE);
            short[] frame = Arrays.copyOfRange(samples, offset, end);
            double hysteresis = sustaining ? settings.releaseHysteresisDb() : 0.0;
            boolean speech = VoiceActivitySegmenter.rmsDb(frame)
                    >= settings.activationDb() - hysteresis
                    && VoiceActivitySegmenter.peakDb(frame) >= settings.peakDb() - hysteresis;
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
