package com.jerara04.mimicvoice.playback;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Resolves a Mimic's speaker identity without allowing a name lookup to
 * override an explicit UUID marker.
 *
 * <p>The resolver deliberately has no Bukkit or ClipStore dependency. The
 * caller supplies the two legacy lookups, which keeps the identity contract
 * directly testable and makes it harder to accidentally introduce a
 * cross-player fallback.</p>
 */
public final class MimicIdentityResolver {

    public static final String UUID_MARKER_KEY = "mimicked_player_uuid";
    public static final String LEGACY_NAME_KEY = "mimicked_player";

    public Resolution resolve(
            Markers markers,
            Function<String, UUID> onlineNameLookup,
            Function<String, UUID> persistedNameLookup) {
        Objects.requireNonNull(markers, "markers");
        Objects.requireNonNull(onlineNameLookup, "onlineNameLookup");
        Objects.requireNonNull(persistedNameLookup, "persistedNameLookup");

        String uuidMarker = markers.uuidMarker();
        String name = firstNonBlank(markers.legacyName(), markers.displayName());

        if (uuidMarker != null) {
            UUID playerId = parseCanonicalUuid(uuidMarker);
            if (playerId == null) {
                // A present but malformed marker is treated as an invalid
                // authoritative marker. Falling back to a name here could
                // make playback switch to the wrong player.
                return new Resolution(
                        null,
                        uuidMarker,
                        markers.legacyName(),
                        markers.displayName(),
                        name,
                        Source.MALFORMED_UUID_MARKER);
            }
            return new Resolution(
                    playerId,
                    uuidMarker,
                    markers.legacyName(),
                    markers.displayName(),
                    name,
                    Source.UUID_MARKER);
        }

        if (name == null) {
            return new Resolution(null, null, markers.legacyName(), markers.displayName(), null, Source.UNRESOLVED);
        }

        UUID onlinePlayerId = onlineNameLookup.apply(name);
        if (onlinePlayerId != null) {
            return new Resolution(
                    onlinePlayerId,
                    null,
                    markers.legacyName(),
                    markers.displayName(),
                    name,
                    Source.ONLINE_LEGACY_NAME);
        }

        UUID persistedPlayerId = persistedNameLookup.apply(name);
        if (persistedPlayerId != null) {
            return new Resolution(
                    persistedPlayerId,
                    null,
                    markers.legacyName(),
                    markers.displayName(),
                    name,
                    Source.PERSISTED_LEGACY_NAME);
        }

        return new Resolution(null, null, markers.legacyName(), markers.displayName(), name, Source.UNRESOLVED);
    }

    private static UUID parseCanonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value) ? parsed : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    public record Markers(String uuidMarker, String legacyName, String displayName) {
    }

    public record IdentityKey(UUID playerId, String uuidMarker, String legacyName, String lookupName) {
    }

    public record Resolution(
            UUID playerId,
            String uuidMarker,
            String legacyName,
            String displayName,
            String lookupName,
            Source source) {

        public boolean isResolved() {
            return playerId != null;
        }

        public IdentityKey identityKey() {
            return new IdentityKey(playerId, uuidMarker, legacyName, lookupName);
        }
    }

    public enum Source {
        UUID_MARKER,
        ONLINE_LEGACY_NAME,
        PERSISTED_LEGACY_NAME,
        UNRESOLVED,
        MALFORMED_UUID_MARKER
    }
}
