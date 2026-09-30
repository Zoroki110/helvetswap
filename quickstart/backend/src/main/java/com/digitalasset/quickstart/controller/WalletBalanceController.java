package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.LedgerVisibilityError;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.ErrorResponse;
import com.digitalasset.quickstart.dto.TokenBalanceDto;
import com.digitalasset.quickstart.service.WalletBalanceService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallet/balances")
public class WalletBalanceController {

    private final WalletBalanceService walletBalanceService;

    public WalletBalanceController(final WalletBalanceService walletBalanceService) {
        this.walletBalanceService = walletBalanceService;
    }

    @GetMapping("/{partyId}")
    @PreAuthorize("isAuthenticated()")
    @WithSpan
    public CompletableFuture<ResponseEntity<?>> getBalances(@PathVariable("partyId") String partyId) {
        return walletBalanceService.getLegacyBalances(partyId)
                .thenApply(result -> toHttpResponse(result, partyId));
    }

    private ResponseEntity<?> toHttpResponse(final Result<List<TokenBalanceDto>, DomainError> result,
                                             final String partyId) {
        if (result.isOk()) {
            return ResponseEntity.ok(result.getValueUnsafe());
        }
        return mapDomainErrorToResponse(result.getErrorUnsafe(), partyId);
    }

    private ResponseEntity<ErrorResponse> mapDomainErrorToResponse(final DomainError error,
                                                                   final String partyId) {
        HttpStatus status = mapStatus(error);
        String path = String.format("/api/wallet/balances/%s", partyId);
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
