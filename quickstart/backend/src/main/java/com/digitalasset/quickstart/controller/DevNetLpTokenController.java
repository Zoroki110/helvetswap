package com.digitalasset.quickstart.controller;

import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devnet/lp")
@Profile("devnet")
public class DevNetLpTokenController {

    private static final Logger LOG = LoggerFactory.getLogger(DevNetLpTokenController.class);
    private final LedgerApi ledgerApi;
    private final AuthUtils authUtils;

    public DevNetLpTokenController(final LedgerApi ledgerApi, final AuthUtils authUtils) {
        this.ledgerApi = ledgerApi;
        this.authUtils = authUtils;
    }

    /**
     * POST /api/devnet/lp/archive
     * Archive LPToken contracts for a given owner (optionally filter by poolId).
     */
    @PostMapping("/archive")
    @WithSpan
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> archiveLpTokens(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody ArchiveLpTokensRequest request
    ) {
        Map<String, Object> body = new HashMap<>();
        if (request == null || request.ownerParty == null || request.ownerParty.isBlank()) {
            body.put("ok", false);
            body.put("error", "ownerParty is required");
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(body));
        }
        String operator = authUtils.getAppProviderPartyId();
        String ownerParty = request.ownerParty;
        String poolIdFilter = request.poolCid != null && !request.poolCid.isBlank() ? request.poolCid : null;

        return ledgerApi.getActiveContractsRawForParty(operator)
                .thenCompose(acs -> {
                    List<LedgerApi.RawActiveContract> candidates = acs.stream()
                            .filter(c -> isLpToken(c.templateId()))
                            .filter(c -> ownerParty.equals(readPartyField(c.createArguments(), "owner", 1)))
                            .filter(c -> poolIdFilter == null || poolIdFilter.equals(readTextField(c.createArguments(), "poolId", 2)))
                            .toList();

                    if (candidates.isEmpty()) {
                        body.put("ok", true);
                        body.put("archivedCount", 0);
                        body.put("archived", List.of());
                        body.put("skipped", List.of());
                        return CompletableFuture.completedFuture(ResponseEntity.ok(body));
                    }

                    List<CompletableFuture<ArchiveResult>> futures = new ArrayList<>();
                    for (LedgerApi.RawActiveContract c : candidates) {
                        ValueOuterClass.Record empty = ValueOuterClass.Record.newBuilder().build();
                        futures.add(
                                ledgerApi.exerciseRaw(
                                                c.templateId(),
                                                c.contractId(),
                                                "Archive",
                                                empty,
                                                List.of(operator),
                                                List.of(),
                                                List.of()
                                        )
                                        .handle((CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp, Throwable err) -> {
                                            if (err != null) {
                                                return new ArchiveResult(c.contractId(), false, err.getMessage());
                                            }
                                            return new ArchiveResult(c.contractId(), true, null);
                                        })
                        );
                    }

                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(ignored -> {
                                List<String> archived = new ArrayList<>();
                                List<Map<String, String>> failed = new ArrayList<>();
                                for (CompletableFuture<ArchiveResult> f : futures) {
                                    ArchiveResult r = f.join();
                                    if (r.ok) {
                                        archived.add(r.contractId);
                                    } else {
                                        failed.add(Map.of("contractId", r.contractId, "error", r.error));
                                    }
                                }
                                body.put("ok", failed.isEmpty());
                                body.put("archivedCount", archived.size());
                                body.put("archived", archived);
                                body.put("failed", failed);
                                return ResponseEntity.ok(body);
                            });
                })
                .exceptionally(ex -> {
                    body.put("ok", false);
                    body.put("error", ex.getMessage());
                    return ResponseEntity.internalServerError().body(body);
                });
    }

    private boolean isLpToken(ValueOuterClass.Identifier id) {
        return id != null
                && "LPToken.LPToken".equals(id.getModuleName())
                && "LPToken".equals(id.getEntityName());
    }

    private String readPartyField(ValueOuterClass.Record record, String label, int indexFallback) {
        if (record == null) return null;
        if (label != null) {
            for (ValueOuterClass.RecordField field : record.getFieldsList()) {
                if (label.equals(field.getLabel()) && field.getValue().hasParty()) {
                    return field.getValue().getParty();
                }
            }
        }
        if (indexFallback >= 0 && record.getFieldsCount() > indexFallback) {
            ValueOuterClass.Value value = record.getFields(indexFallback).getValue();
            if (value.hasParty()) {
                return value.getParty();
            }
        }
        return null;
    }

    private String readTextField(ValueOuterClass.Record record, String label, int indexFallback) {
        if (record == null) return null;
        if (label != null) {
            for (ValueOuterClass.RecordField field : record.getFieldsList()) {
                if (label.equals(field.getLabel()) && field.getValue().hasText()) {
                    return field.getValue().getText();
                }
            }
        }
        if (indexFallback >= 0 && record.getFieldsCount() > indexFallback) {
            ValueOuterClass.Value value = record.getFields(indexFallback).getValue();
            if (value.hasText()) {
                return value.getText();
            }
        }
        return null;
    }

    public static class ArchiveLpTokensRequest {
        public String ownerParty;
        public String poolCid;
    }

    private record ArchiveResult(String contractId, boolean ok, String error) {}
}

