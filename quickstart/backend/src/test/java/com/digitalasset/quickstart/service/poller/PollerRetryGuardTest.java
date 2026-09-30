package com.digitalasset.quickstart.service.poller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PollerRetryGuardTest {

    @Test
    void testRecordAttemptIncrementsCounter() {
        PollerRetryGuard g = new PollerRetryGuard();
        g.recordAttempt("req-1");
        g.recordAttempt("req-1");
        g.recordAttempt("req-1");
        assertEquals(3, g.attempts("req-1"));
    }

    @Test
    void testClassifyPermanentDeadlineExpired() {
        PollerRetryGuard g = new PollerRetryGuard();
        assertEquals(PollerRetryGuard.Classification.PERMANENT,
                g.classify("INVALID_ARGUMENT", "AddLiquidity deadline has expired"));
        assertEquals(PollerRetryGuard.Classification.PERMANENT,
                g.classify("INVALID_ARGUMENT", "Swap deadline has expired"));
    }

    @Test
    void testClassifyPermanentContractNotFoundOnTI() {
        PollerRetryGuard g = new PollerRetryGuard();
        assertEquals(PollerRetryGuard.Classification.PERMANENT,
                g.classify("NOT_FOUND", "CONTRACT_NOT_FOUND on inbound TI cid 00abc..."));
    }

    @Test
    void testClassifyTransientPoolCidContractNotFound() {
        PollerRetryGuard g = new PollerRetryGuard();
        assertEquals(PollerRetryGuard.Classification.TRANSIENT,
                g.classify("NOT_FOUND", "CONTRACT_NOT_FOUND while exercising on poolCid 00def..."));
    }

    @Test
    void testClassifySwapOutputBelowMinOutPermanent() {
        PollerRetryGuard g = new PollerRetryGuard();
        assertEquals(PollerRetryGuard.Classification.PERMANENT,
                g.classify("FAILED_PRECONDITION", "Output below minOut"));
    }

    @Test
    void testClassifyMissingInboundTis() {
        PollerRetryGuard g = new PollerRetryGuard();
        assertEquals(PollerRetryGuard.Classification.MISSING_TIS,
                g.classify("NOT_FOUND", "Missing inbound TIs for pool instrument"));
    }

    @Test
    void testClassifyTransientResourceExhausted() {
        PollerRetryGuard g = new PollerRetryGuard();
        assertEquals(PollerRetryGuard.Classification.TRANSIENT,
                g.classify("RESOURCE_EXHAUSTED", "rate limit"));
        assertEquals(PollerRetryGuard.Classification.TRANSIENT,
                g.classify("DEADLINE_EXCEEDED", "deadline"));
    }

    @Test
    void testClassifyPermanentPermissionDenied() {
        PollerRetryGuard g = new PollerRetryGuard();
        assertEquals(PollerRetryGuard.Classification.PERMANENT,
                g.classify("PERMISSION_DENIED", "operator party not authorized"));
        assertEquals(PollerRetryGuard.Classification.PERMANENT,
                g.classify("UNAUTHORIZED", "UNAUTHORIZED choice"));
    }

    @Test
    void testClassifyPermanentHoldingInsufficient() {
        PollerRetryGuard g = new PollerRetryGuard();
        assertEquals(PollerRetryGuard.Classification.PERMANENT,
                g.classify("INVALID_ARGUMENT", "Holding insufficient for amount"));
    }

    @Test
    void testNextDelayMsExponentialBackoff() {
        PollerRetryGuard g = new PollerRetryGuard();
        assertEquals(5_000L, g.nextDelayMs("req-2"));
        g.recordAttempt("req-2");
        assertEquals(15_000L, g.nextDelayMs("req-2"));
        g.recordAttempt("req-2");
        assertEquals(45_000L, g.nextDelayMs("req-2"));
        g.recordAttempt("req-2");
        assertEquals(45_000L, g.nextDelayMs("req-2"));
    }

    @Test
    void testMarkDeadAndIsDead() {
        PollerRetryGuard g = new PollerRetryGuard();
        assertFalse(g.isDead("req-3"));
        g.markDead("req-3", "PoolIdUnresolvable");
        assertTrue(g.isDead("req-3"));
        java.util.List<PollerRetryGuard.DeadEntry> dead = g.listDead();
        assertEquals(1, dead.size());
        assertEquals("req-3", dead.get(0).requestId());
        assertEquals("PoolIdUnresolvable", dead.get(0).errorMessage());
    }

    @Test
    void testClearRemovesState() {
        PollerRetryGuard g = new PollerRetryGuard();
        g.recordAttempt("req-4");
        g.markDead("req-4", "test");
        assertTrue(g.isDead("req-4"));
        g.clear("req-4");
        assertFalse(g.isDead("req-4"));
        assertEquals(0, g.attempts("req-4"));
        assertEquals(0, g.sizeForTest());
    }

    @Test
    void testShouldGiveUpPermanentAfterOneAttempt() {
        PollerRetryGuard g = new PollerRetryGuard();
        g.recordAttempt("req-5");
        assertTrue(g.shouldGiveUp("req-5", PollerRetryGuard.Classification.PERMANENT));
        assertTrue(g.shouldGiveUp("req-5", PollerRetryGuard.Classification.DEADLINE));
    }

    @Test
    void testShouldGiveUpTransientAfterThreeAttempts() {
        PollerRetryGuard g = new PollerRetryGuard();
        g.recordAttempt("req-6");
        assertFalse(g.shouldGiveUp("req-6", PollerRetryGuard.Classification.TRANSIENT));
        g.recordAttempt("req-6");
        assertFalse(g.shouldGiveUp("req-6", PollerRetryGuard.Classification.TRANSIENT));
        g.recordAttempt("req-6");
        assertTrue(g.shouldGiveUp("req-6", PollerRetryGuard.Classification.TRANSIENT));
    }
}
