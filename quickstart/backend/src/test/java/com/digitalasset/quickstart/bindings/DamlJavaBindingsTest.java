// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.bindings;

import helvet_amm.amm.pool.Pool;
import helvet_amm.token.token.Token;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import daml_stdlib_da_time_types.da.time.types.RelTime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DAML Java bindings generated from `daml codegen java`.
 *
 * These tests verify that:
 * 1. Token.java fields are accessible (getSymbol, getOwner, getAmount)
 * 2. Pool.java fields are accessible (getPoolId, getReserveA, getReserveB)
 * 3. Party.getParty field works correctly (critical!)
 * 4. ContractId<T> types are type-safe
 *
 * These are unit tests (no Spring context needed).
 */
class DamlJavaBindingsTest {

    /**
     * Test: Token field access patterns
     *
     * Verifies that generated Token.java class has correct getter patterns.
     * DAML fields become "get" prefixed public fields in Java.
     */
    @Test
    void testTokenFieldAccess() {
        // Create test parties
        Party issuer = new Party("issuer");
        Party alice = new Party("alice");

        // Create token using generated constructor
        Token token = new Token(
                issuer,
                alice,
                "ETH",
                new BigDecimal("100.5")
        );

        // Test field access
        assertNotNull(token.getIssuer, "Issuer field should be accessible");
        assertNotNull(token.getOwner, "Owner field should be accessible");
        assertNotNull(token.getSymbol, "Symbol field should be accessible");
        assertNotNull(token.getAmount, "Amount field should be accessible");

        // Test field values
        assertEquals(issuer, token.getIssuer, "Issuer should match");
        assertEquals(alice, token.getOwner, "Owner should match");
        assertEquals("ETH", token.getSymbol, "Symbol should match");
        assertEquals(new BigDecimal("100.5"), token.getAmount, "Amount should match");

        System.out.println("✅ Token field access works:");
        System.out.println("  Symbol: " + token.getSymbol);
        System.out.println("  Owner: " + token.getOwner.getParty);
        System.out.println("  Amount: " + token.getAmount);
    }

    /**
     * Test: Party.getParty field access (CRITICAL!)
     *
     * This is the field we use everywhere for party comparisons.
     * Pattern: party.getParty (NOT party.toString() or party.partyId)
     */
    @Test
    void testPartyGetPartyField() {
        Party alice = new Party("alice");
        Party bob = new Party("bob");

        // Critical: getParty field must exist and return string ID
        assertNotNull(alice.getParty, "Party.getParty field must exist");
        assertEquals("alice", alice.getParty, "Party.getParty should return party ID string");
        assertEquals("bob", bob.getParty, "Party.getParty should return party ID string");

        // This is how we compare parties in production code
        assertFalse(alice.getParty.equals(bob.getParty),
                "Different parties should have different getParty values");

        System.out.println("✅ Party.getParty field works:");
        System.out.println("  alice.getParty = " + alice.getParty);
        System.out.println("  bob.getParty = " + bob.getParty);
    }

    /**
     * Test: Party comparison patterns
     *
     * Verifies the correct way to compare Party objects.
     */
    @Test
    void testPartyComparison() {
        Party alice1 = new Party("alice");
        Party alice2 = new Party("alice");
        Party bob = new Party("bob");

        // CORRECT: Compare using getParty string field
        assertTrue(alice1.getParty.equals(alice2.getParty),
                "Same party IDs should compare equal via getParty");
        assertFalse(alice1.getParty.equals(bob.getParty),
                "Different party IDs should not compare equal via getParty");

        // Verify string comparison works
        String aliceId = "alice";
        assertTrue(alice1.getParty.equals(aliceId),
                "Party.getParty should equal string literal");

        System.out.println("✅ Party comparison works correctly");
    }

