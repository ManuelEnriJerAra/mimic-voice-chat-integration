package com.manujerozx.mimicvoice.playback;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.manujerozx.mimicvoice.PluginSettings;
import com.manujerozx.mimicvoice.storage.VoiceClip;

/**
 * Main-thread playback state machine with narrow adapters for Bukkit and voice-chat work.
 *
 * <p>The controller never touches Bukkit or Simple Voice Chat types. Storage completion
 * callbacks are handed to {@link MainThreadExecutor} before they can mutate state or
 * invoke the playback sink.</p>
 */
final class MimicPlaybackController implements AutoCloseable {

    private static final long NO_SCHEDULE = Long.MAX_VALUE;

    private final Supplier<PluginSettings> settings;
    private final ClipSource clips;
    private final PlaybackSink playbackSink;
    private final MainThreadExecutor mainThread;
    private final Clock clock;
    private final DelaySource delays;
    private final Consumer<RuntimeException> failureReporter;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Target> currentTargets = new HashMap<>();
    private boolean closed;
    private long startedPlaybacks;

    MimicPlaybackController(Supplier<PluginSettings> settings,
                            ClipSource clips,
                            PlaybackSink playbackSink,
                            MainThreadExecutor mainThread,
                            Clock clock,
                            DelaySource delays) {
        this(settings, clips, playbackSink, mainThread, clock, delays, ignored -> {
        });
    }

    MimicPlaybackController(Supplier<PluginSettings> settings,
                            ClipSource clips,
                            PlaybackSink playbackSink,
                            MainThreadExecutor mainThread,
                            Clock clock,
                            DelaySource delays,
                            Consumer<RuntimeException> failureReporter) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clips = Objects.requireNonNull(clips, "clips");
        this.playbackSink = Objects.requireNonNull(playbackSink, "playbackSink");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delays = Objects.requireNonNull(delays, "delays");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    void tick(Collection<Target> discovered, boolean voiceApiAvailable) {
        if (closed) {
            return;
        }

        PluginSettings.Playback playbackSettings = settings.get().playback();
        long now = clock.nowMillis();
        if (!playbackSettings.enabled() || !voiceApiAvailable) {
            suspend(now);
            return;
        }

        Set<UUID> seen = new HashSet<>();
        currentTargets.clear();
        for (Target target : discovered) {
            if (target == null || !target.mimic() || !target.valid() || target.dead()) {
                continue;
            }
            UUID entityId = target.entityId();
            if (entityId == null) {
                continue;
            }
            seen.add(entityId);
            currentTargets.put(entityId, target);
            State state = states.computeIfAbsent(entityId, ignored -> new State());
            tickTarget(target, state, playbackSettings, now);
        }

        for (UUID entityId : new HashSet<>(states.keySet())) {
            if (!seen.contains(entityId)) {
                remove(entityId);
            }
        }
    }

    void reload() {
        if (closed) {
            return;
        }
        long now = clock.nowMillis();
        for (State state : states.values()) {
            state.identity.invalidate();
            state.identity.setNextPlaybackAt(now);
            stop(state);
        }
    }

    /** Invalidates reads and active playback for every currently tracked Mimic. */
    void invalidateAll() {
        if (closed) {
            return;
        }
        long now = clock.nowMillis();
        for (State state : states.values()) {
            invalidateForClear(state, now);
        }
    }

    /** Invalidates reads and active playback for Mimics currently using a player. */
    void invalidatePlayer(UUID playerId) {
        if (closed || playerId == null) {
            return;
        }
        long now = clock.nowMillis();
        for (Map.Entry<UUID, Target> entry : currentTargets.entrySet()) {
            if (Objects.equals(entry.getValue().playerId(), playerId)) {
                State state = states.get(entry.getKey());
                if (state != null) {
                    invalidateForClear(state, now);
                }
            }
        }
    }

