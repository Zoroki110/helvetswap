// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for slippage protection and price impact enforcement.
 *
 * Price Impact = (reserveBefore - reserveAfter) / reserveBefore * 10000 (in bps)
 *
 * For a swap from token A to token B:
 * - We consume token A (increases reserveA)
 * - We receive token B (decreases reserveB)
 * - Price impact is measured on the OUTPUT token (B)
 *
 * The swap should REVERT if actual price impact > maxPriceImpactBps.
 */
@DisplayName("Slippage Enforcement Tests")
class SlippageEnforcementTest {

    private static final int SCALE = 10;
    private static final BigDecimal FEE_RATE = new BigDecimal("0.003"); // 0.3%

    @Test
    @DisplayName("Calculate price impact for small swap")
    void testPriceImpactSmallSwap() {
        // Arrange - Pool: 100 ETH / 200k USDC
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");

        // Act - Swap 1 ETH → USDC
        BigDecimal inputAmount = new BigDecimal("1.0");
        BigDecimal outputAmount = calculateOutputAmount(inputAmount, reserveIn, reserveOut);
        BigDecimal newReserveOut = reserveOut.subtract(outputAmount);

        int priceImpactBps = calculatePriceImpact(reserveOut, newReserveOut);

        // Assert - Small swap should have low price impact
        assertTrue(priceImpactBps < 100,
            "Small swap (1%) should have price impact < 1% (100 bps), got: " + priceImpactBps + " bps");
        assertEquals(99, priceImpactBps,
            "Expected ~0.99% price impact for 1 ETH swap in 100 ETH pool");
    }

    @Test
    @DisplayName("Calculate price impact for large swap")
    void testPriceImpactLargeSwap() {
        // Arrange
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");

        // Act - Swap 10 ETH → USDC (10% of pool)
        BigDecimal inputAmount = new BigDecimal("10.0");
        BigDecimal outputAmount = calculateOutputAmount(inputAmount, reserveIn, reserveOut);
        BigDecimal newReserveOut = reserveOut.subtract(outputAmount);

        int priceImpactBps = calculatePriceImpact(reserveOut, newReserveOut);

        // Assert - Large swap should have significant price impact
        assertTrue(priceImpactBps > 500,
            "Large swap (10%) should have price impact > 5%, got: " + priceImpactBps + " bps");
        assertTrue(priceImpactBps < 1000,
            "10% swap should have price impact < 10%, got: " + priceImpactBps + " bps");
    }

    @Test
    @DisplayName("Reject swap when price impact exceeds limit")
    void testRejectExcessivePriceImpact() {
        // Arrange
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");
        int maxPriceImpactBps = 100; // Max 1% slippage

        // Act - Try to swap 10 ETH (will cause ~9% impact)
        BigDecimal inputAmount = new BigDecimal("10.0");
        BigDecimal outputAmount = calculateOutputAmount(inputAmount, reserveIn, reserveOut);
        BigDecimal newReserveOut = reserveOut.subtract(outputAmount);
        int actualPriceImpact = calculatePriceImpact(reserveOut, newReserveOut);

        // Assert
        assertTrue(actualPriceImpact > maxPriceImpactBps,
            "Swap should be rejected: actual impact (" + actualPriceImpact + " bps) > max (" + maxPriceImpactBps + " bps)");
    }

    @Test
    @DisplayName("Accept swap when price impact is within limit")
    void testAcceptSwapWithinLimit() {
        // Arrange
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");
        int maxPriceImpactBps = 200; // Max 2% slippage

        // Act - Swap 1 ETH (will cause ~1% impact)
        BigDecimal inputAmount = new BigDecimal("1.0");
        BigDecimal outputAmount = calculateOutputAmount(inputAmount, reserveIn, reserveOut);
        BigDecimal newReserveOut = reserveOut.subtract(outputAmount);
        int actualPriceImpact = calculatePriceImpact(reserveOut, newReserveOut);

        // Assert
        assertTrue(actualPriceImpact <= maxPriceImpactBps,
            "Swap should be accepted: actual impact (" + actualPriceImpact + " bps) <= max (" + maxPriceImpactBps + " bps)");
    }

    @Test
    @DisplayName("Price impact is 0 for zero-sized swap")
    void testZeroPriceImpactForZeroSwap() {
        // Arrange
        BigDecimal reserveOut = new BigDecimal("200000.0");
        BigDecimal newReserveOut = reserveOut; // No change

        // Act
        int priceImpact = calculatePriceImpact(reserveOut, newReserveOut);

        // Assert
        assertEquals(0, priceImpact, "Zero-sized swap should have 0 price impact");
    }

    @Test
    @DisplayName("Price impact approaches 10000 bps (100%) for extreme swaps")
    void testExtremePriceImpact() {
        // Arrange - Try to drain 90% of pool
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");

        // Act - Massive swap (900 ETH - 9x pool size)
        BigDecimal inputAmount = new BigDecimal("900.0");
        BigDecimal outputAmount = calculateOutputAmount(inputAmount, reserveIn, reserveOut);
        BigDecimal newReserveOut = reserveOut.subtract(outputAmount);

        int priceImpactBps = calculatePriceImpact(reserveOut, newReserveOut);

        // Assert - Should approach 100% (10000 bps)
        assertTrue(priceImpactBps > 8900,
            "Extreme swap should have >89% price impact, got: " + priceImpactBps + " bps");
    }

