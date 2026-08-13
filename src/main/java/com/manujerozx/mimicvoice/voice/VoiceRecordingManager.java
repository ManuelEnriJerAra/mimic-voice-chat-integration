package com.manujerozx.mimicvoice.voice;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private static final long SESSION_EXPIRY_MILLISECONDS = 60_000L;

    private final Logger logger;
    private final Supplier<PluginSettings> settings;
    private final ClipStore clipStore;
    private final ConsentRegistry consentRegistry;
    private final Predicate<UUID> captureAllowed;
    private final Map<UUID, CaptureSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService maintenance;
    private final AtomicLong receivedPackets = new AtomicLong();
    private final AtomicLong savedClips = new AtomicLong();
    private volatile boolean closed;

    public VoiceRecordingManager(Logger logger, Supplier<PluginSettings> settings,
                                 ClipStore clipStore, ConsentRegistry consentRegistry,
                                 Predicate<UUID> captureAllowed) {
        this.logger = logger;
        this.settings = settings;
        this.clipStore = clipStore;
        this.consentRegistry = consentRegistry;
        this.captureAllowed = captureAllowed;
        this.maintenance = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().name("mimic-voice-capture").daemon(true).unstarted(runnable));
        this.maintenance.scheduleAtFixedRate(this::finishIdleSessions, 100L, 100L, TimeUnit.MILLISECONDS);
    }

    public void onMicrophonePacket(VoicechatApi api, Player player, byte[] opusData, boolean whispering) {
        if (closed) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PluginSettings.Recording recording = settings.get().recording();
        if (!recording.enabled()
                || (whispering && !recording.recordWhispers())
                || !captureAllowed.test(playerId)
                || !consentRegistry.mayRecord(playerId)
                || (!recording.permission().isBlank() && !player.hasPermission(recording.permission()))) {
            discard(playerId);
            return;
        }
        if (opusData == null || opusData.length == 0) {
            return;
        }

        receivedPackets.incrementAndGet();
        CaptureSession session;
        synchronized (sessions) {
            if (closed) {
                return;
            }
            session = sessions.computeIfAbsent(playerId, ignored ->
                    new CaptureSession(playerId, player.getName(), api.createDecoder(),
                            new VoiceActivitySegmenter(recording.voiceActivity())));
        }
        try {
            session.accept(opusData, player.getName());
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Could not decode voice packet for " + player.getName(), exception);
            discard(playerId);
        }
    }

    public void discard(UUID playerId) {
        CaptureSession session = sessions.remove(playerId);
        if (session != null) {
            session.close(false);
        }
    }

    public void finish(UUID playerId) {
        CaptureSession session = sessions.remove(playerId);
        if (session != null) {
            session.close(true);
        }
    }

    public void reload() {
        if (closed) {
            return;
        }
        for (UUID playerId : sessions.keySet()) {
            if (settings.get().recording().enabled()) {
                finish(playerId);
            } else {
                discard(playerId);
            }
        }
    }

    public int activeSessions() {
        return sessions.size();
    }

    public long receivedPackets() {
        return receivedPackets.get();
    }

    public long savedClips() {
        return savedClips.get();
    }

    private void finishIdleSessions() {
        try {
            long now = System.currentTimeMillis();
            int resetAfter = settings.get().recording().voiceActivity().streamResetMilliseconds();
            for (Map.Entry<UUID, CaptureSession> entry : sessions.entrySet()) {
                CaptureSession session = entry.getValue();
                long idle = now - session.lastPacketAt();
                if (idle >= SESSION_EXPIRY_MILLISECONDS && sessions.remove(entry.getKey(), session)) {
                    session.close(true);
                } else if (idle >= resetAfter) {
                    session.flushIfIdle(now, resetAfter);
                }
            }
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Voice capture maintenance failed", exception);
        }
    }

    private void save(UUID playerId, String playerName, short[] samples) {
        if (samples == null || samples.length == 0) {
            return;
        }
        savedClips.incrementAndGet();
        clipStore.save(playerId, playerName, samples);
    }

    @Override
    public void close() {
        synchronized (sessions) {
            if (closed) {
                return;
            }
            closed = true;
        }
        maintenance.shutdownNow();
        try {
            if (!maintenance.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("Voice capture maintenance did not stop cleanly.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        for (UUID playerId : sessions.keySet()) {
            finish(playerId);
        }
    }

    private final class CaptureSession {
        private final UUID playerId;
        private final OpusDecoder decoder;
        private final VoiceActivitySegmenter segmenter;
        private String playerName;
        private long lastPacketAt = System.currentTimeMillis();
        private boolean idleFlushed;
        private boolean closed;

        private CaptureSession(UUID playerId, String playerName, OpusDecoder decoder,
                               VoiceActivitySegmenter segmenter) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.decoder = decoder;
            this.segmenter = segmenter;
        }

        private synchronized void accept(byte[] opusData, String latestPlayerName) {
            if (closed) {
                return;
            }
            long now = System.currentTimeMillis();
            int resetAfter = settings.get().recording().voiceActivity().streamResetMilliseconds();
            if (now - lastPacketAt >= resetAfter) {
                flushSegment();
                decoder.resetState();
            }
            playerName = latestPlayerName;
            lastPacketAt = now;
            idleFlushed = false;

            short[] decoded = decoder.decode(opusData);
            for (int offset = 0; offset < decoded.length; offset += PluginSettings.FRAME_SIZE) {
                int end = Math.min(decoded.length, offset + PluginSettings.FRAME_SIZE);
                short[] frame = Arrays.copyOfRange(decoded, offset, end);
                short[] completed = segmenter.accept(frame);
                save(playerId, playerName, completed);
            }
        }

        private synchronized void flushIfIdle(long now, int resetAfter) {
            if (closed || idleFlushed || now - lastPacketAt < resetAfter) {
                return;
            }
            flushSegment();
            decoder.resetState();
            idleFlushed = true;
        }

        private synchronized void close(boolean saveActiveSegment) {
            if (closed) {
                return;
            }
            if (saveActiveSegment) {
                flushSegment();
            } else {
                segmenter.reset();
            }
            decoder.close();
            closed = true;
        }

        private void flushSegment() {
            save(playerId, playerName, segmenter.flush());
        }

        private synchronized long lastPacketAt() {
            return lastPacketAt;
        }
    }
}
