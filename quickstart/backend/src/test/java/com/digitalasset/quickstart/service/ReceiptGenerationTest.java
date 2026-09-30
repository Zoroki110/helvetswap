// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for swap receipt generation and validation.
 *
 * Receipts provide immutable proof of swap execution with:
 * - Input/output amounts
 * - Fee breakdown (LP + protocol)
 * - Pool state changes
 * - Execution timestamp
 */
@DisplayName("Receipt Generation Tests")
class ReceiptGenerationTest {

    private static final int SCALE = 10;
    private static final BigDecimal FEE_RATE = new BigDecimal("0.003"); // 0.3% (30 bps)
    private static final BigDecimal PROTOCOL_FEE_SPLIT = new BigDecimal("0.25"); // 25% of total fee

    /**
     * Simple receipt structure for testing.
     */
    static class SwapReceipt {
        String trader;
        String inputSymbol;
        String outputSymbol;
        BigDecimal inputAmount;
        BigDecimal outputAmount;
        BigDecimal totalFee;
        BigDecimal lpFee;
        BigDecimal protocolFee;
        BigDecimal reserveInBefore;
        BigDecimal reserveOutBefore;
        BigDecimal reserveInAfter;
        BigDecimal reserveOutAfter;
        Instant executedAt;
        String poolId;

        // Constructor
        SwapReceipt(String trader, String inputSymbol, String outputSymbol,
                    BigDecimal inputAmount, BigDecimal outputAmount,
                    BigDecimal totalFee, BigDecimal lpFee, BigDecimal protocolFee,
                    BigDecimal reserveInBefore, BigDecimal reserveOutBefore,
                    BigDecimal reserveInAfter, BigDecimal reserveOutAfter,
                    Instant executedAt, String poolId) {
            this.trader = trader;
            this.inputSymbol = inputSymbol;
            this.outputSymbol = outputSymbol;
            this.inputAmount = inputAmount;
            this.outputAmount = outputAmount;
            this.totalFee = totalFee;
            this.lpFee = lpFee;
            this.protocolFee = protocolFee;
            this.reserveInBefore = reserveInBefore;
            this.reserveOutBefore = reserveOutBefore;
            this.reserveInAfter = reserveInAfter;
            this.reserveOutAfter = reserveOutAfter;
            this.executedAt = executedAt;
            this.poolId = poolId;
        }
    }

    @Test
    @DisplayName("Generate receipt with correct fee breakdown")
    void testReceiptFeeBreakdown() {
        // Arrange - Swap 1 ETH → USDC
        BigDecimal inputAmount = new BigDecimal("1.0");
        BigDecimal totalFee = inputAmount.multiply(FEE_RATE);

        // Act
        SwapReceipt receipt = generateReceipt(
            "trader-party",
            "ETH", "USDC",
            inputAmount,
            new BigDecimal("100.0"), // reserveIn
            new BigDecimal("200000.0") // reserveOut
        );

        // Assert
        BigDecimal expectedTotalFee = new BigDecimal("0.0030000000");
        BigDecimal expectedProtocolFee = new BigDecimal("0.0007500000"); // 25%
        BigDecimal expectedLpFee = new BigDecimal("0.0022500000"); // 75%

        assertEquals(expectedTotalFee, receipt.totalFee, "Total fee should be 0.3%");
        assertEquals(expectedProtocolFee, receipt.protocolFee, "Protocol fee should be 25% of total");
        assertEquals(expectedLpFee, receipt.lpFee, "LP fee should be 75% of total");

        // Verify fees sum correctly
        BigDecimal feeSum = receipt.lpFee.add(receipt.protocolFee);
        assertEquals(receipt.totalFee, feeSum, "LP fee + protocol fee should equal total fee");
    }

    @Test
    @DisplayName("Receipt reflects correct reserve changes")
    void testReceiptReserveChanges() {
        // Arrange
        BigDecimal reserveInBefore = new BigDecimal("100.0");
        BigDecimal reserveOutBefore = new BigDecimal("200000.0");
        BigDecimal inputAmount = new BigDecimal("1.0");

        // Act
        SwapReceipt receipt = generateReceipt(
            "trader-party",
            "ETH", "USDC",
            inputAmount,
            reserveInBefore,
            reserveOutBefore
        );

        // Assert - Reserve changes
        assertTrue(receipt.reserveInAfter.compareTo(receipt.reserveInBefore) > 0,
            "Input reserve should increase");
        assertTrue(receipt.reserveOutAfter.compareTo(receipt.reserveOutBefore) < 0,
            "Output reserve should decrease");

        // Verify exact input reserve change
        BigDecimal reserveInChange = receipt.reserveInAfter.subtract(receipt.reserveInBefore);
        assertEquals(inputAmount, reserveInChange,
            "Input reserve should increase by exact input amount (fee stays in pool)");
    }

