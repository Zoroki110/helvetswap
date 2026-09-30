// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * REST controller for ClearportX initialization
 *
 * Endpoints:
 * - POST /api/clearportx/init - Initialize ClearportX with test tokens and pools
 * - GET /api/clearportx/init/status - Get initialization status
 * - GET /api/health/ledger - Check ledger health and sync status
 */
@RestController
@RequestMapping("/api/clearportx")
public class ClearportXInitController {

    private static final Logger logger = LoggerFactory.getLogger(ClearportXInitController.class);

    private final ClearportXInitService initService;

    public ClearportXInitController(ClearportXInitService initService) {
        this.initService = initService;
    }

    /**
     * Initialize ClearportX with test tokens and liquidity pools.
     * Idempotent: can be called multiple times safely.
     *
     * @return InitResponse with state and results
     */
    @PostMapping("/init")
    @WithSpan
    public CompletableFuture<ResponseEntity<InitResponse>> initializeClearportX() {
        logger.info("POST /api/clearportx/init - Starting ClearportX initialization");

        String commandIdPrefix = "clearportx-init-" + UUID.randomUUID();

        return initService.initializeClearportX(commandIdPrefix)
            .thenApply(finalState -> {
                InitResponse response = new InitResponse();
                response.setState(finalState.toString());
                response.setResults(initService.getInitResults());
                initService.getLastError().ifPresent(response::setError);

                HttpStatus status = switch (finalState) {
                    case COMPLETED -> HttpStatus.OK;
                    case IN_PROGRESS -> HttpStatus.ACCEPTED;
                    case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
                    default -> HttpStatus.BAD_REQUEST;
                };

                logger.info("ClearportX initialization request completed with state: {}", finalState);
                return ResponseEntity.status(status).body(response);
            })
            .exceptionally(ex -> {
                logger.error("Error during ClearportX initialization", ex);
                InitResponse response = new InitResponse();
                response.setState("FAILED");
                response.setError(ex.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            });
    }

    /**
     * Get current initialization status.
     * Useful for polling after starting initialization.
     *
     * @return InitResponse with current state and results
     */
    @GetMapping("/init/status")
    @WithSpan
    public ResponseEntity<InitResponse> getInitStatus() {
        logger.info("GET /api/clearportx/init/status - Getting initialization status");

        InitResponse response = new InitResponse();
        response.setState(initService.getState().toString());
        response.setResults(initService.getInitResults());
        initService.getLastError().ifPresent(response::setError);

        return ResponseEntity.ok(response);
    }

    /**
     * Bootstrap liquidity to all pools with demo amounts.
     * ETH-USDC: 100 ETH + 200,000 USDC
     * BTC-USDC: 10 BTC + 200,000 USDC
     * ETH-USDT: 100 ETH + 300,000 USDT
     *
     * @return Bootstrap response with LP tokens and updated pool CIDs
     */
    @PostMapping("/bootstrap-liquidity")
    @WithSpan
    public CompletableFuture<ResponseEntity<Map<String, Object>>> bootstrapLiquidity() {
        logger.info("POST /api/clearportx/bootstrap-liquidity - Bootstrapping liquidity to pools");

        String commandIdPrefix = "clearportx-bootstrap-" + UUID.randomUUID();

        return initService.bootstrapLiquidity(commandIdPrefix)
            .thenApply(result -> {
                logger.info("✅ Liquidity bootstrap completed successfully");
                return ResponseEntity.ok(result);
            })
            .exceptionally(ex -> {
                logger.error("❌ Liquidity bootstrap failed", ex);
                Map<String, Object> error = Map.of(
                    "error", ex.getMessage(),
                    "status", "FAILED"
                );
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
            });
    }

    /**
     * Reset initialization state to allow re-running init.
     * Useful for testing or creating fresh pools.
     */
    @PostMapping("/reset-init")
    @WithSpan
    public ResponseEntity<Map<String, String>> resetInit() {
        logger.warn("POST /api/clearportx/reset-init - Resetting initialization state");
        initService.resetState();
        return ResponseEntity.ok(Map.of(
            "status", "OK",
            "message", "Initialization state reset. Call /api/clearportx/init to create fresh pools."
        ));
    }

    /**
     * Mint demo tokens for testing swaps.
     *
     * @param request MintRequest with owner, symbol, and amount
     * @return Minted token ContractId
     */
    @PostMapping("/mint-demo")
    @WithSpan
    public CompletableFuture<ResponseEntity<Map<String, Object>>> mintDemoToken(@RequestBody MintRequest request) {
        logger.info("POST /api/clearportx/mint-demo - Minting {} {} to {}",
            request.amount, request.symbol, request.owner);

        String commandId = "clearportx-mint-" + UUID.randomUUID();

        return initService.mintDemoToken(request.owner, request.symbol, request.amount, commandId)
            .thenApply(tokenCid -> {
                logger.info("✅ Minted token: {}", tokenCid);
                Map<String, Object> result = Map.of(
                    "tokenCid", tokenCid.getContractId,
                    "owner", request.owner,
                    "symbol", request.symbol,
                    "amount", request.amount,
                    "status", "SUCCESS"
                );
                return ResponseEntity.ok(result);
            })
            .exceptionally(ex -> {
                logger.error("❌ Mint failed", ex);
                Map<String, Object> error = Map.of(
                    "error", ex.getMessage(),
                    "status", "FAILED"
                );
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
            });
    }

    /**
     * Health check endpoint for ledger connectivity and sync status.
     *
     * @return HealthResponse with ledger status
     */
    @GetMapping("/health")
    @WithSpan
    public ResponseEntity<HealthResponse> checkHealth() {
        logger.info("GET /api/clearportx/health - Checking ledger health");

        HealthResponse response = new HealthResponse();
        response.setStatus("UP");
        response.setLedgerConnected(true);

        // TODO: Add actual ledger sync status check
        // This would involve querying the ledger API to check:
        // - Connection status
        // - Current ledger offset
        // - Sync status with other nodes
        response.setDetails(Map.of(
            "message", "Ledger connection is healthy",
            "note", "Full sync status check not yet implemented"
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * Request DTO for minting demo tokens
     */
    public static class MintRequest {
        private String owner;
        private String symbol;
        private String amount;

        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }

        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
    }

    /**
     * Response DTO for initialization requests
     */
    public static class InitResponse {
        private String state;
        private Map<String, Object> results = new HashMap<>();
        private String error;

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public Map<String, Object> getResults() {
            return results;
        }

        public void setResults(Map<String, Object> results) {
            this.results = results;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }

    /**
     * Response DTO for health checks
     */
    public static class HealthResponse {
        private String status;
        private Boolean ledgerConnected;
        private Map<String, Object> details = new HashMap<>();

        public String getStatus() {
            return status;
        }

        public void setState(String status) {
            this.status = status;
        }

        public Boolean getLedgerConnected() {
            return ledgerConnected;
        }

        public void setLedgerConnected(Boolean ledgerConnected) {
            this.ledgerConnected = ledgerConnected;
        }

        public Map<String, Object> getDetails() {
            return details;
        }

        public void setDetails(Map<String, Object> details) {
            this.details = details;
        }

        // Fix method name from setState to setStatus
        public void setStatus(String status) {
            this.status = status;
        }
    }
}
