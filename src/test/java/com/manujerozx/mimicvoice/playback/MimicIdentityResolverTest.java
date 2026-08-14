package com.manujerozx.mimicvoice.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MimicIdentityResolverTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID UUID_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final MimicIdentityResolver resolver = new MimicIdentityResolver();

    @Test
    void validUuidMarkerResolvesExactlyThatUuid() {
        MimicIdentityResolver.Resolution resolution = resolve(
                new MimicIdentityResolver.Markers(UUID_A.toString(), "Alice", null),
                ignored -> UUID_B,
                ignored -> UUID_B);

        assertEquals(UUID_A, resolution.playerId());
        assertEquals(MimicIdentityResolver.Source.UUID_MARKER, resolution.source());
    }

    @Test
    void uuidMarkerTakesPrecedenceOverConflictingLegacyName() {
        MimicIdentityResolver.Resolution resolution = resolve(
                new MimicIdentityResolver.Markers(UUID_A.toString(), "Bob", null),
                ignored -> UUID_B,
                ignored -> UUID_B);

        assertEquals(UUID_A, resolution.playerId());
        assertEquals("Bob", resolution.legacyName());
        assertEquals(MimicIdentityResolver.Source.UUID_MARKER, resolution.source());
    }

    @Test
    void missingUuidUsesExactOnlineLegacyName() {
        MimicIdentityResolver.Resolution resolution = resolve(
                new MimicIdentityResolver.Markers(null, "Alice", null),
                name -> name.equals("Alice") ? UUID_A : null,
                ignored -> UUID_B);

        assertEquals(UUID_A, resolution.playerId());
        assertEquals(MimicIdentityResolver.Source.ONLINE_LEGACY_NAME, resolution.source());
    }

    @Test
    void missingUuidUsesPersistedNameIndexWhenPlayerIsOffline() {
        MimicIdentityResolver.Resolution resolution = resolve(
                new MimicIdentityResolver.Markers(null, "OfflineAlice", null),
                ignored -> null,
                name -> name.equals("OfflineAlice") ? UUID_A : null);

        assertEquals(UUID_A, resolution.playerId());
        assertEquals(MimicIdentityResolver.Source.PERSISTED_LEGACY_NAME, resolution.source());
    }

    @Test
    void malformedUuidDoesNotCrashOrFallBackToName() {
        AtomicInteger lookups = new AtomicInteger();
        MimicIdentityResolver.Resolution resolution = resolve(
                new MimicIdentityResolver.Markers("not-a-uuid", "Alice", null),
                ignored -> {
                    lookups.incrementAndGet();
                    return UUID_A;
                },
                ignored -> {
                    lookups.incrementAndGet();
                    return UUID_B;
                });

        assertNull(resolution.playerId());
        assertEquals(MimicIdentityResolver.Source.MALFORMED_UUID_MARKER, resolution.source());
        assertEquals(0, lookups.get(), "a malformed authoritative marker must not use a name fallback");
    }

    @Test
    void unresolvedLegacyNameDoesNotCrossFallbackToAnotherPlayer() {
        MimicIdentityResolver.Resolution resolution = resolve(
                new MimicIdentityResolver.Markers(null, "Missing", null),
                ignored -> null,
                ignored -> null);

        assertNull(resolution.playerId());
        assertEquals(MimicIdentityResolver.Source.UNRESOLVED, resolution.source());
    }

    @Test
    void oldNameOnlyMimicEntitiesRemainCompatible() {
        MimicIdentityResolver.Resolution resolution = resolve(
                new MimicIdentityResolver.Markers(null, "LegacyPlayer", null),
                ignored -> null,
                name -> name.equals("LegacyPlayer") ? UUID_B : null);

        assertEquals(UUID_B, resolution.playerId());
        assertEquals("LegacyPlayer", resolution.legacyName());
    }

    @Test
    void displayNameRemainsTheExistingNameFallbackWhenLegacyKeyIsAbsent() {
        MimicIdentityResolver.Resolution resolution = resolve(
                new MimicIdentityResolver.Markers(null, null, "Named Mimic"),
                name -> name.equals("Named Mimic") ? UUID_A : null,
                ignored -> UUID_B);

        assertEquals(UUID_A, resolution.playerId());
        assertNull(resolution.legacyName());
        assertEquals("Named Mimic", resolution.lookupName());
    }

    private MimicIdentityResolver.Resolution resolve(
            MimicIdentityResolver.Markers markers,
            java.util.function.Function<String, UUID> online,
            java.util.function.Function<String, UUID> persisted) {
        return resolver.resolve(markers, online, persisted);
    }
}
