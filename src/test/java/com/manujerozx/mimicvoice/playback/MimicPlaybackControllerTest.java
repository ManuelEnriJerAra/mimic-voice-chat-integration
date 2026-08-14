package com.manujerozx.mimicvoice.playback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.manujerozx.mimicvoice.PluginSettings;
import com.manujerozx.mimicvoice.storage.VoiceClip;

class MimicPlaybackControllerTest {

    private static final UUID ENTITY_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ENTITY_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void nonMimicTargetsAreIgnored() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.tick(new MimicPlaybackController.Target(
                    ENTITY_ID, false, true, false, 1, resolvedUuid(PLAYER_A, "Alice")));

            assertEquals(0, harness.clips.selectedPlayers.size());
            assertEquals(0, harness.controller.trackedMimics());
        }
    }

    @Test
    void unresolvedIdentityNeverSelectsAnotherPlayersClip() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.clips.add(PLAYER_B, clip("b", PLAYER_B));
            harness.tick(target(ENTITY_ID, null, "Missing", 1, MimicIdentityResolver.Source.UNRESOLVED));

            assertTrue(harness.clips.selectedPlayers.isEmpty());
            assertEquals(5_000L, harness.controller.nextPlaybackAt(ENTITY_ID));
        }
    }

    @Test
    void uuidIdentitySelectsOnlyThatUuidClips() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            VoiceClip clipA = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clipA);
            harness.clips.add(PLAYER_B, clip("b", PLAYER_B));
            harness.tick(target(ENTITY_ID, PLAYER_A, "ConflictingName", 1,
                    MimicIdentityResolver.Source.UUID_MARKER));

            assertEquals(List.of(PLAYER_A), harness.clips.selectedPlayers);
        }
    }

    @Test
    void legacyNameResolutionPassesTheResolvedUuidToClipSelection() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.clips.add(PLAYER_B, clip("b", PLAYER_B));
            harness.tick(target(ENTITY_ID, PLAYER_B, "LegacyPlayer", 1,
                    MimicIdentityResolver.Source.ONLINE_LEGACY_NAME));

            assertEquals(List.of(PLAYER_B), harness.clips.selectedPlayers);
        }
    }

    @Test
    void firstPlaybackWaitsUntilConfiguredDelayBoundary() {
        try (Harness harness = new Harness(settings(true, 0, 0, 10, 0, 0, 5))) {
            harness.delays.add(1_000L);
            harness.clips.add(PLAYER_A, clip("a", PLAYER_A));
            MimicPlaybackController.Target target = target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER);

            harness.tick(target);
            harness.clock.now = 999L;
            harness.tick(target);
            assertTrue(harness.clips.selectedPlayers.isEmpty());

            harness.clock.now = 1_000L;
            harness.tick(target);
            assertEquals(List.of(PLAYER_A), harness.clips.selectedPlayers);
        }
    }

    @Test
    void repeatDelayStartsAtPlaybackCompletion() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.delays.add(0L);
            harness.delays.add(2_000L);
            VoiceClip clip = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clip);
            MimicPlaybackController.Target target = target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER);

            harness.tick(target);
            harness.completeRead(clip);
            harness.main.runAll();
            FakePlayback playback = harness.sink.lastPlayback;
            assertNotNull(playback);

            harness.clock.now = 1_000L;
            playback.complete();
            assertEquals(Long.MAX_VALUE, harness.controller.nextPlaybackAt(ENTITY_ID));
            harness.main.runAll();
            assertEquals(3_000L, harness.controller.nextPlaybackAt(ENTITY_ID));

            harness.clock.now = 2_999L;
            harness.tick(target);
            assertEquals(1, harness.clips.selectedPlayers.size());
            harness.clock.now = 3_000L;
            harness.tick(target);
            assertEquals(2, harness.clips.selectedPlayers.size());
        }
    }

    @Test
    void insufficientNearbyListenersPreventsPlayback() {
        try (Harness harness = new Harness(settings(true, 2, 0, 0, 0, 0, 5))) {
            harness.clips.add(PLAYER_A, clip("a", PLAYER_A));
            harness.tick(target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER));

            assertTrue(harness.clips.selectedPlayers.isEmpty());
            assertEquals(1_000L, harness.controller.nextPlaybackAt(ENTITY_ID));
        }
    }

    @Test
    void missingClipUsesRetryWithoutClipDelay() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 7))) {
            harness.tick(target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER));

            assertEquals(List.of(PLAYER_A), harness.clips.selectedPlayers);
            assertTrue(harness.sink.started.isEmpty());
            assertEquals(7_000L, harness.controller.nextPlaybackAt(ENTITY_ID));
        }
    }

    @Test
    void identityChangeInvalidatesInFlightRead() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            VoiceClip clipA = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clipA);
            harness.tick(target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER));

            harness.clock.now = 1L;
            harness.tick(target(ENTITY_ID, PLAYER_B, "Bob", 1,
                    MimicIdentityResolver.Source.UUID_MARKER));
            harness.completeRead(clipA);
            harness.main.runAll();

            assertTrue(harness.sink.started.isEmpty());
        }
    }

    @Test
    void entityRemovalInvalidatesInFlightRead() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            VoiceClip clipA = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clipA);
            harness.tick(target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER));

            harness.tick();
            harness.completeRead(clipA);
            harness.main.runAll();

            assertTrue(harness.sink.started.isEmpty());
            assertEquals(0, harness.controller.trackedMimics());
        }
    }

    @Test
    void clearPlayerInvalidatesInFlightReadAndStopsActivePlayback() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.delays.add(0L);
            VoiceClip clip = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clip);
            MimicPlaybackController.Target target = target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER);
            harness.tick(target);
            harness.controller.invalidatePlayer(PLAYER_A);
            harness.completeRead(clip);
            harness.main.runAll();

            assertTrue(harness.sink.started.isEmpty());
            assertEquals(0, harness.controller.activePlaybacks());

            harness.delays.add(0L);
            harness.clock.now = 5_000L;
            harness.tick(target);
            harness.completeRead(clip);
            harness.main.runAll();
            assertEquals(1, harness.sink.started.size());
            harness.controller.invalidatePlayer(PLAYER_A);
            assertEquals(1, harness.sink.stopCount);
        }
    }

    @Test
    void playbackStopFailureDoesNotAbortStateCleanup() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.delays.add(0L);
            VoiceClip clip = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clip);
            harness.tick(target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER));
            harness.completeRead(clip);
            harness.main.runAll();
            harness.sink.throwOnStop = true;

            harness.controller.invalidatePlayer(PLAYER_A);

            assertEquals(0, harness.controller.activePlaybacks());
            assertEquals(1, harness.sink.stopCount);
        }
    }

    @Test
    void reloadInvalidatesLoadsAndStopsActivePlayback() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.delays.add(0L);
            VoiceClip clipA = clip("a", PLAYER_A);
            VoiceClip clipB = clip("b", PLAYER_B);
            harness.clips.add(PLAYER_A, clipA);
            harness.clips.add(PLAYER_B, clipB);
            MimicPlaybackController.Target targetA = target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER);
            MimicPlaybackController.Target targetB = target(ENTITY_B, PLAYER_B, "Bob", 1,
                    MimicIdentityResolver.Source.UUID_MARKER);

            harness.tick(targetA, targetB);
            harness.completeRead(clipA);
            harness.main.runAll();
            assertEquals(1, harness.sink.started.size());

            harness.controller.reload();
            assertEquals(1, harness.sink.stopCount);
            harness.completeRead(clipB);
            harness.main.runAll();
            assertEquals(1, harness.sink.started.size());
        }
    }

    @Test
    void voiceApiDisappearanceSuspendsAndStopsPlaybackSafely() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.delays.add(0L);
            VoiceClip clip = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clip);
            MimicPlaybackController.Target target = target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER);
            harness.tick(target);
            harness.completeRead(clip);
            harness.main.runAll();

            harness.tick(false, target);
            assertEquals(1, harness.sink.stopCount);
            assertEquals(0, harness.controller.activePlaybacks());
        }
    }

    @Test
    void disabledPlaybackStopsActivePlayback() {
        AtomicReference<PluginSettings> settings = new AtomicReference<>(
                settings(true, 0, 0, 0, 0, 0, 5));
        try (Harness harness = new Harness(settings)) {
            harness.delays.add(0L);
            VoiceClip clip = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clip);
            MimicPlaybackController.Target target = target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER);
            harness.tick(target);
            harness.completeRead(clip);
            harness.main.runAll();

            settings.set(settings(false, 0, 0, 0, 0, 0, 5));
            harness.tick(target);
            assertEquals(1, harness.sink.stopCount);
            assertEquals(0, harness.controller.activePlaybacks());
        }
    }

    @Test
    void activePlaybackFollowsTheCurrentTarget() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.delays.add(0L);
            VoiceClip clip = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clip);
            MimicPlaybackController.Target target = target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER);
            harness.tick(target);
            harness.completeRead(clip);
            harness.main.runAll();

            harness.clock.now = 1L;
            harness.tick(target);
            assertEquals(1, harness.sink.lastPlayback.updates.size());
            assertEquals(ENTITY_ID, harness.sink.lastPlayback.updates.get(0).entityId());
        }
    }

    @Test
    void gainClampsToSigned16BitRange() {
        short[] adjusted = MimicPlaybackController.applyGain(
                new short[] {Short.MAX_VALUE, Short.MIN_VALUE, 10_000}, 2.0);

        assertArrayEquals(new short[] {Short.MAX_VALUE, Short.MIN_VALUE, 20_000}, adjusted);
    }

    @Test
    void alternativeClipIsSelectedAfterPreviousClip() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.delays.add(0L);
            VoiceClip clipA = clip("a", PLAYER_A);
            VoiceClip clipB = clip("b", PLAYER_A);
            harness.clips.add(PLAYER_A, clipA);
            harness.clips.add(PLAYER_A, clipB);
            MimicPlaybackController.Target target = target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER);

            harness.tick(target);
            harness.completeRead(clipA);
            harness.main.runAll();
            harness.sink.lastPlayback.complete();
            harness.main.runAll();
            harness.clock.now = harness.controller.nextPlaybackAt(ENTITY_ID);
            harness.tick(target);
            harness.completeRead(clipB);
            harness.main.runAll();

            assertEquals(List.of("a", "b"), harness.clips.selectedClipIds);
            assertEquals("b", harness.sink.started.get(1).clip().id());
        }
    }

    @Test
    void closeIsIdempotentAndCancelsPlayback() {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.delays.add(0L);
            VoiceClip clip = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clip);
            harness.tick(target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER));
            harness.completeRead(clip);
            harness.main.runAll();

            harness.controller.close();
            harness.controller.close();
            assertEquals(1, harness.sink.stopCount);
            assertEquals(0, harness.controller.trackedMimics());
        }
    }

    @Test
    void storageCompletionOnlyDispatchesStateMutationToMainThread() throws Exception {
        try (Harness harness = new Harness(settings(true, 0, 0, 0, 0, 0, 5))) {
            harness.delays.add(0L);
            VoiceClip clip = clip("a", PLAYER_A);
            harness.clips.add(PLAYER_A, clip);
            harness.tick(target(ENTITY_ID, PLAYER_A, "Alice", 1,
                    MimicIdentityResolver.Source.UUID_MARKER));

            Thread storageThread = Thread.ofPlatform().start(
                    () -> harness.completeRead(clip));
            storageThread.join(5_000L);

            assertTrue(harness.sink.started.isEmpty());
            assertEquals(1, harness.main.size());
            harness.main.runAll();
            assertEquals(Thread.currentThread().getName(), harness.sink.startThreads.get(0));
        }
    }

    private static MimicPlaybackController.Target target(
            UUID entityId, UUID playerId, String name, int listeners,
            MimicIdentityResolver.Source source) {
        MimicIdentityResolver.Resolution resolution = playerId == null
                ? new MimicIdentityResolver.Resolution(null, null, name, null, name, source)
                : source == MimicIdentityResolver.Source.UUID_MARKER
                        ? resolvedUuid(playerId, name)
                        : new MimicIdentityResolver.Resolution(
                                playerId, null, name, null, name, source);
        return new MimicPlaybackController.Target(entityId, true, true, false, listeners, resolution);
    }

    private static MimicIdentityResolver.Resolution resolvedUuid(UUID playerId, String name) {
        return new MimicIdentityResolver.Resolution(
                playerId, playerId.toString(), name, null, name,
                MimicIdentityResolver.Source.UUID_MARKER);
    }

    private static VoiceClip clip(String id, UUID playerId) {
        short[] samples = new short[] {1, 2, 3};
        return new VoiceClip(id, playerId, playerId.toString(), null, samples, samples.length, 0L);
    }

    private static PluginSettings settings(boolean enabled, int minimumListeners,
                                           int firstMinimum, int firstMaximum,
                                           int repeatMinimum, int repeatMaximum,
                                           int retrySeconds) {
        PluginSettings.VoiceActivity activity = new PluginSettings.VoiceActivity(
                -42.0, -32.0, 10.0, 5.0,
                0, 40, 20, 20, 0.25, 10, 1_000);
        return new PluginSettings(
                new PluginSettings.Recording(true, true, "", false, activity),
                new PluginSettings.Storage(false, 20, 72),
                new PluginSettings.Playback(enabled, 32.0F, 1.0, 0.01,
                        minimumListeners, firstMinimum, firstMaximum,
                        repeatMinimum, repeatMaximum, retrySeconds));
    }

    private static final class Harness implements AutoCloseable {
        private final MutableClock clock = new MutableClock();
        private final DelaySequence delays = new DelaySequence();
        private final ManualMainThread main = new ManualMainThread();
        private final FakeClipSource clips = new FakeClipSource();
        private final FakePlaybackSink sink = new FakePlaybackSink();
        private final AtomicReference<PluginSettings> settings;
        private final MimicPlaybackController controller;

        private Harness(PluginSettings settings) {
            this(new AtomicReference<>(settings));
        }

        private Harness(AtomicReference<PluginSettings> settings) {
            this.settings = settings;
            this.controller = new MimicPlaybackController(
                    settings::get, clips, sink, main, clock, delays);
        }

        private void tick(MimicPlaybackController.Target... targets) {
            tick(true, targets);
        }

        private void tick(boolean voiceApiAvailable, MimicPlaybackController.Target... targets) {
            controller.tick(Arrays.asList(targets), voiceApiAvailable);
        }

        private void completeRead(VoiceClip clip) {
            clips.complete(clip, new short[] {1, 2, 3});
        }

        @Override
        public void close() {
            controller.close();
        }
    }

    private static final class MutableClock implements MimicPlaybackController.Clock {
        private long now;

        @Override
        public long nowMillis() {
            return now;
        }
    }

    private static final class DelaySequence implements MimicPlaybackController.DelaySource {
        private final Deque<Long> delays = new ArrayDeque<>();

        private void add(long delayMillis) {
            delays.addLast(delayMillis);
        }

        @Override
        public long nextDelayMillis(int minimumSeconds, int maximumSeconds) {
            return delays.isEmpty() ? 0L : delays.removeFirst();
        }
    }

    private static final class ManualMainThread implements MimicPlaybackController.MainThreadExecutor {
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        private int size() {
            return tasks.size();
        }

        private void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    private static final class FakeClipSource implements MimicPlaybackController.ClipSource {
        private final Map<UUID, List<VoiceClip>> byPlayer = new HashMap<>();
        private final Map<VoiceClip, CompletableFuture<short[]>> pendingReads = new HashMap<>();
        private final List<UUID> selectedPlayers = new ArrayList<>();
        private final List<String> selectedClipIds = new ArrayList<>();

        private void add(UUID playerId, VoiceClip clip) {
            byPlayer.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(clip);
        }

        @Override
        public VoiceClip select(UUID playerId, String previousClipId) {
            selectedPlayers.add(playerId);
            List<VoiceClip> candidates = byPlayer.getOrDefault(playerId, List.of());
            VoiceClip selected = candidates.stream()
                    .filter(clip -> candidates.size() <= 1 || !Objects.equals(clip.id(), previousClipId))
                    .findFirst()
                    .orElse(null);
            if (selected != null) {
                selectedClipIds.add(selected.id());
            }
            return selected;
        }

        @Override
        public CompletableFuture<short[]> read(VoiceClip clip) {
            CompletableFuture<short[]> future = new CompletableFuture<>();
            pendingReads.put(clip, future);
            return future;
        }

        private void complete(VoiceClip clip, short[] samples) {
            CompletableFuture<short[]> future = pendingReads.remove(clip);
            assertNotNull(future, "no pending read for " + clip.id());
            future.complete(samples);
        }
    }

    private static final class FakePlaybackSink implements MimicPlaybackController.PlaybackSink {
        private final List<Started> started = new ArrayList<>();
        private final List<String> startThreads = new ArrayList<>();
        private FakePlayback lastPlayback;
        private int stopCount;
        private boolean throwOnStop;

        @Override
        public MimicPlaybackController.PlaybackHandle start(
                MimicPlaybackController.Target target, VoiceClip clip, short[] samples,
                double gain, Consumer<MimicPlaybackController.PlaybackHandle> stopped) {
            FakePlayback playback = new FakePlayback(target, clip, stopped, this);
            lastPlayback = playback;
            started.add(new Started(target, clip, samples, gain));
            startThreads.add(Thread.currentThread().getName());
            return playback;
        }

        private record Started(MimicPlaybackController.Target target, VoiceClip clip,
                               short[] samples, double gain) {
        }
    }

    private static final class FakePlayback implements MimicPlaybackController.PlaybackHandle {
        private final MimicPlaybackController.Target target;
        private final VoiceClip clip;
        private final Consumer<MimicPlaybackController.PlaybackHandle> stopped;
        private final FakePlaybackSink sink;
        private final List<MimicPlaybackController.Target> updates = new ArrayList<>();

        private FakePlayback(MimicPlaybackController.Target target, VoiceClip clip,
                             Consumer<MimicPlaybackController.PlaybackHandle> stopped,
                             FakePlaybackSink sink) {
            this.target = target;
            this.clip = clip;
            this.stopped = stopped;
            this.sink = sink;
        }

        private void complete() {
            stopped.accept(this);
        }

        @Override
        public void update(MimicPlaybackController.Target target) {
            updates.add(target);
        }

        @Override
        public void stop() {
            sink.stopCount++;
            if (sink.throwOnStop) {
                throw new IllegalStateException("simulated voice API stop failure");
            }
        }
    }
}
