// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.constants;

import java.math.BigDecimal;

/**
 * Centralized constants for swap operations.
 *
 * All monetary calculations, fees, and limits defined here for easy auditing and modification.
 */
public final class SwapConstants {

    private SwapConstants() {
        // Prevent instantiation
    }

    // ========================================
    // PRECISION & SCALE
    // ========================================

    /**
     * Decimal scale for all BigDecimal operations.
     * All amounts are scaled to 10 decimal places for precision.
     */
    public static final int SCALE = 10;

    // ========================================
    // FEE STRUCTURE
    // ========================================

    /**
     * Total swap fee in basis points (0.3% = 30 bps).
     */
    public static final long FEE_BPS = 30;

    /**
     * Total swap fee as decimal (0.003 = 0.3%).
     */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.003");

    /**
     * Protocol fee share in basis points (25% of total fee = 2500 bps of 10000).
     */
    public static final long PROTOCOL_FEE_SHARE_BPS = 2500;

    /**
     * Protocol fee share as decimal (0.25 = 25%).
     */
    public static final BigDecimal PROTOCOL_FEE_SHARE = new BigDecimal("0.25");

    /**
     * LP fee share as decimal (0.75 = 75%).
     */
    public static final BigDecimal LP_FEE_SHARE = new BigDecimal("0.75");

    // Derived values (for convenience)
    public static final long LP_FEE_BPS = 22;  // 22.5 bps rounded down
    public static final long PROTOCOL_FEE_BPS = 8;  // 7.5 bps rounded up

    // ========================================
    // INPUT VALIDATION LIMITS
    // ========================================

    /**
     * Maximum allowed price impact in basis points (10% = 1000 bps).
     * Prevents users from setting unrealistic slippage tolerance.
     */
    public static final int MAX_PRICE_IMPACT_BPS = 1000;

    /**
     * Maximum deadline duration in seconds (60 minutes).
     * Prevents indefinite swap validity.
     */
    public static final long MAX_DEADLINE_SECONDS = 3600;

    /**
     * Default deadline duration in seconds (10 minutes).
     */
    public static final long DEFAULT_DEADLINE_SECONDS = 600;

    /**
     * Minimum swap amount to prevent dust attacks.
     */
    public static final BigDecimal MIN_SWAP_AMOUNT = new BigDecimal("0.000001");

    /**
     * Maximum swap amount relative to pool (50% of reserve).
     * Prevents single swap from draining pool.
     */
    public static final BigDecimal MAX_SWAP_RATIO = new BigDecimal("0.5");

    // ========================================
    // BASIS POINTS UTILITIES
    // ========================================

    /**
     * Basis points representation of 100% (10000 bps = 100%).
     */
    public static final int BPS_100_PERCENT = 10000;

    /**
     * Convert basis points to decimal.
     * Example: 30 bps → 0.003
     */
    public static BigDecimal bpsToDecimal(long bps) {
        return new BigDecimal(bps).divide(new BigDecimal(BPS_100_PERCENT), SCALE, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Convert decimal to basis points.
     * Example: 0.003 → 30 bps
     */
    public static long decimalToBps(BigDecimal decimal) {
        return decimal.multiply(new BigDecimal(BPS_100_PERCENT))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .longValue();
    }

    // ========================================
    // IDEMPOTENCY
    // ========================================

    /**
     * Header name for idempotency key.
     */
    public static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    /**
     * Cache duration for idempotency keys (24 hours in seconds).
     */
    public static final long IDEMPOTENCY_CACHE_DURATION_SECONDS = 86400;

    // ========================================
    // SECURITY
    // ========================================

    /**
     * JWT claim containing party ID.
     */
    public static final String JWT_PARTY_CLAIM = "sub";

    /**
     * Maximum allowed operations per party per minute (rate limiting).
     */
    public static final int MAX_OPERATIONS_PER_MINUTE = 60;
}