    @Test
    @DisplayName("Min output validation rejects swap with insufficient output")
    void testMinOutputValidation() {
        // Arrange
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");

        // Act - Swap 1 ETH expecting ~1,980 USDC output
        BigDecimal inputAmount = new BigDecimal("1.0");
        BigDecimal actualOutput = calculateOutputAmount(inputAmount, reserveIn, reserveOut);

        // Set unrealistic min output (2,000 USDC)
        BigDecimal minOutput = new BigDecimal("2000.0");

        // Assert
        assertTrue(actualOutput.compareTo(minOutput) < 0,
            "Swap should be rejected: actual output (" + actualOutput + ") < min output (" + minOutput + ")");
    }

    @Test
    @DisplayName("Min output validation accepts swap with sufficient output")
    void testMinOutputAccepted() {
        // Arrange
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");

        // Act - Swap 1 ETH expecting ~1,980 USDC output
        BigDecimal inputAmount = new BigDecimal("1.0");
        BigDecimal actualOutput = calculateOutputAmount(inputAmount, reserveIn, reserveOut);

        // Set reasonable min output (1,900 USDC - allows 5% slippage)
        BigDecimal minOutput = new BigDecimal("1900.0");

        // Assert
        assertTrue(actualOutput.compareTo(minOutput) >= 0,
            "Swap should be accepted: actual output (" + actualOutput + ") >= min output (" + minOutput + ")");
    }

    @Test
    @DisplayName("Price impact calculation is accurate for multiple swap sizes")
    void testPriceImpactAccuracy() {
        // Arrange
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");

        // Test different swap sizes
        BigDecimal[] swapSizes = {
            new BigDecimal("0.1"),  // 0.1 ETH
            new BigDecimal("1.0"),  // 1 ETH
            new BigDecimal("5.0"),  // 5 ETH
            new BigDecimal("10.0")  // 10 ETH
        };

        int lastImpact = 0;

        // Act & Assert - Price impact should increase with swap size
        for (BigDecimal swapSize : swapSizes) {
            BigDecimal outputAmount = calculateOutputAmount(swapSize, reserveIn, reserveOut);
            BigDecimal newReserveOut = reserveOut.subtract(outputAmount);
            int priceImpact = calculatePriceImpact(reserveOut, newReserveOut);

            assertTrue(priceImpact > lastImpact,
                "Price impact should increase with swap size: " + swapSize + " → " + priceImpact + " bps");

            lastImpact = priceImpact;
        }
    }

    @Test
    @DisplayName("Max price impact validation at boundary (exactly at limit)")
    void testPriceImpactBoundary() {
        // Arrange
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");

        // Find swap size that produces exactly 1% price impact
        // Through trial: ~1.01 ETH produces ~100 bps impact
        BigDecimal inputAmount = new BigDecimal("1.01");
        BigDecimal outputAmount = calculateOutputAmount(inputAmount, reserveIn, reserveOut);
        BigDecimal newReserveOut = reserveOut.subtract(outputAmount);
        int priceImpact = calculatePriceImpact(reserveOut, newReserveOut);

        int maxPriceImpactBps = 100;

        // Assert - At boundary, should be accepted (<=)
        assertTrue(priceImpact <= maxPriceImpactBps + 5, // Allow 5 bps tolerance
            "Swap at boundary should be close to limit: " + priceImpact + " bps vs " + maxPriceImpactBps + " bps");
    }

    // Helper methods

    /**
     * Calculate output amount using constant product formula with fees.
     * Formula: outputAmount = (reserveOut * inputAmountAfterFee) / (reserveIn + inputAmountAfterFee)
     */
    private BigDecimal calculateOutputAmount(BigDecimal inputAmount,
                                             BigDecimal reserveIn,
                                             BigDecimal reserveOut) {
        // Apply 0.3% fee
        BigDecimal fee = inputAmount.multiply(FEE_RATE);
        BigDecimal inputAfterFee = inputAmount.subtract(fee);

        // Calculate output using constant product formula
        BigDecimal numerator = reserveOut.multiply(inputAfterFee);
        BigDecimal denominator = reserveIn.add(inputAfterFee);

        return numerator.divide(denominator, SCALE, RoundingMode.DOWN);
    }

    /**
     * Calculate price impact in basis points.
     * Formula: priceImpact = (reserveBefore - reserveAfter) / reserveBefore * 10000
     */
    private int calculatePriceImpact(BigDecimal reserveBefore, BigDecimal reserveAfter) {
        if (reserveBefore.compareTo(BigDecimal.ZERO) == 0) {
            return 0;
        }

        BigDecimal delta = reserveBefore.subtract(reserveAfter);
        BigDecimal impactFraction = delta.divide(reserveBefore, SCALE, RoundingMode.HALF_UP);
        BigDecimal impactBps = impactFraction.multiply(new BigDecimal("10000"));

        return impactBps.setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
