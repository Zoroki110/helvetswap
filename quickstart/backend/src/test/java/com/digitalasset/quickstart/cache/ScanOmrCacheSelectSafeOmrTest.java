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

class ScanOmrCacheSelectSafeOmrTest {

    private LedgerConfig ledgerConfig;
    private HttpClient httpClient;
    private ScanOmrCacheConfig config;
    private final Instant now = Instant.parse("2026-05-11T10:00:00Z");
    private final Clock fixed = Clock.fixed(now, ZoneOffset.UTC);

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
    void selectsTheOnlySafeOmrAmongThree() throws Exception {
        String body = ScanOmrCacheBootstrapTest.threeRoundsJson(now);
        doReturn(ScanOmrCacheBootstrapTest.stubResponse(200, body))
                .when(httpClient).send(any(HttpRequest.class), any());

        ScanOmrCache cache = new ScanOmrCache(ledgerConfig, Optional.empty(), config, fixed);
        cache.httpClient = httpClient;
        cache.init();

        Optional<ScanOmrCache.OmrEntry> best = cache.selectSafeOmr(now, Duration.ofSeconds(30));
        assertTrue(best.isPresent(), "expected exactly one safe OMR among the three fixtures");
        assertEquals("cidC", best.get().contractId());
        assertEquals(102L, best.get().roundNumber());
    }

    @Test
    void returnsEmptyWhenAllCandidatesAreFutureOrTooSoonToClose() throws Exception {
        // All three are not-yet-open / closing-soon — none satisfies the rule.
        Instant tooSoon = now.plus(Duration.ofSeconds(10));
        Instant futureOpen = now.plus(Duration.ofMinutes(5));
        Instant futureClose = futureOpen.plus(Duration.ofMinutes(20));
        Instant pastOpen = now.minus(Duration.ofMinutes(19));
        String body = "{\"open_mining_rounds\":["
                + ScanOmrCacheBootstrapTest.roundEntry("a", "tpl:Splice.Round:OpenMiningRound",
                        "AQID", "sync-1", futureOpen, futureClose, 200L)
                + ","
                + ScanOmrCacheBootstrapTest.roundEntry("b", "tpl:Splice.Round:OpenMiningRound",
                        "AQID", "sync-1", pastOpen, tooSoon, 201L)
                + "]}";
        doReturn(ScanOmrCacheBootstrapTest.stubResponse(200, body))
                .when(httpClient).send(any(HttpRequest.class), any());

        ScanOmrCache cache = new ScanOmrCache(ledgerConfig, Optional.empty(), config, fixed);
        cache.httpClient = httpClient;
        cache.init();

        assertTrue(cache.selectSafeOmr(now, Duration.ofSeconds(30)).isEmpty());
    }

    @Test
    void picksTheLatestOpensAtAmongMultipleSafe() throws Exception {
        Instant openOlder = now.minus(Duration.ofMinutes(10));
        Instant openNewer = now.minus(Duration.ofMinutes(2));
        Instant closeOlder = now.plus(Duration.ofMinutes(10));
        Instant closeNewer = now.plus(Duration.ofMinutes(18));
        String body = "{\"open_mining_rounds\":["
                + ScanOmrCacheBootstrapTest.roundEntry("older", "tpl:Splice.Round:OpenMiningRound",
                        "AQID", "sync-1", openOlder, closeOlder, 300L)
                + ","
                + ScanOmrCacheBootstrapTest.roundEntry("newer", "tpl:Splice.Round:OpenMiningRound",
                        "AQID", "sync-1", openNewer, closeNewer, 301L)
                + "]}";
        doReturn(ScanOmrCacheBootstrapTest.stubResponse(200, body))
                .when(httpClient).send(any(HttpRequest.class), any());

        ScanOmrCache cache = new ScanOmrCache(ledgerConfig, Optional.empty(), config, fixed);
        cache.httpClient = httpClient;
        cache.init();

        Optional<ScanOmrCache.OmrEntry> best = cache.selectSafeOmr(now, Duration.ofSeconds(30));
        assertTrue(best.isPresent());
        assertEquals("newer", best.get().contractId());
    }
}
