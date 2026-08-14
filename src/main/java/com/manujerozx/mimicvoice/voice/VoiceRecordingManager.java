package com.manujerozx.mimicvoice.voice;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.manujerozx.mimicvoice.PluginSettings;
import com.manujerozx.mimicvoice.audio.VoiceActivitySegmenter;
import com.manujerozx.mimicvoice.storage.ClipStore;
import com.manujerozx.mimicvoice.storage.ConsentRegistry;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

public final class VoiceRecordingManager implements AutoCloseable {

    static final int DEFAULT_CAPTURE_QUEUE_CAPACITY = 512;

    private static final long SESSION_EXPIRY_MILLISECONDS = 60_000L;
    private static final long WORKER_POLL_MILLISECONDS = 100L;
    private static final long SHUTDOWN_TIMEOUT_MILLISECONDS = 10_000L;

    private final Logger logger;
    private final Supplier<PluginSettings> settings;
    private final ClipSaver clipSaver;
    private final DecoderFactory decoderFactory;
    private final ConsentRegistry consentRegistry;
    private final Predicate<UUID> captureAllowed;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<UUID, CaptureSession> sessions = new HashMap<>();
    private final ArrayBlockingQueue<WorkItem> workQueue;
    private final int queueCapacity;
    private final Thread worker;
    private final Object lifecycle = new Object();
    private final AtomicLong nextStateId = new AtomicLong();
    private final AtomicLong receivedPackets = new AtomicLong();
    private final AtomicLong processedPackets = new AtomicLong();
    private final AtomicLong overloadDroppedPackets = new AtomicLong();
    private final AtomicLong acceptedSegments = new AtomicLong();
    private final AtomicLong saveFailures = new AtomicLong();
    private final AtomicInteger activeSessionCount = new AtomicInteger();
    private volatile boolean closed;
    private volatile boolean capturePaused;
    private volatile boolean shutdownRequested;

    public VoiceRecordingManager(Logger logger, Supplier<PluginSettings> settings,
                                 ClipStore clipStore, ConsentRegistry consentRegistry,
                                 Predicate<UUID> captureAllowed) {
        this(logger, settings, clipStore::saveOwned, VoicechatApi::createDecoder,
                consentRegistry, captureAllowed, DEFAULT_CAPTURE_QUEUE_CAPACITY);
    }

    VoiceRecordingManager(Logger logger, Supplier<PluginSettings> settings,
                          ClipSaver clipSaver, DecoderFactory decoderFactory,
                          ConsentRegistry consentRegistry, Predicate<UUID> captureAllowed,
                          int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.logger = logger;
        this.settings = settings;
        this.clipSaver = clipSaver;
        this.decoderFactory = decoderFactory;
        this.consentRegistry = consentRegistry;
        this.captureAllowed = captureAllowed;
        this.queueCapacity = queueCapacity;
        this.workQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.worker = Thread.ofPlatform().name("mimic-voice-capture-worker").daemon(true).unstarted(
                this::runWorker);
        this.worker.start();
    }

    /**
     * Performs eligibility checks and submits an owned packet without decoding it.
     * The callback runs on Simple Voice Chat's packet-processing thread, so this method
     * must remain bounded and non-blocking.
     */
    public void onMicrophonePacket(VoicechatApi api, Player player, byte[] opusData, boolean whispering) {
        try {
            enqueueMicrophonePacket(api, player, opusData, whispering);
        } catch (RuntimeException exception) {
            // Recording is passive: an admission-side failure must not escape into
            // Simple Voice Chat's packet dispatch path and affect transmission.
            logger.log(Level.FINE, "Ignoring voice recording packet after admission failure", exception);
        }
    }

