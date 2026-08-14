package com.manujerozx.mimicvoice.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.manujerozx.mimicvoice.PluginSettings;
import com.manujerozx.mimicvoice.audio.SpeechQuality;
import com.manujerozx.mimicvoice.audio.WavIO;

public final class ClipStore implements AutoCloseable {

    private static final int MAX_PENDING_SAVES = 256;

    private final Path root;
    private final Path quarantineRoot;
    private final Logger logger;
    private final Supplier<PluginSettings> settings;
    private final ScheduledExecutorService ioExecutor;
    private final PendingAudioBudget pendingAudioBudget = new PendingAudioBudget(MAX_PENDING_SAVES);
    private final Set<PendingSave> pendingSaves = ConcurrentHashMap.newKeySet();
    private final AtomicLong rejectedBackpressureCount = new AtomicLong();
    private final AtomicLong failedSaveCount = new AtomicLong();
    private final AtomicLong successfulSaveCount = new AtomicLong();
    private final AtomicLong lastBackpressureLogAt = new AtomicLong();
    private final Map<UUID, CopyOnWriteArrayList<VoiceClip>> clips = new ConcurrentHashMap<>();
    private final Map<String, UUID> playersByName = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public ClipStore(Path root, Logger logger, Supplier<PluginSettings> settings) {
        this.root = root;
        this.quarantineRoot = root.resolve("_rejected_noise");
        this.logger = logger;
        this.settings = settings;
        this.ioExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().name("mimic-voice-storage").daemon(true).unstarted(runnable));
        this.ioExecutor.scheduleWithFixedDelay(this::runRetentionMaintenance,
                5L, 5L, TimeUnit.MINUTES);
    }

    public CompletableFuture<Integer> initialize() {
        return CompletableFuture.supplyAsync(this::loadPersistedClips, ioExecutor);
    }

    /** Queues a save without blocking and defensively owns caller-provided samples. */
    public boolean save(UUID playerId, String playerName, short[] samples) {
        return submitSave(playerId, playerName, samples, false);
    }

    /** Queues a save while taking ownership of a freshly completed segment. */
    public boolean saveOwned(UUID playerId, String playerName, short[] samples) {
        return submitSave(playerId, playerName, samples, true);
    }

    private boolean submitSave(UUID playerId, String playerName, short[] samples, boolean owned) {
        if (closed || samples == null || samples.length == 0) {
            return false;
        }
        long bytes = (long) samples.length * Short.BYTES;
        if (!pendingAudioBudget.tryReserve(bytes, settings.get().storage().maximumPendingWriteBytes())) {
            rejectForBackpressure(playerName);
            return false;
        }

        short[] ownedSamples;
        PendingSave pending = null;
        try {
            ownedSamples = owned ? samples : samples.clone();
            pending = new PendingSave(playerId, playerName, ownedSamples, bytes);
            pendingSaves.add(pending);
            ioExecutor.execute(pending);
            return true;
        } catch (RejectedExecutionException exception) {
            if (pending == null) {
                releaseReservation(bytes);
            } else {
                pending.cancel();
            }
            rejectForBackpressure(playerName);
            logger.log(Level.WARNING, "Could not queue voice clip storage work", exception);
            return false;
        } catch (RuntimeException exception) {
            if (pending == null) {
                releaseReservation(bytes);
            } else {
                pending.cancel();
            }
            failedSaveCount.incrementAndGet();
            logger.log(Level.WARNING, "Could not prepare voice clip storage work for "
                    + safePlayerName(playerName), exception);
            return false;
        }
    }

    public VoiceClip select(UUID playerId, String previousClipId) {
        if (playerId == null) {
            return null;
        }
        List<VoiceClip> candidates = new ArrayList<>(
                clips.getOrDefault(playerId, new CopyOnWriteArrayList<>()));
        long cutoff = retentionCutoff();
        double minimumSeconds = settings.get().playback().minimumClipSeconds();
        candidates.removeIf(clip -> clip.createdAt() < cutoff
                || clip.durationSeconds(PluginSettings.SAMPLE_RATE) < minimumSeconds);
        if (candidates.isEmpty()) {
            return null;
        }

        if (candidates.size() > 1 && previousClipId != null) {
            candidates.removeIf(clip -> previousClipId.equals(clip.id()));
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    public CompletableFuture<short[]> read(VoiceClip clip) {
        if (clip.memoryAudio() != null) {
            return CompletableFuture.completedFuture(clip.memoryAudio().clone());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                WavIO.WavData data = WavIO.read(clip.path());
                if (data.sampleRate() != PluginSettings.SAMPLE_RATE) {
                    throw new IOException("Unexpected sample rate " + data.sampleRate());
                }
                return data.samples();
            } catch (IOException | RuntimeException exception) {
                invalidateUnreadableClip(clip, exception);
                return null;
            }
        }, ioExecutor);
    }

    public CompletableFuture<Integer> clearAll() {
        return CompletableFuture.supplyAsync(() -> {
            int acceptedCount = clipCount();
            List<VoiceClip> snapshot = allClips();
            clips.clear();
            playersByName.clear();
            snapshot.forEach(this::deleteFile);
            int quarantinedCount = deleteQuarantinedFiles(null);
            removeEmptyDirectories();
            return acceptedCount + quarantinedCount;
        }, ioExecutor);
    }

    public CompletableFuture<Integer> clearPlayer(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            List<VoiceClip> removed = clips.remove(playerId);
            int acceptedCount = 0;
            if (removed != null) {
                removed.forEach(this::deleteFile);
                acceptedCount = removed.size();
            }
            playersByName.entrySet().removeIf(entry -> playerId.equals(entry.getValue()));
            int quarantinedCount = deleteQuarantinedFiles(playerId);
            removeEmptyDirectories();
            return acceptedCount + quarantinedCount;
        }, ioExecutor);
    }

    public UUID findPlayerId(String playerName) {
        return playerName == null ? null : playersByName.get(playerName.toLowerCase(Locale.ROOT));
    }

    public int clipCount() {
        return clips.values().stream().mapToInt(List::size).sum();
    }

    public int playerCount() {
        return (int) clips.values().stream().filter(list -> !list.isEmpty()).count();
    }

    public int pendingSaveCount() {
        return pendingAudioBudget.pendingEntries();
    }

    public long pendingSaveBytes() {
        return pendingAudioBudget.pendingBytes();
    }

    public long saveSucceeded() {
        return successfulSaveCount.get();
    }

    public long saveFailures() {
        return failedSaveCount.get();
    }

    public long saveRejectedBackpressure() {
        return rejectedBackpressureCount.get();
    }

    public long memoryAudioBytes() {
        return allClips().stream()
                .filter(clip -> clip.memoryAudio() != null)
                .mapToLong(clip -> (long) clip.samples() * Short.BYTES)
                .sum();
    }

    /**
     * Places a FIFO barrier after currently queued storage work.
     *
     * <p>This is also used by deterministic component tests that combine the
     * asynchronous storage boundary with playback selection.</p>
     */
    public CompletableFuture<Void> awaitIdle() {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        try {
            ioExecutor.execute(() -> barrier.complete(null));
        } catch (RejectedExecutionException exception) {
            barrier.completeExceptionally(exception);
        }
        return barrier;
    }

    private int loadPersistedClips() {
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not create voice recording directory " + root, exception);
            return 0;
        }

        long cutoff = retentionCutoff();
        purgeExpiredQuarantinedFiles(cutoff);
        if (!settings.get().storage().persistClips()) {
            return 0;
        }
        try (Stream<Path> playerDirectories = Files.list(root)) {
            playerDirectories.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.equals(quarantineRoot))
                    .forEach(directory -> loadPlayerDirectory(directory, cutoff));
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not scan saved voice clips", exception);
        }
        clips.keySet().forEach(this::enforcePlayerLimit);
        return clipCount();
    }

    private void loadPlayerDirectory(Path directory, long cutoff) {
        UUID playerId;
        try {
            playerId = UUID.fromString(directory.getFileName().toString());
        } catch (IllegalArgumentException exception) {
            return;
        }

        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".wav"))
                    .sorted()
                    .forEach(path -> loadClip(playerId, path, cutoff));
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not scan voice clips in " + directory, exception);
        }
    }

    private void loadClip(UUID playerId, Path path, long cutoff) {
        String fileName = path.getFileName().toString();
        int separator = fileName.indexOf('_');
        int randomSuffix = fileName.lastIndexOf('_');
        int extension = fileName.lastIndexOf('.');
        if (separator <= 0 || randomSuffix <= separator || extension <= randomSuffix) {
            quarantineInvalidPersistedClip(path, playerId, null);
            return;
        }
        try {
            long createdAt = Long.parseLong(fileName.substring(0, separator));
            if (createdAt < cutoff) {
                Files.deleteIfExists(path);
                return;
            }
            String playerName = fileName.substring(separator + 1, randomSuffix);
            WavIO.WavData data = WavIO.read(path);
            if (data.sampleRate() != PluginSettings.SAMPLE_RATE || data.samples().length <= 0) {
                quarantineInvalidPersistedClip(path, playerId, null);
                return;
            }
            if (!SpeechQuality.hasEnoughSpeech(
                    data.samples(), settings.get().recording().voiceActivity())) {
                playersByName.put(playerName.toLowerCase(Locale.ROOT), playerId);
                quarantine(path, playerId);
                return;
            }
            register(new VoiceClip(fileName, playerId, playerName, path, null,
                    data.samples().length, createdAt));
        } catch (IOException | RuntimeException exception) {
            quarantineInvalidPersistedClip(path, playerId, exception);
        }
    }

    private void quarantineInvalidPersistedClip(Path path, UUID playerId, Exception failure) {
        try {
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                quarantine(path, playerId);
            }
        } catch (IOException quarantineFailure) {
            logger.log(Level.WARNING, "Could not quarantine invalid saved voice clip " + path,
                    quarantineFailure);
        }
        if (failure != null) {
            logger.log(Level.WARNING, "Quarantined invalid saved voice clip " + path, failure);
        }
    }

    private boolean saveNow(UUID playerId, String playerName, short[] samples) throws IOException {
        String safeName = safePlayerName(playerName);
        long createdAt = System.currentTimeMillis();
        String id = createdAt + "_" + safeName + "_" + UUID.randomUUID().toString().substring(0, 8);
        PluginSettings.Storage storage = settings.get().storage();
        if (!storage.persistClips()) {
            register(new VoiceClip(id, playerId, safeName, null, samples, samples.length, createdAt));
            enforcePlayerLimit(playerId);
            enforceMemoryLimit();
            return true;
        }

        Path path = root.resolve(playerId.toString()).resolve(id + ".wav");
        WavIO.write(path, samples, PluginSettings.SAMPLE_RATE);
        register(new VoiceClip(id, playerId, safeName, path, null, samples.length, createdAt));
        enforcePlayerLimit(playerId);
        return true;
    }

    private void register(VoiceClip clip) {
        clips.computeIfAbsent(clip.speakerId(), ignored -> new CopyOnWriteArrayList<>()).add(clip);
        playersByName.put(clip.speakerName().toLowerCase(Locale.ROOT), clip.speakerId());
    }

    private void enforcePlayerLimit(UUID playerId) {
        CopyOnWriteArrayList<VoiceClip> playerClips = clips.get(playerId);
        if (playerClips == null) {
            return;
        }
        long cutoff = retentionCutoff();
        for (VoiceClip clip : new ArrayList<>(playerClips)) {
            if (clip.createdAt() < cutoff) {
                playerClips.remove(clip);
                deleteFile(clip);
            }
        }
        List<VoiceClip> ordered = new ArrayList<>(playerClips);
        ordered.sort(Comparator.comparingLong(VoiceClip::createdAt).reversed());
        int maximum = settings.get().storage().maximumClipsPerPlayer();
        for (int index = maximum; index < ordered.size(); index++) {
            VoiceClip expired = ordered.get(index);
            playerClips.remove(expired);
            deleteFile(expired);
        }
    }

    private void enforceMemoryLimit() {
        long maximumBytes = settings.get().storage().maximumMemoryAudioBytes();
        List<VoiceClip> memoryClips = allClips().stream()
                .filter(clip -> clip.memoryAudio() != null)
                .sorted(Comparator.comparingLong(VoiceClip::createdAt))
                .toList();
        long totalBytes = memoryClips.stream()
                .mapToLong(clip -> (long) clip.samples() * Short.BYTES)
                .sum();
        for (VoiceClip clip : memoryClips) {
            if (totalBytes <= maximumBytes) {
                break;
            }
            if (removeActiveClip(clip)) {
                totalBytes -= (long) clip.samples() * Short.BYTES;
            }
        }
    }

    private void invalidateUnreadableClip(VoiceClip clip, Exception failure) {
        removeActiveClip(clip);
        Path path = clip.path();
        if (path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                quarantine(path, clip.speakerId());
            } catch (IOException quarantineFailure) {
                logger.log(Level.WARNING, "Could not quarantine unreadable voice clip " + path,
                        quarantineFailure);
            }
        }
        logger.log(Level.WARNING, "Removed unreadable voice clip from playback index " + path, failure);
    }

    private boolean removeActiveClip(VoiceClip clip) {
        CopyOnWriteArrayList<VoiceClip> playerClips = clips.get(clip.speakerId());
        if (playerClips == null || !playerClips.remove(clip)) {
            return false;
        }
        if (playerClips.isEmpty()) {
            clips.remove(clip.speakerId(), playerClips);
        }
        return true;
    }

    private void rejectForBackpressure(String playerName) {
        rejectedBackpressureCount.incrementAndGet();
        long now = System.currentTimeMillis();
        long previous = lastBackpressureLogAt.get();
        if ((previous == 0L || now - previous >= 10_000L)
                && lastBackpressureLogAt.compareAndSet(previous, now)) {
            logger.warning("Voice clip storage backpressure rejected a clip for "
                    + safePlayerName(playerName));
        }
    }

    private void releaseReservation(long bytes) {
        pendingAudioBudget.release(bytes);
    }

    private long retentionCutoff() {
        return System.currentTimeMillis()
                - Duration.ofHours(settings.get().storage().retentionHours()).toMillis();
    }

    private List<VoiceClip> allClips() {
        List<VoiceClip> snapshot = new ArrayList<>();
        clips.values().forEach(snapshot::addAll);
        return snapshot;
    }

    private void deleteFile(VoiceClip clip) {
        if (clip.path() == null) {
            return;
        }
        try {
            Files.deleteIfExists(clip.path());
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not delete voice clip " + clip.path(), exception);
        }
    }

    private void quarantine(Path path, UUID playerId) throws IOException {
        Path quarantineDirectory = quarantineRoot.resolve(playerId.toString());
        Files.createDirectories(quarantineDirectory);
        Path destination = quarantineDirectory.resolve(path.getFileName());
        if (Files.exists(destination)) {
            destination = quarantineDirectory.resolve(UUID.randomUUID() + "_" + path.getFileName());
        }
        Files.move(path, destination);
        logger.info("Quarantined rejected voice clip " + path.getFileName());
    }

    private void purgeExpiredQuarantinedFiles(long cutoff) {
        if (!Files.isDirectory(quarantineRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> directories = Files.list(quarantineRoot)) {
            for (Path directory : directories
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                UUID playerId = directoryPlayerId(directory);
                try (Stream<Path> files = Files.list(directory)) {
                    for (Path path : files
                            .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                            .filter(file -> file.getFileName().toString().endsWith(".wav")).toList()) {
                        try {
                            if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() < cutoff) {
                                Files.deleteIfExists(path);
                            } else if (playerId != null) {
                                indexQuarantinedPlayerName(path, playerId);
                            }
                        } catch (IOException exception) {
                            logger.log(Level.WARNING, "Could not expire quarantined voice clip " + path,
                                    exception);
                        }
                    }
                } catch (IOException exception) {
                    logger.log(Level.WARNING, "Could not scan quarantined voice clips in " + directory,
                            exception);
                }
                deleteDirectoryIfEmpty(directory);
            }
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not scan quarantined voice clips", exception);
        }
        deleteDirectoryIfEmpty(quarantineRoot);
    }

    private UUID directoryPlayerId(Path directory) {
        try {
            return UUID.fromString(directory.getFileName().toString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void indexQuarantinedPlayerName(Path path, UUID playerId) {
        String stem = path.getFileName().toString();
        stem = stem.substring(0, stem.length() - ".wav".length());
        int firstSeparator = stem.indexOf('_');
        if (firstSeparator == 36) {
            try {
                UUID.fromString(stem.substring(0, firstSeparator));
                stem = stem.substring(firstSeparator + 1);
            } catch (IllegalArgumentException ignored) {
                // The first field is the normal creation timestamp, not a collision prefix.
            }
        }
        int timestampSeparator = stem.indexOf('_');
        int suffixSeparator = stem.lastIndexOf('_');
        if (timestampSeparator <= 0 || suffixSeparator <= timestampSeparator) {
            return;
        }
        try {
            Long.parseLong(stem.substring(0, timestampSeparator));
            String playerName = stem.substring(timestampSeparator + 1, suffixSeparator);
            if (!playerName.isBlank()) {
                playersByName.put(playerName.toLowerCase(Locale.ROOT), playerId);
            }
        } catch (NumberFormatException ignored) {
            // Files without the plugin's normal naming scheme cannot be indexed by name.
        }
    }

    private void runRetentionMaintenance() {
        try {
            clips.keySet().forEach(this::enforcePlayerLimit);
            enforceMemoryLimit();
            purgeExpiredQuarantinedFiles(retentionCutoff());
            removeEmptyDirectories();
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Voice clip retention maintenance failed", exception);
        }
    }

    private int deleteQuarantinedFiles(UUID playerId) {
        if (playerId != null) {
            return deleteQuarantinedDirectory(quarantineRoot.resolve(playerId.toString()));
        }
        if (!Files.isDirectory(quarantineRoot, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> directories = Files.list(quarantineRoot)) {
            for (Path directory : directories
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                deleted += deleteQuarantinedDirectory(directory);
            }
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not clear quarantined voice clips", exception);
        }
        deleteDirectoryIfEmpty(quarantineRoot);
        return deleted;
    }

    private int deleteQuarantinedDirectory(Path directory) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path : files
                    .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> file.getFileName().toString().endsWith(".wav")).toList()) {
                try {
                    if (Files.deleteIfExists(path)) {
                        deleted++;
                    }
                } catch (IOException exception) {
                    logger.log(Level.WARNING, "Could not delete quarantined voice clip " + path, exception);
                }
            }
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not scan quarantined voice clips in " + directory, exception);
        }
        deleteDirectoryIfEmpty(directory);
        return deleted;
    }

    private void deleteDirectoryIfEmpty(Path directory) {
        try (Stream<Path> contents = Files.list(directory)) {
            if (contents.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        } catch (IOException ignored) {
            // A failed cleanup does not affect the clip index.
        }
    }

    private void removeEmptyDirectories() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> directories = Files.list(root)) {
            directories.filter(Files::isDirectory).forEach(directory -> {
                try (Stream<Path> contents = Files.list(directory)) {
                    if (contents.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                } catch (IOException ignored) {
                    // A failed cleanup does not affect the clip index.
                }
            });
        } catch (IOException ignored) {
            // A failed cleanup does not affect the clip index.
        }
    }

    private static String safePlayerName(String playerName) {
        String safe = playerName == null ? "unknown" : playerName.replaceAll("[^A-Za-z0-9_]", "_");
        return safe.isBlank() ? "unknown" : safe.substring(0, Math.min(32, safe.length()));
    }

    private final class PendingSave implements Runnable {
        private final UUID playerId;
        private final String playerName;
        private final short[] samples;
        private final long bytes;
        private final AtomicBoolean completed = new AtomicBoolean();

        private PendingSave(UUID playerId, String playerName, short[] samples, long bytes) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.samples = samples;
            this.bytes = bytes;
        }

        @Override
        public void run() {
            try {
                if (saveNow(playerId, playerName, samples)) {
                    successfulSaveCount.incrementAndGet();
                }
            } catch (IOException | RuntimeException exception) {
                failedSaveCount.incrementAndGet();
                logger.log(Level.WARNING, "Could not save speech clip for "
                        + safePlayerName(playerName), exception);
            } finally {
                complete();
            }
        }

        private void cancel() {
            complete();
        }

        private void complete() {
            if (completed.compareAndSet(false, true)) {
                pendingSaves.remove(this);
                releaseReservation(bytes);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Voice clip storage did not finish all pending work before shutdown.");
                ioExecutor.shutdownNow();
                pendingSaves.forEach(PendingSave::cancel);
            }
        } catch (InterruptedException exception) {
            ioExecutor.shutdownNow();
            pendingSaves.forEach(PendingSave::cancel);
            Thread.currentThread().interrupt();
        }
    }
}