    /**
     * Test: Pool field access patterns
     *
     * Verifies that generated Pool.java class has correct fields.
     */
    @Test
    void testPoolFieldAccess() {
        // Create test parties
        Party poolOperator = new Party("pool-operator");
        Party poolParty = new Party("pool-party");
        Party lpIssuer = new Party("lp-issuer");
        Party issuerA = new Party("issuer-a");
        Party issuerB = new Party("issuer-b");
        Party clearportx = new Party("clearportx");

        // Create mock contract IDs
        ContractId<Token> tokenACid = new ContractId<>("token-a-cid");
        ContractId<Token> tokenBCid = new ContractId<>("token-b-cid");

        // Create pool using generated constructor
        Pool pool = new Pool(
                poolOperator,
                poolParty,
                lpIssuer,
                issuerA,
                issuerB,
                "ETH",
                "USDC",
                30L,  // feeBps (0.3%)
                "ETH-USDC",  // poolId
                new RelTime(120000000000L),  // maxTTL (2 minutes in microseconds)
                new BigDecimal("0.0"),  // totalLPSupply
                new BigDecimal("100.0"),  // reserveA
                new BigDecimal("200000.0"),  // reserveB
                Optional.of(tokenACid),  // tokenACid
                Optional.of(tokenBCid),  // tokenBCid
                clearportx,  // protocolFeeReceiver
                10000L,  // maxInBps (100%)
                5000L,   // maxOutBps (50%)
                List.<Party>of()  // setExtraObservers (no extra observers)
        );

        // Test field access
        assertNotNull(pool.getPoolOperator, "Pool operator field should be accessible");
        assertNotNull(pool.getPoolParty, "Pool party field should be accessible");
        assertNotNull(pool.getSymbolA, "SymbolA field should be accessible");
        assertNotNull(pool.getSymbolB, "SymbolB field should be accessible");
        assertNotNull(pool.getReserveA, "ReserveA field should be accessible");
        assertNotNull(pool.getReserveB, "ReserveB field should be accessible");
        assertNotNull(pool.getPoolId, "PoolId field should be accessible");

        // Test field values
        assertEquals("ETH", pool.getSymbolA, "SymbolA should match");
        assertEquals("USDC", pool.getSymbolB, "SymbolB should match");
        assertEquals(new BigDecimal("100.0"), pool.getReserveA, "ReserveA should match");
        assertEquals(new BigDecimal("200000.0"), pool.getReserveB, "ReserveB should match");
        assertEquals("ETH-USDC", pool.getPoolId, "PoolId should match");

        // Test Party fields
        assertEquals(poolOperator, pool.getPoolOperator, "Pool operator should match");
        assertEquals(poolParty, pool.getPoolParty, "Pool party should match");

        System.out.println("✅ Pool field access works:");
        System.out.println("  Pool ID: " + pool.getPoolId);
        System.out.println("  Pair: " + pool.getSymbolA + "-" + pool.getSymbolB);
        System.out.println("  Reserves: " + pool.getReserveA + " / " + pool.getReserveB);
        System.out.println("  Pool Operator: " + pool.getPoolOperator.getParty);
    }

    /**
     * Test: Numeric 10 → BigDecimal mapping
     *
     * Verifies that DAML Numeric 10 types map to Java BigDecimal.
     */
    @Test
    void testNumericToBigDecimalMapping() {
        Token token = new Token(
                new Party("issuer"),
                new Party("owner"),
                "TEST",
                new BigDecimal("123.4567890123")  // 10 decimal places
        );

        // BigDecimal should preserve precision
        assertEquals(new BigDecimal("123.4567890123"), token.getAmount,
                "BigDecimal should preserve 10 decimal places");

        // Arithmetic operations
        BigDecimal doubled = token.getAmount.multiply(new BigDecimal("2"));
        assertEquals(new BigDecimal("246.9135780246"), doubled,
                "BigDecimal arithmetic should work correctly");

        System.out.println("✅ Numeric 10 → BigDecimal mapping works:");
        System.out.println("  Original: " + token.getAmount);
        System.out.println("  Doubled: " + doubled);
    }

