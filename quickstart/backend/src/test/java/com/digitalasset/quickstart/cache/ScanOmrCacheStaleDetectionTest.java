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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * T-2: verifies the snapshot's stale flag flips after staleWarningThresholdSec
 * elapses since lastSuccessfulFetchAt and a subsequent refresh fails.
 */
class ScanOmrCacheStaleDetectionTest {

    private LedgerConfig ledgerConfig;
    private HttpClient httpClient;
    private ScanOmrCacheConfig config;
    private final Instant t0 = Instant.parse("2026-05-11T10:00:00Z");

    @BeforeEach
    void setUp() {
        ledgerConfig = mock(LedgerConfig.class);
        when(ledgerConfig.getRegistryBaseUri()).thenReturn("http://scan-proxy.test");
        when(ledgerConfig.getRegistryAuthHeader()).thenReturn(null);
        when(ledgerConfig.getRegistryAuthToken()).thenReturn(null);
        httpClient = mock(HttpClient.class);
        // 5s stale threshold (was 30s default — tighter for tests).
        config = new ScanOmrCacheConfig(2000, 3, 1, 5, 60);
    }

    @Test
    void staleFlagFlipsAfterThresholdWithFailingRefresh() throws Exception {
        String body = ScanOmrCacheBootstrapTest.threeRoundsJson(t0);
        // First call (init bootstrap) returns 200; subsequent calls fail.
        doReturn(ScanOmrCacheBootstrapTest.stubResponse(200, body))
                .doThrow(new java.io.IOException("simulated scan outage"))
                .when(httpClient).send(any(HttpRequest.class), any());

        // Mutable clock: start at t0, then advance for refresh.
        Instant[] clockNow = new Instant[]{t0};
        Clock mutable = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clockNow[0]; }
        };

        ScanOmrCache cache = new ScanOmrCache(ledgerConfig, Optional.empty(), config, mutable);
        cache.httpClient = httpClient;
        cache.init();
        assertEquals(1L, cache.version());
        assertFalse(cache.isStale());

        // Advance clock past 5s stale threshold; refresh fails; snapshot retained but stale=true.
        clockNow[0] = t0.plus(Duration.ofSeconds(10));
        cache.refresh();

        assertTrue(cache.isStale(), "expected stale flag to flip after 10s elapsed (threshold=5s)");
        assertEquals(1L, cache.version(), "snapshot version unchanged on refresh failure");
        assertEquals(3, cache.currentRounds().size(), "snapshot rounds retained");
    }
}
