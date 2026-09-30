package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.ErrorResponse;
import com.digitalasset.quickstart.ledger.LedgerApi;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.stream.Collectors;
import com.daml.ledger.api.v2.ValueOuterClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import splice_api_token_holding_v1.Identifiers;

/**
 * Debug-only endpoint to dump raw CIP-0056 holding interface views for a party.
 * This returns the raw interface view records (including create arguments) without normalization.
 */
@Profile("local")
@RestController
@RequestMapping("/api/debug/raw-holdings")
public class HoldingsDebugController {

    private static final Logger LOGGER = LoggerFactory.getLogger(HoldingsDebugController.class);

    private final LedgerApi ledgerApi;

    public HoldingsDebugController(final LedgerApi ledgerApi) {
        this.ledgerApi = ledgerApi;
    }

    @GetMapping("/{partyId}")
    @WithSpan
    public CompletableFuture<ResponseEntity<?>> getRawHoldings(@PathVariable("partyId") String partyId) {
        return ledgerApi.getInterfaceViewsForParty(Identifiers.Splice_Api_Token_HoldingV1__Holding, partyId)
                .<ResponseEntity<?>>handle((views, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to load raw holdings for {}", partyId, throwable);
                        ErrorResponse err = new ErrorResponse(
                                "holdings-debug-error",
                                throwable.getMessage(),
                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                String.format("/api/debug/raw-holdings/%s", partyId)
                        );
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
                    }
                    List<RawInterfaceViewDto> payload = views.stream()
                            .map(iv -> new RawInterfaceViewDto(
                                    iv.contractId(),
                                    toJson(iv.viewValue()),
                                    toJson(iv.createArguments())
                            ))
                            .collect(Collectors.toList());
                    return ResponseEntity.ok(payload);
                });
    }

    private String toJson(final ValueOuterClass.Record record) {
        if (record == null) {
            return null;
        }
        // Fall back to proto text format; this is a debug endpoint, no strict JSON needed.
        return record.toString();
    }

    public record RawInterfaceViewDto(
            String contractId,
            String interfaceViewJson,
            String createArgumentsJson
    ) { }
}

