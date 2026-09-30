// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import helvet_amm.amm.pool.Pool;
import helvet_amm.lptoken.lptoken.LPToken;
import helvet_amm.token.token.Token;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.pqs.Contract;
import com.digitalasset.quickstart.pqs.Pqs;
import com.digitalasset.quickstart.repository.DamlRepository;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.utility.PqsSyncUtil;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import daml_stdlib_da_time_types.da.time.types.RelTime;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import static com.digitalasset.quickstart.utility.TracingUtils.tracingCtx;
import static com.digitalasset.quickstart.utility.TracingUtils.trace;

/**
 * ClearportX Initialization Service
 *
 * Initializes the ClearportX DEX with test tokens and liquidity pools.
 * Uses a state machine to ensure idempotence:
 * - NOT_STARTED: No initialization has been performed
 * - IN_PROGRESS: Initialization is currently running
 * - COMPLETED: Initialization completed successfully
 * - FAILED: Initialization failed (can be retried)
 */
@Service
public class ClearportXInitService {

    private static final Logger logger = LoggerFactory.getLogger(ClearportXInitService.class);

    private final LedgerApi ledger;
    @Nullable
    private final Pqs pqs;
    @Nullable
    private final DamlRepository damlRepository;
    private final AuthUtils authUtils;
    private final LedgerHealthService healthService;

    // State management
    private volatile InitState state = InitState.NOT_STARTED;
    private volatile String lastError = null;
    private final Map<String, Object> initResults = new ConcurrentHashMap<>();

    public enum InitState {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    @Autowired
    public ClearportXInitService(
            LedgerApi ledger,
            @Autowired(required = false) @Nullable Pqs pqs,
            @Autowired(required = false) @Nullable DamlRepository damlRepository,
            AuthUtils authUtils,
            LedgerHealthService healthService
    ) {
        this.ledger = ledger;
        this.pqs = pqs;
        this.damlRepository = damlRepository;
        this.authUtils = authUtils;
        this.healthService = healthService;
        if (pqs == null) {
            logger.info("PQS not available - init service will use Ledger API only");
        }
    }

    /**
     * Get current initialization state
     */
    public InitState getState() {
        return state;
    }

