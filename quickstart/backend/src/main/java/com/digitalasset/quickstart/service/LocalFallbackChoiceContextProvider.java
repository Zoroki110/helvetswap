package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.ledger.LedgerApi.RawActiveContractWithBlob;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.TransferInstructionChoiceContextService.ChoiceContextResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local fallback provider for TransferInstruction choice-context disclosures.
 *
 * When all Scan registries are stale/unavailable, this provider builds the required
 * disclosed contracts (OpenMiningRound, AmuletRules) from the local participant's ACS.
 *
 * Feature-flagged via clearportx.choice-context.local-fallback.enabled (default false).
 *
 * Discovery: At startup, auto-discovers which party on the local participant has
 * visibility to OpenMiningRound contracts. The app-provider party may not see OMR
 * (network-level contract), but the validator operator party typically does.
 */
@Service
@Profile("prod")
public class LocalFallbackChoiceContextProvider {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFallbackChoiceContextProvider.class);

    private static final String OMR_MODULE = "Splice.Round";
    private static final String OMR_ENTITY = "OpenMiningRound";
    private static final String AMULET_RULES_MODULE = "Splice.AmuletRules";
    private static final String AMULET_RULES_ENTITY = "AmuletRules";

    private static final long DISCOVERY_TIMEOUT_SECONDS = 30;
    private static final long QUERY_TIMEOUT_SECONDS = 15;

    private final LedgerApi ledgerApi;
    private final AuthUtils authUtils;

    @Value("${clearportx.choice-context.local-fallback.enabled:false}")
    private boolean enabled;

    @Value("${clearportx.choice-context.local-fallback.omr-visibility-party:}")
    private String configuredOmrParty;

    @Value("${clearportx.choice-context.local-fallback.auto-discover:true}")
    private boolean autoDiscover;

    /** Discovered party with OMR visibility (null = not yet discovered or no party found) */
    private final AtomicReference<String> omrVisibilityParty = new AtomicReference<>(null);

    /** Whether discovery has completed (regardless of success) */
    private volatile boolean discoveryComplete = false;

    /** Reason if discovery failed */
    private volatile String discoveryFailureReason = null;

    public LocalFallbackChoiceContextProvider(LedgerApi ledgerApi, AuthUtils authUtils) {
        this.ledgerApi = ledgerApi;
        this.authUtils = authUtils;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            LOG.info("[ChoiceContext-LocalFallback] Disabled (clearportx.choice-context.local-fallback.enabled=false)");
            discoveryComplete = true;
            return;
        }

        if (configuredOmrParty != null && !configuredOmrParty.isBlank()) {
            omrVisibilityParty.set(configuredOmrParty);
            discoveryComplete = true;
            LOG.info("[ChoiceContext-LocalFallback] Using configured OMR visibility party: {}",
                    abbreviate(configuredOmrParty));
            return;
        }

        if (!autoDiscover) {
            LOG.info("[ChoiceContext-LocalFallback] Auto-discover disabled and no party configured; local fallback will not work");
            discoveryComplete = true;
            discoveryFailureReason = "AUTO_DISCOVER_DISABLED_NO_PARTY_CONFIGURED";
            return;
        }

        // Run discovery async to avoid blocking startup
        CompletableFuture.runAsync(this::discoverOmrParty);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDiscoveryStatus() {
        if (!enabled) return "DISABLED";
        if (!discoveryComplete) return "DISCOVERING";
        if (omrVisibilityParty.get() != null) return "READY";
        return "NO_OMR_PARTY";
    }

    /**
     * Attempt to resolve choice-context locally from the participant's ACS.
     *
     * @return ChoiceContextResult if successful, null if unable to produce disclosures
     */
    public ChoiceContextResult resolveLocally(String contractId, String adminHint, String requestId) {
        if (!enabled) {
            LOG.debug("[ChoiceContext-LocalFallback] Disabled, skipping");
            return null;
        }

        if (!discoveryComplete) {
            LOG.warn("[ChoiceContext-LocalFallback] Discovery not yet complete, skipping for requestId={}",
                    requestIdOrDefault(requestId));
            return null;
        }

        String party = omrVisibilityParty.get();
        if (party == null) {
            LOG.warn("[ChoiceContext-LocalFallback] No OMR visibility party available, cannot provide local fallback. " +
                    "Reason: {}", discoveryFailureReason);
            return null;
        }

        try {
            // Query OMR contracts from local ACS
            List<RawActiveContractWithBlob> omrContracts = ledgerApi
                    .getActiveContractsWithBlobForPartyByTemplate(party, OMR_MODULE, OMR_ENTITY)
                    .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (omrContracts.isEmpty()) {
                LOG.warn("[ChoiceContext-LocalFallback] No OpenMiningRound contracts found for party={} requestId={}",
                        abbreviate(party), requestIdOrDefault(requestId));
                return null;
            }

            // Select best OMR (highest round number, deterministic)
            RawActiveContractWithBlob bestOmr = selectBestOmr(omrContracts);
            if (bestOmr == null || bestOmr.createdEventBlob() == null || bestOmr.createdEventBlob().isEmpty()) {
                LOG.warn("[ChoiceContext-LocalFallback] Best OMR has no createdEventBlob, requestId={}",
                        requestIdOrDefault(requestId));
                return null;
            }

            // Query AmuletRules from local ACS
            List<RawActiveContractWithBlob> amuletRulesContracts = ledgerApi
                    .getActiveContractsWithBlobForPartyByTemplate(party, AMULET_RULES_MODULE, AMULET_RULES_ENTITY)
                    .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Build disclosed contracts list
            List<CommandsOuterClass.DisclosedContract> disclosed = new ArrayList<>();

            // Add OMR
            CommandsOuterClass.DisclosedContract omrDisclosed = buildDisclosedContract(bestOmr);
            if (omrDisclosed != null) {
                disclosed.add(omrDisclosed);
            }

            // Add AmuletRules (if available)
            if (!amuletRulesContracts.isEmpty()) {
                RawActiveContractWithBlob amuletRules = amuletRulesContracts.get(0);
                if (amuletRules.createdEventBlob() != null && !amuletRules.createdEventBlob().isEmpty()) {
                    CommandsOuterClass.DisclosedContract arDisclosed = buildDisclosedContract(amuletRules);
                    if (arDisclosed != null) {
                        disclosed.add(arDisclosed);
                    }
                }
            }

            if (disclosed.isEmpty()) {
                LOG.warn("[ChoiceContext-LocalFallback] Failed to build any disclosed contracts, requestId={}",
                        requestIdOrDefault(requestId));
                return null;
            }

            // Build choiceContextData with openMiningRoundCid
            ValueOuterClass.Record extraArgs = buildLocalExtraArgs(bestOmr.contractId());

            String synchronizerId = bestOmr.synchronizerId();

            LOG.info("[ChoiceContext] source=LOCAL_FALLBACK omrParty={} syncId={} omrCid={} " +
                            "amuletRulesCid={} disclosedCount={} requestId={}",
                    abbreviate(party),
                    abbreviate(synchronizerId),
                    abbreviate(bestOmr.contractId()),
                    amuletRulesContracts.isEmpty() ? "none" : abbreviate(amuletRulesContracts.get(0).contractId()),
                    disclosed.size(),
                    requestIdOrDefault(requestId));

            return new ChoiceContextResult(
                    disclosed,
                    extraArgs,
                    1, // contextKeyCount (openMiningRoundCid)
                    "LOCAL_FALLBACK",
                    List.of("LOCAL_FALLBACK"),
                    synchronizerId
            );

        } catch (Exception e) {
            LOG.warn("[ChoiceContext-LocalFallback] Failed to resolve locally for requestId={}: {}",
                    requestIdOrDefault(requestId), e.getMessage());
            return null;
        }
    }

    /**
     * Auto-discover which party on the local participant has OMR visibility.
     * Tries app-provider first, then all other parties.
     */
    private void discoverOmrParty() {
        try {
            LOG.info("[ChoiceContext-LocalFallback] Starting OMR visibility party discovery...");

            String appProviderParty = authUtils.getAppProviderPartyId();

            // Try app-provider party first
            if (appProviderParty != null && !appProviderParty.isBlank()) {
                if (partyHasOmrVisibility(appProviderParty)) {
                    omrVisibilityParty.set(appProviderParty);
                    discoveryComplete = true;
                    LOG.info("[ChoiceContext] OMR_DISCOVERY party={} omrCount=visible (app-provider)",
                            abbreviate(appProviderParty));
                    return;
                }
                LOG.info("[ChoiceContext-LocalFallback] App-provider party {} has no OMR visibility, trying wildcard discovery",
                        abbreviate(appProviderParty));
            }

            // Wildcard: query all contracts with blob for app-provider to find OMR
            // If the participant hosts other parties (e.g., validator operator),
            // we need to check those too. But we don't have PartyManagementService wired in.
            // So we try the app-provider's ACS with wildcard and check for OMR templates.
            List<RawActiveContractWithBlob> allContracts = ledgerApi
                    .getActiveContractsWithBlobForParty(appProviderParty)
                    .get(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<RawActiveContractWithBlob> omrFromWildcard = allContracts.stream()
                    .filter(ac -> isOpenMiningRound(ac.templateId()))
                    .filter(ac -> ac.createdEventBlob() != null && !ac.createdEventBlob().isEmpty())
                    .toList();

            if (!omrFromWildcard.isEmpty()) {
                omrVisibilityParty.set(appProviderParty);
                discoveryComplete = true;
                LOG.info("[ChoiceContext] OMR_DISCOVERY party={} omrCount={} (wildcard match)",
                        abbreviate(appProviderParty), omrFromWildcard.size());
                return;
            }

            // No OMR found via any method
            discoveryComplete = true;
            discoveryFailureReason = "NO_PARTY_HAS_OMR_VISIBILITY";
            LOG.warn("[ChoiceContext] OMR_DISCOVERY_FAILED no party has OpenMiningRound visibility on this participant. " +
                    "Local fallback will not work. Set SWAP_OMR_VISIBILITY_PARTY if a different party should be used.");

        } catch (Exception e) {
            discoveryComplete = true;
            discoveryFailureReason = "DISCOVERY_ERROR: " + e.getMessage();
            LOG.warn("[ChoiceContext-LocalFallback] Discovery failed: {}", e.getMessage());
        }
    }

    private boolean partyHasOmrVisibility(String party) {
        try {
            List<RawActiveContractWithBlob> omrContracts = ledgerApi
                    .getActiveContractsWithBlobForPartyByTemplate(party, OMR_MODULE, OMR_ENTITY)
                    .get(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return !omrContracts.isEmpty() &&
                    omrContracts.stream().anyMatch(ac -> ac.createdEventBlob() != null && !ac.createdEventBlob().isEmpty());
        } catch (Exception e) {
            LOG.debug("[ChoiceContext-LocalFallback] Failed to check OMR visibility for party {}: {}",
                    abbreviate(party), e.getMessage());
            return false;
        }
    }

    private RawActiveContractWithBlob selectBestOmr(List<RawActiveContractWithBlob> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // Try to select by highest round number (same logic as OpenMiningRoundRefreshService)
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
            LOG.debug("[ChoiceContext-LocalFallback] Selected OMR by round number: round={} cid={}",
                    bestRound, abbreviate(best.contractId()));
            return best;
        }

        // Fallback: lexicographically greatest CID
        return candidates.stream()
                .max(java.util.Comparator.comparing(RawActiveContractWithBlob::contractId))
                .orElse(null);
    }

    private static long extractRoundNumber(ValueOuterClass.Record args) {
        if (args == null) return Long.MIN_VALUE;
        for (ValueOuterClass.RecordField field : args.getFieldsList()) {
            if ("round".equals(field.getLabel())) {
                ValueOuterClass.Value val = field.getValue();
                if (val.hasRecord() && val.getRecord().getFieldsCount() > 0) {
                    ValueOuterClass.Value inner = val.getRecord().getFields(0).getValue();
                    if (inner.hasInt64()) return inner.getInt64();
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

    private static CommandsOuterClass.DisclosedContract buildDisclosedContract(RawActiveContractWithBlob ac) {
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
            LOG.warn("[ChoiceContext-LocalFallback] Failed to build DisclosedContract: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build extraArgs (choiceContextData) with openMiningRoundCid for the TI Accept choice.
     * Mirrors the structure built by TransferInstructionChoiceContextService.buildExtraArgs().
     */
    private static ValueOuterClass.Record buildLocalExtraArgs(String omrCid) {
        // Build the AV_ContractId variant for openMiningRoundCid
        ValueOuterClass.Value omrContractIdVariant = ValueOuterClass.Value.newBuilder()
                .setVariant(ValueOuterClass.Variant.newBuilder()
                        .setConstructor("AV_ContractId")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setContractId(omrCid)
                                .build())
                        .build())
                .build();

        ValueOuterClass.TextMap contextValues = ValueOuterClass.TextMap.newBuilder()
                .addEntries(ValueOuterClass.TextMap.Entry.newBuilder()
                        .setKey("openMiningRoundCid")
                        .setValue(omrContractIdVariant)
                        .build())
                .build();

        ValueOuterClass.Record choiceContext = ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("values")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setTextMap(contextValues)
                                .build())
                        .build())
                .build();

        ValueOuterClass.Record emptyMeta = ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("values")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setTextMap(ValueOuterClass.TextMap.newBuilder().build())
                                .build())
                        .build())
                .build();

        return ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("context")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setRecord(choiceContext)
                                .build())
                        .build())
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("meta")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setRecord(emptyMeta)
                                .build())
                        .build())
                .build();
    }

    private static boolean isOpenMiningRound(ValueOuterClass.Identifier templateId) {
        if (templateId == null) return false;
        return OMR_MODULE.equals(templateId.getModuleName())
                && OMR_ENTITY.equals(templateId.getEntityName());
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        if (s.length() <= 30) return s;
        return s.substring(0, 15) + "..." + s.substring(s.length() - 10);
    }

    private static String requestIdOrDefault(String requestId) {
        return requestId == null || requestId.isBlank() ? "n/a" : requestId;
    }
}
