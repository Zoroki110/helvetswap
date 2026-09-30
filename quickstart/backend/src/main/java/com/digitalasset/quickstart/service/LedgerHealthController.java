// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * REST controller for ledger health and sync status.
 *
 * Endpoints:
 * - GET /api/health/ledger - Get sync status between Canton and PQS
 * - GET /api/health/packages - Get package information from PQS
 * - GET /api/health/package/{packageId} - Check if a specific package is indexed
 */
@RestController
@RequestMapping("/api/health")
public class LedgerHealthController {

    private final LedgerHealthService healthService;

    @Autowired
    public LedgerHealthController(LedgerHealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * Get ledger health status including PQS sync information.
     *
     * Response includes:
     * - pqsOffset: Current PQS offset
     * - pqsPackageIds: Package IDs indexed by PQS
     * - clearportxContractCount: Number of ClearportX contracts
     * - synced: Whether PQS appears synced
     * - status: OK, SYNCING, or ERROR
     */
    @GetMapping("/ledger")
    @WithSpan
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getLedgerHealth() {
        return healthService.getHealthStatus()
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Get detailed package information from PQS.
     *
     * Returns all packages indexed by PQS with contract counts.
     */
    @GetMapping("/packages")
    @WithSpan
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getPackages() {
        return healthService.getPackageInfo()
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Check if a specific package ID is indexed by PQS.
     *
     * Useful to detect package allowlist mismatches after DAR deployments.
     *
     * @param packageId The package ID to check (e.g., from daml.yaml)
     */
    @GetMapping("/package/{packageId}")
    @WithSpan
    public CompletableFuture<ResponseEntity<Map<String, Object>>> checkPackage(
            @PathVariable String packageId
    ) {
        return healthService.isPackageIndexed(packageId)
                .thenApply(indexed -> {
                    Map<String, Object> response = Map.of(
                            "packageId", packageId,
                            "indexed", indexed,
                            "status", indexed ? "OK" : "NOT_FOUND"
                    );
                    return ResponseEntity.ok(response);
                });
    }
}
