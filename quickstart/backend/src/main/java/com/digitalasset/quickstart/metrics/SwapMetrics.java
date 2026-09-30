// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.metrics;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Metrics collector for atomic swap operations.
 *
 * Provides Micrometer metrics for:
 * - Swap counters (total, by direction, success/failure)
 * - Pool liquidity gauges (registered once, updated on swap)
 * - Swap amount histograms
 * - Price impact distribution
 * - Execution time tracking
 *
 * CARDINALITY SAFETY:
 * - Uses pool pair (e.g., "ETH-USDC") not contract IDs
 * - Limits tag sets to prevent unbounded growth
 * - Gauges registered once and updated via AtomicReference
 */
@Component
public class SwapMetrics {

    private static final Logger logger = LoggerFactory.getLogger(SwapMetrics.class);

    private final MeterRegistry meterRegistry;
    private final DistributionSummary swapInputAmounts;
    private final DistributionSummary swapOutputAmounts;
    private final DistributionSummary priceImpact;
    private final Timer swapExecutionTime;

    // Pool liquidity tracking (prevent gauge re-registration)
    private final AtomicInteger activePoolsCount = new AtomicInteger(0);
    private final Map<String, AtomicReference<BigDecimal>> poolReserveGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<BigDecimal>> poolKInvariantGauges = new ConcurrentHashMap<>();

    // DEPRECATED: Fee tracking gauges (kept for backward compatibility but not used)
    // Use recordFeeCollected() instead which uses proper counters
    private final Map<String, AtomicReference<BigDecimal>> protocolFees = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<BigDecimal>> lpFees = new ConcurrentHashMap<>();

    public SwapMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Counter: Total swaps prepared
        // NOTE: We don't register global counters here anymore.
        // All swap counters are created dynamically with tags in recordSwap*() methods
        // to avoid Prometheus "tag keys mismatch" errors.

        // Distribution: Swap input amounts
        this.swapInputAmounts = DistributionSummary.builder("clearportx.swap.input.amount")
            .description("Distribution of swap input amounts")
            .baseUnit("tokens")
            .register(meterRegistry);

        // Distribution: Swap output amounts
        this.swapOutputAmounts = DistributionSummary.builder("clearportx.swap.output.amount")
            .description("Distribution of swap output amounts")
            .baseUnit("tokens")
            .register(meterRegistry);

        // Distribution: Price impact (in basis points)
        this.priceImpact = DistributionSummary.builder("clearportx.swap.price_impact.bps")
            .description("Distribution of price impact in basis points")
            .baseUnit("bps")
            .register(meterRegistry);

        // Timer: Swap execution time
        this.swapExecutionTime = Timer.builder("clearportx.swap.execution.time")
            .description("Time taken to execute atomic swaps")
            .register(meterRegistry);

