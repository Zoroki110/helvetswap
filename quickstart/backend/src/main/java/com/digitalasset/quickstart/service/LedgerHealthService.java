// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.pqs.Pqs;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service to check health and sync status between Canton Ledger and PQS.
 *
 * This helps the UI detect when PQS is lagging behind Canton and show
 * appropriate "syncing" messages instead of empty states.
 */
@Service
public class LedgerHealthService {

    private static final Logger logger = LoggerFactory.getLogger(LedgerHealthService.class);

    @Nullable
    private final JdbcTemplate jdbcTemplate;
    @Nullable
    private final Pqs pqs;
    private final LedgerReader ledgerReader;

    @Value("${spring.profiles.active:localnet}")
    private String environment;

    @Value("${application.version:1.0.1}")
    private String applicationVersion;

    @Autowired
    public LedgerHealthService(
            @Autowired(required = false) @Nullable JdbcTemplate jdbcTemplate,
            @Autowired(required = false) @Nullable Pqs pqs,
            LedgerReader ledgerReader
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.pqs = pqs;
        this.ledgerReader = ledgerReader;
        if (jdbcTemplate == null) {
            logger.info("JdbcTemplate not available - PQS health checks disabled");
        }
    }

    /**
     * Get health status including sync information between Canton and PQS.
     *
     * Returns:
     * - pqsOffset: Current PQS indexing offset
     * - pqsPackageIds: List of package IDs that PQS has indexed
     * - clearportxContractCount: Number of ClearportX contracts in PQS
     * - synced: Whether PQS appears to be caught up
     */
    @WithSpan
    public CompletableFuture<Map<String, Object>> getHealthStatus() {
        long startTime = System.currentTimeMillis();

        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> health = new HashMap<>();

            try {
                // PQS-specific checks (only if jdbcTemplate available)
                java.util.List<String> packageNames = java.util.Collections.emptyList();
                Long clearportxCount = 0L;
                boolean hasClearportxPackage = false;
                boolean hasClearportxTemplates = false;

                if (jdbcTemplate != null) {
                    // Get PQS current offset (best-effort with 200ms timeout)
                    try {
                        String pqsOffsetQuery = "SELECT COALESCE(MAX(pk), 0) as max_offset FROM __events";
                        Long pqsOffset = CompletableFuture.supplyAsync(() ->
                            jdbcTemplate.queryForObject(pqsOffsetQuery, Long.class)
                        ).orTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS).join();
                        health.put("pqsOffset", pqsOffset);
                    } catch (Exception ex) {
                        logger.debug("PQS offset query timeout/failed (best-effort): {}", ex.getMessage());
                        // Omit pqsOffset field on timeout
                    }

                    // Get distinct package names in PQS
                    String packageQuery = "SELECT DISTINCT package_name FROM __contract_tpe LIMIT 20";
                    packageNames = jdbcTemplate.queryForList(packageQuery, String.class);
                    health.put("pqsPackageNames", packageNames);

                    // Count ClearportX contracts (Pool, Token, LPToken, SwapRequest)
                    // Query active contracts (where archived_at_ix is null)
                    String clearportxQuery = """
                        SELECT COUNT(*) FROM __contracts c
                        JOIN __contract_tpe ct ON c.tpe_pk = ct.pk
                        WHERE c.archived_at_ix IS NULL
                        AND (ct.module_name LIKE '%AMM%'
                             OR ct.module_name LIKE '%Token%'
                             OR ct.module_name LIKE '%LPToken%'
                             OR ct.package_name LIKE '%clearportx%')
                    """;
                    clearportxCount = jdbcTemplate.queryForObject(clearportxQuery, Long.class);
                    health.put("clearportxContractCount", clearportxCount);

                    // Check if ClearportX packages are indexed
                    hasClearportxPackage = packageNames.stream()
                            .anyMatch(name -> name != null && name.startsWith("clearportx"));

                    // Check if we have active ClearportX contracts
                    hasClearportxTemplates = clearportxCount != null && clearportxCount > 0;
                } else {
                    logger.debug("JdbcTemplate not available - skipping PQS health checks");
                    health.put("pqsEnabled", false);
                }

                // Determine sync status with detailed diagnostics
                String status;
                String diagnostic = null;

                if (jdbcTemplate == null) {
                    // PQS disabled - using Ledger API only (Canton 3.4.7 mode)
                    status = "OK";
                    diagnostic = "Running in Ledger API-only mode (PQS disabled)";
                    logger.info("Health check OK: PQS disabled, using Ledger API directly");
                } else if (!hasClearportxPackage) {
                    // Package not in PQS at all - allowlist issue
                    status = "PACKAGE_NOT_INDEXED";
                    diagnostic = "ClearportX package not found in PQS. Check PQS allowlist configuration.";
                    logger.warn("⚠️  PACKAGE_MISMATCH: ClearportX not in PQS package list. Possible allowlist issue.");
                    logMetric("pqs_package_mismatch", 1);
                } else if (!hasClearportxTemplates) {
                    // Package exists but no contracts - still syncing OR no pools created yet
                    status = "SYNCING";
                    diagnostic = "ClearportX package indexed but no active contracts yet. Either PQS is catching up or no pools have been created.";
                    logger.info("PQS has clearportx package but 0 active contracts - likely syncing or awaiting init");
                } else {
                    // All good - package indexed and contracts present
                    status = "OK";
                    logger.info("✅ Health check OK: clearportxContracts={}", clearportxCount);
                }

                health.put("synced", hasClearportxTemplates || jdbcTemplate == null);
                health.put("status", status);
                if (diagnostic != null) {
                    health.put("diagnostic", diagnostic);
                }
                health.put("hasClearportxPackage", hasClearportxPackage);

                // Add environment information
                health.put("environment", environment);
                health.put("applicationVersion", applicationVersion);

                // Add package versioning information
                health.put("darVersion", "1.0.1");
                health.put("atomicSwapAvailable", checkAtomicSwapAvailable());

                // Get active pools count from Ledger API (ACS - not including archived)
                try {
                    int poolsActive = ledgerReader.pools().join().size();
                    health.put("poolsActive", poolsActive);
                } catch (Exception e) {
                    logger.debug("Could not retrieve active pools count: {}", e.getMessage());
                    health.put("poolsActive", 0);
                }

                // Get package ID from PQS if available
                if (jdbcTemplate != null) {
                    try {
                        String packageIdQuery = """
                            SELECT DISTINCT package_id FROM __contract_tpe
                            WHERE package_name LIKE 'clearportx%'
                            LIMIT 1
                        """;
                        String packageId = jdbcTemplate.queryForObject(packageIdQuery, String.class);
                        health.put("clearportxPackageId", packageId);
                    } catch (Exception e) {
                        logger.debug("Could not retrieve clearportx package ID: {}", e.getMessage());
                    }

                    // Emit metrics for Grafana (only if PQS offset available)
                    if (health.containsKey("pqsOffset")) {
                        logMetric("pqs_offset", (Long) health.get("pqsOffset"));
                    }
                    logMetric("clearportx_contract_count", clearportxCount != null ? clearportxCount : 0);
                }

            } catch (Exception e) {
                logger.error("Failed to get health status: {}", e.getMessage(), e);
                health.put("status", "ERROR");
                health.put("error", e.getMessage());
                health.put("synced", false);
            }

