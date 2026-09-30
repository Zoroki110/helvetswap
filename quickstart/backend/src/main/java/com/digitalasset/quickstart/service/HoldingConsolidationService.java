package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Scheduled job that consolidates (merges) small Token holdings for the operator party.
 *
 * Over time, fee dust and change from swaps accumulate as many small Holding/Token
 * contracts. This service groups them by instrument and exercises Token.Merge to
 * reduce the contract count, keeping ACS queries fast and saving gas.
 *
 * Runs hourly. Only merges groups with more than {@link #MERGE_THRESHOLD} holdings.
 */
@Service
public class HoldingConsolidationService {

    private static final Logger LOG = LoggerFactory.getLogger(HoldingConsolidationService.class);
    private static final int MERGE_THRESHOLD = 3;

    private final LedgerApi ledgerApi;
    private final AuthUtils authUtils;

    @Value("${holdingpool.package-id:}")
    private String packageId;

    @Value("${clearportx.consolidation.enabled:true}")
    private boolean enabled;

    public HoldingConsolidationService(LedgerApi ledgerApi, AuthUtils authUtils) {
        this.ledgerApi = ledgerApi;
        this.authUtils = authUtils;
    }

    @Scheduled(fixedDelay = 3600000)  // every hour
    public void consolidate() {
        if (!enabled) return;
        if (packageId == null || packageId.isBlank()) {
            LOG.debug("[UTXO Merge] Skipping: no package-id configured");
            return;
        }

        String operator = authUtils.getAppProviderPartyId();
        try {
            var acs = ledgerApi.getActiveContractsRawForParty(operator).join();

            // Group Token contracts by (admin, instrumentId)
            Map<String, List<TokenEntry>> groups = new LinkedHashMap<>();
            acs.stream()
                .filter(rac -> "Token".equals(rac.templateId().getEntityName()))
                .forEach(rac -> {
                    try {
                        var args = rac.createArguments();
                        String admin = extractParty(args, "admin");
                        String instrumentId = extractText(args, "id");
                        String amount = extractNumeric(args, "amount");
                        if (admin == null || instrumentId == null) return;
                        String key = admin + "/" + instrumentId;
                        groups.computeIfAbsent(key, k -> new ArrayList<>())
                                .add(new TokenEntry(rac.contractId(), rac.templateId(), amount));
                    } catch (Exception e) {
                        LOG.debug("[UTXO Merge] Skipping contract {}: {}", rac.contractId(), e.getMessage());
                    }
                });

            int totalMerged = 0;
            for (var entry : groups.entrySet()) {
                List<TokenEntry> tokens = entry.getValue();
                if (tokens.size() <= MERGE_THRESHOLD) continue;

                // Sort smallest-first for sequential merge
                tokens.sort(Comparator.comparing(t -> new java.math.BigDecimal(t.amount != null ? t.amount : "0")));

                LOG.info("[UTXO Merge] Merging {} '{}' holdings", tokens.size(), entry.getKey());

                // Iteratively merge: exercise Token.Merge on token[0] with token[1], get new CID, repeat
                String currentCid = tokens.get(0).contractId;
                ValueOuterClass.Identifier currentTid = tokens.get(0).templateId;

                for (int i = 1; i < tokens.size(); i++) {
                    String otherCid = tokens.get(i).contractId;
                    try {
                        ValueOuterClass.Record mergeArg = ValueOuterClass.Record.newBuilder()
                                .addFields(ValueOuterClass.RecordField.newBuilder()
                                        .setLabel("otherTokenCid")
                                        .setValue(ValueOuterClass.Value.newBuilder().setContractId(otherCid).build())
                                        .build())
                                .build();

                        var resp = ledgerApi.exerciseRaw(
                                currentTid,
                                currentCid,
                                "Merge",
                                mergeArg,
                                List.of(operator),
                                List.of(operator),
                                List.of()
                        ).join();

                        // Extract the created Token CID from the response
                        String newCid = extractCreatedCid(resp);
                        if (newCid != null) {
                            currentCid = newCid;
                        }
                        totalMerged++;
                    } catch (Exception e) {
                        LOG.warn("[UTXO Merge] Merge failed for {} + {}: {}", currentCid, otherCid, e.getMessage());
                        break;
                    }
                }
                LOG.info("[UTXO Merge] Merged {} '{}' holdings into 1", tokens.size(), entry.getKey());
            }

            if (totalMerged > 0) {
                LOG.info("[UTXO Merge] Consolidation complete: {} merge operations across {} groups",
                        totalMerged, groups.values().stream().filter(l -> l.size() > MERGE_THRESHOLD).count());
            } else {
                LOG.debug("[UTXO Merge] No consolidation needed (all groups <= {} holdings)", MERGE_THRESHOLD);
            }
        } catch (Exception e) {
            LOG.error("[UTXO Merge] Consolidation cycle failed", e);
        }
    }

    private String extractCreatedCid(com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp) {
        if (resp == null || !resp.hasTransaction()) return null;
        for (var event : resp.getTransaction().getEventsList()) {
            if (!event.hasCreated()) continue;
            if ("Token".equals(event.getCreated().getTemplateId().getEntityName())) {
                return event.getCreated().getContractId();
            }
        }
        return null;
    }

    private String extractText(ValueOuterClass.Record args, String label) {
        return args.getFieldsList().stream()
                .filter(f -> f.getLabel().equals(label))
                .map(f -> f.getValue().getText())
                .findFirst().orElse(null);
    }

    private String extractParty(ValueOuterClass.Record args, String label) {
        return args.getFieldsList().stream()
                .filter(f -> f.getLabel().equals(label))
                .map(f -> f.getValue().getParty())
                .findFirst().orElse(null);
    }

    private String extractNumeric(ValueOuterClass.Record args, String label) {
        return args.getFieldsList().stream()
                .filter(f -> f.getLabel().equals(label))
                .map(f -> f.getValue().getNumeric())
                .findFirst().orElse("0");
    }

    private record TokenEntry(String contractId, ValueOuterClass.Identifier templateId, String amount) {}
}