        // Gauge: Active pools
        Gauge.builder("clearportx.pool.active.count", activePoolsCount, AtomicInteger::get)
            .description("Number of active liquidity pools")
            .register(meterRegistry);
    }

    /**
     * Record a swap preparation.
     */
    public void recordSwapPrepared(String inputSymbol, String outputSymbol) {
        // Tagged counter for specific token pairs
        meterRegistry.counter("clearportx.swap.prepared.total",
            "pair", normalizePair(inputSymbol, outputSymbol)).increment();
    }

    /**
     * Record a successful swap execution.
     */
    public void recordSwapExecuted(String inputSymbol, String outputSymbol,
                                    BigDecimal inputAmount, BigDecimal outputAmount,
                                    int priceImpactBps, long executionTimeMs) {
        // Use same metric name with pair tag for better PromQL queries
        String pair = normalizePair(inputSymbol, outputSymbol);
        meterRegistry.counter("clearportx.swap.executed.total",
            "pair", pair).increment();

        // Record amounts
        swapInputAmounts.record(inputAmount.doubleValue());
        swapOutputAmounts.record(outputAmount.doubleValue());

        // Record price impact
        if (priceImpactBps > 0) {
            priceImpact.record(priceImpactBps);
        }

        // Record execution time
        swapExecutionTime.record(executionTimeMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Record a failed swap.
     */
    public void recordSwapFailed(String inputSymbol, String outputSymbol, String reason) {
        // Tagged counter for failure reasons (limit reason cardinality)
        String normalizedReason = normalizeReason(reason);
        meterRegistry.counter("clearportx.swap.failed.total",
            "reason", normalizedReason,
            "pair", normalizePair(inputSymbol, outputSymbol)).increment();
    }

    /**
     * Update pool liquidity metrics.
     * Gauges are registered once and updated via AtomicReference.
     */
    public void recordPoolLiquidity(String tokenA, String tokenB,
                                     BigDecimal reserveA, BigDecimal reserveB) {
        String pair = normalizePair(tokenA, tokenB);

        // Reserve A gauge
        String keyA = pair + ":" + tokenA;
        poolReserveGauges.computeIfAbsent(keyA, k -> {
            AtomicReference<BigDecimal> ref = new AtomicReference<>(reserveA);
            Gauge.builder("clearportx.pool.reserve.amount", ref, r -> r.get().doubleValue())
                .tag("pair", pair)
                .tag("token", tokenA)
                .description("Pool reserve amount")
                .register(meterRegistry);
            return ref;
        }).set(reserveA);

        // Reserve B gauge
        String keyB = pair + ":" + tokenB;
        poolReserveGauges.computeIfAbsent(keyB, k -> {
            AtomicReference<BigDecimal> ref = new AtomicReference<>(reserveB);
            Gauge.builder("clearportx.pool.reserve.amount", ref, r -> r.get().doubleValue())
                .tag("pair", pair)
                .tag("token", tokenB)
                .description("Pool reserve amount")
                .register(meterRegistry);
            return ref;
        }).set(reserveB);

        // K-invariant gauge
        BigDecimal k = reserveA.multiply(reserveB);
        poolKInvariantGauges.computeIfAbsent(pair, p -> {
            AtomicReference<BigDecimal> ref = new AtomicReference<>(k);
            Gauge.builder("clearportx.pool.k_invariant", ref, r -> r.get().doubleValue())
                .tag("pair", pair)
                .description("Constant product k = reserveA * reserveB")
                .register(meterRegistry);
            return ref;
        }).set(k);
    }

    /**
     * Update active pools count.
     */
    public void setActivePoolsCount(int count) {
        activePoolsCount.set(count);
    }

    /**
     * Update pool reserve amount for a specific token in a pool.
     */
    public void setPoolReserve(String pair, String token, BigDecimal amount) {
        meterRegistry.gauge("clearportx.pool.reserve.amount",
            Tags.of("pair", pair, "token", token),
            amount);
    }

    /**
     * Update pool K-invariant (reserveA * reserveB).
     */
    public void setPoolKInvariant(String pair, BigDecimal kValue) {
        meterRegistry.gauge("clearportx.pool.k.invariant",
            Tags.of("pair", pair),
            kValue);
    }

    /**
     * DEPRECATED: Use recordFeeCollected() instead.
     * Record protocol fee collection using counter (not gauge).
     */
    public void recordProtocolFee(String token, BigDecimal amount) {
        meterRegistry.counter("clearportx.fees.protocol.collected",
            "token", token).increment(amount.doubleValue());
    }

    /**
     * DEPRECATED: Use recordFeeCollected() instead.
     * Record LP fee collection using counter (not gauge).
     */
    public void recordLpFee(String token, BigDecimal amount) {
        meterRegistry.counter("clearportx.fees.lp.collected",
            "token", token).increment(amount.doubleValue());
    }

    /**
     * Record slippage protection trigger.
     */
    public void recordSlippageProtection(String pair, int slippageBps) {
        meterRegistry.counter("clearportx.swap.slippage.triggered",
            "pair", pair).increment();
        meterRegistry.summary("clearportx.swap.slippage.bps",
            "pair", pair).record(slippageBps);
    }

    /**
     * Record fee collection.
     */
    public void recordFeeCollected(String tokenSymbol, BigDecimal lpFee, BigDecimal protocolFee) {
        // LP fees
        meterRegistry.counter("clearportx.fees.lp.collected",
            "token", tokenSymbol).increment(lpFee.doubleValue());

        // Protocol fees
        meterRegistry.counter("clearportx.fees.protocol.collected",
            "token", tokenSymbol).increment(protocolFee.doubleValue());
    }

    /**
     * Record slippage protection trigger.
     */
    public void recordSlippageProtectionTriggered(String inputSymbol, String outputSymbol,
                                                   int actualImpactBps, int maxAllowedBps) {
        meterRegistry.counter("clearportx.swap.slippage_protection.triggered",
            "pair", normalizePair(inputSymbol, outputSymbol)).increment();

        // Record the delta between actual and max
        int delta = actualImpactBps - maxAllowedBps;
        DistributionSummary.builder("clearportx.swap.slippage_protection.delta_bps")
            .description("How much price impact exceeded limit (in bps)")
            .register(meterRegistry)
            .record(delta);
    }

    /**
     * Record deadline expiration.
     */
    public void recordDeadlineExpired(String inputSymbol, String outputSymbol) {
        meterRegistry.counter("clearportx.swap.deadline.expired",
            "pair", normalizePair(inputSymbol, outputSymbol)).increment();
    }

    /**
     * Record concurrent swap attempt.
     */
    public void recordConcurrentSwap(String poolPair) {
        meterRegistry.counter("clearportx.swap.concurrent.total",
            "pair", poolPair).increment();
    }

    /**
     * Get the meter registry (for custom metrics).
     */
    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    // Helper methods for cardinality control

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

    /**
     * Normalize failure reason to limit cardinality.
     */
    private String normalizeReason(String reason) {
        if (reason == null) return "unknown";

        // Map common exception types
        if (reason.contains("ResponseStatusException")) return "response_status_error";
        if (reason.contains("TimeoutException")) return "timeout";
        if (reason.contains("CONTRACT_NOT_FOUND")) return "stale_contract";
        if (reason.contains("INSUFFICIENT")) return "insufficient_balance";
        if (reason.contains("SLIPPAGE")) return "slippage_exceeded";
        if (reason.contains("DEADLINE")) return "deadline_expired";

        // Truncate long reasons
        if (reason.length() > 50) {
            return reason.substring(0, 47) + "...";
        }

        return reason;
    }
}