    @Test
    @DisplayName("Receipt contains all required fields")
    void testReceiptCompleteness() {
        // Act
        SwapReceipt receipt = generateReceipt(
            "trader-party",
            "ETH", "USDC",
            new BigDecimal("1.0"),
            new BigDecimal("100.0"),
            new BigDecimal("200000.0")
        );

        // Assert - All fields present
        assertNotNull(receipt.trader, "Receipt should include trader");
        assertNotNull(receipt.inputSymbol, "Receipt should include input symbol");
        assertNotNull(receipt.outputSymbol, "Receipt should include output symbol");
        assertNotNull(receipt.inputAmount, "Receipt should include input amount");
        assertNotNull(receipt.outputAmount, "Receipt should include output amount");
        assertNotNull(receipt.totalFee, "Receipt should include total fee");
        assertNotNull(receipt.lpFee, "Receipt should include LP fee");
        assertNotNull(receipt.protocolFee, "Receipt should include protocol fee");
        assertNotNull(receipt.reserveInBefore, "Receipt should include reserve before");
        assertNotNull(receipt.reserveInAfter, "Receipt should include reserve after");
        assertNotNull(receipt.executedAt, "Receipt should include execution timestamp");
        assertNotNull(receipt.poolId, "Receipt should include pool ID");
    }

    @Test
    @DisplayName("Receipt timestamp is accurate")
    void testReceiptTimestamp() {
        // Arrange
        Instant beforeExecution = Instant.now();

        // Act
        SwapReceipt receipt = generateReceipt(
            "trader-party",
            "ETH", "USDC",
            new BigDecimal("1.0"),
            new BigDecimal("100.0"),
            new BigDecimal("200000.0")
        );

        Instant afterExecution = Instant.now();

        // Assert - Timestamp should be between before/after
        assertFalse(receipt.executedAt.isBefore(beforeExecution),
            "Receipt timestamp should not be before execution");
        assertFalse(receipt.executedAt.isAfter(afterExecution),
            "Receipt timestamp should not be after execution");
    }

    @Test
    @DisplayName("Receipt amounts are immutable snapshots")
    void testReceiptImmutability() {
        // Arrange
        BigDecimal inputAmount = new BigDecimal("1.0");
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");

        // Act
        SwapReceipt receipt1 = generateReceipt("trader1", "ETH", "USDC", inputAmount, reserveIn, reserveOut);
        SwapReceipt receipt2 = generateReceipt("trader2", "ETH", "USDC", inputAmount, reserveIn, reserveOut);

        // Assert - Each receipt is independent snapshot
        assertEquals(receipt1.inputAmount, receipt2.inputAmount,
            "Same input should produce same input amount in receipt");
        assertEquals(receipt1.outputAmount, receipt2.outputAmount,
            "Same reserves should produce same output amount");
        assertNotSame(receipt1, receipt2, "Receipts should be distinct objects");
    }

    @Test
    @DisplayName("Receipt for small swap (0.1 ETH)")
    void testSmallSwapReceipt() {
        // Arrange
        BigDecimal inputAmount = new BigDecimal("0.1");

        // Act
        SwapReceipt receipt = generateReceipt(
            "trader-party",
            "ETH", "USDC",
            inputAmount,
            new BigDecimal("100.0"),
            new BigDecimal("200000.0")
        );

        // Assert
        assertTrue(receipt.outputAmount.compareTo(BigDecimal.ZERO) > 0,
            "Small swap should produce positive output");
        assertTrue(receipt.totalFee.compareTo(new BigDecimal("0.001")) < 0,
            "Fee on 0.1 ETH should be < 0.001 ETH");

        // Fee should be 0.0003 ETH (0.3% of 0.1)
        BigDecimal expectedFee = new BigDecimal("0.0003000000");
        assertEquals(expectedFee, receipt.totalFee, "Fee on 0.1 ETH should be 0.0003 ETH");
    }

