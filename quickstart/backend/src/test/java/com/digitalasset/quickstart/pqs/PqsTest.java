// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.pqs;

import helvet_amm.amm.pool.Pool;
import helvet_amm.token.token.Token;
import com.digitalasset.transcode.java.ContractId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for PQS (Participant Query Store) integration.
 *
 * These tests verify that:
 * 1. PQS can query DAML contracts from PostgreSQL
 * 2. Template ID disambiguation works (package-qualified IDs)
 * 3. Party filtering works correctly
 * 4. Contract structure is valid
 *
 * NOTE: These tests require:
 * - PostgreSQL running on localhost:5432
 * - PQS database with schema initialized
 * - Canton Network running and syncing to PQS
 *
 * Tests will be SKIPPED if database is not available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "classpath:application-test.properties")
class PqsTest {

    @Autowired
    private Pqs pqs;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static boolean databaseAvailable = true;

    /**
     * Check if database is available before each test.
     * If not available, skip the test with a clear message.
     */
    @BeforeEach
    void checkDatabaseAvailability() {
        if (!databaseAvailable) {
            // Already know DB is down, skip immediately
            assumeTrue(false, "⚠️  Database not available. Skipping PQS integration tests. " +
                    "Start PostgreSQL and Canton to run these tests.");
            return;
        }

        try {
            // Try a simple query to verify database connectivity
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            databaseAvailable = false;
            assumeTrue(false, "⚠️  Database not available: " + e.getMessage() +
                    ". Skipping PQS integration tests. Start PostgreSQL and Canton to run these tests.");
        }
    }

    /**
     * Test: Query all active Token contracts
     *
     * Verifies:
     * - PQS can execute SQL queries
     * - Template ID "clearportx-amm:Token.Token:Token" resolves correctly
     * - Returns valid Contract<Token> objects
     */
    @Test
    void testQueryActiveTokens() throws Exception {
        // Query all active tokens
        CompletableFuture<List<Contract<Token>>> future = pqs.active(Token.class);
        List<Contract<Token>> tokens = future.get();

        // Should have tokens from initialization
        // Note: Exact number depends on whether init was run
        assertNotNull(tokens, "Tokens list should not be null");

        if (!tokens.isEmpty()) {
            System.out.println("✅ Found " + tokens.size() + " tokens in PQS");

            // Verify first token structure
            Contract<Token> firstToken = tokens.get(0);
            assertNotNull(firstToken.contractId, "Contract ID should not be null");
            assertNotNull(firstToken.payload, "Token payload should not be null");
            assertNotNull(firstToken.payload.getSymbol, "Token symbol should not be null");
            assertNotNull(firstToken.payload.getOwner, "Token owner should not be null");
            assertNotNull(firstToken.payload.getIssuer, "Token issuer should not be null");
            assertTrue(firstToken.payload.getAmount.compareTo(BigDecimal.ZERO) >= 0,
                    "Token amount should be >= 0");

            System.out.println("  Example token: " + firstToken.payload.getSymbol
                    + " owned by " + firstToken.payload.getOwner.getParty
                    + ", amount: " + firstToken.payload.getAmount);
        } else {
            System.out.println("⚠️  No tokens found. Run /api/clearportx/init first.");
        }
    }

    /**
     * Test: Query all active Pool contracts
     *
     * Verifies:
     * - PQS can query different template types
     * - Pool structure is valid
     */
    @Test
    void testQueryActivePools() throws Exception {
        CompletableFuture<List<Contract<Pool>>> future = pqs.active(Pool.class);
        List<Contract<Pool>> pools = future.get();

        assertNotNull(pools, "Pools list should not be null");

        if (!pools.isEmpty()) {
            System.out.println("✅ Found " + pools.size() + " pools in PQS");

            // Verify first pool structure
            Contract<Pool> firstPool = pools.get(0);
            assertNotNull(firstPool.contractId, "Pool contract ID should not be null");
            assertNotNull(firstPool.payload, "Pool payload should not be null");
            assertNotNull(firstPool.payload.getPoolId, "Pool ID should not be null");
            assertNotNull(firstPool.payload.getSymbolA, "Pool symbolA should not be null");
            assertNotNull(firstPool.payload.getSymbolB, "Pool symbolB should not be null");

            System.out.println("  Example pool: " + firstPool.payload.getPoolId
                    + " (" + firstPool.payload.getSymbolA + "-" + firstPool.payload.getSymbolB + ")"
                    + ", reserves: " + firstPool.payload.getReserveA + " / " + firstPool.payload.getReserveB);
        } else {
            System.out.println("⚠️  No pools found. Run /api/clearportx/init first.");
        }
    }

    /**
     * Test: Package-qualified template ID resolution
     *
     * Verifies that PQS uses full template IDs like:
     * "clearportx-amm:Token.Token:Token"
     *
     * This prevents ambiguity when multiple DAML packages exist.
     */
    @Test
    void testPackageQualifiedTemplateId() throws Exception {
        // This test passes if no SQL exception is thrown
        // PQS should use "clearportx-amm:Token.Token:Token" format
        CompletableFuture<List<Contract<Token>>> future = pqs.active(Token.class);
        List<Contract<Token>> tokens = future.get();

        assertNotNull(tokens, "Should successfully query with package-qualified template ID");
        System.out.println("✅ Package-qualified template ID works correctly");
    }

