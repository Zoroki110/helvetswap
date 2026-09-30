package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.cache.ScanOmrCache;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.ledger.LedgerApi.RawActiveContractWithBlob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Fast-path mitigation for stale OpenMiningRound (OMR) disclosed contracts.
 *
 * When the Scan SV returns a stale OMR CID that the local participant has already
 * archived, this service queries the LOCAL participant's ACS for the current active
 * OMR (with createdEventBlob) and builds a replacement DisclosedContract.
 *
 * This avoids waiting for Scan to catch up (which can lag 1-5+ minutes).
 *
 * Feature-flagged via clearportx.swap.participant-refresh-omr.enabled (default true).
 */
@Service
@Profile("prod")
public class OpenMiningRoundRefreshService {

    private static final Logger LOG = LoggerFactory.getLogger(OpenMiningRoundRefreshService.class);

    /** Splice module name for OpenMiningRound template */
    private static final String OMR_MODULE_NAME = "Splice.Round";
    /** Splice entity name for OpenMiningRound template */
    private static final String OMR_ENTITY_NAME = "OpenMiningRound";

    private final LedgerApi ledgerApi;
    private final LedgerConfig ledgerConfig;
    private final Optional<RegistryRoutingConfig> registryRouting;
    private final Optional<ScanOmrCache> scanOmrCache;

    HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Phase 3F.10 Step A — minimum margin between now and OMR's targetClosesAt. */
    static final Duration SAFETY_MARGIN = Duration.ofSeconds(30);

    /** Sub-path appended to {@link LedgerConfig#getRegistryBaseUri()} to reach the scan-proxy OMR endpoint. */
    private static final String OMR_SUBPATH = "/open-and-issuing-mining-rounds";

    /** Empty cached-cid arrays force scan to return a fresh full snapshot. */
    private static final String FETCH_BODY =
            "{\"cached_open_mining_round_contract_ids\":[],\"cached_issuing_round_contract_ids\":[]}";

    /** Backoff before the second filter pass when the first finds no candidate. */
    private static final long RETRY_BACKOFF_MS = 500;

    @Value("${clearportx.swap.participant-refresh-omr.enabled:true}")
    private boolean enabled;

    // Default false: Canton 3.x LOCAL_VERDICT_INACTIVE_CONTRACTS does not include
    // the actual inactive contract IDs in the error message, so the inactiveCids set
    // parsed from the error is typically empty. Setting this to true would skip refresh
    // almost always. Set to false to always attempt refresh on INACTIVE_CONTRACTS.
    @Value("${clearportx.swap.participant-refresh-omr.only-when-inactive:false}")
    private boolean onlyWhenInactive;

    @org.springframework.beans.factory.annotation.Autowired
    public OpenMiningRoundRefreshService(LedgerApi ledgerApi,
                                         LedgerConfig ledgerConfig,
                                         Optional<RegistryRoutingConfig> registryRouting,
                                         Optional<ScanOmrCache> scanOmrCache) {
        this.ledgerApi = ledgerApi;
        this.ledgerConfig = ledgerConfig;
        this.registryRouting = registryRouting;
        this.scanOmrCache = scanOmrCache;
    }

    // Backward-compat delegating constructor — preserves the 3-arg shape exercised by
    // existing tests (OpenMiningRoundRefreshServiceSafetyMarginTest +
    // OpenMiningRoundRefreshServiceFallbackTest). Spring binds via the @Autowired 4-arg
    // constructor above at production wiring; tests use this 3-arg form with no cache.
    public OpenMiningRoundRefreshService(LedgerApi ledgerApi,
                                         LedgerConfig ledgerConfig,
                                         Optional<RegistryRoutingConfig> registryRouting) {
        this(ledgerApi, ledgerConfig, registryRouting, Optional.empty());
    }

    /**
     * Result of an OMR refresh attempt.
     */
    public record RefreshResult(
            List<CommandsOuterClass.DisclosedContract> updatedDisclosed,
            boolean replaced,
            String oldCid,
            String newCid,
            String synchronizerIdUsed
    ) {
        public static RefreshResult unchanged(List<CommandsOuterClass.DisclosedContract> disclosed) {
            return new RefreshResult(disclosed, false, null, null, null);
        }
    }

