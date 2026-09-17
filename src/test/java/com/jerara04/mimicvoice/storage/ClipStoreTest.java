package com.jerara04.mimicvoice.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jerara04.mimicvoice.PluginSettings;
import com.jerara04.mimicvoice.audio.WavIO;

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
    void legacyNameIndexIsUnresolvedWhenNameBelongsToMultiplePlayers() throws Exception {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        PluginSettings settings = settings(new PluginSettings.Storage(
                false, 20, 72, 64 * 1024L, 64 * 1024L));

        try (ClipStore store = new ClipStore(temporaryDirectory.resolve("ambiguous-name"),
                Logger.getAnonymousLogger(), () -> settings)) {
            assertTrue(store.saveOwned(firstPlayer, "ReusedName", voiceFrame()));
            assertTrue(store.saveOwned(secondPlayer, "ReusedName", voiceFrame()));
            store.awaitIdle().get();

            assertNull(store.findPlayerId("ReusedName"),
                    "a reused legacy name must not choose either UUID offline");

            assertEquals(1, store.clearPlayer(firstPlayer).get());
            assertEquals(secondPlayer, store.findPlayerId("ReusedName"),
                    "removing one owner should leave a unique legacy-name owner");
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
    void clearReportsAcceptedAndQuarantinedDeletionFailures() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path root = temporaryDirectory.resolve("clear-delete-failure");
        Path accepted = root.resolve(playerId.toString()).resolve(
                System.currentTimeMillis() + "_Player_accepted.wav");
        Path quarantined = root.resolve("_rejected_noise").resolve(playerId.toString())
                .resolve("rejected.wav");
        WavIO.write(accepted, voiceFrame(), PluginSettings.SAMPLE_RATE);
        WavIO.write(quarantined, voiceFrame(), PluginSettings.SAMPLE_RATE);

        try (ClipStore store = new ClipStore(root, Logger.getAnonymousLogger(), () -> settings(20))) {
            assertEquals(1, store.initialize().get());

            Files.delete(accepted);
            Files.createDirectory(accepted);
            Files.writeString(accepted.resolve("retained-child"), "not removable as a file");
            Files.delete(quarantined);
            Files.createDirectory(quarantined);
            Files.writeString(quarantined.resolve("retained-child"), "not removable as a file");

            assertThrows(ExecutionException.class, () -> store.clearPlayer(playerId).get(),
                    "clear must fail visibly when accepted or quarantined data remains");
            assertTrue(Files.exists(accepted));
            assertTrue(Files.exists(quarantined));
            assertNotNull(store.select(playerId, null),
                    "an undeleted accepted clip must remain available for retry");
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

    @Test
    void pendingWriteBackpressureRejectsAnOversizedClipWithoutBlocking() {
        UUID playerId = UUID.randomUUID();
        PluginSettings settings = settings(new PluginSettings.Storage(
                false, 20, 72, 1_000L, PluginSettings.DEFAULT_MAXIMUM_MEMORY_AUDIO_BYTES));

        try (ClipStore store = new ClipStore(temporaryDirectory.resolve("backpressure"),
                Logger.getAnonymousLogger(), () -> settings)) {
            assertFalse(store.saveOwned(playerId, "Player", new short[501]));
            assertEquals(1, store.saveRejectedBackpressure());
            assertEquals(0, store.pendingSaveCount());
            assertEquals(0, store.pendingSaveBytes());
            assertEquals(0, store.saveFailures());
        }
    }

    @Test
    void successfulMemorySaveReleasesPendingBudgetAndReportsBytes() throws Exception {
        UUID playerId = UUID.randomUUID();
        short[] samples = voiceFrame();
        PluginSettings settings = settings(new PluginSettings.Storage(
                false, 20, 72, 64 * 1024L, 64 * 1024L));

        try (ClipStore store = new ClipStore(temporaryDirectory.resolve("memory-save"),
                Logger.getAnonymousLogger(), () -> settings)) {
            assertTrue(store.saveOwned(playerId, "Player", samples));
            store.awaitIdle().get();
            assertEquals(1, store.saveSucceeded());
            assertEquals(0, store.saveFailures());
            assertEquals(0, store.saveRejectedBackpressure());
            assertEquals(0, store.pendingSaveCount());
            assertEquals(0, store.pendingSaveBytes());
            assertEquals((long) samples.length * Short.BYTES, store.memoryAudioBytes());
        }
    }

    @Test
    void failedDiskSaveReleasesPendingBudgetAndReportsFailure() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path root = temporaryDirectory.resolve("storage-root-file");
        Files.writeString(root, "not a directory");
        PluginSettings settings = settings(new PluginSettings.Storage(
                true, 20, 72, 64 * 1024L, 64 * 1024L));

        try (ClipStore store = new ClipStore(root, Logger.getAnonymousLogger(), () -> settings)) {
            assertTrue(store.saveOwned(playerId, "Player", voiceFrame()));
            store.awaitIdle().get();
            assertEquals(0, store.saveSucceeded());
            assertEquals(1, store.saveFailures());
            assertEquals(0, store.pendingSaveCount());
            assertEquals(0, store.pendingSaveBytes());
        }
    }

    @Test
    void clearPlayerRunsAfterEarlierQueuedSaveAndDoesNotAllowItToReappear() throws Exception {
        UUID playerId = UUID.randomUUID();
        PluginSettings settings = settings(new PluginSettings.Storage(
                false, 20, 72, 64 * 1024L, 64 * 1024L));

        try (ClipStore store = new ClipStore(temporaryDirectory.resolve("ordered-clear"),
                Logger.getAnonymousLogger(), () -> settings)) {
            assertTrue(store.saveOwned(playerId, "Player", voiceFrame()));
            assertEquals(1, store.clearPlayer(playerId).get());
            assertEquals(0, store.clipCount());
            assertNull(store.select(playerId, null));
            assertEquals(0, store.pendingSaveCount());
        }
    }

    @Test
    void clearControlAdmissionRemainsAvailableWhenBulkStorageIsSaturated() throws Exception {
        UUID playerId = UUID.randomUUID();
        CountDownLatch firstSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSave = new CountDownLatch(1);
        AtomicBoolean gateEnabled = new AtomicBoolean();
        AtomicBoolean firstSave = new AtomicBoolean();
        Runnable saveGate = () -> {
            if (gateEnabled.get() && firstSave.compareAndSet(false, true)) {
                firstSaveEntered.countDown();
                try {
                    releaseFirstSave.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        PluginSettings settings = settings(new PluginSettings.Storage(
                false, 200, 72, 64 * 1024L, 64 * 1024L));

        try (ClipStore store = new ClipStore(temporaryDirectory.resolve("saturated-clear"),
                Logger.getAnonymousLogger(), () -> settings, WavIO::read, saveGate)) {
            assertTrue(store.saveOwned(playerId, "Player", voiceFrame()));
            store.awaitIdle().get();
            assertNotNull(store.select(playerId, null));

            gateEnabled.set(true);
            assertTrue(store.saveOwned(UUID.randomUUID(), "Blocked", new short[] {1, 2, 3}));
            assertTrue(firstSaveEntered.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < ClipStore.MAX_BULK_IO_ADMISSIONS; index++) {
                store.saveOwned(UUID.randomUUID(), "Bulk" + index, new short[] {1, 2, 3});
            }

            CompletableFuture<Integer> clear = store.clearAll();
            assertFalse(clear.isCompletedExceptionally(),
                    "clear must have a reserved bounded control admission");
            assertNull(store.select(playerId, null),
                    "playback selection must remain suppressed until clear completes");

            releaseFirstSave.countDown();
            assertTrue(clear.get(5, TimeUnit.SECONDS) >= 1);
            assertEquals(0, store.clipCount());
        }
    }

    @Test
    void storageShutdownReportsNonCooperativeReaderAndBlocksPostCloseMutation() throws Exception {
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        ClipStore.WavReader reader = path -> {
            readEntered.countDown();
            while (true) {
                try {
                    if (releaseRead.await(10, TimeUnit.MILLISECONDS)) {
                        return new WavIO.WavData(PluginSettings.SAMPLE_RATE, new short[] {1});
                    }
                } catch (InterruptedException ignored) {
                    // Deliberately model a third-party reader that ignores interruption.
                }
            }
        };
        PluginSettings settings = settings(20);

        try (ClipStore store = new ClipStore(temporaryDirectory.resolve("non-cooperative-storage"),
                Logger.getAnonymousLogger(), () -> settings, reader, () -> { }, 50L, 50L)) {
            CompletableFuture<short[]> read = store.read(new VoiceClip(
                    "blocked", UUID.randomUUID(), "Player",
                    temporaryDirectory.resolve("blocked.wav"), null, 1,
                    System.currentTimeMillis()));
            assertTrue(readEntered.await(5, TimeUnit.SECONDS));

            assertFalse(store.shutdown(),
                    "shutdown must report that a non-cooperative reader remains live");
            assertFalse(store.saveOwned(UUID.randomUUID(), "AfterClose", new short[] {1}),
                    "post-close clip admission must fail closed");

            releaseRead.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!store.storageWorkersStopped() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(store.storageWorkersStopped());
            assertNull(read.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void storageShutdownDoesNotRegisterAPendingSaveAfterClose() throws Exception {
        CountDownLatch saveEntered = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        Runnable saveGate = () -> {
            saveEntered.countDown();
            while (true) {
                try {
                    if (releaseSave.await(10, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException ignored) {
                    // Deliberately model a writer that ignores interruption.
                }
            }
        };
        PluginSettings settings = settings(new PluginSettings.Storage(
                false, 20, 72, 64 * 1024L, 64 * 1024L));
        ClipStore store = new ClipStore(temporaryDirectory.resolve("non-cooperative-save"),
                Logger.getAnonymousLogger(), () -> settings, WavIO::read, saveGate, 50L, 50L);
        try {
            assertTrue(store.saveOwned(UUID.randomUUID(), "BeforeClose", new short[] {1, 2, 3}));
            assertTrue(saveEntered.await(5, TimeUnit.SECONDS));
            assertFalse(store.shutdown(),
                    "shutdown must report that a non-cooperative save remains live");

            releaseSave.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!store.storageWorkersStopped() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(store.storageWorkersStopped());
            assertEquals(0, store.clipCount(),
                    "a save that crosses the close boundary must not register a clip");
            assertEquals(0, store.saveSucceeded());
        } finally {
            releaseSave.countDown();
            store.shutdown();
        }
    }

    @Test
    void memoryOnlyAudioIsEvictedAtGlobalByteBound() throws Exception {
        PluginSettings settings = settings(new PluginSettings.Storage(
                false, 20, 72, 64 * 1024L, 2L * 960L * Short.BYTES));

        try (ClipStore store = new ClipStore(temporaryDirectory.resolve("memory-bound"),
                Logger.getAnonymousLogger(), () -> settings)) {
            for (int index = 0; index < 3; index++) {
                assertTrue(store.saveOwned(UUID.randomUUID(), "Player" + index, voiceFrame()));
            }
            store.awaitIdle().get();
            assertTrue(store.memoryAudioBytes() <= settings.storage().maximumMemoryAudioBytes());
            assertEquals(2, store.clipCount());
        }
    }

    @Test
    void callerOwnedSaveIsClonedBeforeAsynchronousStorage() throws Exception {
        UUID playerId = UUID.randomUUID();
        short[] samples = voiceFrame();
        short expectedFirstSample = samples[0];
        PluginSettings settings = settings(new PluginSettings.Storage(
                false, 20, 72, 64 * 1024L, 64 * 1024L));

        try (ClipStore store = new ClipStore(temporaryDirectory.resolve("save-copy"),
                Logger.getAnonymousLogger(), () -> settings)) {
            assertTrue(store.save(playerId, "Player", samples));
            samples[0] = 1234;
            store.awaitIdle().get();
            VoiceClip clip = store.select(playerId, null);
            assertNotNull(clip);
            assertArrayEquals(new short[] {expectedFirstSample},
                    new short[] {clip.memoryAudio()[0]});
        }
    }

    @Test
    void diskPlaybackReadsHaveASeparateBoundedAdmissionQueue() throws Exception {
        UUID playerId = UUID.randomUUID();
        CountDownLatch firstReadEntered = new CountDownLatch(1);
        CountDownLatch releaseReads = new CountDownLatch(1);
        ClipStore.WavReader reader = path -> {
            firstReadEntered.countDown();
            try {
                releaseReads.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new WavIO.WavData(PluginSettings.SAMPLE_RATE, new short[] {1});
        };

        try (ClipStore store = new ClipStore(temporaryDirectory.resolve("bounded-reads"),
                Logger.getAnonymousLogger(), () -> settings(20), reader)) {
            List<CompletableFuture<short[]>> reads = new ArrayList<>();
            for (int index = 0; index < ClipStore.MAX_PENDING_READS + 8; index++) {
                reads.add(store.read(new VoiceClip("read-" + index, playerId, "Player",
                        temporaryDirectory.resolve("read-" + index + ".wav"), null, 1,
                        System.currentTimeMillis())));
            }

            assertTrue(firstReadEntered.await(5, TimeUnit.SECONDS));
            assertTrue(store.pendingReadCount() <= ClipStore.MAX_PENDING_READS);
            assertTrue(store.readRejectedBackpressure() > 0,
                    "reads beyond the bounded queue must be rejected for retry");
            releaseReads.countDown();
            reads.forEach(read -> assertDoesNotThrow(() -> read.get(5, TimeUnit.SECONDS)));
        }
    }

    @Test
    void unreadableIndexedClipIsRemovedAndQuarantined() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path root = temporaryDirectory.resolve("corrupt-playback");
        String fileName = System.currentTimeMillis() + "_Player_corrupt.wav";
        Path clipPath = root.resolve(playerId.toString()).resolve(fileName);
        WavIO.write(clipPath, voiceFrame(), PluginSettings.SAMPLE_RATE);

        try (ClipStore store = new ClipStore(root, Logger.getAnonymousLogger(), () -> settings(20))) {
            assertEquals(1, store.initialize().get());
            Files.writeString(clipPath, "corrupt");
            VoiceClip clip = store.select(playerId, null);
            assertNotNull(clip);
            assertNull(store.read(clip).get());
            assertNull(store.select(playerId, null));
            assertTrue(Files.exists(root.resolve("_rejected_noise")
                    .resolve(playerId.toString()).resolve(fileName)));
        }
    }

    private static Path clip(Path directory, long timestamp, String suffix) throws Exception {
        Path path = directory.resolve(timestamp + "_Player_" + suffix + ".wav");
        WavIO.write(path, voiceFrame(), PluginSettings.SAMPLE_RATE);
        return path;
    }

    private static PluginSettings settings(int maximumClips) {
        return settings(new PluginSettings.Storage(true, maximumClips, 72));
    }

    private static PluginSettings settings(PluginSettings.Storage storage) {
        PluginSettings.VoiceActivity activity = new PluginSettings.VoiceActivity(
                -42.0, -32.0, 10.0, 5.0,
                0, 300, 20, 20, 0.25, 30, 1500);
        return new PluginSettings(
                new PluginSettings.Recording(true, true, "", true, activity),
                storage,
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
