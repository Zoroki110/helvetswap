package com.digitalasset.quickstart.cache;

import com.digitalasset.quickstart.service.OpenMiningRoundRefreshService;
import com.digitalasset.quickstart.service.OpenMiningRoundRefreshService.ScanOmrEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T-3-bis (Mind OQ-Step-B-3 lock): cross-impl parity between
 * {@link ScanOmrCache#applySelectionRule} (operating on {@link ScanOmrCache.OmrEntry})
 * and {@link OpenMiningRoundRefreshService#selectBest} (operating on {@link ScanOmrEntry}).
 *
 * Both impls share the same generic helper at production by construction (selectBest
 * delegates to applySelectionRule). This test exists as the LOCKED-RULE force-function:
 * if either signature drifts, this test fails loudly.
 */
class SelectionRuleParityTest {

    private static final Duration MARGIN = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2026-05-11T10:00:00Z");
    private static final String TPL = "tpl:Splice.Round:OpenMiningRound";
    private static final String SYNC = "sync-1";
    private static final String BLOB_B64 = Base64.getEncoder().encodeToString(new byte[]{1, 2});

    @Test
    void parityAcrossCanonicalInputs() {
        assertParity("empty", List.of(), null);

        ScanOmrCache.OmrEntry safeOnly = omrCache("safe-1", NOW.minus(Duration.ofMinutes(5)), NOW.plus(Duration.ofMinutes(15)), 1L);
        assertParity("single-safe", List.of(safeOnly), "safe-1");

        ScanOmrCache.OmrEntry tooSoon = omrCache("tooSoon", NOW.minus(Duration.ofMinutes(18)), NOW.plus(Duration.ofSeconds(10)), 2L);
        assertParity("single-unsafe-closing-soon", List.of(tooSoon), null);

        ScanOmrCache.OmrEntry future = omrCache("future", NOW.plus(Duration.ofMinutes(5)), NOW.plus(Duration.ofMinutes(25)), 3L);
        assertParity("single-future-only", List.of(future), null);

        ScanOmrCache.OmrEntry olderSafe = omrCache("older", NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofMinutes(10)), 4L);
        ScanOmrCache.OmrEntry newerSafe = omrCache("newer", NOW.minus(Duration.ofMinutes(2)), NOW.plus(Duration.ofMinutes(18)), 5L);
        assertParity("multi-with-rotation", List.of(olderSafe, newerSafe), "newer");

        ScanOmrCache.OmrEntry pastClosed = omrCache("past", NOW.minus(Duration.ofMinutes(40)), NOW.minus(Duration.ofMinutes(20)), 6L);
        assertParity("past-only-already-closed", List.of(pastClosed), null);

        // Margin boundary: targetClosesAt = now + 30s → rule says > margin, so reject (strict GT).
        ScanOmrCache.OmrEntry atBoundary = omrCache("boundary", NOW.minus(Duration.ofMinutes(19)), NOW.plus(Duration.ofSeconds(30)), 7L);
        assertParity("margin-boundary-strict-gt", List.of(atBoundary), null);

        // Past + safe + future → safe wins.
        assertParity("past-safe-future", List.of(pastClosed, safeOnly, future), "safe-1");
    }

    /** Run both selection-rule impls on equivalent inputs; assert same winner cid. */
    private static void assertParity(String label, List<ScanOmrCache.OmrEntry> cacheEntries, String expectedCid) {
        Optional<ScanOmrCache.OmrEntry> cacheBest = ScanOmrCache.applySelectionRule(
                cacheEntries, NOW, MARGIN,
                ScanOmrCache.OmrEntry::opensAt, ScanOmrCache.OmrEntry::targetClosesAt);

        List<ScanOmrEntry> serviceEntries = cacheEntries.stream().map(SelectionRuleParityTest::toServiceEntry).toList();
        ScanOmrEntry serviceBest = OpenMiningRoundRefreshService.selectBest(serviceEntries, NOW);

        if (expectedCid == null) {
            assertTrue(cacheBest.isEmpty(), label + ": cache impl should select nothing");
            assertNull(serviceBest, label + ": service impl should select nothing");
        } else {
            assertTrue(cacheBest.isPresent(), label + ": cache impl should select something");
            assertEquals(expectedCid, cacheBest.get().contractId(), label + ": cache impl winner");
            assertNotNull(serviceBest, label + ": service impl should select something");
            assertEquals(expectedCid, serviceBest.contractId(), label + ": service impl winner");
        }
    }

    private static ScanOmrCache.OmrEntry omrCache(String cid, Instant opensAt, Instant closesAt, long round) {
        return new ScanOmrCache.OmrEntry(cid, TPL, new byte[]{1, 2}, SYNC, opensAt, closesAt, round);
    }

    private static ScanOmrEntry toServiceEntry(ScanOmrCache.OmrEntry e) {
        return new ScanOmrEntry(
                e.contractId(),
                BLOB_B64,
                e.templateId(),
                e.synchronizerId(),
                e.opensAt(),
                e.targetClosesAt(),
                e.roundNumber()
        );
    }
}
