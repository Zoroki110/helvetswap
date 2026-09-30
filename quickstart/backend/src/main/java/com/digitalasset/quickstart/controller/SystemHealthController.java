package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.dto.HoldingDto;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.HoldingsService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemHealthController {

    private final HoldingsService holdingsService;
    private final AuthUtils authUtils;

    public SystemHealthController(final HoldingsService holdingsService, final AuthUtils authUtils) {
        this.holdingsService = holdingsService;
        this.authUtils = authUtils;
    }

    public record HoldingsReadinessDto(boolean darInstalled, boolean interfaceViewWorking, int visibleHoldingsCount) { }

    @GetMapping("/holdings-ready")
    public CompletableFuture<ResponseEntity<HoldingsReadinessDto>> getHoldingsReadiness() {
        String operatorParty = authUtils.getAppProviderPartyId();
        return holdingsService.getHoldingsByParty(operatorParty)
                .thenApply(this::toResponse);
    }

    private ResponseEntity<HoldingsReadinessDto> toResponse(final Result<List<HoldingDto>, DomainError> result) {
        boolean darInstalled = true;
        boolean interfaceViewWorking = true;
        int visibleHoldingsCount = 0;
        if (result.isOk()) {
            List<HoldingDto> holdings = result.getValueUnsafe();
            visibleHoldingsCount = holdings != null ? holdings.size() : 0;
        } else {
            DomainError error = result.getErrorUnsafe();
            if (isDarMissing(error)) {
                darInstalled = false;
                interfaceViewWorking = false;
            }
        }
        return ResponseEntity.ok(new HoldingsReadinessDto(darInstalled, interfaceViewWorking, visibleHoldingsCount));
    }

    private boolean isDarMissing(final DomainError error) {
        if (!(error instanceof UnexpectedError unexpected)) {
            return false;
        }
        String message = unexpected.message();
        return message != null && message.toLowerCase().contains("splice-api-token-holding-v1");
    }
}
