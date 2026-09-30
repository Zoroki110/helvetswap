package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.HoldingSelectRequest;
import com.digitalasset.quickstart.dto.HoldingSelectResponse;
import com.digitalasset.quickstart.service.HoldingSelectorService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * DevNet-only endpoint for testing UTXO selection of Token Standard holdings.
 *
 * <h2>Purpose</h2>
 * This endpoint allows testing the HoldingSelectorService which is used by
 * Option A (Loop/User accepts CBTC, backend selects resulting holding).
 *
 * <h2>Selection Rule</h2>
 * Smallest-amount-first: selects the holding with the smallest amount that
 * satisfies minAmount. Deterministic via lexicographic contractId tiebreaker.
 *
 * <h2>Endpoint</h2>
 * POST /api/devnet/holdings/select
 *
 * <h2>Example Request</h2>
 * <pre>
 * {
 *   "ownerParty": "ClearportX-DEX-1::1220...",
 *   "instrumentAdmin": "cbtc-network::1220...",
 *   "instrumentId": "CBTC",
 *   "minAmount": "0.1",
 *   "timeoutSeconds": 60,
 *   "pollIntervalMs": 2000
 * }
 * </pre>
 *
 * <h2>Example Response</h2>
 * <pre>
 * {
 *   "found": true,
 *   "holdingCid": "00abcd...",
 *   "instrumentAdmin": "cbtc-network::1220...",
 *   "instrumentId": "CBTC",
 *   "amount": 0.1,
 *   "owner": "ClearportX-DEX-1::1220...",
 *   "attempts": 3,
 *   "elapsedMs": 4521,
 *   "totalHoldingsScanned": 160,
 *   "matchingHoldingsFound": 1,
 *   "selectionRule": "smallest-amount-first",
 *   "error": null
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/devnet/holdings")
@Profile("devnet")
public class DevNetHoldingSelectController {

    private static final Logger LOGGER = LoggerFactory.getLogger(DevNetHoldingSelectController.class);

    private final HoldingSelectorService holdingSelectorService;

    public DevNetHoldingSelectController(final HoldingSelectorService holdingSelectorService) {
        this.holdingSelectorService = holdingSelectorService;
    }

    /**
     * Select a holding matching the criteria with optional polling.
     *
     * @param request Selection criteria
     * @return Selected holding or error after timeout
     */
    @PostMapping("/select")
    @WithSpan
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public CompletableFuture<ResponseEntity<HoldingSelectResponse>> selectHolding(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody HoldingSelectRequest request
    ) {
        LOGGER.info("[DevNetHoldingSelect] POST /api/devnet/holdings/select - owner={}, admin={}, id={}, minAmount={}",
                request.ownerParty(),
                request.instrumentAdmin(),
                request.instrumentId(),
                request.minAmount());

        // Validation
        if (request.ownerParty() == null || request.ownerParty().isBlank()) {
            ResponseEntity<HoldingSelectResponse> response = ResponseEntity.badRequest().body(
                    HoldingSelectResponse.notFound(0, 0, 0, 0, "ownerParty is required")
            );
            return CompletableFuture.completedFuture(ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.DEVNET));
        }

        // Use polling if timeout > 0, otherwise single attempt
        CompletableFuture<HoldingSelectResponse> resultFuture;
        if (request.getTimeoutSeconds() > 0) {
            resultFuture = holdingSelectorService.selectHolding(request);
        } else {
            resultFuture = holdingSelectorService.selectHoldingOnce(request);
        }

        return resultFuture.thenApply(response -> {
            if (response.found()) {
                LOGGER.info("[DevNetHoldingSelect] SUCCESS - found holding cid={}, amount={}",
                        truncateCid(response.holdingCid()), response.amount());
                return ApiSurfaceHeaders.withSurface(ResponseEntity.ok(response), ApiSurfaceHeaders.DEVNET);
            } else {
                LOGGER.warn("[DevNetHoldingSelect] NOT FOUND - {} after {} attempts ({}ms)",
                        response.error(), response.attempts(), response.elapsedMs());
                return ApiSurfaceHeaders.withSurface(ResponseEntity.ok(response), ApiSurfaceHeaders.DEVNET); // Return 200 with found=false
            }
        }).exceptionally(ex -> {
            LOGGER.error("[DevNetHoldingSelect] Exception: {}", ex.getMessage(), ex);
            ResponseEntity<HoldingSelectResponse> response = ResponseEntity.internalServerError().body(
                    HoldingSelectResponse.notFound(0, 0, 0, 0, "Internal error: " + ex.getMessage())
            );
            return ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.DEVNET);
        });
    }

    /**
     * Quick selection endpoint using query parameters (GET for easy testing).
     *
     * Example: GET /api/devnet/holdings/select?ownerParty=...&instrumentId=CBTC&minAmount=0.1
     */
    @GetMapping("/select")
    @WithSpan
    public CompletableFuture<ResponseEntity<HoldingSelectResponse>> selectHoldingGet(
            @RequestParam("ownerParty") String ownerParty,
            @RequestParam(value = "instrumentAdmin", required = false) String instrumentAdmin,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "minAmount", required = false, defaultValue = "0") String minAmount,
            @RequestParam(value = "timeoutSeconds", required = false, defaultValue = "0") Integer timeoutSeconds,
            @RequestParam(value = "pollIntervalMs", required = false, defaultValue = "2000") Integer pollIntervalMs
    ) {
        HoldingSelectRequest request = new HoldingSelectRequest(
                ownerParty,
                instrumentAdmin,
                instrumentId,
                new BigDecimal(minAmount),
                timeoutSeconds,
                pollIntervalMs
        );
        return selectHolding(null, request);
    }

    private String truncateCid(final String cid) {
        if (cid == null || cid.length() <= 20) {
            return cid;
        }
        return cid.substring(0, 16) + "...";
    }
}
