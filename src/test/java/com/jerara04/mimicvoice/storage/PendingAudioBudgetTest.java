package com.jerara04.mimicvoice.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PendingAudioBudgetTest {

    @Test
    void reservationIsBoundedByBothEntriesAndBytes() {
        PendingAudioBudget budget = new PendingAudioBudget(2);

        assertTrue(budget.tryReserve(8, 10));
        assertFalse(budget.tryReserve(3, 10));
        assertEquals(1, budget.pendingEntries());
        assertEquals(8, budget.pendingBytes());

        assertTrue(budget.tryReserve(2, 10));
        assertFalse(budget.tryReserve(1, 10));
        assertEquals(2, budget.pendingEntries());
        assertEquals(10, budget.pendingBytes());

        budget.release(8);
        budget.release(2);
        assertEquals(0, budget.pendingEntries());
        assertEquals(0, budget.pendingBytes());
    }

    @Test
    void anIndividualClipLargerThanConfiguredByteLimitIsRejected() {
        PendingAudioBudget budget = new PendingAudioBudget(256);

        assertFalse(budget.tryReserve(11, 10));
        assertEquals(0, budget.pendingEntries());
        assertEquals(0, budget.pendingBytes());
    }
}
