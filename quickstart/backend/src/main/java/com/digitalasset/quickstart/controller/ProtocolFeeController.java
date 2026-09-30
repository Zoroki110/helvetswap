package com.digitalasset.quickstart.controller;

import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.dto.ErrorResponse;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Admin-only endpoint for querying and withdrawing accumulated protocol fees.
 *
 * Protocol fee contracts (ProtocolFeeCollector) accumulate a share of swap fees
 * and can be withdrawn by the pool operator.
 */
@RestController
@RequestMapping("/api/admin/protocol-fees")
public class ProtocolFeeController {

    private static final Logger LOG = LoggerFactory.getLogger(ProtocolFeeController.class);
    private static final String MODULE_NAME = "AMM.ProtocolFees";
    private static final String ENTITY_NAME = "ProtocolFeeCollector";

    private final LedgerApi ledgerApi;
    private final AuthUtils authUtils;

    @Value("${holdingpool.package-id:}")
    private String packageId;

    public ProtocolFeeController(LedgerApi ledgerApi, AuthUtils authUtils) {
        this.ledgerApi = ledgerApi;
        this.authUtils = authUtils;
    }

    /**
     * List accumulated protocol fees visible to the operator.
     */
    @GetMapping
    @PreAuthorize("@partyGuard.isPoolParty(#jwt)")
    public ResponseEntity<?> listFees(@AuthenticationPrincipal Jwt jwt) {
        String operator = authUtils.getAppProviderPartyId();
        try {
            var acs = ledgerApi.getActiveContractsRawForParty(operator).join();
            ValueOuterClass.Identifier tid = templateId();
            List<Map<String, Object>> fees = new ArrayList<>();

            acs.stream()
                .filter(rac -> matchesTemplate(rac.templateId(), tid))
                .forEach(rac -> {
                    try {
                        var args = rac.createArguments();
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("contractId", rac.contractId());
                        entry.put("poolId", extractText(args, "poolId"));
                        entry.put("tokenSymbol", extractText(args, "tokenSymbol"));
                        entry.put("accumulatedFees", extractNumeric(args, "accumulatedFees"));
                        entry.put("lastCollectionTime", extractTimestamp(args, "lastCollectionTime"));
                        fees.add(entry);
                    } catch (Exception e) {
                        LOG.warn("Failed to parse ProtocolFeeCollector {}: {}", rac.contractId(), e.getMessage());
                    }
                });

            return ResponseEntity.ok(Map.of("fees", fees, "count", fees.size()));
        } catch (Exception e) {
            LOG.error("Failed to list protocol fees", e);
            return ResponseEntity.status(500).body(new ErrorResponse("INTERNAL", e.getMessage(), 500, "/api/admin/protocol-fees"));
        }
    }

    /**
     * Withdraw accumulated protocol fees from a specific collector.
     */
    @PostMapping("/withdraw")
    @PreAuthorize("@partyGuard.isPoolParty(#jwt)")
    public ResponseEntity<?> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody WithdrawRequest request
    ) {
        if (request.contractId == null || request.contractId.isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION", "contractId is required", 400, "/api/admin/protocol-fees/withdraw"));
        }

        String operator = authUtils.getAppProviderPartyId();
        try {
            ValueOuterClass.Identifier tid = templateId();

            // WithdrawProtocolFees choice argument: { recipient = operator }
            ValueOuterClass.Record choiceArg = ValueOuterClass.Record.newBuilder()
                    .addFields(ValueOuterClass.RecordField.newBuilder()
                            .setLabel("recipient")
                            .setValue(ValueOuterClass.Value.newBuilder().setParty(operator).build())
                            .build())
                    .build();

            var resp = ledgerApi.exerciseRaw(
                    tid,
                    request.contractId,
                    "WithdrawProtocolFees",
                    choiceArg,
                    List.of(operator),
                    List.of(operator),
                    List.of()
            ).join();

            LOG.info("[ProtocolFee] Withdrew fees from {} for operator {}", request.contractId, operator);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "contractId", request.contractId,
                    "withdrawnBy", operator
            ));
        } catch (Exception e) {
            LOG.error("Protocol fee withdrawal failed for {}: {}", request.contractId, e.getMessage(), e);
            return ResponseEntity.status(500).body(new ErrorResponse("INTERNAL", e.getMessage(), 500, "/api/admin/protocol-fees/withdraw"));
        }
    }

    private ValueOuterClass.Identifier templateId() {
        return ValueOuterClass.Identifier.newBuilder()
                .setPackageId(packageId)
                .setModuleName(MODULE_NAME)
                .setEntityName(ENTITY_NAME)
                .build();
    }

    private boolean matchesTemplate(ValueOuterClass.Identifier actual, ValueOuterClass.Identifier expected) {
        return actual.getModuleName().equals(expected.getModuleName())
                && actual.getEntityName().equals(expected.getEntityName());
    }

    private String extractText(ValueOuterClass.Record args, String label) {
        return args.getFieldsList().stream()
                .filter(f -> f.getLabel().equals(label))
                .map(f -> f.getValue().getText())
                .findFirst().orElse(null);
    }

    private String extractNumeric(ValueOuterClass.Record args, String label) {
        return args.getFieldsList().stream()
                .filter(f -> f.getLabel().equals(label))
                .map(f -> f.getValue().getNumeric())
                .findFirst().orElse("0");
    }

    private String extractTimestamp(ValueOuterClass.Record args, String label) {
        return args.getFieldsList().stream()
                .filter(f -> f.getLabel().equals(label))
                .map(f -> String.valueOf(f.getValue().getTimestamp()))
                .findFirst().orElse(null);
    }

    public static class WithdrawRequest {
        public String contractId;
    }
}