    private void enqueueMicrophonePacket(VoicechatApi api, Player player, byte[] opusData,
                                         boolean whispering) {
        if (closed || capturePaused) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PluginSettings.Recording recording = settings.get().recording();
        if (!recording.enabled()
                || (whispering && !recording.recordWhispers())
                || !captureAllowed.test(playerId)
                || !consentRegistry.mayRecord(playerId)
                || (!recording.permission().isBlank() && !player.hasPermission(recording.permission()))) {
            discardExisting(playerId);
            return;
        }
        if (api == null || opusData == null || opusData.length == 0) {
            return;
        }

        PlayerState state = states.computeIfAbsent(playerId,
                ignored -> new PlayerState(playerId, nextStateId.incrementAndGet()));
        long generation;
        synchronized (state.lock) {
            if (closed || capturePaused || !state.accepting) {
                return;
            }
            // Serialize the final eligibility decision with consent/permission
            // controls. This closes the race where a packet passed the first check
            // just before an explicit discard created the state tombstone.
            recording = settings.get().recording();
            if (!recording.enabled()
                    || (whispering && !recording.recordWhispers())
                    || !captureAllowed.test(playerId)
                    || !consentRegistry.mayRecord(playerId)
                    || (!recording.permission().isBlank()
                            && !player.hasPermission(recording.permission()))) {
                requestControlLocked(state, ControlAction.DISCARD);
                return;
            }
            generation = state.generation;
        }

        // The event/packet object is owned by Simple Voice Chat. Retain only this copy
        // because the worker may run after the event callback has returned.
        byte[] ownedOpusData = opusData.clone();
        receivedPackets.incrementAndGet();
        PacketWork work = new PacketWork(api, playerId, state.stateId, generation,
                safePlayerName(player.getName()), whispering, ownedOpusData,
                System.currentTimeMillis());
        if (closed || !workQueue.offer(work)) {
            if (!closed) {
                overloadDroppedPackets.incrementAndGet();
                requestControl(state, ControlAction.DISCARD);
            }
        }
    }

    public void discard(UUID playerId) {
        if (closed || playerId == null) {
            return;
        }
        PlayerState state = states.computeIfAbsent(playerId,
                ignored -> new PlayerState(playerId, nextStateId.incrementAndGet()));
        requestControl(state, ControlAction.DISCARD);
    }

    public void finish(UUID playerId) {
        requestControl(playerId, ControlAction.FINISH);
    }

    /**
     * Ends a player's current capture and blocks packets until that player joins again.
     * The state tombstone closes the race where a quit event arrives before the first
     * packet for that player has created a state entry.
     */
    public void endPlayer(UUID playerId) {
        if (closed || playerId == null) {
            return;
        }
        PlayerState state = states.computeIfAbsent(playerId,
                ignored -> new PlayerState(playerId, nextStateId.incrementAndGet()));
        synchronized (state.lock) {
            state.accepting = false;
            requestControlLocked(state, ControlAction.FINISH);
        }
    }

    /** Clears the quit barrier; privacy and permission checks still apply. */
    public void resumePlayer(UUID playerId) {
        PlayerState state = states.get(playerId);
        if (state != null) {
            state.accepting = true;
        }
    }

    /** Pauses packet admission and finishes/discards the current sessions. */
    public void pause() {
        if (closed) {
            return;
        }
        capturePaused = true;
        ControlAction action = settings.get().recording().enabled()
                ? ControlAction.FINISH : ControlAction.DISCARD;
        states.values().forEach(state -> requestControl(state, action));
    }

    /** Resumes packet admission after a voice-server pause. */
    public void resume() {
        if (!closed) {
            capturePaused = false;
        }
    }

    public void reload() {
        if (closed) {
            return;
        }
        ControlAction action = settings.get().recording().enabled()
                ? ControlAction.FINISH : ControlAction.DISCARD;
        states.values().forEach(state -> requestControl(state, action));
    }

    public int activeSessions() {
        return activeSessionCount.get();
    }

    public long receivedPackets() {
        return receivedPackets.get();
    }

    public long processedPackets() {
        return processedPackets.get();
    }

