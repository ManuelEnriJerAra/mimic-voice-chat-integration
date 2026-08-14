package com.manujerozx.mimicvoice;

import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(Recording recording, Storage storage, Playback playback) {

    public static final int SAMPLE_RATE = 48_000;
    public static final int FRAME_SIZE = 960;
    public static final int FRAME_MILLISECONDS = 20;
    public static final long BYTES_PER_MEBIBYTE = 1_048_576L;
    public static final long DEFAULT_MAXIMUM_PENDING_WRITE_BYTES = 64L * BYTES_PER_MEBIBYTE;
    public static final long DEFAULT_MAXIMUM_MEMORY_AUDIO_BYTES = 512L * BYTES_PER_MEBIBYTE;

    public static PluginSettings from(FileConfiguration config) {
        String configuredPermission = config.getString("recording.permission", "mimicvoice.record");
        Recording recording = new Recording(
                config.getBoolean("recording.enabled", true),
                config.getBoolean("recording.record-whispers", true),
                configuredPermission == null ? "" : configuredPermission.strip(),
                config.getBoolean("recording.privacy-notice", true),
                new VoiceActivity(
                        clamp(config.getDouble("recording.voice-activity.activation-db", -42.0), -80.0, -6.0),
                        clamp(config.getDouble("recording.voice-activity.peak-db", -32.0), -80.0, -3.0),
                        clamp(config.getDouble("recording.voice-activity.noise-margin-db", 10.0), 1.0, 30.0),
                        clamp(config.getDouble("recording.voice-activity.release-hysteresis-db", 5.0), 0.0, 15.0),
                        clamp(config.getInt("recording.voice-activity.pre-roll-milliseconds", 100), 0, 500),
                        clamp(config.getInt("recording.voice-activity.hangover-milliseconds", 1200), 40, 3_000),
                        clamp(config.getInt("recording.voice-activity.minimum-speech-milliseconds", 500), 20, 5_000),
                        clamp(config.getInt("recording.voice-activity.minimum-clip-milliseconds", 900), 20, 5_000),
                        clamp(config.getDouble("recording.voice-activity.minimum-speech-ratio", 0.25), 0.05, 1.0),
                        clamp(config.getInt("recording.voice-activity.maximum-clip-seconds", 30), 1, 60),
                        clamp(config.getInt("recording.voice-activity.stream-reset-milliseconds", 1500), 100, 10_000)));

        Storage storage = new Storage(
                config.getBoolean("storage.persist-clips", true),
                clamp(config.getInt("storage.maximum-clips-per-player", 20), 1, 200),
                clamp(config.getLong("storage.retention-hours", 72L), 1L, 24L * 365L),
                mebibytes(config.getLong("storage.maximum-pending-write-megabytes", 64L), 1L, 4_096L),
                mebibytes(config.getLong("storage.maximum-memory-audio-megabytes", 512L), 1L, 8_192L));

        int firstMinimum = clamp(config.getInt("playback.first-delay-seconds.minimum", 5), 0, 3_600);
        int firstMaximum = Math.max(firstMinimum,
                clamp(config.getInt("playback.first-delay-seconds.maximum", 20), 0, 3_600));
        int repeatMinimum = clamp(config.getInt("playback.repeat-delay-seconds.minimum", 5), 1, 3_600);
        int repeatMaximum = Math.max(repeatMinimum,
                clamp(config.getInt("playback.repeat-delay-seconds.maximum", 20), 1, 3_600));
        Playback playback = new Playback(
                config.getBoolean("playback.enabled", true),
                (float) clamp(config.getDouble("playback.distance", 32.0), 1.0, 128.0),
                clamp(config.getDouble("playback.volume", 0.90), 0.0, 2.0),
                clamp(config.getDouble("playback.minimum-clip-seconds", 1.0), 0.1, 30.0),
                clamp(config.getInt("playback.minimum-nearby-listeners", 1), 0, 100),
                firstMinimum,
                firstMaximum,
                repeatMinimum,
                repeatMaximum,
                clamp(config.getInt("playback.retry-without-clip-seconds", 10), 1, 600));
        return new PluginSettings(recording, storage, playback);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }

    public record Recording(boolean enabled, boolean recordWhispers, String permission,
                            boolean privacyNotice, VoiceActivity voiceActivity) {
    }

    public record VoiceActivity(double activationDb, double peakDb, double noiseMarginDb,
                                double releaseHysteresisDb, int preRollMilliseconds,
                                int hangoverMilliseconds, int minimumSpeechMilliseconds,
                                int minimumClipMilliseconds, double minimumSpeechRatio,
                                int maximumClipSeconds, int streamResetMilliseconds) {
        public int preRollFrames() {
            return frames(preRollMilliseconds);
        }

        public int hangoverFrames() {
            return Math.max(1, frames(hangoverMilliseconds));
        }

        public int minimumSpeechFrames() {
            return Math.max(1, frames(minimumSpeechMilliseconds));
        }

        public int minimumClipFrames() {
            return Math.max(1, frames(minimumClipMilliseconds));
        }

        public int maximumClipFrames() {
            return Math.max(1, maximumClipSeconds * 1_000 / FRAME_MILLISECONDS);
        }

        private int frames(int milliseconds) {
            return Math.max(0, (int) Math.ceil(milliseconds / (double) FRAME_MILLISECONDS));
        }
    }

    public record Storage(boolean persistClips, int maximumClipsPerPlayer, long retentionHours,
                          long maximumPendingWriteBytes, long maximumMemoryAudioBytes) {

        public Storage(boolean persistClips, int maximumClipsPerPlayer, long retentionHours) {
            this(persistClips, maximumClipsPerPlayer, retentionHours,
                    DEFAULT_MAXIMUM_PENDING_WRITE_BYTES, DEFAULT_MAXIMUM_MEMORY_AUDIO_BYTES);
        }
    }

    public record Playback(boolean enabled, float distance, double volume, double minimumClipSeconds,
                           int minimumNearbyListeners, int firstMinimumSeconds,
                           int firstMaximumSeconds, int repeatMinimumSeconds,
                           int repeatMaximumSeconds, int retryWithoutClipSeconds) {
    }

    private static long mebibytes(long value, long minimum, long maximum) {
        return clamp(value, minimum, maximum) * BYTES_PER_MEBIBYTE;
    }
}