            return health;
        });
    }

    /**
     * Check if AtomicSwapProposal template is available in the DAML bindings.
     * This validates that the DAR with atomic swap support is deployed.
     *
     * @return true if AtomicSwapProposal class exists in bindings
     */
    private boolean checkAtomicSwapAvailable() {
        try {
            // Attempt to load the AtomicSwapProposal class from generated bindings
            Class.forName("clearportx_amm.amm.atomicswap.AtomicSwapProposal");
            logger.debug("✅ AtomicSwapProposal template found in bindings");
            return true;
        } catch (ClassNotFoundException e) {
            logger.warn("⚠️  AtomicSwapProposal template NOT found in bindings - atomic swap unavailable");
            return false;
        }
    }

    /**
     * Validate that PQS is indexing a specific package ID.
     * Useful to detect package allowlist mismatches.
     *
     * @param packageName The package name to check for
     * @return true if PQS has indexed contracts from this package
     */
    @WithSpan
    public CompletableFuture<Boolean> isPackageIndexed(String packageName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String query = """
                    SELECT COUNT(*) FROM __contracts c
                    JOIN __contract_tpe ct ON c.tpe_pk = ct.pk
                    WHERE ct.package_name = ?
                    AND c.archived_at_ix IS NULL
                """;
                Long count = jdbcTemplate.queryForObject(query, Long.class, packageName);
                boolean indexed = count != null && count > 0;

                if (!indexed) {
                    logger.warn("Package {} not found in PQS - possible allowlist/package mismatch", packageName);
                } else {
                    logger.info("Package {} is indexed in PQS ({} active contracts)", packageName, count);
                }

                return indexed;
            } catch (Exception e) {
                logger.error("Failed to check if package {} is indexed: {}", packageName, e.getMessage());
                return false;
            }
        });
    }

    /**
     * Get detailed package information from PQS.
     * Helps diagnose package version mismatches.
     */
    @WithSpan
    public CompletableFuture<Map<String, Object>> getPackageInfo() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> info = new HashMap<>();

            try {
                // Get all unique package names with contract counts
                String query = """
                    SELECT
                        ct.package_name,
                        COUNT(*) as active_contract_count,
                        COUNT(CASE WHEN c.archived_at_ix IS NOT NULL THEN 1 END) as archived_contract_count
                    FROM __contracts c
                    JOIN __contract_tpe ct ON c.tpe_pk = ct.pk
                    GROUP BY ct.package_name
                    ORDER BY active_contract_count DESC
                    LIMIT 50
                """;

                var packages = jdbcTemplate.queryForList(query);
                info.put("packages", packages);
                info.put("totalPackages", packages.size());

                logger.info("Found {} distinct packages in PQS", packages.size());

            } catch (Exception e) {
                logger.error("Failed to get package info: {}", e.getMessage());
                info.put("error", e.getMessage());
            }

            return info;
        });
    }

    /**
     * Log metrics for observability (Grafana/Prometheus).
     * Format matches Prometheus exposition format for easy scraping.
     */
    private void logMetric(String metricName, long value) {
        logger.info("METRIC: pqs_{}={}", metricName, value);
    }
}
