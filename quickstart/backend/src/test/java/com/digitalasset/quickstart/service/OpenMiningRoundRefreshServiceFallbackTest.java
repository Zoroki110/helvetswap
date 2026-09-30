package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryEndpoint;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryKind;
import com.digitalasset.quickstart.ledger.LedgerApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Phase 3F.10 Step A.2 registry-routing fallback in
 * {@link OpenMiningRoundRefreshService#fetchScanOmrResponse}.
 *
 * Covers:
 *  - multiRegistry_succeedsOnFallback        — first registry 502, second 200 → success
 *  - multiRegistry_throwsWhenAllFail         — all 3 endpoints non-2xx → IllegalStateException with detail
 *  - emptyRouting_fallsThroughLegacyAndThrowsIfBlank — empty routing list + blank legacy uri → throw
 *  - optionalEmpty_legacySingleUriPath       — Optional.empty() routing + legacy uri 200 → success
 */
class OpenMiningRoundRefreshServiceFallbackTest {

    private LedgerApi ledgerApi;
    private LedgerConfig ledgerConfig;
    private RegistryRoutingConfig routing;
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        ledgerApi = mock(LedgerApi.class);
        ledgerConfig = mock(LedgerConfig.class);
        routing = mock(RegistryRoutingConfig.class);
        mockHttpClient = mock(HttpClient.class);
        // Auth header optional — return null/blank so the auth block is skipped in fetchScanOmrResponse.
        when(ledgerConfig.getRegistryAuthHeader()).thenReturn(null);
        when(ledgerConfig.getRegistryAuthToken()).thenReturn(null);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    private OpenMiningRoundRefreshService newService(Optional<RegistryRoutingConfig> rr) {
        OpenMiningRoundRefreshService s = new OpenMiningRoundRefreshService(ledgerApi, ledgerConfig, rr);
        s.httpClient = mockHttpClient;
        return s;
    }

    // ─── Test 1: ep1 → 502, ep2 → 200; expect ep2 body returned, 2 sends ───
    @Test
    void multiRegistry_succeedsOnFallback() throws Exception {
        when(routing.getRegistriesToTry(null)).thenReturn(List.of(
                new RegistryEndpoint("http://ep1.local", RegistryKind.SCAN, null),
                new RegistryEndpoint("http://ep2.local", RegistryKind.SCAN, null)));
        // doReturn(...) bypasses generic-erasure inference issues with HttpResponse<String>.
        doReturn(response(502, ""), response(200, "{\"open_mining_rounds\":{}}"))
                .when(mockHttpClient).send(any(), any());

        OpenMiningRoundRefreshService svc = newService(Optional.of(routing));
        String body = svc.fetchScanOmrResponse();

        assertEquals("{\"open_mining_rounds\":{}}", body);
        verify(mockHttpClient, times(2)).send(any(), any());
    }

    // ─── Test 2: all 3 endpoints non-2xx → IllegalStateException with per-attempt detail ───
    @Test
    void multiRegistry_throwsWhenAllFail() throws Exception {
        when(routing.getRegistriesToTry(null)).thenReturn(List.of(
                new RegistryEndpoint("http://ep1.local", RegistryKind.SCAN, null),
                new RegistryEndpoint("http://ep2.local", RegistryKind.SCAN, null),
                new RegistryEndpoint("http://ep3.local", RegistryKind.SCAN, null)));
        doReturn(response(502, ""), response(503, ""), response(504, ""))
                .when(mockHttpClient).send(any(), any());

        OpenMiningRoundRefreshService svc = newService(Optional.of(routing));
        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::fetchScanOmrResponse);

        assertTrue(ex.getMessage().contains("all 3 registries"),
                "message must mention all 3 registries: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP_502"), "msg: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP_503"), "msg: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP_504"), "msg: " + ex.getMessage());
        verify(mockHttpClient, times(3)).send(any(), any());
    }

    // ─── Test 3: routing returns empty list → falls back to legacy uri; legacy blank → throw ───
    @Test
    void emptyRouting_fallsThroughLegacyAndThrowsIfBlank() throws Exception {
        when(routing.getRegistriesToTry(null)).thenReturn(List.of());
        when(ledgerConfig.getRegistryBaseUri()).thenReturn(null);

        OpenMiningRoundRefreshService svc = newService(Optional.of(routing));
        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::fetchScanOmrResponse);

        assertTrue(ex.getMessage().contains("no scan registries configured"),
                "message must mention no-registries-configured: " + ex.getMessage());
        verify(mockHttpClient, never()).send(any(), any());
    }

    // ─── Test 4: Optional.empty() routing + legacy uri 200 → success on legacy single-uri path ───
    @Test
    void optionalEmpty_legacySingleUriPath() throws Exception {
        when(ledgerConfig.getRegistryBaseUri()).thenReturn("http://legacy.local");
        doReturn(response(200, "{\"open_mining_rounds\":{}}"))
                .when(mockHttpClient).send(any(), any());

        OpenMiningRoundRefreshService svc = newService(Optional.empty());
        String body = svc.fetchScanOmrResponse();

        assertEquals("{\"open_mining_rounds\":{}}", body);
        verify(mockHttpClient, times(1)).send(any(), any());
    }
}
