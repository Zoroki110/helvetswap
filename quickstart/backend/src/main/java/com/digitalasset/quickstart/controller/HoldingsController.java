package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.LedgerVisibilityError;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.ErrorResponse;
import com.digitalasset.quickstart.dto.HoldingDto;
import com.digitalasset.quickstart.dto.HoldingUtxoDto;
import com.digitalasset.quickstart.service.HoldingsService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/holdings")
public class HoldingsController {

    private final HoldingsService holdingsService;

    public HoldingsController(final HoldingsService holdingsService) {
        this.holdingsService = holdingsService;
    }

    @GetMapping("/{partyId}")
    @WithSpan
    @PreAuthorize("isAuthenticated()")
    public CompletableFuture<ResponseEntity<?>> getHoldings(@PathVariable("partyId") String partyId) {
        return holdingsService.getHoldingsByParty(partyId)
                .thenApply(result -> toHttpResponse(result, partyId));
    }

    @GetMapping("/{partyId}/utxos")
    @WithSpan
    @PreAuthorize("isAuthenticated()")
    public CompletableFuture<ResponseEntity<?>> getHoldingUtxos(
            @PathVariable("partyId") String partyId,
            @RequestParam(value = "ownerOnly", required = false, defaultValue = "true") boolean ownerOnly
    ) {
        return holdingsService.getHoldingUtxos(partyId)
                .thenApply(result -> toUtxoResponse(result, partyId, ownerOnly));
    }

    private ResponseEntity<?> toHttpResponse(final Result<List<HoldingDto>, DomainError> result,
                                             final String partyId) {
        if (result.isOk()) {
            return ResponseEntity.ok(result.getValueUnsafe());
        }
        return mapDomainErrorToResponse(result.getErrorUnsafe(), partyId);
    }

    private ResponseEntity<?> toUtxoResponse(final Result<List<HoldingUtxoDto>, DomainError> result,
                                             final String partyId,
                                             final boolean ownerOnly) {
        if (result.isOk()) {
            List<HoldingUtxoDto> utxos = result.getValueUnsafe();
            if (ownerOnly) {
                utxos = utxos.stream()
                        .filter(utxo -> partyId.equals(utxo.owner))
                        .toList();
            }
            return ResponseEntity.ok(utxos);
        }
        return mapDomainErrorToResponse(result.getErrorUnsafe(), partyId + "/utxos");
    }

    private ResponseEntity<ErrorResponse> mapDomainErrorToResponse(final DomainError error,
                                                                   final String partyId) {
        HttpStatus status = mapStatus(error);
        String path = String.format("/api/holdings/%s", partyId);
        ErrorResponse payload = new ErrorResponse(error.code(), error.message(), status.value(), path);
        return ResponseEntity.status(status).body(payload);
    }

    private HttpStatus mapStatus(final DomainError error) {
        if (error instanceof ValidationError validationError) {
            return validationError.type() == ValidationError.Type.AUTHENTICATION
                    ? HttpStatus.UNAUTHORIZED
                    : HttpStatus.BAD_REQUEST;
        }
        if (error instanceof LedgerVisibilityError) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (error instanceof UnexpectedError) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        HttpStatus derived = HttpStatus.resolve(error.httpStatus());
        return derived != null ? derived : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
