package com.manujerozx.mimicvoice.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * UUID-keyed recording consent with all YAML I/O isolated to one daemon worker.
 *
 * <p>Consent changes are applied to the in-memory policy immediately. The
 * returned future reports whether the newest snapshot containing that change
 * was durably persisted, so a command can remain responsive without hiding a
 * privacy-persistence failure.</p>
 */
public final class ConsentRegistry implements AutoCloseable {

    private static final long SHUTDOWN_TIMEOUT_MILLISECONDS = 5_000L;
    static final int MAX_PENDING_RESULTS = 256;
    static final int MAX_INITIAL_LOAD_OVERRIDES = 256;

    private final File file;
    private final Logger logger;
    private final ConsentPersistence persistence;
    private final Object monitor = new Object();
    private final Thread ioThread;
    private final Map<UUID, Boolean> overrides = new HashMap<>();
    private final List<CompletableFuture<Boolean>> pendingResults = new ArrayList<>();
    private volatile Set<UUID> optedOut = Set.of();
    private Set<UUID> pendingSnapshot;
    private boolean initialLoadOverrideOverflow;
    private boolean closed;
    private final CompletableFuture<Boolean> initialized = new CompletableFuture<>();

    public ConsentRegistry(File dataFolder, Logger logger) {
        this(new File(dataFolder, "recording-opt-outs.yml"), logger,
                new FileConsentPersistence(new File(dataFolder, "recording-opt-outs.yml"), logger));
    }

    ConsentRegistry(File file, Logger logger, ConsentPersistence persistence) {
        this.file = file;
        this.logger = logger;
        this.persistence = persistence;
        this.ioThread = Thread.ofPlatform().name("mimic-voice-consent").daemon(true).unstarted(this::runIo);
        this.ioThread.start();
    }

    /** Completes on the storage worker after the initial YAML load finishes. */
    public CompletableFuture<Boolean> initialize() {
        return initialized;
    }

    public boolean mayRecord(UUID playerId) {
        return !optedOut.contains(playerId);
    }

    /**
     * Applies a consent change immediately and asynchronously persists it.
     * The future is false when persistence failed; the in-memory opt-out still
     * remains active for the current session.
     */
    public CompletableFuture<Boolean> setAllowed(UUID playerId, boolean allowed) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> persisted = new CompletableFuture<>();
        boolean acceptedForDurability = true;
        synchronized (monitor) {
            if (closed) {
                persisted.complete(false);
                return persisted;
            }
            Set<UUID> updated = new HashSet<>(optedOut);
            if (allowed) {
                updated.remove(playerId);
            } else {
                updated.add(playerId);
            }
            optedOut = Set.copyOf(updated);
            if (!initialized.isDone()
                    && !overrides.containsKey(playerId)
                    && overrides.size() >= MAX_INITIAL_LOAD_OVERRIDES) {
                initialLoadOverrideOverflow = true;
                acceptedForDurability = false;
            } else if (!initialized.isDone()) {
                overrides.put(playerId, allowed);
            }
            pendingSnapshot = optedOut;
            if (pendingResults.size() < MAX_PENDING_RESULTS) {
                pendingResults.add(persisted);
            } else {
                acceptedForDurability = false;
            }
            monitor.notifyAll();
        }
        if (!acceptedForDurability) {
            persisted.complete(false);
        }
        return persisted;
    }

    private void runIo() {
        boolean loadSucceeded = true;
        Set<UUID> loadedValues;
        try {
            loadedValues = persistence.load();
        } catch (RuntimeException exception) {
            loadSucceeded = false;
            loadedValues = Set.of();
            logger.log(Level.SEVERE, "Could not load " + file.getName()
                    + "; recording remains paused until the file can be read", exception);
        }

        synchronized (monitor) {
            Set<UUID> merged = new HashSet<>(loadedValues);
            overrides.forEach((playerId, allowed) -> {
                if (allowed) {
                    merged.remove(playerId);
                } else {
                    merged.add(playerId);
                }
            });
            optedOut = Set.copyOf(merged);
            if (pendingSnapshot != null) {
                pendingSnapshot = optedOut;
            }
            overrides.clear();
            initialized.complete(loadSucceeded && !initialLoadOverrideOverflow);
            monitor.notifyAll();
        }

        while (true) {
            Set<UUID> snapshot;
            List<CompletableFuture<Boolean>> results;
            synchronized (monitor) {
                while (!closed && pendingSnapshot == null) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        closed = true;
                        break;
                    }
                }
                if (pendingSnapshot == null) {
                    complete(resultsOrEmpty(), false);
                    return;
                }
                snapshot = pendingSnapshot;
                pendingSnapshot = null;
                results = new ArrayList<>(pendingResults);
                pendingResults.clear();
            }

            boolean saved;
            try {
                saved = persistence.save(snapshot);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Could not save " + file.getName(), exception);
                saved = false;
            }
            complete(results, saved);
        }
    }

    private List<CompletableFuture<Boolean>> resultsOrEmpty() {
        synchronized (monitor) {
            List<CompletableFuture<Boolean>> results = new ArrayList<>(pendingResults);
            pendingResults.clear();
            return results;
        }
    }

    private void complete(List<CompletableFuture<Boolean>> results, boolean saved) {
        results.forEach(result -> result.complete(saved));
    }

    int pendingResultCount() {
        synchronized (monitor) {
            return pendingResults.size();
        }
    }

    int pendingInitialOverrideCount() {
        synchronized (monitor) {
            return overrides.size();
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            monitor.notifyAll();
        }
        if (Thread.currentThread() == ioThread) {
            return;
        }
        try {
            ioThread.join(SHUTDOWN_TIMEOUT_MILLISECONDS);
        } catch (InterruptedException exception) {
            ioThread.interrupt();
            Thread.currentThread().interrupt();
        }
        if (ioThread.isAlive()) {
            logger.warning("Consent storage worker did not stop before shutdown; interrupting it.");
            ioThread.interrupt();
            try {
                ioThread.join(1_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    interface ConsentPersistence {
        Set<UUID> load();

        default boolean save(Set<UUID> snapshot) {
            return true;
        }
    }

    private static final class FileConsentPersistence implements ConsentPersistence {
        private final File file;
        private final Logger logger;

        private FileConsentPersistence(File file, Logger logger) {
            this.file = file;
            this.logger = logger;
        }

        @Override
        public Set<UUID> load() {
            Set<UUID> loadedValues = new HashSet<>();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String serialized : yaml.getStringList("opted-out")) {
                try {
                    loadedValues.add(UUID.fromString(serialized));
                } catch (IllegalArgumentException ignored) {
                    logger.warning("Ignoring invalid UUID in " + file.getName() + ": " + serialized);
                }
            }
            return loadedValues;
        }

        @Override
        public boolean save(Set<UUID> snapshot) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("opted-out", snapshot.stream().map(UUID::toString).sorted().toList());
            Path target = file.toPath();
            Path temporary = target.resolveSibling(file.getName() + ".tmp-" + UUID.randomUUID());
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (IOException exception) {
                logger.log(Level.WARNING, "Could not save " + file.getName(), exception);
                return false;
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    logger.log(Level.FINE, "Could not remove temporary consent file " + temporary, exception);
                }
            }
        }
    }
}
