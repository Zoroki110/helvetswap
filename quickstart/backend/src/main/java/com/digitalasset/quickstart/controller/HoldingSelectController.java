package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.HoldingSelectRequest;
import com.digitalasset.quickstart.dto.HoldingSelectResponse;
import java.util.concurrent.CompletableFuture;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/holdings")
@Profile("devnet")
public class HoldingSelectController {

    private final DevNetHoldingSelectController devNetHoldingSelectController;

    public HoldingSelectController(final DevNetHoldingSelectController devNetHoldingSelectController) {
        this.devNetHoldingSelectController = devNetHoldingSelectController;
    }

    /**
     * POST /api/holdings/select (agnostic surface)
     */
    @PostMapping("/select")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public CompletableFuture<ResponseEntity<HoldingSelectResponse>> selectHolding(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody HoldingSelectRequest request
    ) {
        return devNetHoldingSelectController.selectHolding(jwt, request)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }

    /**
     * GET /api/holdings/select
     */
    @GetMapping("/select")
    public CompletableFuture<ResponseEntity<HoldingSelectResponse>> selectHoldingGet(
            @RequestParam("ownerParty") String ownerParty,
            @RequestParam(value = "instrumentAdmin", required = false) String instrumentAdmin,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "minAmount", required = false, defaultValue = "0") String minAmount,
            @RequestParam(value = "timeoutSeconds", required = false, defaultValue = "0") Integer timeoutSeconds,
            @RequestParam(value = "pollIntervalMs", required = false, defaultValue = "2000") Integer pollIntervalMs
    ) {
        return devNetHoldingSelectController.selectHoldingGet(
                        ownerParty,
                        instrumentAdmin,
                        instrumentId,
                        minAmount,
                        timeoutSeconds,
                        pollIntervalMs
                )
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }
}