    /**
     * Test: ContractId type safety
     *
     * Verifies that ContractId<Token> and ContractId<Pool> are type-safe.
     */
    @Test
    void testContractIdTypeSafety() {
        // Create typed contract IDs
        ContractId<Token> tokenCid = new ContractId<>("token-contract-123");
        ContractId<Pool> poolCid = new ContractId<>("pool-contract-456");

        // Test getContractId field
        assertNotNull(tokenCid.getContractId, "ContractId.getContractId should not be null");
        assertEquals("token-contract-123", tokenCid.getContractId,
                "ContractId should store correct value");

        assertNotNull(poolCid.getContractId, "ContractId.getContractId should not be null");
        assertEquals("pool-contract-456", poolCid.getContractId,
                "ContractId should store correct value");

        // Test that types are different (compile-time safety)
        // This wouldn't compile if types weren't safe:
        // ContractId<Token> wrongType = poolCid;  // Compilation error!

        System.out.println("✅ ContractId type safety works:");
        System.out.println("  Token CID: " + tokenCid.getContractId);
        System.out.println("  Pool CID: " + poolCid.getContractId);
    }

    /**
     * Test: Token constructor with all fields
     *
     * Verifies that Token can be constructed with proper validation.
     */
    @Test
    void testTokenConstructor() {
        // Valid token
        Token validToken = new Token(
                new Party("issuer"),
                new Party("owner"),
                "BTC",
                new BigDecimal("0.5")
        );

        assertEquals("BTC", validToken.getSymbol);
        assertEquals(new BigDecimal("0.5"), validToken.getAmount);

        // Zero amount (should be valid)
        Token zeroToken = new Token(
                new Party("issuer"),
                new Party("owner"),
                "ETH",
                BigDecimal.ZERO
        );

        assertEquals(BigDecimal.ZERO, zeroToken.getAmount);

        System.out.println("✅ Token constructor works correctly");
    }

    /**
     * Test: Pool fee configuration
     *
     * Verifies that pool fee basis points are handled correctly.
     */
    @Test
    void testPoolFeeConfiguration() {
        Pool pool = createTestPool();

        // Fee should be in basis points (0-10000)
        assertTrue(pool.getFeeBps >= 0 && pool.getFeeBps <= 10000,
                "Fee basis points should be between 0 and 10000");

        // Common fee values
        assertEquals(30, pool.getFeeBps, "Test pool should have 30 bps (0.3%) fee");

        // Calculate actual fee percentage
        BigDecimal feePercentage = new BigDecimal(pool.getFeeBps)
                .divide(new BigDecimal("10000"), 4, BigDecimal.ROUND_HALF_UP);

        assertEquals(new BigDecimal("0.0030"), feePercentage,
                "30 bps should equal 0.3%");

        System.out.println("✅ Pool fee configuration works:");
        System.out.println("  Fee BPS: " + pool.getFeeBps);
        System.out.println("  Fee %: " + feePercentage.multiply(new BigDecimal("100")) + "%");
    }

    /**
     * Helper: Create test pool
     */
    private Pool createTestPool() {
        return new Pool(
                new Party("pool-operator"),
                new Party("pool-party"),
                new Party("lp-issuer"),
                new Party("issuer-eth"),
                new Party("issuer-usdc"),
                "ETH",
                "USDC",
                30L,  // feeBps (0.3% fee)
                "ETH-USDC",  // poolId
                new RelTime(120000000000L),  // maxTTL (2 minutes)
                new BigDecimal("0.0"),  // totalLPSupply
                new BigDecimal("100.0"),  // reserveA
                new BigDecimal("200000.0"),  // reserveB
                Optional.of(new ContractId<>("token-a-cid")),  // tokenACid
                Optional.of(new ContractId<>("token-b-cid")),  // tokenBCid
                new Party("clearportx"),  // protocolFeeReceiver
                10000L,  // maxInBps
                5000L,  // maxOutBps
                List.<Party>of()  // setExtraObservers (no extra observers)
        );
    }
}
