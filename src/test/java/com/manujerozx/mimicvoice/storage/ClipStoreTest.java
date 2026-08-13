package com.manujerozx.mimicvoice.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.manujerozx.mimicvoice.PluginSettings;
import com.manujerozx.mimicvoice.audio.WavIO;

class ClipStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reloadPreservesPlayerNamesContainingUnderscores() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path root = temporaryDirectory.resolve("recordings");
        Path clipPath = root.resolve(playerId.toString()).resolve(
                System.currentTimeMillis() + "_Player_Name_deadbeef.wav");
        WavIO.write(clipPath, voiceFrame(), PluginSettings.SAMPLE_RATE);
        PluginSettings settings = settings(20);

        try (ClipStore store = new ClipStore(root, Logger.getAnonymousLogger(), () -> settings)) {
            assertEquals(1, store.initialize().get());
            assertEquals(playerId, store.findPlayerId("Player_Name"));
            VoiceClip selected = store.select(playerId, null);
            assertNotNull(selected);
            assertEquals(playerId, selected.speakerId());
            assertEquals("Player_Name", selected.speakerName());
            assertNull(store.select(UUID.randomUUID(), null),
                    "A Mimic must not fall back to another player's clips");
        }
    }

    @Test
    void fullPlayerPoolDeletesOldestClipAndKeepsNewest() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path root = temporaryDirectory.resolve("rolling-recordings");
        Path playerDirectory = root.resolve(playerId.toString());
        long now = System.currentTimeMillis();
        Path oldest = clip(playerDirectory, now - 3_000L, "oldest");
        Path middle = clip(playerDirectory, now - 2_000L, "middle");
        Path newest = clip(playerDirectory, now - 1_000L, "newest");
        PluginSettings settings = settings(2);

        try (ClipStore store = new ClipStore(root, Logger.getAnonymousLogger(), () -> settings)) {
            assertEquals(2, store.initialize().get());
            assertEquals(2, store.clipCount());
            assertFalse(Files.exists(oldest));
            assertTrue(Files.exists(middle));
            assertTrue(Files.exists(newest));
        }
    }

    @Test
    void persistedSparseNoiseIsQuarantinedWithoutDeletion() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path root = temporaryDirectory.resolve("noisy-recordings");
        String fileName = System.currentTimeMillis() + "_Player_noise.wav";
        Path source = root.resolve(playerId.toString()).resolve(fileName);
        short[] samples = new short[PluginSettings.FRAME_SIZE * 5];
        System.arraycopy(voiceFrame(), 0, samples, 0, PluginSettings.FRAME_SIZE);
        WavIO.write(source, samples, PluginSettings.SAMPLE_RATE);

        try (ClipStore store = new ClipStore(root, Logger.getAnonymousLogger(), () -> settings(20))) {
            assertEquals(0, store.initialize().get());
            assertEquals(0, store.clipCount());
            assertFalse(Files.exists(source));
            Path quarantined = root.resolve("_rejected_noise")
                    .resolve(playerId.toString()).resolve(fileName);
            assertTrue(Files.exists(quarantined));
            assertEquals(1, store.clearAll().get());
            assertFalse(Files.exists(quarantined));
        }
    }

    @Test
    void expiredQuarantinedAudioIsDeletedDuringInitialization() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path root = temporaryDirectory.resolve("expired-quarantine");
        Path quarantined = root.resolve("_rejected_noise").resolve(playerId.toString())
                .resolve("old.wav");
        WavIO.write(quarantined, voiceFrame(), PluginSettings.SAMPLE_RATE);
        Files.setLastModifiedTime(quarantined,
                FileTime.fromMillis(System.currentTimeMillis() - Duration.ofHours(73).toMillis()));

        try (ClipStore store = new ClipStore(root, Logger.getAnonymousLogger(), () -> settings(20))) {
            assertEquals(0, store.initialize().get());
            assertFalse(Files.exists(quarantined));
        }
    }

    @Test
    void clearingPlayerDeletesAcceptedAndQuarantinedAudio() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path root = temporaryDirectory.resolve("player-clear");
        Path accepted = root.resolve(playerId.toString()).resolve(
                System.currentTimeMillis() + "_Player_accepted.wav");
        Path quarantined = root.resolve("_rejected_noise").resolve(playerId.toString())
                .resolve("rejected.wav");
        WavIO.write(accepted, voiceFrame(), PluginSettings.SAMPLE_RATE);
        WavIO.write(quarantined, voiceFrame(), PluginSettings.SAMPLE_RATE);

        try (ClipStore store = new ClipStore(root, Logger.getAnonymousLogger(), () -> settings(20))) {
            assertEquals(1, store.initialize().get());
            assertEquals(2, store.clearPlayer(playerId).get());
            assertFalse(Files.exists(accepted));
            assertFalse(Files.exists(quarantined));
        }
    }

    @Test
    void quarantinedPlayerNameRemainsClearableAfterRestart() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path root = temporaryDirectory.resolve("quarantine-name-index");
        Path quarantined = root.resolve("_rejected_noise").resolve(playerId.toString()).resolve(
                System.currentTimeMillis() + "_Player_Name_rejected.wav");
        WavIO.write(quarantined, voiceFrame(), PluginSettings.SAMPLE_RATE);

        try (ClipStore store = new ClipStore(root, Logger.getAnonymousLogger(), () -> settings(20))) {
            assertEquals(0, store.initialize().get());
            assertEquals(playerId, store.findPlayerId("Player_Name"));
            assertEquals(1, store.clearPlayer(playerId).get());
            assertFalse(Files.exists(quarantined));
        }
    }

    private static Path clip(Path directory, long timestamp, String suffix) throws Exception {
        Path path = directory.resolve(timestamp + "_Player_" + suffix + ".wav");
        WavIO.write(path, voiceFrame(), PluginSettings.SAMPLE_RATE);
        return path;
    }

    private static PluginSettings settings(int maximumClips) {
        PluginSettings.VoiceActivity activity = new PluginSettings.VoiceActivity(
                -42.0, -32.0, 10.0, 5.0,
                0, 300, 20, 20, 0.25, 30, 1500);
        return new PluginSettings(
                new PluginSettings.Recording(true, true, "", true, activity),
                new PluginSettings.Storage(true, maximumClips, 72),
                new PluginSettings.Playback(true, 32.0F, 0.9, 0.01,
                        0, 5, 20, 5, 20, 10));
    }

    private static short[] voiceFrame() {
        short[] samples = new short[PluginSettings.FRAME_SIZE];
        for (int index = 0; index < samples.length; index++) {
            double angle = 2.0 * Math.PI * 220.0 * index / PluginSettings.SAMPLE_RATE;
            samples[index] = (short) Math.round(Math.sin(angle) * 8_000.0);
        }
        return samples;
    }
}
