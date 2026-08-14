package com.manujerozx.mimicvoice.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;

public final class ConsentRegistry {

    private final File file;
    private final Logger logger;
    private volatile Set<UUID> optedOut = Set.of();

    public ConsentRegistry(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "recording-opt-outs.yml");
        this.logger = logger;
        load();
    }

    public boolean mayRecord(UUID playerId) {
        return !optedOut.contains(playerId);
    }

    /**
     * Applies a consent change immediately and returns whether it was durably saved.
     */
    public synchronized boolean setAllowed(UUID playerId, boolean allowed) {
        Set<UUID> updated = new HashSet<>(optedOut);
        if (allowed) {
            updated.remove(playerId);
        } else {
            updated.add(playerId);
        }
        optedOut = Set.copyOf(updated);
        return save(updated);
    }

    private synchronized void load() {
        Set<UUID> loaded = new HashSet<>();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String serialized : yaml.getStringList("opted-out")) {
            try {
                loaded.add(UUID.fromString(serialized));
            } catch (IllegalArgumentException ignored) {
                logger.warning("Ignoring invalid UUID in " + file.getName() + ": " + serialized);
            }
        }
        optedOut = Set.copyOf(loaded);
    }

    private boolean save(Set<UUID> snapshot) {
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
