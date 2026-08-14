package com.manujerozx.mimicvoice.storage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking accounting for PCM arrays waiting at the storage boundary.
 * The byte limit is supplied at reservation time so a config reload can lower
 * the admission ceiling without changing the ownership of already queued work.
 */
final class PendingAudioBudget {

    private final int maximumEntries;
    private final AtomicInteger pendingEntries = new AtomicInteger();
    private final AtomicLong pendingBytes = new AtomicLong();

    PendingAudioBudget(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    boolean tryReserve(long bytes, long maximumBytes) {
        if (bytes <= 0 || maximumBytes <= 0 || bytes > maximumBytes) {
            return false;
        }
        if (!reserveEntry()) {
            return false;
        }

        while (true) {
            long current = pendingBytes.get();
            if (current > maximumBytes - bytes
                    || !pendingBytes.compareAndSet(current, current + bytes)) {
                if (current > maximumBytes - bytes) {
                    pendingEntries.decrementAndGet();
                    return false;
                }
                continue;
            }
            return true;
        }
    }

    void release(long bytes) {
        if (bytes <= 0) {
            return;
        }
        pendingBytes.addAndGet(-bytes);
        pendingEntries.decrementAndGet();
    }

    int pendingEntries() {
        return pendingEntries.get();
    }

    long pendingBytes() {
        return pendingBytes.get();
    }

    private boolean reserveEntry() {
        while (true) {
            int current = pendingEntries.get();
            if (current >= maximumEntries
                    || !pendingEntries.compareAndSet(current, current + 1)) {
                if (current >= maximumEntries) {
                    return false;
                }
                continue;
            }
            return true;
        }
    }
}