    void playbackStopped(UUID entityId, PlaybackHandle handle) {
        if (closed) {
            return;
        }
        State state = states.get(entityId);
        if (state == null || state.playback != handle) {
            return;
        }
        state.playback = null;
        PluginSettings.Playback playbackSettings = settings.get().playback();
        state.identity.setNextPlaybackAt(clock.nowMillis()
                + delays.nextDelayMillis(playbackSettings.repeatMinimumSeconds(),
                        playbackSettings.repeatMaximumSeconds()));
    }

    int trackedMimics() {
        return states.size();
    }

    int activePlaybacks() {
        return (int) states.values().stream().filter(state -> state.playback != null).count();
    }

    long startedPlaybacks() {
        return startedPlaybacks;
    }

    long nextPlaybackAt(UUID entityId) {
        State state = states.get(entityId);
        return state == null ? NO_SCHEDULE : state.identity.nextPlaybackAt();
    }

    boolean loading(UUID entityId) {
        State state = states.get(entityId);
        return state != null && state.identity.loading();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (State state : states.values()) {
            state.identity.invalidate();
            stop(state);
        }
        states.clear();
        currentTargets.clear();
    }

    private void tickTarget(Target target, State state,
                            PluginSettings.Playback playbackSettings, long now) {
        state.identity.updateIdentity(
                target.identityKey(),
                now,
                delays.nextDelayMillis(playbackSettings.firstMinimumSeconds(),
                        playbackSettings.firstMaximumSeconds()),
                () -> stop(state));

        UUID playerId = target.playerId();
        if (playerId == null) {
            state.identity.setNextPlaybackAt(now
                    + playbackSettings.retryWithoutClipSeconds() * 1_000L);
            return;
        }
        if (state.identity.loading() || state.playback != null
                || now < state.identity.nextPlaybackAt()) {
            if (state.playback != null) {
                state.playback.update(target);
            }
            return;
        }
        if (target.nearbyListeners() < playbackSettings.minimumNearbyListeners()) {
            state.identity.setNextPlaybackAt(now + 1_000L);
            return;
        }

        VoiceClip clip;
        try {
            clip = clips.select(playerId, state.identity.lastClipId());
        } catch (RuntimeException exception) {
            scheduleRetry(state, playbackSettings, now);
            return;
        }
        if (clip == null) {
            scheduleRetry(state, playbackSettings, now);
            return;
        }

        long identityVersion = state.identity.identityVersion();
        MimicIdentityResolver.IdentityKey expectedIdentity = target.identityKey();
        state.identity.setLoading(true);
        state.identity.setNextPlaybackAt(NO_SCHEDULE);
        try {
            CompletableFuture<short[]> read = clips.read(clip);
            read.whenComplete((samples, failure) -> {
                try {
                    mainThread.execute(() -> completeRead(target.entityId(), state, identityVersion,
                            expectedIdentity, clip, samples, failure));
                } catch (RuntimeException ignored) {
                    // Shutdown can race a storage completion. The generation remains
                    // invalid and no playback may be started after dispatch fails.
                }
            });
        } catch (RuntimeException exception) {
            state.identity.setLoading(false);
            scheduleRetry(state, playbackSettings, now);
        }
    }

    private void completeRead(UUID entityId, State state, long identityVersion,
                              MimicIdentityResolver.IdentityKey expectedIdentity,
                              VoiceClip clip, short[] samples, Throwable failure) {
        if (closed || states.get(entityId) != state
                || !state.identity.isCurrent(identityVersion, expectedIdentity)) {
            return;
        }
        state.identity.setLoading(false);
        PluginSettings.Playback playbackSettings = settings.get().playback();
        Target target = currentTargets.get(entityId);
        if (failure != null || samples == null || target == null || !target.valid()
                || target.dead() || !Objects.equals(target.identityKey(), expectedIdentity)
                || target.nearbyListeners() < playbackSettings.minimumNearbyListeners()) {
            scheduleRetry(state, playbackSettings, clock.nowMillis());
            return;
        }

        PlaybackHandle handle;
        try {
            handle = playbackSink.start(target, clip, samples, playbackSettings.volume(),
                    playback -> dispatchPlaybackStopped(entityId, state, playback));
        } catch (RuntimeException exception) {
            scheduleRetry(state, playbackSettings, clock.nowMillis());
            return;
        }
        if (handle == null) {
            scheduleRetry(state, playbackSettings, clock.nowMillis());
            return;
        }

        state.playback = handle;
        state.identity.setLastClipId(clip.id());
        startedPlaybacks++;
    }

