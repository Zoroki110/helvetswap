// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.controller;

import helvet_amm.amm.pool.Pool;
import helvet_amm.amm.receipt.Receipt;
import helvet_amm.amm.swaprequest.SwapReady;
import helvet_amm.amm.swaprequest.SwapRequest;
import com.digitalasset.quickstart.controller.DomainErrorStatusMapper;
import helvet_amm.amm.atomicswap.AtomicSwapProposal;
import helvet_amm.token.token.Token;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.*;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.ledger.StaleAcsRetry;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.security.PartyMappingService;
import com.digitalasset.quickstart.metrics.SwapMetrics;
import com.digitalasset.quickstart.security.JwtAuthService;
import com.digitalasset.quickstart.security.JwtAuthService.AuthenticatedUser;
import com.digitalasset.quickstart.validation.SwapValidator;
import com.digitalasset.quickstart.service.IdempotencyService;
import com.digitalasset.quickstart.service.TokenMergeService;
import com.digitalasset.quickstart.constants.SwapConstants;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import jakarta.servlet.http.HttpServletRequest;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * SwapController - Handles swap preparation and execution
 *
 * CORS configured globally in WebSecurityConfig
 */
@RestController
@RequestMapping("/api/swap")
public class SwapController {
    private static final Logger logger = LoggerFactory.getLogger(SwapController.class);
    private final LedgerApi ledger;
    private final AuthUtils authUtils;
    private final PartyMappingService partyMappingService;
    private final SwapMetrics swapMetrics;
    private final SwapValidator swapValidator;
    private final IdempotencyService idempotencyService;
    private final TokenMergeService tokenMergeService;
    private final JwtAuthService jwtAuthService;

    public SwapController(LedgerApi ledger, AuthUtils authUtils, PartyMappingService partyMappingService,
                          SwapMetrics swapMetrics, SwapValidator swapValidator, IdempotencyService idempotencyService,
                          TokenMergeService tokenMergeService, JwtAuthService jwtAuthService) {
        this.ledger = ledger;
        this.authUtils = authUtils;
        this.partyMappingService = partyMappingService;
        this.swapMetrics = swapMetrics;
        this.swapValidator = swapValidator;
        this.idempotencyService = idempotencyService;
        this.tokenMergeService = tokenMergeService;
        this.jwtAuthService = jwtAuthService;
    }

