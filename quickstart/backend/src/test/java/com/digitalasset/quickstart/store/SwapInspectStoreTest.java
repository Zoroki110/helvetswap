package com.digitalasset.quickstart.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SwapInspectStoreTest {

    private SwapInspectStore store;

    @BeforeEach
    void setUp() {
        store = new SwapInspectStore();
    }

    @Test
    void getReturnsEmptyForUnknownRequest() {
        assertEquals(Optional.empty(), store.get("nope"));
        assertEquals(Optional.empty(), store.get(null));
        assertEquals(Optional.empty(), store.get(""));
    }

    @Test
    void markPollingWritesPollingState() {
        store.markPolling("req-1", 0);
        Optional<SwapInspectStore.State> s = store.get("req-1");
        assertTrue(s.isPresent());
        assertTrue(s.get() instanceof SwapInspectStore.Polling);
        SwapInspectStore.Polling p = (SwapInspectStore.Polling) s.get();
        assertEquals("req-1", p.requestId());
        assertEquals(0, p.attempt());
    }

    @Test
    void markCompletedWritesSuccessState() {
        store.markCompleted("req-2", "update-abc", "payout-xyz");
        SwapInspectStore.State s = store.get("req-2").orElseThrow();
        assertTrue(s instanceof SwapInspectStore.Completed);
        SwapInspectStore.Completed c = (SwapInspectStore.Completed) s;
        assertEquals(SwapInspectStore.InspectStatus.SUCCESS, c.status());
        assertEquals("update-abc", c.updateId().orElse(null));
        assertEquals("payout-xyz", c.payoutCid().orElse(null));
        assertTrue(c.errorClass().isEmpty());
        assertTrue(c.errorMessage().isEmpty());
    }

    @Test
    void markFailedWritesFailedState() {
        store.markFailed("req-3", "OPEN_ROUND_ROTATION_DRIFT", "Must be unlocked");
        SwapInspectStore.State s = store.get("req-3").orElseThrow();
        assertTrue(s instanceof SwapInspectStore.Completed);
        SwapInspectStore.Completed c = (SwapInspectStore.Completed) s;
        assertEquals(SwapInspectStore.InspectStatus.FAILED, c.status());
        assertEquals("OPEN_ROUND_ROTATION_DRIFT", c.errorClass().orElse(null));
        assertEquals("Must be unlocked", c.errorMessage().orElse(null));
        assertTrue(c.updateId().isEmpty());
        assertTrue(c.payoutCid().isEmpty());
    }

    @Test
    void markPollingDoesNotOverwriteCompleted() {
        store.markCompleted("req-4", "update-1", "payout-1");
        store.markPolling("req-4", 5);
        SwapInspectStore.State s = store.get("req-4").orElseThrow();
        assertTrue(s instanceof SwapInspectStore.Completed,
                "markPolling must not regress a Completed entry to Polling");
        assertEquals(SwapInspectStore.InspectStatus.SUCCESS,
                ((SwapInspectStore.Completed) s).status());
    }

    @Test
    void markFailedOverwritesPolling() {
        store.markPolling("req-5", 0);
        store.markFailed("req-5", "TI_ARCHIVED", "CONTRACT_NOT_FOUND");
        SwapInspectStore.State s = store.get("req-5").orElseThrow();
        assertTrue(s instanceof SwapInspectStore.Completed);
        assertEquals(SwapInspectStore.InspectStatus.FAILED,
                ((SwapInspectStore.Completed) s).status());
    }

    @Test
    void markCompletedOverwritesPolling() {
        store.markPolling("req-6", 0);
        store.markCompleted("req-6", "u", "p");
        SwapInspectStore.State s = store.get("req-6").orElseThrow();
        assertEquals(SwapInspectStore.InspectStatus.SUCCESS,
                ((SwapInspectStore.Completed) s).status());
    }

    @Test
    void markCompletedAcceptsNullPayoutCid() {
        store.markCompleted("req-7", "update-only", null);
        SwapInspectStore.Completed c = (SwapInspectStore.Completed) store.get("req-7").orElseThrow();
        assertEquals("update-only", c.updateId().orElse(null));
        assertTrue(c.payoutCid().isEmpty());
    }

    @Test
    void blankRequestIdsAreNoOp() {
        store.markPolling("", 0);
        store.markCompleted(null, "u", "p");
        store.markFailed("   ", "X", "y");
        assertEquals(0, store.size());
    }

    @Test
    void sizeReflectsRecordCount() {
        assertEquals(0, store.size());
        store.markPolling("a", 0);
        store.markCompleted("b", "u", "p");
        store.markFailed("c", "X", "y");
        assertEquals(3, store.size());
    }
}