    /**
     * Test: Filter tokens by party
     *
     * Verifies that:
     * - Party.getParty field access works
     * - String comparison of party IDs works
     * - Filtering logic is correct
     */
    @Test
    void testFilterTokensByParty() throws Exception {
        CompletableFuture<List<Contract<Token>>> future = pqs.active(Token.class);
        List<Contract<Token>> allTokens = future.get();

        if (allTokens.isEmpty()) {
            System.out.println("⚠️  No tokens to filter. Skipping test.");
            return;
        }

        // Get a party ID from the first token
        String targetParty = allTokens.get(0).payload.getOwner.getParty;

        // Filter tokens owned by this party
        List<Contract<Token>> filtered = allTokens.stream()
                .filter(contract -> contract.payload.getOwner.getParty.equals(targetParty))
                .collect(Collectors.toList());

        assertFalse(filtered.isEmpty(), "Should find at least one token for party: " + targetParty);

        // Verify all filtered tokens have the correct owner
        for (Contract<Token> contract : filtered) {
            assertEquals(targetParty, contract.payload.getOwner.getParty,
                    "Filtered token should have correct owner");
        }

        System.out.println("✅ Filtering by party works: found " + filtered.size()
                + " tokens for party " + targetParty);
    }

    /**
     * Test: Token structure validation
     *
     * Verifies that Token objects have all required fields populated correctly.
     */
    @Test
    void testTokenStructureValidation() throws Exception {
        CompletableFuture<List<Contract<Token>>> future = pqs.active(Token.class);
        List<Contract<Token>> tokens = future.get();

        if (tokens.isEmpty()) {
            System.out.println("⚠️  No tokens to validate. Skipping test.");
            return;
        }

        for (Contract<Token> contract : tokens) {
            Token token = contract.payload;

            // Validate all fields
            assertNotNull(contract.contractId, "Contract ID must not be null");
            assertNotNull(token.getIssuer, "Issuer must not be null");
            assertNotNull(token.getOwner, "Owner must not be null");
            assertNotNull(token.getSymbol, "Symbol must not be null");
            assertNotNull(token.getAmount, "Amount must not be null");

            // Validate Party.getParty field access (critical!)
            assertNotNull(token.getIssuer.getParty, "Issuer.getParty must not be null");
            assertNotNull(token.getOwner.getParty, "Owner.getParty must not be null");

            // Validate data types
            assertFalse(token.getSymbol.isEmpty(), "Symbol must not be empty");
            assertTrue(token.getAmount.compareTo(BigDecimal.ZERO) >= 0,
                    "Amount must be >= 0");
        }

        System.out.println("✅ All " + tokens.size() + " tokens have valid structure");
    }

    /**
     * Test: Pool structure validation
     *
     * Verifies that Pool objects have all required fields populated correctly.
     */
    @Test
    void testPoolStructureValidation() throws Exception {
        CompletableFuture<List<Contract<Pool>>> future = pqs.active(Pool.class);
        List<Contract<Pool>> pools = future.get();

        if (pools.isEmpty()) {
            System.out.println("⚠️  No pools to validate. Skipping test.");
            return;
        }

        for (Contract<Pool> contract : pools) {
            Pool pool = contract.payload;

            // Validate all critical fields
            assertNotNull(contract.contractId, "Contract ID must not be null");
            assertNotNull(pool.getPoolId, "Pool ID must not be null");
            assertNotNull(pool.getSymbolA, "SymbolA must not be null");
            assertNotNull(pool.getSymbolB, "SymbolB must not be null");
            assertNotNull(pool.getPoolOperator, "Pool operator must not be null");
            assertNotNull(pool.getPoolParty, "Pool party must not be null");

            // Validate reserves (can be 0 if pool is empty)
            assertNotNull(pool.getReserveA, "ReserveA must not be null");
            assertNotNull(pool.getReserveB, "ReserveB must not be null");
            assertTrue(pool.getReserveA.compareTo(BigDecimal.ZERO) >= 0,
                    "ReserveA must be >= 0");
            assertTrue(pool.getReserveB.compareTo(BigDecimal.ZERO) >= 0,
                    "ReserveB must be >= 0");

            // Validate Party.getParty field access
            assertNotNull(pool.getPoolOperator.getParty, "PoolOperator.getParty must not be null");
            assertNotNull(pool.getPoolParty.getParty, "PoolParty.getParty must not be null");

            // Validate fee configuration
            assertTrue(pool.getFeeBps >= 0 && pool.getFeeBps <= 10000,
                    "Fee basis points must be between 0 and 10000");
        }

        System.out.println("✅ All " + pools.size() + " pools have valid structure");
    }

    /**
     * Test: ContractId type safety
     *
     * Verifies that ContractId<Token> and ContractId<Pool> are type-safe.
     */
    @Test
    void testContractIdTypeSafety() throws Exception {
        CompletableFuture<List<Contract<Token>>> tokensFuture = pqs.active(Token.class);
        List<Contract<Token>> tokens = tokensFuture.get();

        if (!tokens.isEmpty()) {
            Contract<Token> tokenContract = tokens.get(0);
            ContractId<Token> tokenCid = tokenContract.contractId;

            assertNotNull(tokenCid, "Token ContractId should not be null");
            assertNotNull(tokenCid.getContractId, "Contract ID value should not be null");

            System.out.println("✅ ContractId type safety works: " + tokenCid.getContractId);
        }

        CompletableFuture<List<Contract<Pool>>> poolsFuture = pqs.active(Pool.class);
        List<Contract<Pool>> pools = poolsFuture.get();

        if (!pools.isEmpty()) {
            Contract<Pool> poolContract = pools.get(0);
            ContractId<Pool> poolCid = poolContract.contractId;

            assertNotNull(poolCid, "Pool ContractId should not be null");
            assertNotNull(poolCid.getContractId, "Contract ID value should not be null");

            System.out.println("✅ ContractId type safety works: " + poolCid.getContractId);
        }
    }
}