    public long overloadDroppedPackets() {
        return overloadDroppedPackets.get();
    }

    public long savedClips() {
        return acceptedSegments.get();
    }

    public long saveFailures() {
        return saveFailures.get();
    }

    public int queueDepth() {
        return workQueue.size();
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    boolean workerAlive() {
        return worker.isAlive();
    }

    /** Test seam: places a FIFO barrier after all currently queued work. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        if (closed && !worker.isAlive()) {
            return true;
        }
        CountDownLatch completed = new CountDownLatch(1);
        if (!workQueue.offer(new BarrierWork(completed), timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return false;
        }
        return completed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runWorker() {
        long nextMaintenance = System.currentTimeMillis() + WORKER_POLL_MILLISECONDS;
        try {
            while (true) {
                WorkItem work = null;
                try {
                    work = workQueue.poll(WORKER_POLL_MILLISECONDS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // close() interrupts the poll to make shutdown prompt; accepted
                    // packets still drain because the loop exits only after the queue is empty.
                }

                drainPendingControls();
                if (work instanceof PacketWork packet) {
                    processPacket(packet);
                } else if (work instanceof BarrierWork barrier) {
                    drainPendingControls();
                    barrier.completed().countDown();
                }

                long now = System.currentTimeMillis();
                if (now >= nextMaintenance) {
                    finishIdleSessions(now);
                    nextMaintenance = now + WORKER_POLL_MILLISECONDS;
                }
                drainPendingControls();

                if (shutdownRequested && workQueue.isEmpty()) {
                    drainPendingControls();
                    closeAllSessions(true);
                    return;
                }
            }
        } catch (Throwable failure) {
            synchronized (lifecycle) {
                closed = true;
                shutdownRequested = true;
            }
            logger.log(Level.SEVERE, "Voice capture worker failed", failure);
            closeAllSessions(false);
        }
    }

    private void processPacket(PacketWork work) {
        processedPackets.incrementAndGet();
        PlayerState state = states.get(work.playerId());
        if (state == null || state.stateId != work.stateId()) {
            CaptureSession staleSession = sessions.get(work.playerId());
            if (staleSession != null && staleSession.stateId == work.stateId()
                    && staleSession.generation == work.generation()) {
                closeSession(staleSession.playerId, staleSession.stateId, false, -1L);
            }
            return;
        }

        // A control request can race with the worker's queue poll. Applying it here
        // makes the control a barrier before a packet captured in the new generation.
        applyPendingControl(state);
        if (!isCurrent(state, work.generation())) {
            CaptureSession staleSession = sessions.get(work.playerId());
            if (staleSession != null && staleSession.stateId == work.stateId()
                    && staleSession.generation == work.generation()) {
                closeSession(staleSession.playerId, staleSession.stateId, false, -1L);
            }
            return;
        }

        CaptureSession session = sessions.get(work.playerId());
        if (session != null && (session.stateId != work.stateId()
                || session.generation != work.generation())) {
            closeSession(session.playerId, session.stateId, false, -1L);
            session = null;
        }
        if (session == null) {
            try {
                OpusDecoder decoder = decoderFactory.create(work.api());
                if (decoder == null) {
                    throw new IllegalStateException("Simple Voice Chat returned a null Opus decoder");
                }
                session = new CaptureSession(state, work, decoder,
                        new VoiceActivitySegmenter(settings.get().recording().voiceActivity()));
                sessions.put(work.playerId(), session);
                activeSessionCount.incrementAndGet();
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Could not create an Opus decoder for " + work.playerId(), exception);
                requestControl(state, ControlAction.DISCARD);
                return;
            }
        }

        try {
            session.accept(work);
            if (!isCurrent(state, work.generation())) {
                applyPendingControl(state);
                if (sessions.get(work.playerId()) == session) {
                    closeSession(work.playerId(), work.stateId(), false, -1L);
                }
            }
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Could not decode voice packet for " + work.playerName(), exception);
            closeSession(work.playerId(), work.stateId(), false, -1L);
            requestControl(state, ControlAction.DISCARD);
        }
    }

    private void drainPendingControls() {
        states.values().forEach(this::applyPendingControl);
    }

    private void applyPendingControl(PlayerState state) {
        ControlRequest request;
        synchronized (state.lock) {
            request = state.pending;
            if (request == null) {
                return;
            }
            if (request.generation() != state.generation) {
                state.pending = null;
                return;
            }
            state.pending = null;
        }

        if (request.action() == ControlAction.FINISH) {
            closeSession(state.playerId, state.stateId, true, request.generation());
        } else {
            closeSession(state.playerId, state.stateId, false, -1L);
        }
    }

    private void requestControl(UUID playerId, ControlAction action) {
        PlayerState state = states.get(playerId);
        if (state != null) {
            requestControl(state, action);
        }
    }

    private void discardExisting(UUID playerId) {
        PlayerState state = states.get(playerId);
        if (state != null) {
            requestControl(state, ControlAction.DISCARD);
        }
    }

    private void requestControl(PlayerState state, ControlAction action) {
        synchronized (state.lock) {
            requestControlLocked(state, action);
        }
    }

    private void requestControlLocked(PlayerState state, ControlAction action) {
        state.generation++;
        ControlAction effectiveAction = state.pending == null
                ? action : combine(state.pending.action(), action);
        state.pending = new ControlRequest(state.generation, effectiveAction);
    }

    private ControlAction combine(ControlAction current, ControlAction requested) {
        return current == ControlAction.DISCARD || requested == ControlAction.DISCARD
                ? ControlAction.DISCARD : ControlAction.FINISH;
    }

    private boolean isCurrent(PlayerState state, long generation) {
        return state.generation == generation;
    }

    private void finishIdleSessions(long now) {
        int resetAfter = settings.get().recording().voiceActivity().streamResetMilliseconds();
        for (CaptureSession session : new HashMap<>(sessions).values()) {
            PlayerState state = session.state;
            if (!isCurrent(state, session.generation)) {
                applyPendingControl(state);
                if (sessions.get(session.playerId) == session) {
                    closeSession(session.playerId, session.stateId, false, -1L);
                }
                continue;
            }

            long idle = now - session.lastPacketAt;
            if (idle >= SESSION_EXPIRY_MILLISECONDS) {
                closeSession(session.playerId, session.stateId, true, -1L);
            } else if (!session.idleFlushed && idle >= resetAfter) {
                session.flushSegment(-1L);
                session.decoder.resetState();
                session.idleFlushed = true;
            }
        }
    }

    private void publishSegment(PlayerState state, long generation, String playerName,
                                short[] samples, long allowedGeneration) {
        if (samples == null || samples.length == 0) {
            return;
        }
        // The generation read is the save's linearization point. Do not hold the
        // state lock while ClipStore clones PCM and submits asynchronous I/O; that
        // would make a microphone callback wait behind a large completed phrase.
        if (state.generation != generation
                && (allowedGeneration < 0 || state.generation != allowedGeneration)) {
            return;
        }
        try {
            if (clipSaver.save(state.playerId, playerName, samples)) {
                acceptedSegments.incrementAndGet();
            } else {
                saveFailures.incrementAndGet();
            }
        } catch (RuntimeException exception) {
            saveFailures.incrementAndGet();
            logger.log(Level.WARNING, "Could not submit speech clip for " + playerName, exception);
        }
    }

    private void closeSession(UUID playerId, long stateId, boolean saveActiveSegment,
                              long allowedGeneration) {
        CaptureSession session = sessions.get(playerId);
        if (session == null || session.stateId != stateId || !sessions.remove(playerId, session)) {
            return;
        }
        try {
            session.close(saveActiveSegment, allowedGeneration);
        } finally {
            activeSessionCount.decrementAndGet();
        }
    }

    private void closeAllSessions(boolean saveActiveSegments) {
        for (CaptureSession session : new HashMap<>(sessions).values()) {
            closeSession(session.playerId, session.stateId, saveActiveSegments, -1L);
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (!closed) {
                closed = true;
                shutdownRequested = true;
                worker.interrupt();
            }
        }
        if (Thread.currentThread() == worker) {
            return;
        }
        try {
            worker.join(SHUTDOWN_TIMEOUT_MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            logger.warning("Voice capture worker did not stop within the shutdown timeout; interrupting it.");
            worker.interrupt();
            try {
                worker.join(1_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String safePlayerName(String playerName) {
        return playerName == null ? "unknown" : playerName;
    }

    @FunctionalInterface
    interface ClipSaver {
        boolean save(UUID playerId, String playerName, short[] samples);
    }

    @FunctionalInterface
    interface DecoderFactory {
        OpusDecoder create(VoicechatApi api);
    }

    private enum ControlAction {
        DISCARD,
        FINISH
    }

    private record ControlRequest(long generation, ControlAction action) {
    }

    private record PacketWork(VoicechatApi api, UUID playerId, long stateId, long generation,
                              String playerName, boolean whispering, byte[] opusData,
                              long receivedAtMilliseconds) implements WorkItem {
    }

    private record BarrierWork(CountDownLatch completed) implements WorkItem {
    }

    private sealed interface WorkItem permits PacketWork, BarrierWork {
    }

    private final class PlayerState {
        private final UUID playerId;
        private final long stateId;
        private final Object lock = new Object();
        private volatile long generation;
        private volatile boolean accepting = true;
        private ControlRequest pending;

        private PlayerState(UUID playerId, long stateId) {
            this.playerId = playerId;
            this.stateId = stateId;
        }
    }

    private final class CaptureSession {
        private final PlayerState state;
        private final UUID playerId;
        private final long stateId;
        private final long generation;
        private final OpusDecoder decoder;
        private final VoiceActivitySegmenter segmenter;
        private String playerName;
        private long lastPacketAt;
        private boolean idleFlushed;

        private CaptureSession(PlayerState state, PacketWork firstWork, OpusDecoder decoder,
                                VoiceActivitySegmenter segmenter) {
            this.state = state;
            this.playerId = firstWork.playerId();
            this.stateId = firstWork.stateId();
            this.generation = firstWork.generation();
            this.decoder = decoder;
            this.segmenter = segmenter;
            this.playerName = firstWork.playerName();
            this.lastPacketAt = firstWork.receivedAtMilliseconds();
        }

        private void accept(PacketWork work) {
            long now = work.receivedAtMilliseconds();
            int resetAfter = settings.get().recording().voiceActivity().streamResetMilliseconds();
            if (now - lastPacketAt >= resetAfter) {
                flushSegment(-1L);
                decoder.resetState();
            }
            if (!isCurrent(state, generation)) {
                return;
            }
            playerName = work.playerName();
            lastPacketAt = Math.max(lastPacketAt, now);
            idleFlushed = false;

            short[] decoded = decoder.decode(work.opusData());
            if (decoded == null || decoded.length == 0) {
                return;
            }
            for (int offset = 0; offset < decoded.length; offset += PluginSettings.FRAME_SIZE) {
                if (!isCurrent(state, generation)) {
                    return;
                }
                int length = Math.min(decoded.length - offset, PluginSettings.FRAME_SIZE);
                short[] completed = segmenter.accept(decoded, offset, length);
                publishSegment(state, generation, playerName, completed, -1L);
            }
        }

        private void flushSegment(long allowedGeneration) {
            publishSegment(state, generation, playerName,
                    segmenter.flush(), allowedGeneration);
        }

        private void close(boolean saveActiveSegment, long allowedGeneration) {
            if (saveActiveSegment) {
                flushSegment(allowedGeneration);
            } else {
                segmenter.reset();
            }
            decoder.close();
        }
    }
}
