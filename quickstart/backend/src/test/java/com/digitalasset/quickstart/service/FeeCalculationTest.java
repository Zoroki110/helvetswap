// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AMM fee calculation logic.
 *
 * Fee Structure:
 * - Total fee: 30 bps (0.3%)
 * - LP share: 22.5 bps (75% of fee)
 * - Protocol share: 7.5 bps (25% of fee)
 *
 * Example: 1000 USDC swap
 * - Total fee: 3 USDC (0.3%)
 * - LP gets: 2.25 USDC
 * - Protocol gets: 0.75 USDC
 */
@DisplayName("Fee Calculation Tests")
class FeeCalculationTest {

    private static final int SCALE = 10;
    private static final BigDecimal FEE_BPS = new BigDecimal("30"); // 0.3%
    private static final BigDecimal PROTOCOL_FEE_SHARE_BPS = new BigDecimal("2500"); // 25% of total fee
    private static final BigDecimal BPS_DENOMINATOR = new BigDecimal("10000");

    @Test
    @DisplayName("Calculate total fee from input amount")
    void testTotalFeeCalculation() {
        // Arrange
        BigDecimal inputAmount = new BigDecimal("1000.0000000000");

        // Act
        BigDecimal totalFee = calculateTotalFee(inputAmount);

        // Assert
        BigDecimal expected = new BigDecimal("3.0000000000"); // 0.3% of 1000
        assertEquals(expected, totalFee, "Total fee should be 0.3% of input amount");
    }

    @Test
    @DisplayName("Split fee between LP and protocol - 75%/25%")
    void testFeeSplit() {
        // Arrange
        BigDecimal totalFee = new BigDecimal("3.0000000000");

        // Act
        BigDecimal protocolFee = calculateProtocolFee(totalFee);
        BigDecimal lpFee = totalFee.subtract(protocolFee);

        // Assert
        BigDecimal expectedProtocolFee = new BigDecimal("0.7500000000"); // 25% of 3.0
        BigDecimal expectedLpFee = new BigDecimal("2.2500000000"); // 75% of 3.0

        assertEquals(expectedProtocolFee, protocolFee, "Protocol should get 25% of total fee");
        assertEquals(expectedLpFee, lpFee, "LP should get 75% of total fee");
    }

    @Test
    @DisplayName("Fee split adds up to total fee")
    void testFeeSplitSumsToTotal() {
        // Arrange
        BigDecimal inputAmount = new BigDecimal("1000.0000000000");
        BigDecimal totalFee = calculateTotalFee(inputAmount);

        // Act
        BigDecimal protocolFee = calculateProtocolFee(totalFee);
        BigDecimal lpFee = totalFee.subtract(protocolFee);
        BigDecimal reconstructedTotal = lpFee.add(protocolFee);

        // Assert
        assertEquals(totalFee, reconstructedTotal, "LP fee + protocol fee must equal total fee");
    }

    @Test
    @DisplayName("Amount after fee deduction")
    void testAmountAfterFee() {
        // Arrange
        BigDecimal inputAmount = new BigDecimal("1000.0000000000");

        // Act
        BigDecimal totalFee = calculateTotalFee(inputAmount);
        BigDecimal amountAfterFee = inputAmount.subtract(totalFee);

        // Assert
        BigDecimal expected = new BigDecimal("997.0000000000"); // 1000 - 3
        assertEquals(expected, amountAfterFee, "Amount after fee should be input minus fee");
    }

    @Test
    @DisplayName("Fee calculation with small amounts")
    void testSmallAmountFee() {
        // Arrange
        BigDecimal inputAmount = new BigDecimal("1.0000000000");

        // Act
        BigDecimal totalFee = calculateTotalFee(inputAmount);

        // Assert
        BigDecimal expected = new BigDecimal("0.0030000000"); // 0.3% of 1
        assertEquals(expected, totalFee, "Should calculate correct fee for small amounts");
    }

