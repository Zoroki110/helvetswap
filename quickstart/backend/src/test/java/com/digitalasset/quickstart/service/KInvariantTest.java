// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AMM k-invariant (constant product formula).
 *
 * The constant product formula: k = reserveA * reserveB
 *
 * After any swap (excluding fees), k should be preserved or increase:
 * k_after >= k_before
 *
 * In practice, k increases slightly due to fees added to reserves.
 */
@DisplayName("K-Invariant Tests")
class KInvariantTest {

    private static final int SCALE = 10;

    @Test
    @DisplayName("K-invariant is preserved in swap (without fees)")
    void testKInvariantPreserved() {
        // Arrange - Initial pool state
        BigDecimal reserveA = new BigDecimal("100.0"); // 100 ETH
        BigDecimal reserveB = new BigDecimal("200000.0"); // 200k USDC
        BigDecimal k_before = calculateK(reserveA, reserveB);

        // Act - Swap 1 ETH for USDC (no fees for this test)
        BigDecimal inputAmount = new BigDecimal("1.0");
        BigDecimal outputAmount = calculateOutputAmount(inputAmount, reserveA, reserveB);

        BigDecimal newReserveA = reserveA.add(inputAmount);
        BigDecimal newReserveB = reserveB.subtract(outputAmount);
        BigDecimal k_after = calculateK(newReserveA, newReserveB);

        // Assert
        // Due to rounding, k_after should be very close to k_before
        BigDecimal tolerance = new BigDecimal("0.0001");
        assertTrue(k_after.subtract(k_before).abs().compareTo(tolerance) < 0,
            "K should be preserved (within rounding tolerance): k_before=" + k_before + ", k_after=" + k_after);
    }

    @Test
    @DisplayName("K-invariant increases with fees")
    void testKInvariantIncreasesWithFees() {
        // Arrange
        BigDecimal reserveA = new BigDecimal("100.0");
        BigDecimal reserveB = new BigDecimal("200000.0");
        BigDecimal k_before = calculateK(reserveA, reserveB);

        // Act - Swap with 0.3% fee
        BigDecimal inputAmount = new BigDecimal("1.0");
        BigDecimal fee = inputAmount.multiply(new BigDecimal("0.003")); // 0.3% fee
        BigDecimal inputAfterFee = inputAmount.subtract(fee);

        BigDecimal outputAmount = calculateOutputAmount(inputAfterFee, reserveA, reserveB);

        // New reserves include the full input (including fee)
        BigDecimal newReserveA = reserveA.add(inputAmount); // Fee stays in pool
        BigDecimal newReserveB = reserveB.subtract(outputAmount);
        BigDecimal k_after = calculateK(newReserveA, newReserveB);

        // Assert
        assertTrue(k_after.compareTo(k_before) > 0,
            "K should increase when fees are included: k_before=" + k_before + ", k_after=" + k_after);
    }

    @Test
    @DisplayName("Multiple swaps maintain k >= k_initial")
    void testMultipleSwapsMaintainK() {
        // Arrange
        BigDecimal reserveA = new BigDecimal("100.0");
        BigDecimal reserveB = new BigDecimal("200000.0");
        BigDecimal k_initial = calculateK(reserveA, reserveB);

        // Act - Perform 5 swaps
        for (int i = 0; i < 5; i++) {
            BigDecimal inputAmount = new BigDecimal("0.5");
            BigDecimal fee = inputAmount.multiply(new BigDecimal("0.003"));
            BigDecimal inputAfterFee = inputAmount.subtract(fee);

            BigDecimal outputAmount = calculateOutputAmount(inputAfterFee, reserveA, reserveB);

            reserveA = reserveA.add(inputAmount);
            reserveB = reserveB.subtract(outputAmount);

            BigDecimal k_current = calculateK(reserveA, reserveB);

            // Assert k never decreases
            assertTrue(k_current.compareTo(k_initial) >= 0,
                "K should never decrease: k_initial=" + k_initial + ", k_current=" + k_current);

            k_initial = k_current; // Update for next iteration
        }
    }

