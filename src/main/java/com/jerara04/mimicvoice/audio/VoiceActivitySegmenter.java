package com.jerara04.mimicvoice.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import com.jerara04.mimicvoice.PluginSettings;

/**
 * Adaptive energy-based voice activity segmentation for 48 kHz mono PCM frames.
 * It rejects silence-only streams and short impulses, while retaining a very small
 * amount of context around real speech so words do not lose their first phoneme.
 */
public final class VoiceActivitySegmenter {

    private record Frame(short[] samples, boolean speech) {
    }

    private final PluginSettings.VoiceActivity settings;
    private final Deque<Frame> preRoll = new ArrayDeque<>();
    private final List<Frame> activeFrames = new ArrayList<>();

    private boolean active;
    private int voicedFrames;
    private int silentTailFrames;
    private double noiseFloorDb = -60.0;

    public VoiceActivitySegmenter(PluginSettings.VoiceActivity settings) {
        this.settings = settings;
    }

    /**
     * Accepts one decoded packet. Returns a completed speech clip, or {@code null}.
     */
    public short[] accept(short[] samples) {
        return samples == null ? null : accept(samples, 0, samples.length);
    }

    /**
     * Accepts a range of a decoder-owned PCM buffer. The range is copied once because
     * active phrases retain frames beyond the decoder call's ownership boundary.
     */
    public short[] accept(short[] samples, int offset, int length) {
        if (samples == null || length <= 0 || offset < 0 || offset > samples.length - length) {
            return null;
        }

        short[] frame = Arrays.copyOfRange(samples, offset, offset + length);
        double rmsDb = rmsDb(samples, offset, length);
        double peakDb = peakDb(samples, offset, length);
        boolean speech = isSpeech(rmsDb, peakDb, active);

        if (!active) {
            if (!speech) {
                updateNoiseFloor(rmsDb);
                rememberPreRoll(new Frame(frame, false));
                return null;
            }

            active = true;
            activeFrames.addAll(preRoll);
            preRoll.clear();
            activeFrames.add(new Frame(frame, true));
            voicedFrames = 1;
            silentTailFrames = 0;
            return activeFrames.size() >= settings.maximumClipFrames() ? finish() : null;
        }

        activeFrames.add(new Frame(frame, speech));
        if (speech) {
            voicedFrames++;
            silentTailFrames = 0;
        } else {
            silentTailFrames++;
        }

        if (activeFrames.size() >= settings.maximumClipFrames()
                || silentTailFrames >= settings.hangoverFrames()) {
            return finish();
        }
        return null;
    }

    /**
     * Completes speech after the microphone stream goes idle.
     */
    public short[] flush() {
        return active ? finish() : null;
    }

    public void reset() {
        preRoll.clear();
        activeFrames.clear();
        active = false;
        voicedFrames = 0;
        silentTailFrames = 0;
    }

    public boolean isActive() {
        return active;
    }

    private short[] finish() {
        int firstSpeech = -1;
        int lastSpeech = -1;
        for (int index = 0; index < activeFrames.size(); index++) {
            if (activeFrames.get(index).speech()) {
                if (firstSpeech < 0) {
                    firstSpeech = index;
                }
                lastSpeech = index;
            }
        }

        // Keep the configured leading context and one 20 ms trailing boundary frame.
        // This prevents hard consonant clipping without retaining the long silence
        // tail used by the VAD hangover.
        int first = firstSpeech < 0 ? 0 : Math.max(0, firstSpeech - settings.preRollFrames());
        int last = lastSpeech < 0 ? -1 : Math.min(activeFrames.size() - 1, lastSpeech + 1);
        int outputFrames = last >= first ? last - first + 1 : 0;
        boolean meaningful = voicedFrames >= settings.minimumSpeechFrames()
                && outputFrames >= settings.minimumClipFrames()
                && voicedFrames / (double) Math.max(1, outputFrames) >= settings.minimumSpeechRatio();

        short[] result = null;
        if (meaningful) {
            int sampleCount = 0;
            for (int index = first; index <= last; index++) {
                sampleCount += activeFrames.get(index).samples().length;
            }
            result = new short[sampleCount];
            int offset = 0;
            for (int index = first; index <= last; index++) {
                short[] samples = activeFrames.get(index).samples();
                System.arraycopy(samples, 0, result, offset, samples.length);
                offset += samples.length;
            }
        }

        reset();
        return result;
    }

    private boolean isSpeech(double rmsDb, double peakDb, boolean sustaining) {
        double hysteresis = sustaining ? settings.releaseHysteresisDb() : 0.0;
        double rmsThreshold = Math.max(
                settings.activationDb() - hysteresis,
                noiseFloorDb + settings.noiseMarginDb() - hysteresis);
        double peakThreshold = settings.peakDb() - hysteresis;
        return rmsDb >= rmsThreshold && peakDb >= peakThreshold;
    }

    private void rememberPreRoll(Frame frame) {
        if (settings.preRollFrames() <= 0) {
            return;
        }
        preRoll.addLast(frame);
        while (preRoll.size() > settings.preRollFrames()) {
            preRoll.removeFirst();
        }
    }

    private void updateNoiseFloor(double rmsDb) {
        if (!Double.isFinite(rmsDb)) {
            return;
        }
        double capped = Math.min(rmsDb, settings.activationDb() - 3.0);
        noiseFloorDb = noiseFloorDb * 0.95 + capped * 0.05;
        noiseFloorDb = Math.max(-90.0, Math.min(settings.activationDb() - 3.0, noiseFloorDb));
    }

    static double rmsDb(short[] samples) {
        return rmsDb(samples, 0, samples == null ? 0 : samples.length);
    }

    static double rmsDb(short[] samples, int offset, int length) {
        if (samples == null || length <= 0) {
            return -96.0;
        }
        double sum = 0.0;
        for (int index = offset; index < offset + length; index++) {
            short sample = samples[index];
            double normalized = sample / 32768.0;
            sum += normalized * normalized;
        }
        double rms = Math.sqrt(sum / length);
        return rms <= 1.0e-9 ? -96.0 : 20.0 * Math.log10(rms);
    }

    static double peakDb(short[] samples) {
        return peakDb(samples, 0, samples == null ? 0 : samples.length);
    }

    static double peakDb(short[] samples, int offset, int length) {
        if (samples == null || length <= 0) {
            return -96.0;
        }
        int peak = 0;
        for (int index = offset; index < offset + length; index++) {
            short sample = samples[index];
            peak = Math.max(peak, Math.abs((int) sample));
        }
        double normalized = peak / 32768.0;
        return normalized <= 1.0e-9 ? -96.0 : 20.0 * Math.log10(normalized);
    }
}