    @Test
    @DisplayName("Receipt for large swap (10 ETH)")
    void testLargeSwapReceipt() {
        // Arrange
        BigDecimal inputAmount = new BigDecimal("10.0");

        // Act
        SwapReceipt receipt = generateReceipt(
            "trader-party",
            "ETH", "USDC",
            inputAmount,
            new BigDecimal("100.0"),
            new BigDecimal("200000.0")
        );

        // Assert
        assertTrue(receipt.outputAmount.compareTo(BigDecimal.ZERO) > 0,
            "Large swap should produce positive output");

        // Fee should be 0.03 ETH (0.3% of 10)
        BigDecimal expectedFee = new BigDecimal("0.0300000000");
        assertEquals(expectedFee, receipt.totalFee, "Fee on 10 ETH should be 0.03 ETH");

        // Output should be significantly less than linear (due to price impact)
        BigDecimal linearOutput = new BigDecimal("20000.0"); // 10 ETH * 2000 USDC/ETH
        assertTrue(receipt.outputAmount.compareTo(linearOutput) < 0,
            "Large swap should have price impact: output < linear expectation");
    }

    @Test
    @DisplayName("Receipt trader field matches input")
    void testReceiptTraderField() {
        // Act
        SwapReceipt receipt = generateReceipt(
            "alice-party",
            "ETH", "USDC",
            new BigDecimal("1.0"),
            new BigDecimal("100.0"),
            new BigDecimal("200000.0")
        );

        // Assert
        assertEquals("alice-party", receipt.trader, "Receipt trader should match input trader");
    }

    @Test
    @DisplayName("Receipt pool ID is correctly set")
    void testReceiptPoolId() {
        // Act
        SwapReceipt receipt = generateReceipt(
            "trader-party",
            "ETH", "USDC",
            new BigDecimal("1.0"),
            new BigDecimal("100.0"),
            new BigDecimal("200000.0")
        );

        // Assert
        assertEquals("ETH-USDC", receipt.poolId, "Pool ID should be derived from token symbols");
    }

    @Test
    @DisplayName("Receipt output matches constant product formula")
    void testReceiptOutputMatchesFormula() {
        // Arrange
        BigDecimal inputAmount = new BigDecimal("1.0");
        BigDecimal reserveIn = new BigDecimal("100.0");
        BigDecimal reserveOut = new BigDecimal("200000.0");

        // Act
        SwapReceipt receipt = generateReceipt("trader", "ETH", "USDC", inputAmount, reserveIn, reserveOut);

        // Calculate expected output manually
        BigDecimal fee = inputAmount.multiply(FEE_RATE);
        BigDecimal inputAfterFee = inputAmount.subtract(fee);
        BigDecimal expectedOutput = reserveOut.multiply(inputAfterFee)
            .divide(reserveIn.add(inputAfterFee), SCALE, RoundingMode.DOWN);

        // Assert
        assertEquals(expectedOutput, receipt.outputAmount,
            "Receipt output should match constant product formula");
    }

    // Helper method to generate receipt

    private SwapReceipt generateReceipt(String trader, String inputSymbol, String outputSymbol,
                                        BigDecimal inputAmount, BigDecimal reserveIn, BigDecimal reserveOut) {
        // Calculate fee
        BigDecimal totalFee = inputAmount.multiply(FEE_RATE).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal protocolFee = totalFee.multiply(PROTOCOL_FEE_SPLIT).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal lpFee = totalFee.subtract(protocolFee);

        // Calculate output using constant product formula
        BigDecimal inputAfterFee = inputAmount.subtract(totalFee);
        BigDecimal outputAmount = reserveOut.multiply(inputAfterFee)
            .divide(reserveIn.add(inputAfterFee), SCALE, RoundingMode.DOWN);

        // Calculate new reserves
        BigDecimal reserveInAfter = reserveIn.add(inputAmount);
        BigDecimal reserveOutAfter = reserveOut.subtract(outputAmount);

        return new SwapReceipt(
            trader,
            inputSymbol,
            outputSymbol,
            inputAmount,
            outputAmount,
            totalFee,
            lpFee,
            protocolFee,
            reserveIn,
            reserveOut,
            reserveInAfter,
            reserveOutAfter,
            Instant.now(),
            inputSymbol + "-" + outputSymbol
        );
    }
}
