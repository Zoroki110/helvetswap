package com.digitalasset.quickstart.cache;

import com.digitalasset.quickstart.config.LedgerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScanOmrCacheBootstrapTest {

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
        // Fast retries so tests don't block.
        config = new ScanOmrCacheConfig(2000, 3, 1, 30, 60);
    }

    @Test
    void bootstrapPopulatesSnapshotOnFirstSuccess() throws Exception {
        String body = threeRoundsJson(fixed.instant());
        doReturn(stubResponse(200, body)).when(httpClient).send(any(HttpRequest.class), any());

        ScanOmrCache cache = new ScanOmrCache(ledgerConfig, Optional.empty(), config, fixed);
        cache.httpClient = httpClient;
        cache.init();

        assertEquals(1L, cache.version());
        assertEquals(3, cache.currentRounds().size());
        assertTrue(cache.lastFetchedAt().isAfter(Instant.EPOCH));
        assertFalse(cache.isStale());
    }

    @Test
    void bootstrapEntersFailModeWhenAllRetriesFail() throws Exception {
        doThrow(new java.io.IOException("scan-proxy unreachable"))
                .when(httpClient).send(any(HttpRequest.class), any());

        ScanOmrCache cache = new ScanOmrCache(ledgerConfig, Optional.empty(), config, fixed);
        cache.httpClient = httpClient;
        cache.init();

        assertEquals(0L, cache.version());
        assertEquals(0, cache.currentRounds().size());
        assertTrue(cache.isStale());
        assertTrue(cache.selectSafeOmr(fixed.instant(), Duration.ofSeconds(30)).isEmpty());
    }

    static String threeRoundsJson(Instant baseNow) {
        String blob = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        // Round A: not-yet-open. Round B: closing-soon (within 30s). Round C: safe.
        Instant opensA = baseNow.plus(Duration.ofMinutes(2));
        Instant closesA = opensA.plus(Duration.ofMinutes(20));
        Instant opensB = baseNow.minus(Duration.ofMinutes(18));
        Instant closesB = baseNow.plus(Duration.ofSeconds(15));
        Instant opensC = baseNow.minus(Duration.ofMinutes(5));
        Instant closesC = baseNow.plus(Duration.ofMinutes(15));
        return "{\"open_mining_rounds\":["
                + roundEntry("cidA", "tpl-a:Splice.Round:OpenMiningRound", blob, "sync-1", opensA, closesA, 100L)
                + ","
                + roundEntry("cidB", "tpl-b:Splice.Round:OpenMiningRound", blob, "sync-1", opensB, closesB, 101L)
                + ","
                + roundEntry("cidC", "tpl-c:Splice.Round:OpenMiningRound", blob, "sync-1", opensC, closesC, 102L)
                + "]}";
    }

    static String roundEntry(String cid, String templateId, String blobB64, String sync,
                             Instant opensAt, Instant targetClosesAt, long round) {
        return "{\"domain_id\":\"" + sync + "\",\"contract\":{"
                + "\"contract_id\":\"" + cid + "\","
                + "\"template_id\":\"" + templateId + "\","
                + "\"created_event_blob\":\"" + blobB64 + "\","
                + "\"payload\":{\"round\":{\"number\":\"" + round + "\"},"
                + "\"opensAt\":\"" + opensAt.toString() + "\","
                + "\"targetClosesAt\":\"" + targetClosesAt.toString() + "\"}}}";
    }

    @SuppressWarnings("unchecked")
    static HttpResponse<String> stubResponse(int status, String body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() {
                return HttpHeaders.of(new TreeMap<>(), (a, b) -> true);
            }
            @Override public String body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("http://test"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
