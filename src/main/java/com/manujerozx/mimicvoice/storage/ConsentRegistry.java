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
    private final Set<UUID> optedOut = new HashSet<>();

    public ConsentRegistry(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "recording-opt-outs.yml");
        this.logger = logger;
        load();
    }

    public synchronized boolean mayRecord(UUID playerId) {
        return !optedOut.contains(playerId);
    }

    /**
     * Applies a consent change immediately and returns whether it was durably saved.
     */
    public synchronized boolean setAllowed(UUID playerId, boolean allowed) {
        if (allowed) {
            optedOut.remove(playerId);
        } else {
            optedOut.add(playerId);
        }
        return save();
    }

    private synchronized void load() {
        optedOut.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String serialized : yaml.getStringList("opted-out")) {
            try {
                optedOut.add(UUID.fromString(serialized));
            } catch (IllegalArgumentException ignored) {
                logger.warning("Ignoring invalid UUID in " + file.getName() + ": " + serialized);
            }
        }
    }

    private synchronized boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("opted-out", optedOut.stream().map(UUID::toString).sorted().toList());
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
