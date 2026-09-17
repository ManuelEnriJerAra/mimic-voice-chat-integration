package com.jerara04.mimicvoice.voice;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main-thread-maintained, immutable player metadata for the voice callback.
 *
 * <p>The Simple Voice Chat microphone callback is not a Bukkit scheduler
 * callback. It may only perform a short concurrent lookup here; Bukkit player
 * objects are never retained or queried by the callback.</p>
 */
public final class PlayerSnapshotCache {

    private final ConcurrentHashMap<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    public Snapshot get(UUID playerId) {
        return playerId == null ? null : snapshots.get(playerId);
    }

    public void put(UUID playerId, String playerName, boolean hasPermission) {
        if (playerId != null) {
            snapshots.put(playerId, new Snapshot(playerName, hasPermission));
        }
    }

    public void remove(UUID playerId) {
        if (playerId != null) {
            snapshots.remove(playerId);
        }
    }

    /** Replaces the cache with a snapshot assembled on the Bukkit main thread. */
    public void replaceAll(Map<UUID, Snapshot> replacement) {
        snapshots.clear();
        snapshots.putAll(replacement);
    }

    public record Snapshot(String playerName, boolean hasPermission) {
    }
}