    private void dispatchPlaybackStopped(UUID entityId, State state, PlaybackHandle handle) {
        try {
            mainThread.execute(() -> {
                if (states.get(entityId) == state && state.playback == handle) {
                    playbackStopped(entityId, handle);
                }
            });
        } catch (RuntimeException ignored) {
            // Shutdown is terminal for playback state.
        }
    }

    private void suspend(long now) {
        long retryAt = now + 1_000L;
        for (State state : states.values()) {
            state.identity.invalidate();
            state.identity.setNextPlaybackAt(retryAt);
            stop(state);
        }
    }

    private void remove(UUID entityId) {
        State state = states.remove(entityId);
        currentTargets.remove(entityId);
        if (state != null) {
            state.identity.invalidate();
            stop(state);
        }
    }

    private void stop(State state) {
        if (state.playback != null) {
            PlaybackHandle playback = state.playback;
            state.playback = null;
            try {
                playback.stop();
            } catch (RuntimeException exception) {
                reportFailure(exception);
            }
        }
    }

    private void invalidateForClear(State state, long now) {
        PluginSettings.Playback playbackSettings = settings.get().playback();
        state.identity.invalidate();
        state.identity.setNextPlaybackAt(now
                + playbackSettings.retryWithoutClipSeconds() * 1_000L);
        stop(state);
    }

    private void reportFailure(RuntimeException exception) {
        try {
            failureReporter.accept(exception);
        } catch (RuntimeException ignored) {
            // A failure reporter must never break playback cleanup.
        }
    }

    private void scheduleRetry(State state, PluginSettings.Playback playbackSettings, long now) {
        state.identity.setNextPlaybackAt(now
                + playbackSettings.retryWithoutClipSeconds() * 1_000L);
    }

    static short[] applyGain(short[] samples, double gain) {
        if (Math.abs(gain - 1.0) < 1.0e-6) {
            return samples;
        }
        short[] adjusted = new short[samples.length];
        for (int index = 0; index < samples.length; index++) {
            int scaled = (int) Math.round(samples[index] * gain);
            adjusted[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        }
        return adjusted;
    }

    @FunctionalInterface
    interface Clock {
        long nowMillis();
    }

    @FunctionalInterface
    interface DelaySource {
        long nextDelayMillis(int minimumSeconds, int maximumSeconds);
    }

    @FunctionalInterface
    interface MainThreadExecutor {
        void execute(Runnable task);
    }

    interface ClipSource {
        VoiceClip select(UUID playerId, String previousClipId);

        CompletableFuture<short[]> read(VoiceClip clip);
    }

    interface PlaybackSink {
        PlaybackHandle start(Target target, VoiceClip clip, short[] samples,
                             double gain, Consumer<PlaybackHandle> stopped);
    }

    interface PlaybackHandle {
        void update(Target target);

        void stop();
    }

    record Target(UUID entityId, boolean mimic, boolean valid, boolean dead,
                  int nearbyListeners, MimicIdentityResolver.Resolution identity) {

        UUID playerId() {
            return identity == null ? null : identity.playerId();
        }

        MimicIdentityResolver.IdentityKey identityKey() {
            return identity == null ? null : identity.identityKey();
        }
    }

    private static final class State {
        private final MimicIdentityTracker identity = new MimicIdentityTracker();
        private PlaybackHandle playback;
    }
}
