package com.jerara04.mimicvoice.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MimicIdentityTrackerTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID UUID_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void identityChangeInvalidatesInFlightLoadAndLastClip() {
        MimicIdentityTracker tracker = new MimicIdentityTracker();
        MimicIdentityResolver.IdentityKey identityA = key(UUID_A, "Alice");
        MimicIdentityResolver.IdentityKey identityB = key(UUID_B, "Bob");

        tracker.updateIdentity(identityA, 100L, 500L, null);
        tracker.setLoading(true);
        tracker.setLastClipId("clip-a");
        long oldVersion = tracker.identityVersion();

        assertTrue(tracker.updateIdentity(identityB, 200L, 700L, null));
        assertFalse(tracker.isCurrent(oldVersion, identityA));
        assertFalse(tracker.loading());
        assertNull(tracker.lastClipId());
        assertEquals(900L, tracker.nextPlaybackAt());
    }

    @Test
    void identityChangeInvokesStopForActivePlayback() {
        MimicIdentityTracker tracker = new MimicIdentityTracker();
        AtomicInteger stops = new AtomicInteger();

        tracker.updateIdentity(key(UUID_A, "Alice"), 100L, 0L, null);
        tracker.updateIdentity(key(UUID_B, "Bob"), 200L, 0L, stops::incrementAndGet);

        assertEquals(1, stops.get());
    }

    @Test
    void sameIdentityDoesNotResetSchedulingOrGeneration() {
        MimicIdentityTracker tracker = new MimicIdentityTracker();
        MimicIdentityResolver.IdentityKey identity = key(UUID_A, "Alice");

        assertTrue(tracker.updateIdentity(identity, 100L, 500L, null));
        tracker.setLoading(true);
        tracker.setLastClipId("clip-a");
        long version = tracker.identityVersion();
        long nextPlaybackAt = tracker.nextPlaybackAt();

        assertFalse(tracker.updateIdentity(identity, 200L, 900L, () -> {
            throw new AssertionError("same identity must not stop or reset playback");
        }));
        assertEquals(version, tracker.identityVersion());
        assertEquals(nextPlaybackAt, tracker.nextPlaybackAt());
        assertTrue(tracker.loading());
        assertEquals("clip-a", tracker.lastClipId());
    }

    @Test
    void resolvedUuidAndLegacyNameBothBelongToIdentityKey() {
        MimicIdentityTracker tracker = new MimicIdentityTracker();

        tracker.updateIdentity(key(UUID_A, "Alice"), 100L, 0L, null);
        long versionAfterA = tracker.identityVersion();
        tracker.updateIdentity(key(UUID_A, "Bob"), 200L, 0L, null);
        assertTrue(tracker.identityVersion() > versionAfterA);

        long versionAfterNameChange = tracker.identityVersion();
        tracker.updateIdentity(key(UUID_B, "Bob"), 300L, 0L, null);
        assertTrue(tracker.identityVersion() > versionAfterNameChange);
    }

    private static MimicIdentityResolver.IdentityKey key(UUID playerId, String name) {
        return new MimicIdentityResolver.IdentityKey(playerId, null, name, name);
    }
}