    /**
     * POST /api/swap/prepare - Prepare a swap (create SwapRequest + execute PrepareSwap)
     *
     * Flow:
     * 1. Extract trader from JWT
     * 2. Validate pool at ledger end
     * 3. Find trader's input token
     * 4. Create SwapRequest
     * 5. Exercise SwapRequest.PrepareSwap
     * 6. Return SwapReady CID
     */
    @PostMapping("/prepare")
    @WithSpan
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public CompletableFuture<PrepareSwapResponse> prepareSwap(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody PrepareSwapRequest req,
        @RequestHeader(value = SwapConstants.IDEMPOTENCY_HEADER, required = false) String idempotencyKey
    ) {
        if (jwt == null || jwt.getSubject() == null) {
            logger.error("POST /api/swap/prepare called without valid JWT");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required - JWT subject missing");
        }

        String jwtSubject = jwt.getSubject();
        String trader = partyMappingService.mapJwtSubjectToParty(jwtSubject);

        // IDEMPOTENCY: Check if this request was already processed
        if (idempotencyKey != null) {
            idempotencyService.validateIdempotencyKey(idempotencyKey);
            Object cachedResponse = idempotencyService.checkIdempotency(idempotencyKey);
            if (cachedResponse != null) {
                logger.info("Returning cached response for idempotency key: {}", idempotencyKey);
                return CompletableFuture.completedFuture((PrepareSwapResponse) cachedResponse);
            }
        }

        String commandId = UUID.randomUUID().toString();

        // Enforce scale=10 for all amounts
        BigDecimal inputAmount = req.inputAmount.setScale(10, RoundingMode.DOWN);
        BigDecimal minOutput = req.minOutput.setScale(10, RoundingMode.DOWN);

        logger.info("POST /api/swap/prepare - JWT subject: {}, Canton party: {}, poolId: {}, {}→{}, amount: {}",
            jwtSubject, trader, req.poolId, req.inputSymbol, req.outputSymbol, req.inputAmount);

        // Record metrics: swap preparation started
        long startTime = System.currentTimeMillis();
        swapMetrics.recordSwapPrepared(req.inputSymbol, req.outputSymbol);

        // Step 1: Validate pool at ledger end - find pool with POSITIVE reserves
        return ledger.getActiveContracts(Pool.class)
            .thenCompose(pools -> {
                // Note: Active pools count is updated by PoolMetricsScheduler (scheduled task)
                // Not updated here to avoid traffic-dependent metrics

                Optional<LedgerApi.ActiveContract<Pool>> maybePool = pools.stream()
                    .filter(p -> p.payload.getPoolId.equals(req.poolId))
                    .filter(p -> p.payload.getReserveA.compareTo(BigDecimal.ZERO) > 0)  // MUST have reserves
                    .filter(p -> p.payload.getReserveB.compareTo(BigDecimal.ZERO) > 0)  // MUST have reserves
                    .findFirst();

                if (maybePool.isEmpty()) {
                    logger.error("Pool not found or has no liquidity: {}", req.poolId);
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Pool not found or has no liquidity: " + req.poolId + " (add liquidity first)");
                }

                LedgerApi.ActiveContract<Pool> pool = maybePool.get();
                Pool poolPayload = pool.payload;

                String poolParty = poolPayload.getPoolParty.getParty;
                String poolOperator = poolPayload.getPoolOperator.getParty;

                logger.info("Found pool {} - poolParty: {}, poolOperator: {}",
                    req.poolId, poolParty, poolOperator);

                // Step 2: Validate trader's input token
                return ledger.getActiveContracts(Token.class)
                    .thenCompose(tokens -> {
                        Optional<LedgerApi.ActiveContract<Token>> maybeToken = tokens.stream()
                            .filter(t -> t.payload.getSymbol.equals(req.inputSymbol) &&
                                         t.payload.getOwner.getParty.equals(trader))
                            .findFirst();

                        if (maybeToken.isEmpty()) {
                            logger.error("Input token ({}) not found for trader {}", req.inputSymbol, trader);
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Token " + req.inputSymbol + " not found for trader");
                        }

                        LedgerApi.ActiveContract<Token> inputToken = maybeToken.get();

                        if (inputToken.payload.getAmount.compareTo(req.inputAmount) < 0) {
                            logger.error("Insufficient input token: has {}, needs {}",
                                inputToken.payload.getAmount, req.inputAmount);
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "Insufficient " + req.inputSymbol + ": have " + inputToken.payload.getAmount +
                                ", need " + req.inputAmount);
                        }

                        logger.info("Input token validation passed - CID: {}, amount: {}",
                            inputToken.contractId.getContractId, inputToken.payload.getAmount);

                        // Step 3: Create SwapRequest + execute PrepareSwap
                        Instant deadline = Instant.now().plusSeconds(600); // 10 minutes

                        SwapRequest.PrepareSwap prepareChoice = new SwapRequest.PrepareSwap(
                            new Party(authUtils.getAppProviderPartyId())  // protocolFeeReceiver
                        );

                        // First create SwapRequest template
                        SwapRequest swapRequest = new SwapRequest(
                            new Party(trader),
                            new ContractId<>(pool.contractId.getContractId),
                            new Party(poolParty),
                            new Party(poolOperator),
                            poolPayload.getIssuerA,
                            poolPayload.getIssuerB,
                            poolPayload.getSymbolA,
                            poolPayload.getSymbolB,
                            poolPayload.getFeeBps,
                            poolPayload.getMaxTTL,
                            new ContractId<>(inputToken.contractId.getContractId),
                            req.inputSymbol,
                            req.inputAmount,
                            req.outputSymbol,
                            req.minOutput,
                            deadline,
                            req.maxPriceImpactBps.longValue()  // Convert Integer to Long
                        );

                        logger.info("Creating SwapRequest with deterministic CID extraction for trader: {}", trader);

                        // Create SwapRequest with createAndGetCid (deterministic, race-free)
                        // Use swapRequest.templateId() to get the correct package ID
                        return ledger.createAndGetCid(
                                swapRequest,
                                List.of(trader),  // actAs: trader creates the SwapRequest
                                List.of(poolParty),  // readAs: poolParty can see it
                                commandId + "-create",
                                swapRequest.templateId()  // Use instance template ID (has correct package ID)
                            )
                            .thenCompose(swapRequestCid -> {
                                logger.info("✅ SwapRequest created with CID: {} (via transaction tree)", swapRequestCid.getContractId);

                                // Now exercise PrepareSwap choice on the swapRequestCid
                                return ledger.exerciseAndGetResult(
                                    swapRequestCid,
                                    prepareChoice,
                                    commandId + "-prepare"
                                );
                            })
                            .thenApply(result -> {
                                // Result is Tuple2<ContractId<SwapReady>, ContractId<Token>>
                                ContractId<SwapReady> swapReadyCid = result.get_1;
                                ContractId<Token> poolInputTokenCid = result.get_2;

                                logger.info("METRIC: prepare_swap_success{{trader=\"{}\", pool=\"{}\", input=\"{}\", output=\"{}\"}}",
                                    trader, req.poolId, req.inputSymbol, req.outputSymbol);
                                logger.info("PrepareSwap success - swapReadyCid: {}, poolInputTokenCid: {}",
                                    swapReadyCid.getContractId, poolInputTokenCid.getContractId);

                                PrepareSwapResponse response = new PrepareSwapResponse(
                                    swapReadyCid.getContractId,
                                    poolInputTokenCid.getContractId,
                                    req.inputSymbol,
                                    req.outputSymbol,
                                    req.inputAmount.toPlainString(),
                                    req.minOutput.toPlainString()
                                );

                                // IDEMPOTENCY: Register successful response
                                if (idempotencyKey != null) {
                                    idempotencyService.registerSuccess(idempotencyKey, commandId, null, response);
                                }

                                return response;
                            });
                    });
            })
            .exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                String errorMessage = cause.getMessage();