    @Test
    @DisplayName("Reverse swap maintains k-invariant")
    void testReverseSwapMaintainsK() {
        // Arrange
        BigDecimal reserveA = new BigDecimal("100.0");
        BigDecimal reserveB = new BigDecimal("200000.0");
        BigDecimal k_initial = calculateK(reserveA, reserveB);

        // Act - Swap A→B then B→A
        // Swap 1: A→B
        BigDecimal inputA = new BigDecimal("1.0");
        BigDecimal outputB = calculateOutputAmount(inputA, reserveA, reserveB);
        reserveA = reserveA.add(inputA);
        reserveB = reserveB.subtract(outputB);
        BigDecimal k_after_swap1 = calculateK(reserveA, reserveB);

        // Swap 2: B→A (reverse)
        BigDecimal inputB = outputB;
        BigDecimal outputA = calculateOutputAmount(inputB, reserveB, reserveA);
        reserveB = reserveB.add(inputB);
        reserveA = reserveA.subtract(outputA);
        BigDecimal k_after_swap2 = calculateK(reserveA, reserveB);

        // Assert
        assertTrue(k_after_swap1.compareTo(k_initial) >= 0, "K should increase after swap 1");
        assertTrue(k_after_swap2.compareTo(k_after_swap1) >= 0, "K should increase after swap 2");
    }

    @Test
    @DisplayName("Large swap maintains k-invariant")
    void testLargeSwapMaintainsK() {
        // Arrange
        BigDecimal reserveA = new BigDecimal("100.0");
        BigDecimal reserveB = new BigDecimal("200000.0");
        BigDecimal k_before = calculateK(reserveA, reserveB);

        // Act - Large swap (10% of pool)
        BigDecimal inputAmount = new BigDecimal("10.0");
        BigDecimal fee = inputAmount.multiply(new BigDecimal("0.003"));
        BigDecimal inputAfterFee = inputAmount.subtract(fee);

        BigDecimal outputAmount = calculateOutputAmount(inputAfterFee, reserveA, reserveB);

        BigDecimal newReserveA = reserveA.add(inputAmount);
        BigDecimal newReserveB = reserveB.subtract(outputAmount);
        BigDecimal k_after = calculateK(newReserveA, newReserveB);

        // Assert
        assertTrue(k_after.compareTo(k_before) > 0,
            "K should increase even with large swaps: k_before=" + k_before + ", k_after=" + k_after);
    }

    @Test
    @DisplayName("K calculation is commutative")
    void testKIsCommutative() {
        // Arrange
        BigDecimal reserveA = new BigDecimal("100.0");
        BigDecimal reserveB = new BigDecimal("200000.0");

        // Act
        BigDecimal k1 = calculateK(reserveA, reserveB);
        BigDecimal k2 = calculateK(reserveB, reserveA);

        // Assert
        assertEquals(k1, k2, "K should be commutative: k(A,B) = k(B,A)");
    }

    @Test
    @DisplayName("K is always positive for positive reserves")
    void testKIsPositive() {
        // Arrange
        BigDecimal[] testReserves = {
            new BigDecimal("0.1"),
            new BigDecimal("1.0"),
            new BigDecimal("100.0"),
            new BigDecimal("1000000.0")
        };

        // Act & Assert
        for (BigDecimal reserve : testReserves) {
            BigDecimal k = calculateK(reserve, reserve);
            assertTrue(k.compareTo(BigDecimal.ZERO) > 0,
                "K should be positive for reserve=" + reserve);
        }
    }

    // Helper methods

    /**
     * Calculate k-invariant: k = reserveA * reserveB
     */
    private BigDecimal calculateK(BigDecimal reserveA, BigDecimal reserveB) {
        return reserveA.multiply(reserveB).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Calculate output amount using constant product formula.
     * Formula: outputAmount = (reserveOut * inputAmount) / (reserveIn + inputAmount)
     *
     * This ensures: (reserveIn + inputAmount) * (reserveOut - outputAmount) = k
     */
    private BigDecimal calculateOutputAmount(BigDecimal inputAmount,
                                             BigDecimal reserveIn,
                                             BigDecimal reserveOut) {
        // outputAmount = (reserveOut * inputAmount) / (reserveIn + inputAmount)
        BigDecimal numerator = reserveOut.multiply(inputAmount);
        BigDecimal denominator = reserveIn.add(inputAmount);

        return numerator.divide(denominator, SCALE, RoundingMode.DOWN);
    }
}
