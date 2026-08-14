package com.manujerozx.mimicvoice.playback;

import java.util.Objects;

/**
 * Main-thread state for identity-sensitive playback work.
 *
 * <p>Changing the identity invalidates the load generation, playback
 * assumptions, and repeat scheduling. An unchanged identity leaves those
 * values untouched.</p>
 */
public final class MimicIdentityTracker {

    private MimicIdentityResolver.IdentityKey identityKey;
    private long identityVersion;
    private boolean loading;
    private String lastClipId;
    private long nextPlaybackAt;

    public boolean updateIdentity(
            MimicIdentityResolver.IdentityKey nextIdentity,
            long now,
            long firstDelayMillis,
            Runnable stopPlayback) {
        if (Objects.equals(identityKey, nextIdentity)) {
            return false;
        }

        identityKey = nextIdentity;
        identityVersion++;
        loading = false;
        lastClipId = null;
        nextPlaybackAt = now + firstDelayMillis;
        if (stopPlayback != null) {
            stopPlayback.run();
        }
        return true;
    }

    public void invalidate() {
        identityVersion++;
        loading = false;
    }

    public boolean isCurrent(long version, MimicIdentityResolver.IdentityKey expectedIdentity) {
        return identityVersion == version && Objects.equals(identityKey, expectedIdentity);
    }

    public MimicIdentityResolver.IdentityKey identityKey() {
        return identityKey;
    }

    public long identityVersion() {
        return identityVersion;
    }

    public boolean loading() {
        return loading;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    public String lastClipId() {
        return lastClipId;
    }

    public void setLastClipId(String lastClipId) {
        this.lastClipId = lastClipId;
    }

    public long nextPlaybackAt() {
        return nextPlaybackAt;
    }

    public void setNextPlaybackAt(long nextPlaybackAt) {
        this.nextPlaybackAt = nextPlaybackAt;
    }
}