    /**
     * Attempt to replace a stale OpenMiningRound disclosed contract with a fresh one
     * from the local participant's ACS.
     *
     * @param disclosed         Current disclosed contracts (from Scan + payout)
     * @param inactiveCids      Contract IDs reported as inactive by the participant
     * @param currentSyncId     The synchronizerId used for the swap submission
     * @param submittingParty   The party submitting the swap (for ACS query)
     * @return RefreshResult with updated disclosed list if replacement was made
     */
    public CompletableFuture<RefreshResult> refreshOpenMiningRound(
            List<CommandsOuterClass.DisclosedContract> disclosed,
            Set<String> inactiveCids,
            String currentSyncId,
            String submittingParty
    ) {
        if (!enabled) {
            LOG.debug("[OMR-Refresh] Feature disabled, skipping");
            return CompletableFuture.completedFuture(RefreshResult.unchanged(disclosed));
        }

        if (disclosed == null || disclosed.isEmpty()) {
            return CompletableFuture.completedFuture(RefreshResult.unchanged(disclosed));
        }

        // Find the OMR in the disclosed list by template module/entity
        int omrIndex = -1;
        CommandsOuterClass.DisclosedContract staleOmr = null;
        for (int i = 0; i < disclosed.size(); i++) {
            CommandsOuterClass.DisclosedContract dc = disclosed.get(i);
            if (isOpenMiningRound(dc.getTemplateId())) {
                omrIndex = i;
                staleOmr = dc;
                break;
            }
        }

        if (staleOmr == null) {
            LOG.debug("[OMR-Refresh] No OpenMiningRound found in disclosed contracts, skipping");
            return CompletableFuture.completedFuture(RefreshResult.unchanged(disclosed));
        }

        String staleCid = staleOmr.getContractId();

        // If onlyWhenInactive, only refresh when the OMR CID is in the inactive set
        if (onlyWhenInactive && (inactiveCids == null || !inactiveCids.contains(staleCid))) {
            LOG.debug("[OMR-Refresh] OMR cid={} not in inactive set, skipping (onlyWhenInactive=true)", abbreviate(staleCid));
            return CompletableFuture.completedFuture(RefreshResult.unchanged(disclosed));
        }

        LOG.info("[OMR-Refresh] Stale OMR detected cid={} syncId={}, querying participant ACS for party={}",
                abbreviate(staleCid), abbreviate(currentSyncId), abbreviate(submittingParty));

        final int targetIndex = omrIndex;
        final CommandsOuterClass.DisclosedContract targetStaleOmr = staleOmr;

        return ledgerApi.getActiveContractsWithBlobForParty(submittingParty)
                .thenApply(allContracts -> {
                    // Filter to OMR template + matching synchronizerId
                    List<RawActiveContractWithBlob> omrCandidates = allContracts.stream()
                            .filter(ac -> isOpenMiningRound(ac.templateId()))
                            .filter(ac -> matchesSynchronizerId(ac.synchronizerId(), currentSyncId))
                            .filter(ac -> !ac.contractId().equals(staleCid)) // exclude the stale one
                            .filter(ac -> ac.createdEventBlob() != null && !ac.createdEventBlob().isEmpty())
                            .toList();

                    if (omrCandidates.isEmpty()) {
                        LOG.warn("[OMR-Refresh] No fresh OMR candidates found on participant ACS for syncId={}", abbreviate(currentSyncId));
                        return RefreshResult.unchanged(disclosed);
                    }

                    RawActiveContractWithBlob chosen;
                    if (omrCandidates.size() == 1) {
                        chosen = omrCandidates.get(0);
                    } else {
                        // Multiple candidates: pick deterministically by highest round number if available
                        chosen = selectBestCandidate(omrCandidates);
                        if (chosen == null) {
                            LOG.warn("[OMR-Refresh] Ambiguous: {} OMR candidates, cannot select deterministically", omrCandidates.size());
                            return RefreshResult.unchanged(disclosed);
                        }
                    }

                    // Build replacement DisclosedContract
                    CommandsOuterClass.DisclosedContract replacement = buildDisclosedContract(chosen, targetStaleOmr);
                    if (replacement == null) {
                        LOG.warn("[OMR-Refresh] Failed to build replacement DisclosedContract");
                        return RefreshResult.unchanged(disclosed);
                    }

                    // Replace only the OMR entry, keep order stable
                    List<CommandsOuterClass.DisclosedContract> updated = new ArrayList<>(disclosed);
                    updated.set(targetIndex, replacement);

                    LOG.info("[OMR-Refresh] Replaced stale OMR old={} new={} syncId={}",
                            abbreviate(staleCid), abbreviate(chosen.contractId()), abbreviate(chosen.synchronizerId()));

                    return new RefreshResult(updated, true, staleCid, chosen.contractId(), chosen.synchronizerId());
                })
                .exceptionally(ex -> {
                    LOG.warn("[OMR-Refresh] Failed to refresh OMR (graceful degradation): {}", ex.getMessage());
                    return RefreshResult.unchanged(disclosed);
                });
    }

