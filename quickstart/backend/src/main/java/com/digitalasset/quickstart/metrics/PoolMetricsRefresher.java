// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.metrics;

import helvet_amm.amm.pool.Pool;
import com.digitalasset.quickstart.ledger.LedgerApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Background task to refresh pool metrics (reserves, K-invariant, active count).
 * Runs every 10 seconds to update Grafana dashboards.
 */
@Component
public class PoolMetricsRefresher {
    private static final Logger logger = LoggerFactory.getLogger(PoolMetricsRefresher.class);

    private final LedgerApi ledgerApi;
    private final SwapMetrics swapMetrics;

    public PoolMetricsRefresher(LedgerApi ledgerApi, SwapMetrics swapMetrics) {
        this.ledgerApi = ledgerApi;
        this.swapMetrics = swapMetrics;
    }

    /**
     * Refresh pool metrics every 10 seconds.
     * Updates: active pool count, reserves, K-invariant for each pool.
     */
    @Scheduled(fixedDelayString = "${metrics.pools.refresh-ms:10000}")
    public void refreshPoolMetrics() {
        ledgerApi.getActiveContracts(Pool.class)
            .thenAccept(pools -> {
                // Count unique active pools (by token pair, not poolId)
                // This excludes archived pools and counts unique trading pairs
                long activeCount = pools.stream()
                    .filter(p -> p.payload.getReserveA.compareTo(BigDecimal.ZERO) > 0)
                    .filter(p -> p.payload.getReserveB.compareTo(BigDecimal.ZERO) > 0)
                    .map(p -> normalizePair(p.payload.getSymbolA, p.payload.getSymbolB))  // Normalize to unique pair
                    .distinct()
                    .count();

                swapMetrics.setActivePoolsCount((int) activeCount);

                // Update reserves and K-invariant for each unique token pair
                // Group by normalized pair and take the one with latest reserves
                pools.stream()
                    .filter(p -> p.payload.getReserveA.compareTo(BigDecimal.ZERO) > 0)
                    .filter(p -> p.payload.getReserveB.compareTo(BigDecimal.ZERO) > 0)
                    .collect(java.util.stream.Collectors.toMap(
                        p -> normalizePair(p.payload.getSymbolA, p.payload.getSymbolB),
                        p -> p,
                        (p1, p2) -> p1  // Keep first occurrence (latest in stream)
                    ))
                    .values()
                    .forEach(p -> {
                        // Use existing recordPoolLiquidity which properly manages gauge lifecycle
                        swapMetrics.recordPoolLiquidity(
                            p.payload.getSymbolA,
                            p.payload.getSymbolB,
                            p.payload.getReserveA,
                            p.payload.getReserveB
                        );
                    });

                logger.debug("Refreshed metrics for {} unique active pools", activeCount);
            })
            .exceptionally(ex -> {
                logger.error("Failed to refresh pool metrics", ex);
                return null;
            });
    }

    /**
     * Normalize token pair to consistent format (alphabetical order).
     */
    private String normalizePair(String tokenA, String tokenB) {
        return tokenA.compareTo(tokenB) < 0
            ? tokenA + "-" + tokenB
            : tokenB + "-" + tokenA;
    }
}
