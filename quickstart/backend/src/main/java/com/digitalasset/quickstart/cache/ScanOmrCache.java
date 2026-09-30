package com.digitalasset.quickstart.cache;

import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Phase 3F.10 Step B — fresh OMR cache backed by scan-proxy delta-poll.
 *
 * Eliminates the OMR rotation-drift race documented in
 * {@code /root/OMR_CACHE_SERVICE_REVIEW.md} by maintaining an in-process snapshot
 * of the active {@code OpenMiningRound} set, refreshed on a tight cadence
 * ({@code scanOmr.cache.pollIntervalMs}, default 2 s). On swap submission,
 * {@link com.digitalasset.quickstart.service.OpenMiningRoundRefreshService}
 * consults {@link #selectSafeOmr(Instant, Duration)} first and falls through
 * to the existing direct-fetch path on cache miss / fail-open.
 *
 * Locked design contract: {@code /root/STEP_B_SCAN_OMR_CACHE.md}.
 * OQ-Step-B-1 through OQ-Step-B-5 resolutions: Mind dispatch 2026-05-11.
 *
 * Concurrency:
 *  - Reads are lock-free via {@link AtomicReference}.
 *  - Writes happen on the single Spring {@code @Scheduled} task thread; atomicity
 *    guaranteed by {@code compareAndSet} on the snapshot reference.
 *  - No {@code synchronized}, no {@code ReentrantLock}.
 */
@Component
@Profile("devnet")
public class ScanOmrCache {

    private static final Logger LOG = LoggerFactory.getLogger(ScanOmrCache.class);

    private static final String OMR_SUBPATH = "/open-and-issuing-mining-rounds";

    private final LedgerConfig ledgerConfig;
    private final Optional<RegistryRoutingConfig> registryRouting;
    private final ScanOmrCacheConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    /** Package-private for tests to inject a Mockito-spy or stub. */
    HttpClient httpClient = HttpClient.newHttpClient();

    private static final ScanOmrSnapshot EMPTY_SNAPSHOT =
            new ScanOmrSnapshot(Collections.emptyList(), Instant.EPOCH, 0L, true);

    private final AtomicReference<ScanOmrSnapshot> snapshot = new AtomicReference<>(EMPTY_SNAPSHOT);
    private final AtomicReference<Instant> lastSuccessfulFetchAt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicLong refreshFailureCount = new AtomicLong(0L);

    @org.springframework.beans.factory.annotation.Autowired
    public ScanOmrCache(LedgerConfig ledgerConfig,
                        Optional<RegistryRoutingConfig> registryRouting,
                        ScanOmrCacheConfig config) {
        this(ledgerConfig, registryRouting, config, Clock.systemUTC());
    }

    /** Test-only constructor allowing injection of a fixed/offset Clock. */
    ScanOmrCache(LedgerConfig ledgerConfig,
                 Optional<RegistryRoutingConfig> registryRouting,
                 ScanOmrCacheConfig config,
                 Clock clock) {
        this.ledgerConfig = ledgerConfig;
        this.registryRouting = registryRouting;
        this.config = config;
        this.clock = clock;
    }

    @PostConstruct
    void init() {
        int maxRetries = config.bootstrapMaxRetries();
        long backoffMs = config.bootstrapRetryBackoffMs();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ScanOmrSnapshot fresh = pollOnce();
                if (fresh != null) {
                    ScanOmrSnapshot installed = new ScanOmrSnapshot(
                            fresh.rounds(), fresh.fetchedAt(), 1L, false);
                    snapshot.set(installed);
                    lastSuccessfulFetchAt.set(clock.instant());
                    refreshFailureCount.set(0L);
                    LOG.info("[SCAN-OMR-CACHE] bootstrap OK attempt={} roundCount={} version={}",
                            attempt, installed.rounds().size(), installed.version());
                    return;
                }
            } catch (Exception e) {
                LOG.warn("[SCAN-OMR-CACHE] bootstrap attempt {} of {} failed: {}",
                        attempt, maxRetries, e.getMessage());
            }
            if (attempt < maxRetries) {
                long sleep = backoffMs * (1L << (attempt - 1));
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOG.warn("[SCAN-OMR-CACHE] bootstrap interrupted during retry sleep");
                    return;
                }
            }
        }
        LOG.warn("[SCAN-OMR-CACHE] bootstrap FAILED, entering fail-open mode after {} retries", maxRetries);
    }

    @Scheduled(fixedDelayString = "${scan-omr.cache.poll-interval-ms:2000}")
    public void refresh() {
        boolean wasInFailMode = (snapshot.get().version() == 0L);
        try {
            ScanOmrSnapshot fresh = pollOnce();
            if (fresh == null) {
                onFailure("non-2xx or malformed response (null snapshot)");
                return;
            }
            ScanOmrSnapshot prior = snapshot.get();
            ScanOmrSnapshot installed = new ScanOmrSnapshot(
                    fresh.rounds(),
                    fresh.fetchedAt(),
                    prior.version() + 1L,
                    false
            );
            snapshot.set(installed);
            lastSuccessfulFetchAt.set(clock.instant());
            refreshFailureCount.set(0L);
            if (wasInFailMode) {
                LOG.info("[SCAN-OMR-CACHE] recovery OK after bootstrap fail-mode roundCount={} version={}",
                        installed.rounds().size(), installed.version());
            } else if ((installed.version() % 30L) == 0L) {
                LOG.info("[SCAN-OMR-CACHE] refresh OK roundCount={} version={}",
                        installed.rounds().size(), installed.version());
            }
        } catch (Exception e) {
            onFailure(e.getMessage());
        }
        maybeFlipStale();
    }

    private void onFailure(String reason) {
        long n = refreshFailureCount.incrementAndGet();
        if (n == 1 || (n % 10) == 0) {
            LOG.warn("[SCAN-OMR-CACHE] refresh failure count={} reason={}", n, reason);
        }
    }

    private void maybeFlipStale() {
        ScanOmrSnapshot current = snapshot.get();
        if (current.version() == 0L) return;
        Duration sinceFetch = Duration.between(lastSuccessfulFetchAt.get(), clock.instant());
        if (!current.stale() && sinceFetch.getSeconds() > config.staleWarningThresholdSec()) {
            snapshot.compareAndSet(current, new ScanOmrSnapshot(
                    current.rounds(), current.fetchedAt(), current.version(), true));
            LOG.warn("[SCAN-OMR-CACHE] snapshot stale staleSec={} version={}",
                    sinceFetch.getSeconds(), current.version());
        }
    }

    /**
     * Apply Step A's locked selection rule against the current snapshot. Returns the
     * OMR with {@code opensAt <= now} AND latest {@code opensAt} AND
     * {@code targetClosesAt - now > safetyMargin}, or empty if no entry satisfies
     * or the cache is in fail-open mode.
     *
     * <p>Fail-open mode is entered when no successful poll has happened within
     * {@code failOpenAfterStaleSec} of {@code lastFetchedAt}; in that case, callers
     * fall through to the direct-fetch path documented at
     * {@code OpenMiningRoundRefreshService.applySafetyMarginFromScan}.
     */
    public Optional<OmrEntry> selectSafeOmr(Instant now, Duration safetyMargin) {
        ScanOmrSnapshot s = snapshot.get();
        if (s.version() == 0L) return Optional.empty();
        Duration sinceFetch = Duration.between(lastSuccessfulFetchAt.get(), now);
        if (sinceFetch.getSeconds() > config.failOpenAfterStaleSec()) {
            return Optional.empty();
        }
        return applySelectionRule(s.rounds(), now, safetyMargin,
                OmrEntry::opensAt, OmrEntry::targetClosesAt);
    }

    public List<OmrEntry> currentRounds() {
        return List.copyOf(snapshot.get().rounds());
    }

    public Instant lastFetchedAt() {
        return lastSuccessfulFetchAt.get();
    }

    public long version() {
        return snapshot.get().version();
    }

    public boolean isStale() {
        return snapshot.get().stale();
    }

    // ────────────────────────────────────────────────────────────────────────
    // LOCKED-RULE — mirror in OpenMiningRoundRefreshService.selectBest; change both together.
    // Phase 3F.10 Step A selection rule: pick the entry with opensAt <= now AND
    // latest opensAt AND targetClosesAt - now > safetyMargin. Returns empty if no
    // entry satisfies. Generic accessor form lets selectBest reuse this helper
    // against ScanOmrEntry; selectSafeOmr uses it against OmrEntry.
    // ────────────────────────────────────────────────────────────────────────
    public static <T> Optional<T> applySelectionRule(
            List<T> candidates,
            Instant now,
            Duration safetyMargin,
            Function<T, Instant> opensAt,
            Function<T, Instant> targetClosesAt) {
        T best = null;
        for (T c : candidates) {
            Instant openAt = opensAt.apply(c);
            if (openAt.isAfter(now)) continue;
            if (Duration.between(now, targetClosesAt.apply(c)).compareTo(safetyMargin) <= 0) continue;
            if (best == null || openAt.isAfter(opensAt.apply(best))) {
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Internal — HTTP poll + JSON parse
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Fetch a fresh snapshot via HTTP GET against scan-proxy. Returns a populated
     * snapshot on 2xx, {@code null} on non-2xx, throws on transport errors.
     *
     * <p>GET-only protocol: per Mind's live evidence 2026-05-11
     * ({@code curl -X OPTIONS} returned {@code HTTP/1.1 405 Allow: GET}) and Addendum 1
     * to {@code /root/STEP_B_SCAN_OMR_CACHE.md}, the local scan-proxy at
     * {@code /api/validator/v0/scan-proxy/open-and-issuing-mining-rounds} accepts
     * GET only. Each successful GET returns the full current OMR set (no delta
     * protocol — scan-proxy returns a fresh in-process snapshot every time). The
     * fallback direct-fetch path in {@code OpenMiningRoundRefreshService} continues
     * to POST against the raw scan SV ({@code /api/scan/v0/...}); the two paths are
     * intentionally asymmetric in HTTP method.
     */
    private ScanOmrSnapshot pollOnce() throws Exception {
        String base = resolveBaseUri();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("no scan registry base URI configured");
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + OMR_SUBPATH;

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .GET();
        if (ledgerConfig.getRegistryAuthHeader() != null && !ledgerConfig.getRegistryAuthHeader().isBlank()
                && ledgerConfig.getRegistryAuthToken() != null && !ledgerConfig.getRegistryAuthToken().isBlank()) {
            b.header(ledgerConfig.getRegistryAuthHeader(), ledgerConfig.getRegistryAuthToken());
        }
        HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            LOG.debug("[SCAN-OMR-CACHE] poll non-2xx status={} url={}", resp.statusCode(), abbreviate(url));
            return null;
        }
        List<OmrEntry> rounds = parseScanResponse(resp.body());
        return new ScanOmrSnapshot(rounds, clock.instant(), 0L, false);
    }

    /** OQ-1 lock: cache polls scan-proxy via the default registry baseUri only. */
    private String resolveBaseUri() {
        if (registryRouting.isPresent()) {
            String def = registryRouting.get().getDefaultBaseUri();
            if (def != null && !def.isBlank()) return def;
        }
        return ledgerConfig.getRegistryBaseUri();
    }

    private List<OmrEntry> parseScanResponse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode omrMap = root.path("open_mining_rounds");
        List<OmrEntry> out = new ArrayList<>();
        if (!omrMap.isArray()) return out;
        Iterator<JsonNode> it = omrMap.elements();
        while (it.hasNext()) {
            JsonNode e = it.next();
            OmrEntry parsed = OmrEntry.fromJson("", e);
            if (parsed != null) out.add(parsed);
        }
        return out;
    }

    private static String abbreviate(String s) {
        if (s == null) return "<null>";
        if (s.length() <= 80) return s;
        return s.substring(0, 40) + "..." + s.substring(s.length() - 20);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Public records (snapshot + entry shape)
    // ────────────────────────────────────────────────────────────────────────

    public record ScanOmrSnapshot(
            List<OmrEntry> rounds,
            Instant fetchedAt,
            long version,
            boolean stale
    ) {}

    public record OmrEntry(
            String contractId,
            String templateId,
            byte[] createdEventBlob,
            String synchronizerId,
            Instant opensAt,
            Instant targetClosesAt,
            long roundNumber
    ) {
        public static OmrEntry fromJson(String mapKey, JsonNode entryNode) {
            try {
                JsonNode contract = entryNode.path("contract");
                String contractId = contract.path("contract_id").asText(mapKey);
                String templateId = contract.path("template_id").asText(null);
                String createdEventBlobB64 = contract.path("created_event_blob").asText(null);
                String synchronizerId = entryNode.path("domain_id").asText(null);
                if (synchronizerId == null || synchronizerId.isBlank()) {
                    synchronizerId = contract.path("synchronizer_id").asText(null);
                }
                JsonNode payload = contract.path("payload");
                long roundNumber = payload.path("round").path("number").asLong(-1L);
                Instant opensAt = parseInstant(payload.path("opensAt"));
                Instant targetClosesAt = parseInstant(payload.path("targetClosesAt"));
                if (createdEventBlobB64 == null || opensAt == null || targetClosesAt == null) {
                    return null;
                }
                byte[] blob = Base64.getDecoder().decode(createdEventBlobB64);
                return new OmrEntry(contractId, templateId, blob, synchronizerId,
                        opensAt, targetClosesAt, roundNumber);
            } catch (Exception ex) {
                return null;
            }
        }

        private static Instant parseInstant(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) return null;
            String text = node.asText(null);
            if (text == null || text.isBlank()) return null;
            try {
                return Instant.parse(text);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