    /**
     * Check if a template identifier matches OpenMiningRound (module/entity only, package-id independent).
     */
    private static boolean isOpenMiningRound(ValueOuterClass.Identifier templateId) {
        if (templateId == null) return false;
        return OMR_MODULE_NAME.equals(templateId.getModuleName())
                && OMR_ENTITY_NAME.equals(templateId.getEntityName());
    }

    /**
     * Match synchronizerId: strict equality, or accept if either is blank (lenient fallback).
     */
    private static boolean matchesSynchronizerId(String candidateSyncId, String targetSyncId) {
        if (targetSyncId == null || targetSyncId.isBlank()) return true;
        if (candidateSyncId == null || candidateSyncId.isBlank()) return true;
        return candidateSyncId.equals(targetSyncId);
    }

    /**
     * Select the best OMR candidate deterministically.
     * Attempts to parse the "round" field from createArguments (DAML Numeric/Int64 at known positions).
     * Falls back to lexicographic CID comparison for determinism.
     */
    private static RawActiveContractWithBlob selectBestCandidate(List<RawActiveContractWithBlob> candidates) {
        // Try to extract round number from createArguments
        RawActiveContractWithBlob best = null;
        long bestRound = Long.MIN_VALUE;
        boolean roundFieldFound = false;

        for (RawActiveContractWithBlob c : candidates) {
            long round = extractRoundNumber(c.createArguments());
            if (round != Long.MIN_VALUE) {
                roundFieldFound = true;
                if (round > bestRound) {
                    bestRound = round;
                    best = c;
                }
            }
        }

        if (roundFieldFound && best != null) {
            LOG.info("[OMR-Refresh] Selected OMR by round number: round={} cid={}", bestRound, abbreviate(best.contractId()));
            return best;
        }

        // Fallback: pick by lexicographically greatest CID for determinism
        return candidates.stream()
                .max(java.util.Comparator.comparing(RawActiveContractWithBlob::contractId))
                .orElse(null);
    }

    /**
     * Best-effort extraction of round number from OMR createArguments.
     * OpenMiningRound has a "round" field (Splice.Types.Round = Int64) typically in the first few fields.
     */
    private static long extractRoundNumber(ValueOuterClass.Record args) {
        if (args == null) return Long.MIN_VALUE;
        for (ValueOuterClass.RecordField field : args.getFieldsList()) {
            if ("round".equals(field.getLabel())) {
                ValueOuterClass.Value val = field.getValue();
                if (val.hasRecord() && val.getRecord().getFieldsCount() > 0) {
                    // Splice.Types.Round is a newtype: Record { unRound: Int64 }
                    ValueOuterClass.Value inner = val.getRecord().getFields(0).getValue();
                    if (inner.hasInt64()) return inner.getInt64();
                    // May also be encoded as Numeric
                    if (inner.hasNumeric()) {
                        try { return Long.parseLong(inner.getNumeric().split("\\.")[0]); } catch (NumberFormatException e) { /* ignore */ }
                    }
                }
                if (val.hasInt64()) return val.getInt64();
                if (val.hasNumeric()) {
                    try { return Long.parseLong(val.getNumeric().split("\\.")[0]); } catch (NumberFormatException e) { /* ignore */ }
                }
            }
        }
        return Long.MIN_VALUE;
    }

