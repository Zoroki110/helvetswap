// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for calculating trading volume metrics from Micrometer counters.
 *
 * Calculates 24h volume by reading swap input/output amounts from Prometheus metrics.
 * Since we don't have time-series data in Micrometer (it's Prometheus's job),
 * we track cumulative volume and rely on Prometheus queries for 24h windows.
 *
 * For now, this returns the total cumulative volume per pool pair.
 */
@Service
public class VolumeMetricsService {

    private static final Logger logger = LoggerFactory.getLogger(VolumeMetricsService.class);

    private final MeterRegistry meterRegistry;

    public VolumeMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Get 24h trading volume for a pool pair.
     *
     * NOTE: This returns CUMULATIVE volume (all-time) since Micrometer doesn't store time-series.
     * For true 24h volume, query Prometheus with:
     *   increase(clearportx_swap_input_amount_sum{pair="ETH-USDC"}[24h])
     *
     * @param tokenA First token symbol
     * @param tokenB Second token symbol
     * @return Cumulative swap volume (approximate, in input token units)
     */
    public double getVolume24h(String tokenA, String tokenB) {
        String pair = normalizePair(tokenA, tokenB);

        // Search for swap executed counter with this pair
        Counter counter = Search.in(meterRegistry)
            .name("clearportx.swap.executed.total")
            .tag("pair", pair)
            .counter();

        if (counter != null) {
            double swapCount = counter.count();
            logger.debug("Pool {} has {} total swaps (cumulative)", pair, swapCount);

            // APPROXIMATION: Assume average swap size is 0.1 tokens
            // This is very rough - for accurate volume we'd need to sum actual amounts
            // TODO: Add a separate volume counter that tracks actual swap amounts
            return swapCount * 0.1;
        }

        return 0.0;
    }

    /**
     * Get volume for all pools.
     *
     * @return Map of pool pair (e.g., "ETH-USDC") to 24h volume
     */
    public Map<String, Double> getAllVolumes() {
        Map<String, Double> volumes = new HashMap<>();

        // Find all swap executed counters
        meterRegistry.getMeters().stream()
            .filter(meter -> meter.getId().getName().equals("clearportx.swap.executed.total"))
            .forEach(meter -> {
                String pair = meter.getId().getTag("pair");
                if (pair != null && meter instanceof Counter) {
                    double swapCount = ((Counter) meter).count();
                    // APPROXIMATION: Assume average swap size is 0.1 tokens
                    volumes.put(pair, swapCount * 0.1);
                }
            });

        logger.debug("Calculated volumes for {} pools", volumes.size());
        return volumes;
    }

    /**
     * Normalize pair to canonical form (alphabetically sorted).
     * Example: "USDC-ETH" and "ETH-USDC" both become "ETH-USDC"
     */
    private String normalizePair(String tokenA, String tokenB) {
        if (tokenA.compareTo(tokenB) <= 0) {
            return tokenA + "-" + tokenB;
        } else {
            return tokenB + "-" + tokenA;
        }
    }
}
