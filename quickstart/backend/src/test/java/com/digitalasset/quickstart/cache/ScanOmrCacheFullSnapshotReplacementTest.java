package com.digitalasset.quickstart.cache;

import com.digitalasset.quickstart.config.LedgerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * T-6 (post-AMEND): replaces the original {@code ScanOmrCacheRefreshDeltaTest}.
 *
 * Verifies the snapshot-replacement contract under GET-only polling: each successful
 * GET to scan-proxy returns the CURRENT full OMR set; the cache's snapshot is
 * REPLACED (not merged) on each successful refresh; {@code version} increments
 * monotonically; cids from a prior snapshot no longer appear after a refresh that
 * returns a different set.
 *
 * <p>Pre-AMEND, this test class asserted delta-poll body construction
 * ({@code cached_open_mining_round_contract_ids} carryover). Per Mind's authoritative
 * {@code OPTIONS Allow: GET} probe 2026-05-11 + Addendum 1 to
 * {@code /root/STEP_B_SCAN_OMR_CACHE.md}, the scan-proxy is GET-only with no
 * request body — the delta protocol does not apply. This rewrite preserves the
 * "two consecutive polls" coverage at the new full-snapshot-replacement layer.
 */
class ScanOmrCacheFullSnapshotReplacementTest {

    private LedgerConfig ledgerConfig;
    private HttpClient httpClient;
    private ScanOmrCacheConfig config;
    private final Clock fixed = Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        ledgerConfig = mock(LedgerConfig.class);
        when(ledgerConfig.getRegistryBaseUri()).thenReturn("http://scan-proxy.test");
        when(ledgerConfig.getRegistryAuthHeader()).thenReturn(null);
        when(ledgerConfig.getRegistryAuthToken()).thenReturn(null);
        httpClient = mock(HttpClient.class);
        config = new ScanOmrCacheConfig(2000, 3, 1, 30, 60);
    }

    @Test
    void secondPollFullyReplacesPriorSnapshot() throws Exception {
        Instant now = fixed.instant();
        String body1 = ScanOmrCacheBootstrapTest.threeRoundsJson(now);
        String body2 = twoNewRoundsJson(now);

        doReturn(ScanOmrCacheBootstrapTest.stubResponse(200, body1))
                .doReturn(ScanOmrCacheBootstrapTest.stubResponse(200, body2))
                .when(httpClient).send(any(HttpRequest.class), any());

        ScanOmrCache cache = new ScanOmrCache(ledgerConfig, Optional.empty(), config, fixed);
        cache.httpClient = httpClient;
        cache.init();

        Set<String> firstCids = cache.currentRounds().stream()
                .map(ScanOmrCache.OmrEntry::contractId)
                .collect(Collectors.toSet());
        assertEquals(1L, cache.version(), "bootstrap installs version 1");
        assertEquals(Set.of("cidA", "cidB", "cidC"), firstCids);

        cache.refresh();

        Set<String> secondCids = cache.currentRounds().stream()
                .map(ScanOmrCache.OmrEntry::contractId)
                .collect(Collectors.toSet());
        assertEquals(2L, cache.version(), "refresh installs version 2");
        assertEquals(Set.of("cidX", "cidY"), secondCids, "second poll fully replaces the prior set");
        assertFalse(secondCids.contains("cidA"), "prior cids removed on full replacement");
        assertFalse(secondCids.contains("cidB"), "prior cids removed on full replacement");
        assertFalse(secondCids.contains("cidC"), "prior cids removed on full replacement");
    }

    private static String twoNewRoundsJson(Instant baseNow) {
        String blob = Base64.getEncoder().encodeToString(new byte[]{5, 6, 7, 8});
        Instant opensX = baseNow.minus(Duration.ofMinutes(3));
        Instant closesX = baseNow.plus(Duration.ofMinutes(17));
        Instant opensY = baseNow.minus(Duration.ofMinutes(13));
        Instant closesY = baseNow.plus(Duration.ofMinutes(7));
        return "{\"open_mining_rounds\":["
                + ScanOmrCacheBootstrapTest.roundEntry("cidX", "tpl-x:Splice.Round:OpenMiningRound",
                        blob, "sync-1", opensX, closesX, 200L)
                + ","
                + ScanOmrCacheBootstrapTest.roundEntry("cidY", "tpl-y:Splice.Round:OpenMiningRound",
                        blob, "sync-1", opensY, closesY, 201L)
                + "]}";
    }
}