    /**
     * Build a gRPC DisclosedContract from a participant ACS entry.
     * Preserves the templateId from the ACS (authoritative) and uses the createdEventBlob directly.
     */
    private static CommandsOuterClass.DisclosedContract buildDisclosedContract(
            RawActiveContractWithBlob ac,
            CommandsOuterClass.DisclosedContract reference
    ) {
        try {
            CommandsOuterClass.DisclosedContract.Builder builder = CommandsOuterClass.DisclosedContract.newBuilder()
                    .setTemplateId(ac.templateId())
                    .setContractId(ac.contractId())
                    .setCreatedEventBlob(ac.createdEventBlob());
            if (ac.synchronizerId() != null && !ac.synchronizerId().isBlank()) {
                builder.setSynchronizerId(ac.synchronizerId());
            }
            return builder.build();
        } catch (Exception e) {
            LOG.warn("[OMR-Refresh] Failed to build DisclosedContract: {}", e.getMessage());
            return null;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        if (s.length() <= 30) return s;
        return s.substring(0, 15) + "..." + s.substring(s.length() - 10);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phase 3F.10 Step A — pre-emptive OMR safety-margin substitution
    //
    // Pre-flight, fetch scan's /open-and-issuing-mining-rounds, locally select
    // the OMR with `opensAt <= now` AND latest `opensAt` AND
    // `targetClosesAt - now > SAFETY_MARGIN`, and substitute it into the
    // disclosed-contracts list before the choice exercise. Eliminates the
    // rotation-drift "Must be unlocked" race when the registry's choice-context
    // returns a stale OMR cid.
    //
    // On all-fail-margin: retry once after 500ms; if still null, throw
    // OmrSafetyMarginFailException → caller dead-sets via SwapInspectStore.
    // ────────────────────────────────────────────────────────────────────────

    public CompletableFuture<List<CommandsOuterClass.DisclosedContract>> applySafetyMarginFromScan(
            List<CommandsOuterClass.DisclosedContract> disclosed,
            String synchronizerId,
            String requestId
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Phase 3F.10 Step B — cache-first path. On cache hit, short-circuit
                // the direct scan-proxy fetch. On miss / fail-open, fall through to
                // the existing direct-fetch path which preserves all Step A.2 semantics.
                Optional<ScanOmrCache.OmrEntry> cached =
                        scanOmrCache.flatMap(c -> c.selectSafeOmr(Instant.now(), SAFETY_MARGIN));
                if (cached.isPresent()) {
                    ScanOmrEntry cacheBest = fromOmrEntry(cached.get());
                    if (synchronizerId != null && !synchronizerId.isBlank()
                            && cacheBest.synchronizerId() != null
                            && !synchronizerId.equals(cacheBest.synchronizerId())) {
                        LOG.warn("[SCAN-OMR-CACHE-HIT] synchronizerId mismatch, abort substitution requestId={} existing={} new={}",
                                requestId, abbreviate(synchronizerId), abbreviate(cacheBest.synchronizerId()));
                        return disclosed;
                    }
                    String prevCid = findExistingOmrCid(disclosed);
                    List<CommandsOuterClass.DisclosedContract> substitutedFromCache =
                            substituteOmr(disclosed, cacheBest);
                    LOG.info("[SCAN-OMR-CACHE-HIT] substituted requestId={} fromCid={} toCid={} round={} marginSec={}",
                            requestId,
                            abbreviate(prevCid),
                            abbreviate(cacheBest.contractId()),
                            cacheBest.roundNumber(),
                            Duration.between(Instant.now(), cacheBest.targetClosesAt()).toSeconds());
                    return substitutedFromCache;
                }
                LOG.debug("[SCAN-OMR-CACHE-MISS-FALLTHROUGH] requestId={}", requestId);
                String body = fetchScanOmrResponse();
                ScanOmrEntry best = selectBest(parseScanResponse(body), Instant.now());
                if (best == null) {
                    LOG.info("[OMR-SAFETY-MARGIN] first-pass null, sleeping {}ms before retry requestId={}",
                            RETRY_BACKOFF_MS, requestId);
                    Thread.sleep(RETRY_BACKOFF_MS);
                    body = fetchScanOmrResponse();
                    best = selectBest(parseScanResponse(body), Instant.now());
                }
                if (best == null) {
                    throw new OmrSafetyMarginFailException(requestId,
                            "all OMRs queued or within "
                                    + SAFETY_MARGIN.toSeconds() + "s of close after retry");
                }
                if (synchronizerId != null && !synchronizerId.isBlank()
                        && best.synchronizerId() != null
                        && !synchronizerId.equals(best.synchronizerId())) {
                    LOG.warn("[OMR-SAFETY-MARGIN] synchronizerId mismatch, abort substitution requestId={} existing={} new={}",
                            requestId, abbreviate(synchronizerId), abbreviate(best.synchronizerId()));
                    return disclosed;
                }
                String oldCid = findExistingOmrCid(disclosed);
                List<CommandsOuterClass.DisclosedContract> substituted = substituteOmr(disclosed, best);
                LOG.info("[OMR-SAFETY-MARGIN] substituted requestId={} fromCid={} toCid={} round={} marginSec={}",
                        requestId,
                        abbreviate(oldCid),
                        abbreviate(best.contractId()),
                        best.roundNumber(),
                        Duration.between(Instant.now(), best.targetClosesAt()).toSeconds());
                return substituted;
            } catch (OmrSafetyMarginFailException ex) {
                throw ex;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted during safety-margin retry", ie);
            } catch (Exception ex) {
                LOG.warn("[OMR-SAFETY-MARGIN] graceful degradation on error requestId={}: {}",
                        requestId, ex.getMessage());
                return disclosed;
            }
        });
    }

    /**
     * POST scan-proxy /open-and-issuing-mining-rounds with empty cached-cid arrays
     * (forces fresh full snapshot). Auth header taken from {@link LedgerConfig}
     * — same plumbing as {@link TransferInstructionChoiceContextService#postChoiceContext}.
     */
    String fetchScanOmrResponse() throws Exception {
        List<RegistryRoutingConfig.RegistryEndpoint> registries = resolveRegistries();
        if (registries.isEmpty()) {
            throw new IllegalStateException(
                    "no scan registries configured (registry-routing absent and registryBaseUri blank)");
        }
        Map<String, String> providerResults = new LinkedHashMap<>();
        for (RegistryRoutingConfig.RegistryEndpoint ep : registries) {
            String base = ep.baseUri();
            if (base == null || base.isBlank()) continue;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String url = base + OMR_SUBPATH;
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(FETCH_BODY));
                if (ledgerConfig.getRegistryAuthHeader() != null && !ledgerConfig.getRegistryAuthHeader().isBlank()
                        && ledgerConfig.getRegistryAuthToken() != null && !ledgerConfig.getRegistryAuthToken().isBlank()) {
                    b.header(ledgerConfig.getRegistryAuthHeader(), ledgerConfig.getRegistryAuthToken());
                }
                HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    if (providerResults.size() > 0) {
                        LOG.info("[OMR-SAFETY-MARGIN] scan OMR fetch succeeded after {} fallback(s) base={}",
                                providerResults.size(), abbreviate(url));
                    }
                    return resp.body();
                }
                providerResults.put(abbreviate(url), "HTTP_" + resp.statusCode());
            } catch (Exception e) {
                providerResults.put(abbreviate(url), "ERROR: " + e.getMessage());
            }
        }
        throw new IllegalStateException(
                "scan OMR fetch failed all " + registries.size() + " registries: " + providerResults);
    }

    private List<RegistryRoutingConfig.RegistryEndpoint> resolveRegistries() {
        if (registryRouting.isPresent()) {
            List<RegistryRoutingConfig.RegistryEndpoint> list = registryRouting.get().getRegistriesToTry(null);
            if (!list.isEmpty()) return list;
        }
        String base = ledgerConfig.getRegistryBaseUri();
        if (base == null || base.isBlank()) return List.of();
        return List.of(new RegistryRoutingConfig.RegistryEndpoint(base, RegistryRoutingConfig.RegistryKind.SCAN, null));
    }

    List<ScanOmrEntry> parseScanResponse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode omrMap = root.path("open_mining_rounds");
        List<ScanOmrEntry> out = new ArrayList<>();
        if (!omrMap.isObject()) return out;
        Iterator<Map.Entry<String, JsonNode>> it = omrMap.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            ScanOmrEntry parsed = ScanOmrEntry.fromJson(e.getKey(), e.getValue());
            if (parsed != null) out.add(parsed);
        }
        return out;
    }

    // ────────────────────────────────────────────────────────────────────────
    // LOCKED-RULE — mirror in ScanOmrCache.applySelectionRule; change both together.
    // Phase 3F.10 Step A selection rule (preserved here as a thin typed wrapper around
    // the shared static helper). Pick the entry with opensAt <= now AND latest opensAt
    // AND targetClosesAt - now > SAFETY_MARGIN. Returns null if no entry satisfies —
    // caller retries or dead-sets.
    // ────────────────────────────────────────────────────────────────────────
    public static ScanOmrEntry selectBest(List<ScanOmrEntry> candidates, Instant now) {
        return ScanOmrCache.applySelectionRule(
                candidates, now, SAFETY_MARGIN,
                ScanOmrEntry::opensAt, ScanOmrEntry::targetClosesAt
        ).orElse(null);
    }

    /**
     * Phase 3F.10 Step B — convert a cache-side OmrEntry to the existing ScanOmrEntry
     * record consumed by {@link #substituteOmr}. Re-encodes createdEventBlob to base64
     * because substituteOmr's downstream call to {@code buildDisclosedContractFromScan}
     * base64-decodes it. INVARIANT_NOTE_2: known round-trip waste; deferred optimization.
     */
    private static ScanOmrEntry fromOmrEntry(ScanOmrCache.OmrEntry e) {
        return new ScanOmrEntry(
                e.contractId(),
                Base64.getEncoder().encodeToString(e.createdEventBlob()),
                e.templateId(),
                e.synchronizerId(),
                e.opensAt(),
                e.targetClosesAt(),
                e.roundNumber()
        );
    }

    static List<CommandsOuterClass.DisclosedContract> substituteOmr(
            List<CommandsOuterClass.DisclosedContract> disclosed, ScanOmrEntry best
    ) {
        List<CommandsOuterClass.DisclosedContract> updated = new ArrayList<>(disclosed);
        for (int i = 0; i < updated.size(); i++) {
            if (isOpenMiningRound(updated.get(i).getTemplateId())) {
                updated.set(i, buildDisclosedContractFromScan(best));
                return updated;
            }
        }
        LOG.warn("[OMR-SAFETY-MARGIN] no OMR found in disclosed list to substitute (size={}); keeping original",
                disclosed.size());
        return disclosed;
    }

    private static CommandsOuterClass.DisclosedContract buildDisclosedContractFromScan(ScanOmrEntry e) {
        String[] parts = e.templateId().split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid templateId format: " + e.templateId());
        }
        ValueOuterClass.Identifier tid = ValueOuterClass.Identifier.newBuilder()
                .setPackageId(parts[0])
                .setModuleName(parts[1])
                .setEntityName(parts[2])
                .build();
        byte[] blob = Base64.getDecoder().decode(e.createdEventBlob());
        CommandsOuterClass.DisclosedContract.Builder b = CommandsOuterClass.DisclosedContract.newBuilder()
                .setTemplateId(tid)
                .setContractId(e.contractId())
                .setCreatedEventBlob(ByteString.copyFrom(blob));
        if (e.synchronizerId() != null && !e.synchronizerId().isBlank()) {
            b.setSynchronizerId(e.synchronizerId());
        }
        return b.build();
    }

    private static String findExistingOmrCid(List<CommandsOuterClass.DisclosedContract> disclosed) {
        for (CommandsOuterClass.DisclosedContract dc : disclosed) {
            if (isOpenMiningRound(dc.getTemplateId())) {
                return dc.getContractId();
            }
        }
        return null;
    }

    /**
     * Parsed view of one entry under {@code open_mining_rounds.<cid>} of scan's
     * {@code /api/scan/v0/open-and-issuing-mining-rounds} response. Carries only
     * the fields the safety-margin filter and substitution need.
     */
    public record ScanOmrEntry(
            String contractId,
            String createdEventBlob,
            String templateId,
            String synchronizerId,
            Instant opensAt,
            Instant targetClosesAt,
            long roundNumber
    ) {
        static ScanOmrEntry fromJson(String mapKey, JsonNode entryNode) {
            try {
                JsonNode contract = entryNode.path("contract");
                JsonNode payload = contract.path("payload");
                String cid = contract.path("contract_id").asText(null);
                if (cid == null || cid.isBlank()) cid = mapKey;
                String blob = contract.path("created_event_blob").asText(null);
                String tmpl = contract.path("template_id").asText(null);
                String syncId = entryNode.path("domain_id").asText(null);
                JsonNode opensNode = payload.path("opensAt");
                JsonNode closesNode = payload.path("targetClosesAt");
                if (cid == null || blob == null || tmpl == null
                        || opensNode.isMissingNode() || closesNode.isMissingNode()) {
                    return null;
                }
                Instant opensAt = Instant.parse(opensNode.asText());
                Instant closesAt = Instant.parse(closesNode.asText());
                long roundNum = -1;
                JsonNode roundNode = payload.path("round").path("number");
                if (!roundNode.isMissingNode()) {
                    try { roundNum = Long.parseLong(roundNode.asText()); } catch (NumberFormatException ignore) {}
                }
                return new ScanOmrEntry(cid, blob, tmpl, syncId, opensAt, closesAt, roundNum);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