                logger.error("PrepareSwap failed: {}", errorMessage, ex);

                if (errorMessage != null && errorMessage.contains("CONTRACT_NOT_FOUND")) {
                    logger.warn("Stale CID detected - pool or token was archived");
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Pool or token changed; refresh and retry");
                }

                logger.error("METRIC: prepare_swap_failure{{reason=\"{}\", pool=\"{}\"}}",
                    errorMessage, req.poolId);

                if (cause instanceof ResponseStatusException) {
                    throw (ResponseStatusException) cause;
                }

                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage, cause);
            });
    }

    /**
     * POST /api/swap/execute - Execute a prepared swap
     *
     * Flow:
     * 1. Extract poolParty from pool (must be app_provider)
     * 2. Validate SwapReady exists
     * 3. Exercise SwapReady.ExecuteSwap
     * 4. Return receipt
     */
    @PostMapping("/execute")
    @WithSpan
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public CompletableFuture<ExecuteSwapResponse> executeSwap(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ExecuteSwapRequest req,
        @RequestHeader(value = SwapConstants.IDEMPOTENCY_HEADER, required = false) String idempotencyKey
    ) {
        if (jwt == null || jwt.getSubject() == null) {
            logger.error("POST /api/swap/execute called without valid JWT");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required - JWT subject missing");
        }

        String jwtSubject = jwt.getSubject();
        String executingParty = partyMappingService.mapJwtSubjectToParty(jwtSubject);

        // IDEMPOTENCY: Check if this request was already processed
        if (idempotencyKey != null) {
            idempotencyService.validateIdempotencyKey(idempotencyKey);
            Object cachedResponse = idempotencyService.checkIdempotency(idempotencyKey);
            if (cachedResponse != null) {
                logger.info("Returning cached response for idempotency key: {}", idempotencyKey);
                return CompletableFuture.completedFuture((ExecuteSwapResponse) cachedResponse);
            }
        }

        String commandId = UUID.randomUUID().toString();

        logger.info("POST /api/swap/execute - JWT subject: {}, Canton party: {}, swapReadyCid: {}, commandId: {}",
            jwtSubject, executingParty, req.swapReadyCid, commandId);

        // Record metrics: swap execution started
        long executionStartTime = System.currentTimeMillis();

        // Step 1: Validate SwapReady exists
        return ledger.getActiveContracts(SwapReady.class)
            .thenCompose(swapReadies -> {
                Optional<LedgerApi.ActiveContract<SwapReady>> maybeSwapReady = swapReadies.stream()
                    .filter(sr -> sr.contractId.getContractId.equals(req.swapReadyCid))
                    .findFirst();

                if (maybeSwapReady.isEmpty()) {
                    logger.error("SwapReady not found: {}", req.swapReadyCid);
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "SwapReady not found: " + req.swapReadyCid);
                }

                LedgerApi.ActiveContract<SwapReady> swapReady = maybeSwapReady.get();
                SwapReady swapReadyPayload = swapReady.payload;

                String poolParty = swapReadyPayload.getPoolParty.getParty;
                String trader = swapReadyPayload.getTrader.getParty;

                // Verify executing party is poolParty
                if (!executingParty.equals(poolParty)) {
                    logger.error("ExecuteSwap must be called by poolParty. Expected: {}, got: {}",
                        poolParty, executingParty);
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only poolParty can execute swaps");
                }

                logger.info("SwapReady found - trader: {}, poolParty: {}, {}→{}, amount: {}",
                    trader, poolParty,
                    swapReadyPayload.getInputSymbol,
                    swapReadyPayload.getOutputSymbol,
                    swapReadyPayload.getInputAmount);

                // Step 2: Exercise ExecuteSwap (with automatic retry on stale Pool CID)
                SwapReady.ExecuteSwap executeChoice = new SwapReady.ExecuteSwap();

                logger.info("Executing swap with poolParty: {}, commandId: {}", poolParty, commandId);

                return StaleAcsRetry.run(
                    () -> ledger.exerciseAndGetResult(
                        swapReady.contractId,
                        executeChoice,
                        commandId
                    ),
                    () -> logger.info("Refreshing ACS for ExecuteSwap retry (stale pool CID)"),
                    "ExecuteSwap"
                ).thenCompose(receiptCid -> {
                    // Fetch receipt to get details
                    return ledger.getActiveContracts(Receipt.class)
                        .thenApply(receipts -> {
                            Optional<LedgerApi.ActiveContract<Receipt>> maybeReceipt = receipts.stream()
                                .filter(r -> r.contractId.getContractId.equals(receiptCid.getContractId))
                                .findFirst();

                            if (maybeReceipt.isEmpty()) {
                                logger.warn("Receipt not found immediately after creation: {}", receiptCid.getContractId);
                                // Return minimal response if receipt not found
                                return new ExecuteSwapResponse(
                                    receiptCid.getContractId,
                                    trader,
                                    swapReadyPayload.getInputSymbol,
                                    swapReadyPayload.getOutputSymbol,
                                    swapReadyPayload.getInputAmount.toPlainString(),
                                    "unknown",
                                    Instant.now().toString()
                                );
                            }

                            Receipt receipt = maybeReceipt.get().payload;

                            // Record metrics: swap executed successfully
                            long executionTime = System.currentTimeMillis() - executionStartTime;
                            swapMetrics.recordSwapExecuted(
                                receipt.getInputSymbol,
                                receipt.getOutputSymbol,
                                receipt.getAmountIn,
                                receipt.getAmountOut,
                                0, // Price impact not available in receipt
                                executionTime
                            );

                            // Record fee collection
                            BigDecimal totalFee = receipt.getAmountIn.multiply(new BigDecimal("0.003"));
                            BigDecimal protocolFee = totalFee.multiply(new BigDecimal("0.25"));
                            BigDecimal lpFee = totalFee.subtract(protocolFee);
                            swapMetrics.recordFeeCollected(receipt.getInputSymbol, lpFee, protocolFee);

                            logger.info("METRIC: execute_swap_success{{trader=\"{}\", pool_party=\"{}\", input=\"{}\", output=\"{}\"}}",
                                trader, poolParty, receipt.getInputSymbol, receipt.getOutputSymbol);
                            logger.info("ExecuteSwap success - receiptCid: {}, amountIn: {}, amountOut: {}, executionTime: {}ms",
                                receiptCid.getContractId, receipt.getAmountIn, receipt.getAmountOut, executionTime);

                            ExecuteSwapResponse response = new ExecuteSwapResponse(
                                receiptCid.getContractId,
                                receipt.getTrader.getParty,
                                receipt.getInputSymbol,
                                receipt.getOutputSymbol,
                                receipt.getAmountIn.toPlainString(),
                                receipt.getAmountOut.toPlainString(),
                                receipt.getTimestamp.toString()
                            );

                            // IDEMPOTENCY: Register successful response
                            if (idempotencyKey != null) {
                                idempotencyService.registerSuccess(idempotencyKey, commandId, null, response);
                            }

                            return response;
                        });
                });
            })
            .exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                String errorMessage = cause.getMessage();

                // Record metrics: swap failed (symbols not available in exception handler)

                logger.error("ExecuteSwap failed: {}", errorMessage, ex);

                if (errorMessage != null && errorMessage.contains("CONTRACT_NOT_FOUND")) {
                    logger.warn("Stale CID detected - SwapReady was archived");
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "SwapReady changed; refresh and retry");
                }

                if (errorMessage != null && errorMessage.contains("expired")) {
                    throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "Swap deadline expired");
                }

                if (errorMessage != null && errorMessage.contains("slippage")) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Slippage protection triggered: " + errorMessage);
                }

                logger.error("METRIC: execute_swap_failure{{reason=\"{}\"}}",  errorMessage);

                if (cause instanceof ResponseStatusException) {
                    throw (ResponseStatusException) cause;
                }

                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage, cause);
            });
    }

    /**
     * ATOMIC SWAP - Single-step swap endpoint that avoids poolCid staleness
     * This directly calls Pool.AtomicSwap choice without intermediate SwapReady contract
     */
    @PostMapping("/atomic")
    @WithSpan
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public CompletableFuture<AtomicSwapResponse> atomicSwap(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PrepareSwapRequest req,
            HttpServletRequest request,
            @RequestHeader(value = SwapConstants.IDEMPOTENCY_HEADER, required = false) String idempotencyKey
    ) {
        String authorization = request != null ? request.getHeader(HttpHeaders.AUTHORIZATION) : null;
        String trader = resolveTraderParty(jwt, authorization);
        String jwtSubject = jwt != null ? jwt.getSubject() : trader;

        // IDEMPOTENCY: Check if this request was already processed
        if (idempotencyKey != null) {
            idempotencyService.validateIdempotencyKey(idempotencyKey);
            Object cachedResponse = idempotencyService.checkIdempotency(idempotencyKey);
            if (cachedResponse != null) {
                logger.info("Returning cached response for idempotency key: {}", idempotencyKey);
                return CompletableFuture.completedFuture((AtomicSwapResponse) cachedResponse);
            }
        }

        String commandId = UUID.randomUUID().toString();

        // HARDENED INPUT VALIDATION using centralized validator
        swapValidator.validateTokenPair(req.inputSymbol, req.outputSymbol);
        swapValidator.validateInputAmount(req.inputAmount);
        swapValidator.validateMinOutput(req.minOutput);
        swapValidator.validateMaxPriceImpact(req.maxPriceImpactBps);

        // Enforce scale using centralized constant
        BigDecimal inputAmount = req.inputAmount.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        BigDecimal minOutput = req.minOutput.setScale(SwapConstants.SCALE, RoundingMode.DOWN);

        logger.info("POST /api/swap/atomic - JWT subject: {}, Canton party: {}, poolId: {}, {}→{}, amount: {}",
            jwtSubject, trader, req.poolId, req.inputSymbol, req.outputSymbol, inputAmount);

        // Record metrics: swap preparation started
        long startTime = System.currentTimeMillis();
        swapMetrics.recordSwapPrepared(req.inputSymbol, req.outputSymbol);

        // Step 1: Fetch FRESH snapshots (Ledger API gRPC; no app cache, no PQS)
        CompletableFuture<List<LedgerApi.ActiveContract<Pool>>> poolsFuture =
            ledger.getActiveContracts(Pool.class);
        CompletableFuture<List<LedgerApi.ActiveContract<Token>>> tokensFuture =
            ledger.getActiveContracts(Token.class);

        return poolsFuture.thenCombine(tokensFuture, (pools, tokens) -> {
            logger.info("🔍 ACS snapshot: {} pools, {} tokens", pools.size(), tokens.size());

            // Build Set of active token CIDs (to validate pool canonicals are alive)
            Set<ContractId<Token>> activeTokenCids = tokens.stream()
                .map(t -> t.contractId)
                .collect(Collectors.toSet());

            logger.info("Active token CIDs in ACS: {}", activeTokenCids.size());

            // Find pool: either by poolId if provided, or by token pair auto-discovery
            // CRITICAL: Also validate pool's tokenACid/tokenBCid are ACTIVE (not archived)
            Optional<LedgerApi.ActiveContract<Pool>> maybePool;
            if (req.poolId != null && !req.poolId.isEmpty()) {
                // Exact poolId match
                maybePool = pools.stream()
                    .filter(p -> p.payload.getPoolId.equals(req.poolId))
                    .filter(p -> p.payload.getReserveA.compareTo(BigDecimal.ZERO) > 0)
                    .filter(p -> p.payload.getReserveB.compareTo(BigDecimal.ZERO) > 0)
                    .filter(p -> {
                        // CRITICAL FIX: Validate pool canonicals are present AND active
                        boolean hasTokenA = p.payload.getTokenACid.isPresent()
                            && activeTokenCids.contains(p.payload.getTokenACid.get());
                        boolean hasTokenB = p.payload.getTokenBCid.isPresent()
                            && activeTokenCids.contains(p.payload.getTokenBCid.get());
                        if (!hasTokenA || !hasTokenB) {
                            logger.warn("Pool {} has stale canonicals: tokenACid={}, tokenBCid={}, skipping",
                                p.payload.getPoolId,
                                p.payload.getTokenACid.map(Object::toString).orElse("NONE"),
                                p.payload.getTokenBCid.map(Object::toString).orElse("NONE"));
                        }
                        return hasTokenA && hasTokenB;
                    })
                    .findFirst();
            } else {
                // Auto-discover pool by token symbols
                maybePool = pools.stream()
                    .filter(p -> (p.payload.getSymbolA.equals(req.inputSymbol) && p.payload.getSymbolB.equals(req.outputSymbol)) ||
                                 (p.payload.getSymbolA.equals(req.outputSymbol) && p.payload.getSymbolB.equals(req.inputSymbol)))
                    .filter(p -> p.payload.getReserveA.compareTo(BigDecimal.ZERO) > 0)
                    .filter(p -> p.payload.getReserveB.compareTo(BigDecimal.ZERO) > 0)
                    .filter(p -> {
                        // CRITICAL FIX: Validate pool canonicals are present AND active
                        boolean hasTokenA = p.payload.getTokenACid.isPresent()
                            && activeTokenCids.contains(p.payload.getTokenACid.get());
                        boolean hasTokenB = p.payload.getTokenBCid.isPresent()
                            && activeTokenCids.contains(p.payload.getTokenBCid.get());
                        if (!hasTokenA || !hasTokenB) {
                            logger.warn("Pool {} has stale canonicals: tokenACid={}, tokenBCid={}, skipping",
                                p.payload.getPoolId,
                                p.payload.getTokenACid.map(Object::toString).orElse("NONE"),
                                p.payload.getTokenBCid.map(Object::toString).orElse("NONE"));
                        }
                        return hasTokenA && hasTokenB;
                    })
                    .findFirst();
            }

            if (maybePool.isEmpty()) {
                logger.error("No valid pool found (all pools have stale/missing canonicals): {}", req.poolId);
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "NO_VALID_POOL_CANONICALS - Pool needs liquidity refresh to update token CIDs");
            }

            LedgerApi.ActiveContract<Pool> pool = maybePool.get();
            Pool poolPayload = pool.payload;
            String poolParty = poolPayload.getPoolParty.getParty;

            logger.info("✅ Selected pool {} with ACTIVE canonicals: tokenACid={}, tokenBCid={}, reserves: {}={}, {}={}",
                pool.payload.getPoolId,
                pool.payload.getTokenACid.map(Object::toString).orElse("NONE"),
                pool.payload.getTokenBCid.map(Object::toString).orElse("NONE"),
                poolPayload.getSymbolA, poolPayload.getReserveA,
                poolPayload.getSymbolB, poolPayload.getReserveB);

            return new PoolAndTokens(pool, poolParty, tokens);
        }).thenCompose(result -> {
            LedgerApi.ActiveContract<Pool> pool = result.pool;
            String poolParty = result.poolParty;
            List<LedgerApi.ActiveContract<Token>> tokens = result.tokens;
            Pool poolPayload = pool.payload;

            // Step 2: Find trader's input token (FRESH from Ledger API - no cache!)
            logger.info("🔍 Selecting trader input token from {} active tokens", tokens.size());

            // Filter and select best token (largest amount)
            Optional<LedgerApi.ActiveContract<Token>> maybeToken = tokens.stream()
                .filter(t -> t.payload.getSymbol.equals(req.inputSymbol))
                .filter(t -> t.payload.getOwner.getParty.equals(trader))
                .filter(t -> t.payload.getAmount.compareTo(inputAmount) >= 0)
                .max((a, b) -> a.payload.getAmount.compareTo(b.payload.getAmount));

            if (maybeToken.isEmpty()) {
                logger.error("Trader {} has insufficient {} balance for amount {}",
                    trader, req.inputSymbol, req.inputAmount);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Insufficient " + req.inputSymbol + " balance");
            }

            LedgerApi.ActiveContract<Token> traderToken = maybeToken.get();

            // CRITICAL: Log the selected token CID to verify it's fresh
            logger.info("✅ Selected FRESH trader token: CID={}, symbol={}, amount={}, owner={}",
                traderToken.contractId,
                traderToken.payload.getSymbol,
                traderToken.payload.getAmount,
                traderToken.payload.getOwner.getParty);

            // Double-check: verify this CID exists in the snapshot
            boolean cidPresentInSnapshot = tokens.stream()
                .anyMatch(t -> t.contractId.equals(traderToken.contractId));
            if (!cidPresentInSnapshot) {
                throw new IllegalStateException("Selected token disappeared from snapshot!");
            }

            // Step 3: Calculate deadline (5 minutes from now)
            Instant deadline = Instant.now().plusSeconds(300);

            // Step 4: Create AtomicSwapProposal (signed by trader)
            AtomicSwapProposal proposalTemplate = new AtomicSwapProposal(
                new Party(trader),
                pool.contractId,
                new Party(poolParty),
                new Party(poolPayload.getPoolOperator.getParty),
                new Party(poolPayload.getIssuerA.getParty),
                new Party(poolPayload.getIssuerB.getParty),
                poolPayload.getSymbolA,
                poolPayload.getSymbolB,
                poolPayload.getFeeBps,
                poolPayload.getMaxTTL,
                new Party(poolPayload.getProtocolFeeReceiver.getParty),
                traderToken.contractId,
                req.inputSymbol,
                inputAmount,
                req.outputSymbol,
                minOutput,
                (long) req.maxPriceImpactBps,
                deadline
            );

            logger.info("Creating atomic swap proposal - trader: {}, poolParty: {}, {}→{}, amount: {}",
                trader, poolParty, req.inputSymbol, req.outputSymbol, inputAmount);

            // Step 5: Create proposal and execute atomically with both parties
            return ledger.createAndGetCid(
                proposalTemplate,
                List.of(trader),
                List.of(),
                commandId + "-create",
                proposalTemplate.TEMPLATE_ID
            ).thenCompose(proposalCid -> {
                // Execute with both trader and poolParty authorization
                AtomicSwapProposal.ExecuteAtomicSwap executeChoice = new AtomicSwapProposal.ExecuteAtomicSwap();

                logger.info("Executing atomic swap with both parties - proposalCid: {}", proposalCid.getContractId);

                // Execute choice with both trader and poolParty authorization
                return ledger.exerciseAndGetResult(
                    proposalCid,
                    executeChoice,
                    commandId + "-execute"
                ).thenCompose(receiptCid -> {
                    // Fetch receipt to get actual swap details
                    return ledger.getActiveContracts(Receipt.class)
                                    .thenApply(receipts -> {
                                        Optional<LedgerApi.ActiveContract<Receipt>> maybeReceipt = receipts.stream()
                                            .filter(r -> r.contractId.getContractId.equals(receiptCid.getContractId))
                                            .findFirst();

                                        if (maybeReceipt.isEmpty()) {
                                            logger.warn("Receipt not found: {}", receiptCid.getContractId);
                                            return new AtomicSwapResponse(
                                                receiptCid.getContractId,
                                                trader,
                                                req.inputSymbol,
                                                req.outputSymbol,
                                                inputAmount.toPlainString(),
                                                minOutput.toPlainString(),
                                                Instant.now().toString()
                                            );
                                        }

                                        Receipt receipt = maybeReceipt.get().payload;

                                        // Record metrics: successful swap execution
                                        long executionTimeMs = System.currentTimeMillis() - startTime;
                                        swapMetrics.recordSwapExecuted(
                                            receipt.getInputSymbol,
                                            receipt.getOutputSymbol,
                                            receipt.getAmountIn,
                                            receipt.getAmountOut,
                                            0,  // priceImpactBps - TODO: calculate from pool reserves
                                            executionTimeMs
                                        );

                                        // Record fee collection (25% protocol, 75% LP)
                                        BigDecimal totalFee = receipt.getAmountIn.multiply(new BigDecimal("0.003")); // 0.3% total fee
                                        BigDecimal protocolFee = totalFee.multiply(new BigDecimal("0.25")); // 25% to ClearportX
                                        BigDecimal lpFee = totalFee.multiply(new BigDecimal("0.75")); // 75% to LPs
                                        swapMetrics.recordProtocolFee(receipt.getInputSymbol, protocolFee);
                                        swapMetrics.recordLpFee(receipt.getInputSymbol, lpFee);

                                        logger.info("METRIC: atomic_swap_success{{trader=\"{}\", pool_party=\"{}\", input=\"{}\", output=\"{}\"}}",
                                            trader, poolParty, receipt.getInputSymbol, receipt.getOutputSymbol);
                                        logger.info("Atomic swap success - receiptCid: {}, amountIn: {}, amountOut: {}",
                                            receiptCid.getContractId, receipt.getAmountIn, receipt.getAmountOut);

                                        AtomicSwapResponse response = new AtomicSwapResponse(
                                            receiptCid.getContractId,
                                            receipt.getTrader.getParty,
                                            receipt.getInputSymbol,
                                            receipt.getOutputSymbol,
                                            receipt.getAmountIn.toPlainString(),
                                            receipt.getAmountOut.toPlainString(),
                                            receipt.getTimestamp.toString()
                                        );

                                        // IDEMPOTENCY: Register successful execution
                                        if (idempotencyKey != null) {
                                            idempotencyService.registerSuccess(
                                                idempotencyKey,
                                                commandId,
                                                receiptCid.getContractId,
                                                response
                                            );
                                        }

                                        return response;
                                    });
                });
            });
        })
        .exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                String errorMessage = "Atomic swap failed: " + cause.getMessage();

                // Record metrics: swap failure
                swapMetrics.recordSwapFailed(req.inputSymbol, req.outputSymbol, cause.getMessage());

                logger.error("METRIC: atomic_swap_failure{{reason=\"{}\"}}",
                    cause.getMessage().replace("\"", "'"));
                logger.error(errorMessage, cause);

                if (cause instanceof ResponseStatusException) {
                    throw (ResponseStatusException) cause;
                }

                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage, cause);
            });
    }

    // Helper class to pass pool and tokens between stages
    private static class PoolAndTokens {
        final LedgerApi.ActiveContract<Pool> pool;
        final String poolParty;
        final List<LedgerApi.ActiveContract<Token>> tokens;

        PoolAndTokens(LedgerApi.ActiveContract<Pool> pool, String poolParty, List<LedgerApi.ActiveContract<Token>> tokens) {
            this.pool = pool;
            this.poolParty = poolParty;
            this.tokens = tokens;
        }
    }

    private String resolveTraderParty(Jwt jwt, String authorizationHeader) {
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            Result<AuthenticatedUser, DomainError> authResult = jwtAuthService.authenticate(authorizationHeader);
            if (authResult.isOk()) {
                return authResult.getValueUnsafe().partyId();
            }
            DomainError error = authResult.getErrorUnsafe();
            throw new ResponseStatusException(DomainErrorStatusMapper.map(error), error.message());
        }
        if (jwt != null && jwt.getSubject() != null) {
            return partyMappingService.mapJwtSubjectToParty(jwt.getSubject());
        }
        logger.error("POST /api/swap/atomic called without valid JWT");
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required - JWT subject missing");
    }

}