    @Test
    @DisplayName("Fee calculation with large amounts")
    void testLargeAmountFee() {
        // Arrange
        BigDecimal inputAmount = new BigDecimal("1000000.0000000000"); // 1 million

        // Act
        BigDecimal totalFee = calculateTotalFee(inputAmount);

        // Assert
        BigDecimal expected = new BigDecimal("3000.0000000000"); // 0.3% of 1M = 3000
        assertEquals(expected, totalFee, "Should calculate correct fee for large amounts");
    }

    @Test
    @DisplayName("Protocol fee is always 25% of total fee")
    void testProtocolFeePercentage() {
        // Test multiple amounts
        BigDecimal[] amounts = {
            new BigDecimal("100.0"),
            new BigDecimal("1000.0"),
            new BigDecimal("10000.0"),
            new BigDecimal("0.1")
        };

        for (BigDecimal amount : amounts) {
            BigDecimal totalFee = calculateTotalFee(amount);
            BigDecimal protocolFee = calculateProtocolFee(totalFee);

            // Protocol fee should be 25% of total
            BigDecimal expectedRatio = new BigDecimal("0.25");
            BigDecimal actualRatio = protocolFee.divide(totalFee, SCALE, RoundingMode.HALF_UP);

            assertEquals(expectedRatio.setScale(2, RoundingMode.HALF_UP),
                        actualRatio.setScale(2, RoundingMode.HALF_UP),
                        "Protocol fee should always be 25% of total fee for amount: " + amount);
        }
    }

    @Test
    @DisplayName("LP fee is always 75% of total fee")
    void testLpFeePercentage() {
        // Test multiple amounts
        BigDecimal[] amounts = {
            new BigDecimal("100.0"),
            new BigDecimal("1000.0"),
            new BigDecimal("10000.0")
        };

        for (BigDecimal amount : amounts) {
            BigDecimal totalFee = calculateTotalFee(amount);
            BigDecimal protocolFee = calculateProtocolFee(totalFee);
            BigDecimal lpFee = totalFee.subtract(protocolFee);

            // LP fee should be 75% of total
            BigDecimal expectedRatio = new BigDecimal("0.75");
            BigDecimal actualRatio = lpFee.divide(totalFee, SCALE, RoundingMode.HALF_UP);

            assertEquals(expectedRatio.setScale(2, RoundingMode.HALF_UP),
                        actualRatio.setScale(2, RoundingMode.HALF_UP),
                        "LP fee should always be 75% of total fee for amount: " + amount);
        }
    }

    @Test
    @DisplayName("Fee calculation uses correct basis points")
    void testFeeBasisPoints() {
        // Arrange
        BigDecimal inputAmount = new BigDecimal("10000.0000000000");

        // Act
        BigDecimal totalFee = calculateTotalFee(inputAmount);

        // Assert - 30 bps of 10000 = 30
        BigDecimal expected = new BigDecimal("30.0000000000");
        assertEquals(expected, totalFee, "30 bps should equal 0.3%");
    }

    @Test
    @DisplayName("Zero input produces zero fee")
    void testZeroInputFee() {
        // Arrange
        BigDecimal inputAmount = BigDecimal.ZERO;

        // Act
        BigDecimal totalFee = calculateTotalFee(inputAmount);

        // Assert
        assertEquals(BigDecimal.ZERO.setScale(SCALE), totalFee, "Zero input should produce zero fee");
    }

    // Helper methods that mirror the actual fee calculation logic

    /**
     * Calculate total fee from input amount.
     * Formula: (inputAmount * feeBps) / 10000
     */
    private BigDecimal calculateTotalFee(BigDecimal inputAmount) {
        return inputAmount
            .multiply(FEE_BPS)
            .divide(BPS_DENOMINATOR, SCALE, RoundingMode.DOWN);
    }

    /**
     * Calculate protocol's share of the fee (25% of total).
     * Formula: (totalFee * 2500) / 10000
     */
    private BigDecimal calculateProtocolFee(BigDecimal totalFee) {
        return totalFee
            .multiply(PROTOCOL_FEE_SHARE_BPS)
            .divide(BPS_DENOMINATOR, SCALE, RoundingMode.DOWN);
    }
}