    /**
     * Get last error message (if state is FAILED)
     */
    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError);
    }

    /**
     * Get initialization results
     */
    public Map<String, Object> getInitResults() {
        return new HashMap<>(initResults);
    }

    /**
     * Reset initialization state to allow re-running init.
     * Useful for testing or recovering from failed states.
     */
    public void resetState() {
        logger.warn("Resetting initialization state - init will run again on next call");
        this.state = InitState.NOT_STARTED;
        this.lastError = null;
        this.initResults.clear();
    }

    /**
     * Initialize ClearportX with test tokens and liquidity pools.
     * Idempotent: returns immediately if already COMPLETED or IN_PROGRESS.
     *
     * Creates:
     * - Test tokens: USDC (1,000,000), ETH (1,000), BTC (50), USDT (1,000,000)
     * - Pools: ETH-USDC, BTC-USDC, ETH-USDT
     * - Initial liquidity for each pool
     *
     * @param commandIdPrefix Prefix for command IDs (e.g., "clearportx-init")
     * @return CompletableFuture<InitState> - Final state after initialization
     */
    @WithSpan
    public CompletableFuture<InitState> initializeClearportX(String commandIdPrefix) {
        var ctx = tracingCtx(logger, "Initializing ClearportX",
                "commandIdPrefix", commandIdPrefix,
                "currentState", state.toString()
        );

        // Idempotence check
        if (state == InitState.COMPLETED) {
            logger.info("ClearportX already initialized (state: COMPLETED)");
            return CompletableFuture.completedFuture(InitState.COMPLETED);
        }

        if (state == InitState.IN_PROGRESS) {
            logger.info("ClearportX initialization already in progress");
            return CompletableFuture.completedFuture(InitState.IN_PROGRESS);
        }

        // Set state to IN_PROGRESS
        state = InitState.IN_PROGRESS;
        lastError = null;
        initResults.clear();

        logger.info("Starting ClearportX initialization");

        // Get appProviderParty ID
        String appProviderPartyId = authUtils.getAppProviderPartyId();

        // Step 1: Create test tokens
        return createTestTokens(appProviderPartyId, commandIdPrefix)
            .thenCompose(tokenCids -> {
                initResults.put("tokens", tokenCids);
                logger.info("Created {} test tokens", tokenCids.size());

                // Step 2: Create empty pools
                return createPools(appProviderPartyId, commandIdPrefix)
                    .thenCompose(poolCids -> {
                        initResults.put("pools", poolCids);
                        logger.info("Created {} pools", poolCids.size());

                        // Step 3: Add liquidity to pools
                        return addLiquidityToPools(
                            appProviderPartyId,
                            tokenCids,
                            poolCids,
                            commandIdPrefix
                        ).thenApply(lpTokenCids -> {
                            initResults.put("lpTokens", lpTokenCids);
                            logger.info("Added liquidity to {} pools", lpTokenCids.size());

                            // Success!
                            state = InitState.COMPLETED;
                            logger.info("ClearportX initialization completed successfully");
                            return InitState.COMPLETED;
                        });
                    });
            })
            .exceptionally(ex -> {
                logger.error("ClearportX initialization failed", ex);
                state = InitState.FAILED;
                lastError = ex.getMessage();
                return InitState.FAILED;
            });
    }

    /**
     * Create test tokens for ClearportX and query their ContractIds from PQS
     *
     * EXPLANATION:
     * 1. We create Token contracts on the ledger using ledger.create()
     * 2. Canton processes these and syncs them to PQS (PostgreSQL database)
     * 3. We query PQS to find the contracts we just created
     * 4. We extract their ContractIds to use later
     */
    private CompletableFuture<Map<String, ContractId<Token>>> createTestTokens(
            String appProviderPartyId,
            String commandIdPrefix
    ) {
        var ctx = tracingCtx(logger, "Creating test tokens",
                "party", appProviderPartyId
        );

        return trace(ctx, () -> {
            Party appProviderParty = new Party(appProviderPartyId);

            // Define test tokens: symbol -> amount
            Map<String, BigDecimal> testTokens = new LinkedHashMap<>();
            testTokens.put("USDC", new BigDecimal("1000000.0"));  // 1M USDC
            testTokens.put("ETH", new BigDecimal("1000.0"));      // 1K ETH
            testTokens.put("BTC", new BigDecimal("50.0"));        // 50 BTC
            testTokens.put("USDT", new BigDecimal("1000000.0"));  // 1M USDT

            // Use app-provider itself as liquidity provider
            // Self-transfer check has been removed from Token.Transfer in DAML
            Party liquidityProviderParty = appProviderParty;

            // STEP 1: Create tokens for app-provider and get ContractIds directly (no PQS wait!)
            List<CompletableFuture<Map.Entry<String, ContractId<Token>>>> createFutures = new ArrayList<>();

            for (Map.Entry<String, BigDecimal> entry : testTokens.entrySet()) {
                String symbol = entry.getKey();
                BigDecimal amount = entry.getValue();

                // Token for app-provider (for general use)
                Token appToken = new Token(
                    appProviderParty,  // issuer
                    appProviderParty,  // owner
                    symbol,
                    amount
                );
                String cmd1 = commandIdPrefix + "-token-" + symbol.toLowerCase() + "-app-" + UUID.randomUUID();
                CompletableFuture<Map.Entry<String, ContractId<Token>>> f1 =
                    ledger.createAndGetCid(appToken, List.of(appProviderPartyId), List.of(), cmd1, Token.TEMPLATE_ID)
                        .thenApply(cid -> {
                            logger.info("✅ Created {} for app-provider: {}", symbol, cid.getContractId);
                            return Map.entry(symbol, cid);
                        });
                createFutures.add(f1);

                // Token for liquidity-provider (for bootstrap liquidity - 10x larger)
                Token lpToken = new Token(
                    appProviderParty,           // issuer (still app-provider)
                    liquidityProviderParty,     // owner (liquidity-provider)
                    symbol,
                    amount.multiply(new BigDecimal("10"))  // 10x for LP
                );
                String cmd2 = commandIdPrefix + "-token-" + symbol.toLowerCase() + "-lp-" + UUID.randomUUID();
                ledger.createAndGetCid(lpToken, List.of(appProviderPartyId), List.of(), cmd2, Token.TEMPLATE_ID)
                    .thenAccept(cid -> logger.info("✅ Created {} for liquidity-provider: {}", symbol, cid.getContractId));
            }

            // STEP 2: Wait for all app-provider token creates and collect ContractIds
            return CompletableFuture.allOf(createFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    logger.info("All token creates completed. Got ContractIds directly from Ledger API!");

                    // Collect the ContractIds from futures
                    Map<String, ContractId<Token>> tokenCids = new HashMap<>();
                    for (CompletableFuture<Map.Entry<String, ContractId<Token>>> future : createFutures) {
                        Map.Entry<String, ContractId<Token>> entry = future.join();
                        tokenCids.put(entry.getKey(), entry.getValue());
                        logger.info("📝 Token {}: ContractId = {}",
                            entry.getKey(),
                            entry.getValue().getContractId);
                    }

                    if (tokenCids.size() != testTokens.size()) {
                        logger.warn("Expected {} tokens but found {}. Some may still be syncing to PQS.",
                            testTokens.size(), tokenCids.size());
                    }

                    return tokenCids;
                });
        });
    }

    /**
     * Create empty liquidity pools and query their ContractIds from PQS
     *
     * EXPLANATION:
     * Same as createTestTokens, but for Pool contracts.
     * We create empty pools (reserves = 0) and then query PQS to get their ContractIds.
     */
    private CompletableFuture<Map<String, ContractId<Pool>>> createPools(
            String appProviderPartyId,
            String commandIdPrefix
    ) {
        var ctx = tracingCtx(logger, "Creating pools",
                "party", appProviderPartyId
        );

        return trace(ctx, () -> {
            Party appProviderParty = new Party(appProviderPartyId);

            // Define pools: poolId -> (symbolA, symbolB)
            List<PoolConfig> poolConfigs = Arrays.asList(
                new PoolConfig("ETH-USDC", "ETH", "USDC"),
                new PoolConfig("BTC-USDC", "BTC", "USDC"),
                new PoolConfig("ETH-USDT", "ETH", "USDT")
            );

            // STEP 1: Create all pools and get ContractIds directly (no PQS wait!)
            List<CompletableFuture<Map.Entry<String, ContractId<Pool>>>> createFutures = new ArrayList<>();

            for (PoolConfig config : poolConfigs) {
                Pool pool = new Pool(
                    appProviderParty,        // poolOperator
                    appProviderParty,        // poolParty
                    appProviderParty,        // lpIssuer
                    appProviderParty,        // issuerA
                    appProviderParty,        // issuerB
                    config.symbolA,
                    config.symbolB,
                    30L,                     // feeBps (0.3%)
                    config.poolId,
                    new RelTime(7200000000L), // maxTTL (2 hours in microseconds)
                    BigDecimal.ZERO,         // totalLPSupply (empty pool)
                    BigDecimal.ZERO,         // reserveA (empty pool)
                    BigDecimal.ZERO,         // reserveB (empty pool)
                    Optional.<ContractId<Token>>empty(),        // tokenACid (no reserves)
                    Optional.<ContractId<Token>>empty(),        // tokenBCid (no reserves)
                    appProviderParty,        // protocolFeeReceiver
                    1000L,                   // maxInBps (10%)
                    1000L,                   // maxOutBps (10%)
                    List.of()                // extraObservers
                );

                String commandId = commandIdPrefix + "-pool-" + config.poolId.toLowerCase() + "-" + UUID.randomUUID();

                // Create pool and get ContractId directly
                CompletableFuture<Map.Entry<String, ContractId<Pool>>> future =
                    ledger.createAndGetCid(pool, List.of(appProviderPartyId), List.of(), commandId, Pool.TEMPLATE_ID)
                        .thenApply(cid -> {
                            logger.info("✅ Created {} pool: {}", config.poolId, cid.getContractId);
                            return Map.entry(config.poolId, cid);
                        });

                createFutures.add(future);
            }

            // STEP 2: Wait for all pool creates and collect ContractIds
            return CompletableFuture.allOf(createFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    logger.info("All pool creates completed. Got ContractIds directly from Ledger API!");

                    // Collect the ContractIds from futures
                    Map<String, ContractId<Pool>> poolCids = new HashMap<>();
                    for (CompletableFuture<Map.Entry<String, ContractId<Pool>>> future : createFutures) {
                        Map.Entry<String, ContractId<Pool>> entry = future.join();
                        poolCids.put(entry.getKey(), entry.getValue());
                        logger.info("📝 Pool {}: ContractId = {}",
                            entry.getKey(),
                            entry.getValue().getContractId);
                    }

                    logger.info("✅ Successfully created {} pools with direct ContractId retrieval", poolCids.size());
                    return poolCids;
                });
        });
    }

    /**
     * Add liquidity to all pools by exercising the AddLiquidity choice
     *
     * EXPLANATION OF ADD LIQUIDITY FLOW:
     *
     * For each pool (e.g., ETH-USDC):
     * 1. We have ContractIds for the pool and tokens
     * 2. We create an AddLiquidity choice object with:
     *    - provider: appProvider (who's adding liquidity)
     *    - tokenACid: ETH token ContractId
     *    - tokenBCid: USDC token ContractId
     *    - amountA: 100 ETH
     *    - amountB: 100000 USDC
     *    - minLPTokens: 0 (no slippage protection for init)
     *    - deadline: now + 1 hour
     *
     * 3. We exercise this choice on the Pool contract
     * 4. DAML executes:
     *    a. Transfers tokens from provider to pool
     *    b. Mints LP tokens for the provider
     *    c. Archives old pool and creates new with updated reserves
     * 5. We get back: (LPToken ContractId, new Pool ContractId)
     *
     * MULTI-PARTY AUTHORIZATION:
     * AddLiquidity requires: controller provider, poolParty, lpIssuer
     * In our case: provider = poolParty = lpIssuer = appProvider
     * So we only need to authorize as appProvider (one party, three roles)
     */
    private CompletableFuture<Map<String, ContractId<LPToken>>> addLiquidityToPools(
            String appProviderPartyId,
            Map<String, ContractId<Token>> tokenCids,
            Map<String, ContractId<Pool>> poolCids,
            String commandIdPrefix
    ) {
        var ctx = tracingCtx(logger, "Adding liquidity to pools",
                "party", appProviderPartyId,
                "numPools", poolCids.size()
        );

        return trace(ctx, () -> {
            Party appProviderParty = new Party(appProviderPartyId);

            // Define liquidity amounts for each pool (demo amounts for investor presentation)
            // ETH-USDC: 100 ETH + 200,000 USDC (price: 1 ETH = 2000 USDC)
            // BTC-USDC: 10 BTC + 200,000 USDC (price: 1 BTC = 20000 USDC)
            // ETH-USDT: 100 ETH + 300,000 USDT (price: 1 ETH = 3000 USDT)
            Map<String, LiquidityConfig> liquidityConfigs = new HashMap<>();
            liquidityConfigs.put("ETH-USDC", new LiquidityConfig("ETH", "USDC", new BigDecimal("100.0"), new BigDecimal("200000.0")));
            liquidityConfigs.put("BTC-USDC", new LiquidityConfig("BTC", "USDC", new BigDecimal("10.0"), new BigDecimal("200000.0")));
            liquidityConfigs.put("ETH-USDT", new LiquidityConfig("ETH", "USDT", new BigDecimal("100.0"), new BigDecimal("300000.0")));

            // STEP 1: Exercise AddLiquidity on each pool
            List<CompletableFuture<LiquidityResult>> liquidityFutures = new ArrayList<>();

            for (Map.Entry<String, ContractId<Pool>> poolEntry : poolCids.entrySet()) {
                String poolId = poolEntry.getKey();
                ContractId<Pool> poolCid = poolEntry.getValue();
                LiquidityConfig config = liquidityConfigs.get(poolId);

                if (config == null) {
                    logger.warn("No liquidity config for pool {}, skipping", poolId);
                    continue;
                }

                // Get token ContractIds
                ContractId<Token> tokenACid = tokenCids.get(config.symbolA);
                ContractId<Token> tokenBCid = tokenCids.get(config.symbolB);

                if (tokenACid == null || tokenBCid == null) {
                    logger.error("Missing token ContractIds for pool {}. TokenA: {}, TokenB: {}",
                        poolId, tokenACid != null, tokenBCid != null);
                    continue;
                }

                // STEP 2: Create AddLiquidity choice
                // Deadline: 1 hour from now
                Instant deadline = Instant.now().plusSeconds(3600);

                Pool.AddLiquidity addLiquidityChoice = new Pool.AddLiquidity(
                    appProviderParty,           // provider
                    tokenACid,                  // tokenACid (e.g., ETH)
                    tokenBCid,                  // tokenBCid (e.g., USDC)
                    config.amountA,             // amountA (e.g., 100 ETH)
                    config.amountB,             // amountB (e.g., 100000 USDC)
                    BigDecimal.ZERO,            // minLPTokens (no slippage check for init)
                    deadline                    // deadline
                );

                String commandId = commandIdPrefix + "-add-liquidity-" + poolId.toLowerCase() + "-" + UUID.randomUUID();

                // STEP 3: Exercise the choice on the Pool contract
                logger.info("💧 Adding liquidity to {}: {} {} + {} {}",
                    poolId, config.amountA, config.symbolA, config.amountB, config.symbolB);

                CompletableFuture<LiquidityResult> future = ledger.exerciseAndGetResult(poolCid, addLiquidityChoice, commandId)
                    .thenApply(result -> {
                        // Result is Tuple2<ContractId<LPToken>, ContractId<Pool>>
                        // Access tuple elements with get_1 and get_2 (not _1 and _2)
                        ContractId<LPToken> lpTokenCid = result.get_1;
                        ContractId<Pool> newPoolCid = result.get_2;

                        logger.info("✅ Added liquidity to {}. LPToken: {}, New Pool: {}",
                            poolId,
                            lpTokenCid.getContractId,
                            newPoolCid.getContractId);

                        return new LiquidityResult(poolId, lpTokenCid, newPoolCid);
                    })
                    .exceptionally(ex -> {
                        // Check if it's a CONTRACT_NOT_FOUND error (PQS returned archived contract)
                        String errorMsg = ex.getMessage();
                        if (errorMsg != null && errorMsg.contains("CONTRACT_NOT_FOUND")) {
                            logger.warn("⚠️  Pool {} contract not found (PQS returned archived contract). Skipping.", poolId);
                            return null; // Return null to signal this pool should be skipped
                        }

                        logger.error("❌ Failed to add liquidity to {}: {}", poolId, errorMsg);
                        throw new RuntimeException("Failed to add liquidity to " + poolId, ex);
                    });

                liquidityFutures.add(future);
            }

            // STEP 4: Wait for all liquidity additions to complete
            return CompletableFuture.allOf(liquidityFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // Collect LP token ContractIds (skip null results from archived contracts)
                    Map<String, ContractId<LPToken>> lpTokenCids = new HashMap<>();
                    int skippedCount = 0;

                    for (CompletableFuture<LiquidityResult> future : liquidityFutures) {
                        try {
                            LiquidityResult result = future.get();
                            if (result != null) {
                                lpTokenCids.put(result.poolId, result.lpTokenCid);
                            } else {
                                skippedCount++;
                            }
                        } catch (Exception e) {
                            logger.error("Error collecting liquidity result", e);
                            skippedCount++;
                        }
                    }

                    if (skippedCount > 0) {
                        logger.warn("⚠️  Skipped {} pools due to CONTRACT_NOT_FOUND (PQS lag or archived contracts)", skippedCount);
                    }
                    logger.info("🎉 Successfully added liquidity to {} pools", lpTokenCids.size());
                    return lpTokenCids;
                });
        });
    }

    /**
     * Helper class for pool configuration
     */
    private static class PoolConfig {
        final String poolId;
        final String symbolA;
        final String symbolB;

        PoolConfig(String poolId, String symbolA, String symbolB) {
            this.poolId = poolId;
            this.symbolA = symbolA;
            this.symbolB = symbolB;
        }
    }

    /**
     * Helper class for liquidity configuration
     */
    private static class LiquidityConfig {
        final String symbolA;
        final String symbolB;
        final BigDecimal amountA;
        final BigDecimal amountB;

        LiquidityConfig(String symbolA, String symbolB, BigDecimal amountA, BigDecimal amountB) {
            this.symbolA = symbolA;
            this.symbolB = symbolB;
            this.amountA = amountA;
            this.amountB = amountB;
        }
    }

    /**
     * Bootstrap liquidity to existing pools.
     * This queries PQS for the latest pools and tokens, then adds liquidity.
     */
    @WithSpan
    public CompletableFuture<Map<String, Object>> bootstrapLiquidity(String commandIdPrefix) {
        var ctx = tracingCtx(logger, "Bootstrapping liquidity",
                "commandIdPrefix", commandIdPrefix
        );

        return trace(ctx, () -> {
            String appProviderPartyId = authUtils.getAppProviderPartyId();
            // Use app-provider as liquidity provider (self-transfer is now allowed)
            String liquidityProviderPartyId = appProviderPartyId;

            // Step 0: Validate PQS package indexing (fail-fast guard)
            // Check if PQS is indexing ClearportX contracts - if not, we'll never find pools
            logger.info("Validating PQS package indexing before polling...");

            return healthService.getHealthStatus()
                .thenCompose(health -> {
                    Long clearportxCount = (Long) health.get("clearportxContractCount");
                    Boolean synced = (Boolean) health.get("synced");

                    // Guard: If PQS has never seen ClearportX contracts, warn but continue polling
                    // (They might appear during the poll if init just completed)
                    if (clearportxCount != null && clearportxCount == 0) {
                        logger.warn("⚠️  PQS has no ClearportX contracts yet. This might indicate:");
                        logger.warn("   1. PQS is still syncing (normal after init)");
                        logger.warn("   2. PQS package allowlist doesn't include ClearportX (configuration issue)");
                        logger.warn("   3. No pools have been created yet (call /init first)");
                        logger.warn("   Will poll for {} seconds in case contracts appear...", 45);
                    }

                    // Step 1: Query PQS for latest pools and tokens (with exponential backoff for sync)
                    // This handles PQS eventual consistency - we poll until pools appear
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            // Wait for PQS to sync pools with exponential backoff
                            return com.digitalasset.quickstart.utility.PqsSyncUtil.pollPqsUntil(
                                    () -> {
                                        try {
                                            return Optional.of(pqs.active(Pool.class).join());
                                        } catch (Exception e) {
                                            logger.warn("Failed to query pools from PQS: {}", e.getMessage());
                                            return Optional.empty();
                                        }
                                    },
                                    pools -> !pools.isEmpty(),  // Wait until at least one pool exists
                                    java.time.Duration.ofSeconds(45),
                                    "active pools"
                            );
                        } catch (Exception e) {
                            logger.error("❌ PQS sync timeout - pools not found after 45s: {}", e.getMessage());
                            logger.error("   Possible causes:");
                            logger.error("   1. PQS package allowlist doesn't include ClearportX package");
                            logger.error("   2. PQS is severely lagging behind Canton");
                            logger.error("   3. No pools exist (call /api/clearportx/init first)");
                            throw new RuntimeException("PQS did not sync pools in time. Check PQS configuration and logs.", e);
                        }
                    });
                })
                .thenCompose(pools -> {
                    return pqs.active(Token.class)
                        .thenApply(tokens -> Map.of("pools", pools, "tokens", tokens));
                })
                .thenCompose(data -> {
                    @SuppressWarnings("unchecked")
                    List<Contract<Pool>> pools = (List<Contract<Pool>>) data.get("pools");
                    @SuppressWarnings("unchecked")
                    List<Contract<Token>> tokens = (List<Contract<Token>>) data.get("tokens");

                    return CompletableFuture.completedFuture(Map.of("pools", pools, "tokens", tokens));
                })
                .thenCompose(data -> {
                    @SuppressWarnings("unchecked")
                    List<Contract<Pool>> pools = (List<Contract<Pool>>) data.get("pools");
                    @SuppressWarnings("unchecked")
                    List<Contract<Token>> tokens = (List<Contract<Token>>) data.get("tokens");

                    return CompletableFuture.supplyAsync(() -> {
                                // Extract ContractIds - use latest contracts per poolId/symbol
                                Map<String, ContractId<Pool>> poolCids = new HashMap<>();
                                Map<String, ContractId<Token>> tokenCids = new HashMap<>();

                                logger.info("Looking for pools operated by: {}", appProviderPartyId);
                                logger.info("Looking for tokens owned by liquidity-provider: {}", liquidityProviderPartyId);

                                // Get all pools operated by app-provider
                                // Keep only the LAST occurrence of each poolId (newest contract)
                                // This handles case where PQS returns multiple versions/generations
                                for (Contract<Pool> contract : pools) {
                                    Pool pool = contract.payload;
                                    String poolOperatorId = pool.getPoolOperator.getParty;
                                    if (poolOperatorId.equals(appProviderPartyId)) {
                                        // Always update to latest - if PQS returns multiple, last one wins
                                        poolCids.put(pool.getPoolId, contract.contractId);
                                        logger.info("Found pool {} with reserves: A={}, B={} (CID: {}...)",
                                                pool.getPoolId, pool.getReserveA, pool.getReserveB,
                                                contract.contractId.getContractId.substring(0, 12));
                                    }
                                }

                                logger.info("✅ Selected {} unique pools after deduplication", poolCids.size());

                                // Get LIQUIDITY-PROVIDER's tokens (issued by app-provider, owned by liquidity-provider)
                                for (Contract<Token> contract : tokens) {
                                    Token token = contract.payload;
                                    String ownerId = token.getOwner.getParty;
                                    String issuerId = token.getIssuer.getParty;
                                    // Token must be owned by liquidity-provider and issued by app-provider
                                    if (ownerId.equals(liquidityProviderPartyId) && issuerId.equals(appProviderPartyId)) {
                                        if (!tokenCids.containsKey(token.getSymbol)) {
                                            tokenCids.put(token.getSymbol, contract.contractId);
                                            logger.info("Found {} token for liquidity-provider: {}", token.getSymbol, token.getAmount);
                                        }
                                    }
                                }

                                logger.info("Found {} pools and {} tokens for liquidity-provider", poolCids.size(), tokenCids.size());

                                // Step 2: Add liquidity using liquidity-provider as the provider
                                return addLiquidityToPools(liquidityProviderPartyId, tokenCids, poolCids, commandIdPrefix)
                                    .thenApply(lpTokens -> {
                                        Map<String, Object> result = new HashMap<>();
                                        result.put("lpTokens", lpTokens);
                                        result.put("poolsUpdated", poolCids.keySet());
                                        result.put("status", "SUCCESS");
                                        return result;
                                    });
                            })
                            .thenCompose(future -> future);
                });
        });
    }

    /**
     * Mint a demo token for testing swaps.
     * Issuer will be poolOperator (app-provider), owner is specified.
     */
    @WithSpan
    public CompletableFuture<ContractId<Token>> mintDemoToken(
            String ownerPartyId,
            String symbol,
            String amount,
            String commandId
    ) {
        var ctx = tracingCtx(logger, "Minting demo token",
                "owner", ownerPartyId,
                "symbol", symbol,
                "amount", amount
        );

        return trace(ctx, () -> {
            String appProviderPartyId = authUtils.getAppProviderPartyId();
            Party issuer = new Party(appProviderPartyId);
            Party owner = new Party(ownerPartyId);
            BigDecimal tokenAmount = new BigDecimal(amount);

            Token token = new Token(issuer, owner, symbol, tokenAmount);

            return ledger.create(token, commandId)
                .thenCompose(v -> {
                    // Wait a moment for PQS to sync
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    // Query PQS to get the ContractId
                    return pqs.activeWhere(Token.class,
                        "payload->>'symbol' = ? AND payload->>'owner' = ?",
                        symbol, ownerPartyId)
                    .thenApply(contracts -> {
                        if (contracts.isEmpty()) {
                            throw new RuntimeException("Token not found in PQS after creation");
                        }
                        return contracts.get(0).contractId;
                    });
                });
        });
    }

    /**
     * Helper class for liquidity addition result
     */
    private static class LiquidityResult {
        final String poolId;
        final ContractId<LPToken> lpTokenCid;
        final ContractId<Pool> newPoolCid;

        LiquidityResult(String poolId, ContractId<LPToken> lpTokenCid, ContractId<Pool> newPoolCid) {
            this.poolId = poolId;
            this.lpTokenCid = lpTokenCid;
            this.newPoolCid = newPoolCid;
        }
    }
}
