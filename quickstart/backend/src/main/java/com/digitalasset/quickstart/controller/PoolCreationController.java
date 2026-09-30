package com.digitalasset.quickstart.controller;

import helvet_amm.amm.pool.Pool;
import helvet_amm.token.token.Token;
import helvet_amm.lptoken.lptoken.LPToken;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.InputTokenStaleOrNotVisibleError;
import com.digitalasset.quickstart.common.errors.LedgerVisibilityError;
import com.digitalasset.quickstart.common.errors.PoolEmptyError;
import com.digitalasset.quickstart.common.errors.PriceImpactTooHighError;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.MintTokensRequest;
import com.digitalasset.quickstart.dto.MintTokensResponse;
import com.digitalasset.quickstart.service.TokenMintService;
import com.digitalasset.quickstart.service.AddLiquidityCommandFactory;
import com.digitalasset.quickstart.service.AddLiquidityService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import com.digitalasset.quickstart.dto.AddLiquidityRequest;
import com.digitalasset.quickstart.dto.AddLiquidityResponse;
import helvet_amm.amm.swaprequest.SwapRequest;
import helvet_amm.amm.swaprequest.SwapReady;
import com.digitalasset.quickstart.ledger.StaleAcsRetry;
import com.digitalasset.quickstart.ledger.LedgerApi;
// import com.digitalasset.quickstart.service.PartyRegistryService;
import com.digitalasset.transcode.java.Party;
import com.digitalasset.transcode.java.ContractId;
import daml_stdlib_da_time_types.da.time.types.RelTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
// import clearportx_amm_production_gv.amm.pool.SwapTrace; // Commented out - not in current GV DAR

/**
 * Direct pool creation endpoint for debugging.
 * Creates a pool using the backend's LedgerApi with full logging.
 */
@Profile("local")
@RestController
@RequestMapping("/api/debug")
public class PoolCreationController {

    private static final Logger logger = LoggerFactory.getLogger(PoolCreationController.class);

    @Autowired
    private LedgerApi ledgerApi;

    @Autowired
    private TokenMintService tokenMintService;

    @Autowired
    private AddLiquidityService addLiquidityService;

    // @Autowired
    // private PartyRegistryService partyRegistry;

    @PostMapping(value = "/pools-for-party", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> poolsForPartyPostCompat(@RequestBody Map<String, String> body) {
        String party = body != null ? body.getOrDefault("party", "") : "";
        if (party == null || party.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "party is required"));
        }
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
            List<Map<String, Object>> list = new ArrayList<>();
            for (var p : pools) {
                var pay = p.payload;
                list.add(Map.of(
                        "poolId", pay.getPoolId,
                        "reserveA", pay.getReserveA.toPlainString(),
                        "reserveB", pay.getReserveB.toPlainString(),
                        "symbolA", pay.getSymbolA,
                        "symbolB", pay.getSymbolB,
                        "poolCid", p.contractId.getContractId
                ));
            }
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // (GET mapping already exists later in this controller as poolsForParty)

    @PostMapping("/create-pool-direct")
    public ResponseEntity<?> createPoolDirect(@RequestBody CreatePoolRequest request) {
        logger.info("=== DIRECT POOL CREATION REQUEST ===");
        logger.info("Request: {}", request);

        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();

        try {
            // Step 1: Using provided party IDs directly (assuming they are fully qualified)
            steps.add("Using provided party IDs");
            logger.info("Step 1: Using provided party IDs directly...");

            String operatorFqid = request.operatorParty;
            String poolPartyFqid = request.poolParty;
            String ethIssuerFqid = request.ethIssuer;
            String usdcIssuerFqid = request.usdcIssuer;
            String lpIssuerFqid = request.lpIssuer;
            String feeReceiverFqid = request.feeReceiver;

            logger.info("  Operator: {}", operatorFqid);
            logger.info("  Pool party: {}", poolPartyFqid);
            logger.info("  ETH issuer: {}", ethIssuerFqid);
            logger.info("  USDC issuer: {}", usdcIssuerFqid);
            logger.info("  LP issuer: {}", lpIssuerFqid);
            logger.info("  Fee receiver: {}", feeReceiverFqid);

            result.put("parties", Map.of(
                "operator", operatorFqid,
                "poolParty", poolPartyFqid,
                "ethIssuer", ethIssuerFqid,
                "usdcIssuer", usdcIssuerFqid,
                "lpIssuer", lpIssuerFqid,
                "feeReceiver", feeReceiverFqid
            ));

            String tokenASymbol = normalizeSymbol(request.tokenASymbol, "ETH");
            String tokenBSymbol = normalizeSymbol(request.tokenBSymbol, "USDC");
            BigDecimal bootstrapAmountA = parseAmountOrDefault(request.tokenAInitial, "100.0");
            BigDecimal bootstrapAmountB = parseAmountOrDefault(request.tokenBInitial, "200000.0");
            long swapFeeBps = defaultLong(request.swapFeeBps, 10000L);
            long protocolFeeBps = defaultLong(request.protocolFeeBps, 5000L);

            result.put("tokenConfig", Map.of(
                    "tokenASymbol", tokenASymbol,
                    "tokenBSymbol", tokenBSymbol,
                    "bootstrapAmountA", bootstrapAmountA.toPlainString(),
                    "bootstrapAmountB", bootstrapAmountB.toPlainString(),
                    "swapFeeBps", swapFeeBps,
                    "protocolFeeBps", protocolFeeBps
            ));

            boolean doBootstrap = request.bootstrapTokens != null && request.bootstrapTokens;
            if (doBootstrap) {
            // Step 2: Create ETH token
            steps.add("Creating ETH token");
            logger.info("Step 2: Creating {} token ({})...", tokenASymbol, bootstrapAmountA.toPlainString());

            Token ethToken = new Token(
                new Party(ethIssuerFqid),
                new Party(poolPartyFqid),
                tokenASymbol,
                bootstrapAmountA
            );

            ContractId<Token> ethTokenCid = ledgerApi.createAndGetCid(
                ethToken,
                List.of(ethIssuerFqid),
                Collections.emptyList(),
                UUID.randomUUID().toString(),
                helvet_amm.Identifiers.Token_Token__Token
            ).join();

                logger.info("  ✓ ETH token created: {}", ethTokenCid.getContractId);
                result.put("ethTokenCid", ethTokenCid.getContractId);

            // Step 3: Create USDC token
            steps.add("Creating token B");
            logger.info("Step 3: Creating {} token ({})...", tokenBSymbol, bootstrapAmountB.toPlainString());

            Token usdcToken = new Token(
                new Party(usdcIssuerFqid),
                new Party(poolPartyFqid),
                tokenBSymbol,
                bootstrapAmountB
            );

            ContractId<Token> usdcTokenCid = ledgerApi.createAndGetCid(
                usdcToken,
                List.of(usdcIssuerFqid),
                Collections.emptyList(),
                UUID.randomUUID().toString(),
                helvet_amm.Identifiers.Token_Token__Token
            ).join();

                logger.info("  ✓ USDC token created: {}", usdcTokenCid.getContractId);
                result.put("usdcTokenCid", usdcTokenCid.getContractId);
            } else {
                steps.add("Skipping issuer-minted token bootstrap (bootstrapTokens=false)");
                logger.info("Skipping token bootstrap; creating empty pool under latest DAR");
            }

            // Step 4: Create Pool contract
            steps.add("Creating Pool contract");
            logger.info("Step 4: Creating Pool contract...");

            String poolId = (request.poolId != null && !request.poolId.isBlank())
                    ? request.poolId
                    : String.format(Locale.ROOT, "%s-%s-direct", tokenASymbol.toLowerCase(Locale.ROOT), tokenBSymbol.toLowerCase(Locale.ROOT));

            Pool pool = new Pool(
                new Party(operatorFqid),
                new Party(poolPartyFqid),
                new Party(lpIssuerFqid),
                new Party(ethIssuerFqid),
                new Party(usdcIssuerFqid),
                tokenASymbol,
                tokenBSymbol,
                30L,
                poolId,
                new RelTime(86400000000L),
                new BigDecimal("0.0"),
                new BigDecimal("0.0"),
                new BigDecimal("0.0"),
                Optional.<ContractId<Token>>empty(),
                Optional.<ContractId<Token>>empty(),
                new Party(feeReceiverFqid),
                swapFeeBps,
                protocolFeeBps,
                List.of()
            );

            ContractId<Pool> poolCid = ledgerApi.createAndGetCid(
                pool,
                List.of(operatorFqid, poolPartyFqid),
                Collections.emptyList(),
                UUID.randomUUID().toString(),
                helvet_amm.Identifiers.AMM_Pool__Pool
            ).join();

            logger.info("  ✓ Pool created: {}", poolCid.getContractId);
            result.put("poolCid", poolCid.getContractId);

            // Step 5: Verify pool is visible
            steps.add("Verifying pool visibility");
            logger.info("Step 5: Verifying pool visibility...");

            var pools = ledgerApi.getActiveContracts(Pool.class).join();
            logger.info("  Operator sees {} pool(s)", pools.size());

            result.put("poolCount", pools.size());
            result.put("success", true);
            result.put("steps", steps);
            result.put("message", "Pool created successfully!");

            logger.info("=== POOL CREATION SUCCESSFUL ===");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Pool creation failed at step: {}", steps.isEmpty() ? "unknown" : steps.get(steps.size() - 1), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("failedAt", steps.isEmpty() ? "unknown" : steps.get(steps.size() - 1));
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/archive-pool")
    public ResponseEntity<?> archivePool(
        @RequestBody Map<String, String> body,
        @RequestHeader(value = "X-Party", required = false) String xParty
    ) {
        String poolId = body.getOrDefault("poolId", "");
        String poolCidStr = body.getOrDefault("poolCid", "");

        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();

        try {
            steps.add("Locate pool to archive");
            var pools = ledgerApi.getActiveContracts(Pool.class).join();

            Optional<LedgerApi.ActiveContract<Pool>> targetOpt;
            if (!poolCidStr.isBlank()) {
                targetOpt = pools.stream()
                    .filter(p -> p.contractId.getContractId.equals(poolCidStr))
                    .findFirst();
            } else if (!poolId.isBlank()) {
                targetOpt = pools.stream()
                    .filter(p -> p.payload.getPoolId.equals(poolId))
                    .sorted((p1, p2) -> {
                        // Prefer lowest TVL (likely empty pool)
                        java.math.BigDecimal tvl1 = p1.payload.getReserveA.multiply(p1.payload.getReserveB);
                        java.math.BigDecimal tvl2 = p2.payload.getReserveA.multiply(p2.payload.getReserveB);
                        return tvl1.compareTo(tvl2);
                    })
                    .findFirst();
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Provide poolId or poolCid"
                ));
            }

            if (targetOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", "Pool not found"
                ));
            }

            var pool = targetOpt.get();
            String poolParty = pool.payload.getPoolParty.getParty;

            steps.add("Archive pool");
            Pool.Archive archive = new Pool.Archive();
            String cmd = java.util.UUID.randomUUID().toString();

            // Authorize with poolParty; readAs poolParty
            StaleAcsRetry.run(
                () -> ledgerApi.exerciseAndGetResultWithParties(
                    pool.contractId,
                    archive,
                    cmd,
                    java.util.List.of(poolParty),
                    java.util.List.of(poolParty),
                    java.util.List.of()
                ),
                () -> ledgerApi.getActiveContracts(Pool.class).join(),
                "ArchivePoolDebug"
            ).join();

            result.put("success", true);
            result.put("archivedCid", pool.contractId.getContractId);
            result.put("steps", steps);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }

        @PostMapping("/mint-tokens")
    @WithSpan
    public CompletableFuture<ResponseEntity<?>> mintTokens(@RequestBody MintTokensRequest request) {
        Result<TokenMintService.MintTokensCommand, DomainError> commandResult = parseMintRequest(request);
        if (commandResult.isErr()) {
            return completedError(commandResult.getErrorUnsafe());
        }
        return tokenMintService.mintTokens(commandResult.getValueUnsafe())
                .thenApply(this::toHttpResponse);
    }

    private Result<TokenMintService.MintTokensCommand, DomainError> parseMintRequest(final MintTokensRequest request) {
        if (request.getIssuerParty() == null || request.getIssuerParty().isBlank()) {
            return Result.err(new ValidationError("issuerParty is required", ValidationError.Type.REQUEST));
        }
        if (request.getOwnerParty() == null || request.getOwnerParty().isBlank()) {
            return Result.err(new ValidationError("ownerParty is required", ValidationError.Type.REQUEST));
        }
        if (request.getSymbolA() == null || request.getSymbolA().isBlank() ||
                request.getSymbolB() == null || request.getSymbolB().isBlank()) {
            return Result.err(new ValidationError("symbolA and symbolB are required", ValidationError.Type.REQUEST));
        }
        try {
            BigDecimal amountA = new BigDecimal(request.getAmountA()).setScale(10, RoundingMode.HALF_UP);
            BigDecimal amountB = new BigDecimal(request.getAmountB()).setScale(10, RoundingMode.HALF_UP);
            return Result.ok(new TokenMintService.MintTokensCommand(
                    request.getIssuerParty(),
                    request.getOwnerParty(),
                    request.getSymbolA(),
                    amountA,
                    request.getSymbolB(),
                    amountB
            ));
        } catch (Exception ex) {
            return Result.err(new ValidationError("Amounts must be numeric", ValidationError.Type.REQUEST));
        }
    }

    private ResponseEntity<?> toHttpResponse(Result<?, DomainError> result) {
        if (result.isOk()) {
            return ResponseEntity.ok(result.getValueUnsafe());
        }
        return mapDomainErrorToResponse(result.getErrorUnsafe());
    }

    private ResponseEntity<?> mapDomainErrorToResponse(DomainError error) {
        HttpStatus status = mapStatus(error);
        return ResponseEntity.status(status).body(error);
    }

    private HttpStatus mapStatus(DomainError error) {
        if (error instanceof ValidationError) {
            return HttpStatus.BAD_REQUEST;
        }
        if (error instanceof InputTokenStaleOrNotVisibleError || error instanceof PoolEmptyError) {
            return HttpStatus.CONFLICT;
        }
        if (error instanceof PriceImpactTooHighError) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        if (error instanceof LedgerVisibilityError) {
            return HttpStatus.CONFLICT;
        }
        if (error instanceof UnexpectedError) {
            HttpStatus derived = HttpStatus.resolve(error.httpStatus());
            return derived != null ? derived : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        HttpStatus derived = HttpStatus.resolve(error.httpStatus());
        return derived != null ? derived : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private CompletableFuture<ResponseEntity<?>> completedError(DomainError error) {
        return CompletableFuture.completedFuture(mapDomainErrorToResponse(error));
    }

    public static class CreatePoolRequest {
        public String operatorParty;
        public String poolParty;
        public String ethIssuer;
        public String usdcIssuer;
        public String lpIssuer;
        public String feeReceiver;
        public String poolId; // optional override
        public Boolean bootstrapTokens; // optional
        public String tokenASymbol;
        public String tokenBSymbol;
        public String tokenAInitial;
        public String tokenBInitial;
        public Long swapFeeBps;
        public Long protocolFeeBps;

        @Override
        public String toString() {
            return String.format(
                Locale.ROOT,
                "CreatePoolRequest{op=%s, pool=%s, tokenA=%s, tokenB=%s, ethIss=%s, usdcIss=%s, lpIss=%s, fee=%s, poolId=%s, bootstrapTokens=%s, swapFeeBps=%s, protocolFeeBps=%s}",
                operatorParty,
                poolParty,
                tokenASymbol,
                tokenBSymbol,
                ethIssuer,
                usdcIssuer,
                lpIssuer,
                feeReceiver,
                poolId,
                bootstrapTokens,
                swapFeeBps,
                protocolFeeBps
            );
        }
    }

    private static String normalizeSymbol(String symbol, String fallback) {
        if (symbol == null || symbol.isBlank()) {
            return fallback;
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal parseAmountOrDefault(String value, String fallback) {
        try {
            if (value == null || value.isBlank()) {
                return new BigDecimal(fallback);
            }
            return new BigDecimal(value);
        } catch (Exception e) {
            return new BigDecimal(fallback);
        }
    }

    private static long defaultLong(Long value, long fallback) {
        return value != null ? value : fallback;
    }

    @PostMapping("/add-liquidity")
    public ResponseEntity<?> addLiquidityDebug(
            @RequestBody AddLiquidityRequest req,
            @RequestHeader(value = "X-Party", required = false) String xParty
    ) {
        String party = xParty != null && !xParty.isBlank()
                ? xParty
                : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
        String commandId = UUID.randomUUID().toString();
        Result<AddLiquidityService.AddLiquidityCommand, DomainError> commandResult = AddLiquidityCommandFactory.from(
                party,
                req.poolId,
                req.amountA,
                req.amountB,
                req.minLPTokens,
                commandId
        );
        if (commandResult.isErr()) {
            return mapAddLiquidityError(commandResult.getErrorUnsafe(), "/api/debug/add-liquidity");
        }
        return respondAddLiquidity(commandResult.getValueUnsafe(), "/api/debug/add-liquidity");
    }

    @PostMapping("/party-acs")
    public ResponseEntity<?> partyAcs(@RequestBody java.util.Map<String,String> body) {
        String party = body.getOrDefault("party", "");
        if (party.isBlank()) return ResponseEntity.badRequest().body(java.util.Map.of("success", false, "error", "Provide party"));
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
            var tokens = ledgerApi.getActiveContractsForParty(Token.class, party).join();
            java.util.List<java.util.Map<String,Object>> p = new java.util.ArrayList<>();
            for (var c : pools) {
                var pay = c.payload;
                p.add(java.util.Map.of(
                    "poolCid", c.contractId.getContractId,
                    "poolId", pay.getPoolId,
                    "symbolA", pay.getSymbolA,
                    "symbolB", pay.getSymbolB,
                    "reserveA", pay.getReserveA.toPlainString(),
                    "reserveB", pay.getReserveB.toPlainString()
                ));
            }
            java.util.List<java.util.Map<String,Object>> t = new java.util.ArrayList<>();
            for (var c : tokens) {
                var pay = c.payload;
                t.add(java.util.Map.of(
                    "cid", c.contractId.getContractId,
                    "symbol", pay.getSymbol,
                    "owner", pay.getOwner.getParty,
                    "amount", pay.getAmount.toPlainString()
                ));
            }
            return ResponseEntity.ok(java.util.Map.of("success", true, "pools", p, "tokens", t));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/party-acs")
    public ResponseEntity<?> partyAcsGet(
        @RequestParam(value = "template", required = false) String template,
        @RequestHeader(value = "X-Party", required = false) String xParty
    ) {
        String party = (xParty != null && !xParty.isBlank()) ? xParty : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
        if (party.isBlank()) return ResponseEntity.badRequest().body(java.util.Map.of("success", false, "error", "Provide X-Party or APP_PROVIDER_PARTY"));
        try {
            if (template == null || template.isBlank() || template.equals("AMM.Pool.Pool")) {
                var pools = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
                java.util.List<String> cids = new java.util.ArrayList<>();
                for (var c : pools) cids.add(c.contractId.getContractId);
                return ResponseEntity.ok(java.util.Map.of("success", true, "party", party, "count", pools.size(), "cids", cids));
            } else if (template.equals("Token.Token.Token")) {
                var toks = ledgerApi.getActiveContractsForParty(Token.class, party).join();
                java.util.List<String> cids = new java.util.ArrayList<>();
                for (var c : toks) cids.add(c.contractId.getContractId);
                return ResponseEntity.ok(java.util.Map.of("success", true, "party", party, "count", toks.size(), "cids", cids));
            }
            return ResponseEntity.badRequest().body(java.util.Map.of("success", false, "error", "Unknown template: " + template));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/whoami")
    public ResponseEntity<?> whoami(@RequestHeader(value = "X-Party", required = false) String xParty) {
        String party = (xParty != null && !xParty.isBlank()) ? xParty : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
        return ResponseEntity.ok(java.util.Map.of("resolvedParty", party));
    }

    @PostMapping("/swap-by-cid")
    public ResponseEntity<?> swapByCid(
        @RequestBody java.util.Map<String, Object> body,
        @RequestHeader(value = "X-Party") String xParty
    ) {
        String poolCidStr = String.valueOf(body.getOrDefault("poolCid", ""));
        String inputSymbol = String.valueOf(body.getOrDefault("inputSymbol", ""));
        String outputSymbol = String.valueOf(body.getOrDefault("outputSymbol", ""));
        java.math.BigDecimal amountIn = new java.math.BigDecimal(String.valueOf(body.getOrDefault("amountIn", "0")));
        java.math.BigDecimal minOutput = new java.math.BigDecimal(String.valueOf(body.getOrDefault("minOutput", "0")));
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
            var maybe = pools.stream().filter(p -> p.contractId.getContractId.equals(poolCidStr)).findFirst();
            if (maybe.isEmpty()) return ResponseEntity.status(404).body(java.util.Map.of("success", false, "error", "Pool not visible for party"));
            var pool = maybe.get();
            var p = pool.payload;

            var tokens = ledgerApi.getActiveContractsForParty(Token.class, xParty).join();
            var inputTok = tokens.stream().filter(t -> t.payload.getSymbol.equals(inputSymbol) && t.payload.getOwner.getParty.equals(xParty))
                    .max(java.util.Comparator.comparing(t -> t.payload.getAmount)).orElse(null);
            if (inputTok == null) return ResponseEntity.status(404).body(java.util.Map.of("success", false, "error", "Input token not found"));
            if (inputTok.payload.getAmount.compareTo(amountIn) < 0) return ResponseEntity.status(422).body(java.util.Map.of("success", false, "error", "Insufficient input"));

            var deadline = java.time.Instant.now().plusSeconds(600);
            helvet_amm.amm.pool.Pool.AtomicSwap choice = new helvet_amm.amm.pool.Pool.AtomicSwap(
                new com.digitalasset.transcode.java.Party(xParty),
                inputTok.contractId,
                inputSymbol,
                amountIn,
                outputSymbol,
                minOutput.compareTo(java.math.BigDecimal.ZERO) > 0 ? minOutput : java.math.BigDecimal.ONE,
                5000L,
                deadline
            );

            var res = ledgerApi.exerciseAndGetResultWithParties(
                pool.contractId,
                choice,
                java.util.UUID.randomUUID().toString(),
                java.util.List.of(xParty, p.getPoolParty.getParty),
                java.util.List.of(p.getPoolParty.getParty)
            ).join();

            return ResponseEntity.ok(java.util.Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Map.of("success", false, "error", e.getMessage()));
        }
    }
    @PostMapping("/add-liquidity-by-cid")
    public ResponseEntity<?> addLiquidityByCid(
            @RequestBody java.util.Map<String, Object> body,
            @RequestHeader(value = "X-Party") String xParty
    ) {
        String poolId = String.valueOf(body.getOrDefault("poolId", "")).trim();
        String poolCid = String.valueOf(body.getOrDefault("poolCid", "")).trim();
        if (poolId.isBlank()) {
            poolId = resolvePoolIdFromCid(poolCid, xParty);
            if (poolId == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "POOL_IDENTIFIER_REQUIRED",
                        "message", "Provide poolId or poolCid"
                ));
            }
        }
        BigDecimal amountA = new BigDecimal(String.valueOf(body.getOrDefault("amountA", "0")));
        BigDecimal amountB = new BigDecimal(String.valueOf(body.getOrDefault("amountB", "0")));
        BigDecimal minLP = new BigDecimal(String.valueOf(body.getOrDefault("minLPTokens", "0")));
        String commandId = UUID.randomUUID().toString();
        Result<AddLiquidityService.AddLiquidityCommand, DomainError> commandResult = AddLiquidityCommandFactory.from(
                xParty,
                poolId,
                amountA,
                amountB,
                minLP,
                commandId
        );
        if (commandResult.isErr()) {
            return mapAddLiquidityError(commandResult.getErrorUnsafe(), "/api/debug/add-liquidity-by-cid");
        }
        return respondAddLiquidity(commandResult.getValueUnsafe(), "/api/debug/add-liquidity-by-cid");
    }

    @PostMapping("/add-liquidity-for-party")
    public ResponseEntity<?> addLiquidityForParty(
            @RequestBody AddLiquidityRequest req,
            @RequestHeader(value = "X-Party") String xParty
    ) {
        String commandId = UUID.randomUUID().toString();
        Result<AddLiquidityService.AddLiquidityCommand, DomainError> commandResult = AddLiquidityCommandFactory.from(
                xParty,
                req.poolId,
                req.amountA,
                req.amountB,
                req.minLPTokens,
                commandId
        );
        if (commandResult.isErr()) {
            return mapAddLiquidityError(commandResult.getErrorUnsafe(), "/api/debug/add-liquidity-for-party");
        }
        return respondAddLiquidity(commandResult.getValueUnsafe(), "/api/debug/add-liquidity-for-party");
    }

    @PostMapping("/swap-debug")
    public ResponseEntity<?> swapDebug(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Party", required = false) String xParty
    ) {
        String poolId = String.valueOf(body.getOrDefault("poolId", ""));
        String inputSymbol = String.valueOf(body.getOrDefault("inputSymbol", ""));
        String outputSymbol = String.valueOf(body.getOrDefault("outputSymbol", ""));
        java.math.BigDecimal amountIn = new java.math.BigDecimal(String.valueOf(body.getOrDefault("amountIn", "0")));
        java.math.BigDecimal minOutput = new java.math.BigDecimal(String.valueOf(body.getOrDefault("minOutput", "0")));

        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();

        try {
            if (poolId.isBlank() || inputSymbol.isBlank() || outputSymbol.isBlank() || amountIn.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "poolId, inputSymbol, outputSymbol and amountIn are required"
                ));
            }

            steps.add("Locate pool (trader view)");
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, xParty != null && !xParty.isBlank() ? xParty : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "")).join();
            var poolOpt = pools.stream()
                .filter(p -> p.payload.getPoolId.equals(poolId))
                .sorted((p1, p2) -> {
                    java.math.BigDecimal tvl1 = p1.payload.getReserveA.multiply(p1.payload.getReserveB);
                    java.math.BigDecimal tvl2 = p2.payload.getReserveA.multiply(p2.payload.getReserveB);
                    return tvl2.compareTo(tvl1);
                })
                .findFirst();
            if (poolOpt.isEmpty()) {
                // Fallback: accept pool with positive reserves even if canonicals not yet visible
                poolOpt = pools.stream()
                    .filter(p -> p.payload.getPoolId.equals(poolId))
                    .filter(p -> p.payload.getReserveA.compareTo(java.math.BigDecimal.ZERO) > 0
                              && p.payload.getReserveB.compareTo(java.math.BigDecimal.ZERO) > 0)
                    .sorted((p1, p2) -> p2.payload.getReserveA.multiply(p2.payload.getReserveB)
                        .compareTo(p1.payload.getReserveA.multiply(p1.payload.getReserveB)))
                    .findFirst();
                if (poolOpt.isEmpty()) {
                    String ids = pools.stream().map(p -> p.payload.getPoolId).distinct()
                        .collect(java.util.stream.Collectors.joining(","));
                    logger.warn("Atomic swap: poolId={} not found. Available poolIds={} ({} pools)", poolId, ids, pools.size());
                    return ResponseEntity.status(404).body(Map.of(
                        "success", false,
                        "error", "Pool not found: " + poolId,
                        "availablePoolIds", ids
                    ));
                }
            }
            var pool = poolOpt.get();
            var poolPayload = pool.payload;
            String trader = xParty != null && !xParty.isBlank()
                    ? xParty
                    : System.getenv().getOrDefault("APP_PROVIDER_PARTY", poolPayload.getPoolParty.getParty);
            String poolParty = poolPayload.getPoolParty.getParty;
            String poolOperator = poolPayload.getPoolOperator.getParty;

            steps.add("Find trader input token");
            var tokens = ledgerApi.getActiveContracts(Token.class).join();
            java.util.Comparator<LedgerApi.ActiveContract<Token>> byAmountDesc =
                java.util.Comparator.comparing((LedgerApi.ActiveContract<Token> t) -> t.payload.getAmount).reversed();
            var inputTokenOpt = tokens.stream()
                .filter(t -> t.payload.getSymbol.equals(inputSymbol) && t.payload.getOwner.getParty.equals(trader))
                .sorted(byAmountDesc)
                .findFirst();
            if (inputTokenOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Input token not found for trader"));
            }
            var inputToken = inputTokenOpt.get();
            if (inputToken.payload.getAmount.compareTo(amountIn) < 0) {
                return ResponseEntity.status(422).body(Map.of("success", false, "error", "Insufficient input balance"));
            }

            steps.add("Create SwapRequest and execute");
            var receiptCid = StaleAcsRetry.run(
                () -> {
                    String flowCmd = java.util.UUID.randomUUID().toString();
                    // Re-select latest pool by poolId (prefer highest TVL)
                    var poolsLatest = ledgerApi.getActiveContracts(Pool.class).join();
                    var poolOptLatest = poolsLatest.stream()
                        .filter(p -> p.payload.getPoolId.equals(poolId))
                        .sorted((p1, p2) -> p2.payload.getReserveA.multiply(p2.payload.getReserveB)
                            .compareTo(p1.payload.getReserveA.multiply(p1.payload.getReserveB)))
                        .findFirst();
                    if (poolOptLatest.isEmpty()) {
                        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Pool not found: " + poolId));
                    }
                    var poolLatest = poolOptLatest.get();
                    var poolPayloadLatest = poolLatest.payload;
                    String poolPartyLatest = poolPayloadLatest.getPoolParty.getParty;
                    String poolOperatorLatest = poolPayloadLatest.getPoolOperator.getParty;

                    // Re-select latest input token and re-run full flow on retry
                    var tokensLatest = ledgerApi.getActiveContracts(Token.class).join();
                    java.util.Comparator<LedgerApi.ActiveContract<Token>> byAmountDescLatest =
                        java.util.Comparator.comparing((LedgerApi.ActiveContract<Token> t) -> t.payload.getAmount).reversed();
                    var inputTokenLatestOpt = tokensLatest.stream()
                        .filter(t -> t.payload.getSymbol.equals(inputSymbol) && t.payload.getOwner.getParty.equals(trader))
                        .sorted(byAmountDescLatest)
                        .findFirst();
                    if (inputTokenLatestOpt.isEmpty()) {
                        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Input token not found for trader"));
                    }
                    var inputTokenLatest = inputTokenLatestOpt.get();
                    if (inputTokenLatest.payload.getAmount.compareTo(amountIn) < 0) {
                        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Insufficient input balance"));
                    }

                    logger.info("Swap using poolCid={}, inputToken(symbol={}, amount={}, cid={}), trader={}, poolParty={}",
                        poolLatest.contractId.getContractId,
                        inputSymbol, inputTokenLatest.payload.getAmount, inputTokenLatest.contractId.getContractId,
                        trader,
                        poolPartyLatest
                    );

                    java.time.Instant deadlineLatest = java.time.Instant.now().plusSeconds(600);
                    long maxPriceImpactBpsLatest = 5000L;

                    SwapRequest swapReqLatest = new SwapRequest(
                        new com.digitalasset.transcode.java.Party(trader),
                        new com.digitalasset.transcode.java.ContractId<>(poolLatest.contractId.getContractId),
                        new com.digitalasset.transcode.java.Party(poolPartyLatest),
                        new com.digitalasset.transcode.java.Party(poolOperatorLatest),
                        poolPayloadLatest.getIssuerA,
                        poolPayloadLatest.getIssuerB,
                        poolPayloadLatest.getSymbolA,
                        poolPayloadLatest.getSymbolB,
                        poolPayloadLatest.getFeeBps,
                        poolPayloadLatest.getMaxTTL,
                        new com.digitalasset.transcode.java.ContractId<>(inputTokenLatest.contractId.getContractId),
                        inputSymbol,
                        amountIn,
                        outputSymbol,
                        minOutput.compareTo(java.math.BigDecimal.ZERO) > 0 ? minOutput : java.math.BigDecimal.ONE,
                        deadlineLatest,
                        maxPriceImpactBpsLatest
                    );

                    SwapRequest.PrepareSwap prepareLatest = new SwapRequest.PrepareSwap(new com.digitalasset.transcode.java.Party(poolOperatorLatest));

                    return ledgerApi.createAndGetCid(
                        swapReqLatest,
                        java.util.List.of(trader),
                        java.util.List.of(poolPartyLatest),
                        flowCmd + "-create",
                        swapReqLatest.templateId()
                    ).thenCompose(srCid -> ledgerApi.exerciseAndGetResult(
                        srCid,
                        prepareLatest,
                        flowCmd + "-prepare"
                    )).thenCompose(preparedLatest -> {
                        com.digitalasset.transcode.java.ContractId<SwapReady> swapReadyCidLatest = preparedLatest.get_1;
                        // Inspect SwapReady payload to log poolInputCid and context
                        var swapReadies = ledgerApi.getActiveContracts(SwapReady.class).join();
                        var srOpt = swapReadies.stream()
                            .filter(sr -> sr.contractId.getContractId.equals(swapReadyCidLatest.getContractId))
                            .findFirst();
                        if (srOpt.isPresent()) {
                            var sr = srOpt.get().payload;
                            logger.info("SwapReady created: poolInputCid={}, inputSymbol={}, outputSymbol={}, amountAfterFee={}",
                                sr.getPoolInputCid.getContractId,
                                sr.getInputSymbol,
                                sr.getOutputSymbol,
                                sr.getInputAmount
                            );
                        } else {
                            logger.warn("SwapReady not found in ACS immediately after creation: {}", swapReadyCidLatest.getContractId);
                        }
                        SwapReady.ExecuteSwap execLatest = new SwapReady.ExecuteSwap();
                        return ledgerApi.exerciseAndGetResult(
                            swapReadyCidLatest,
                            execLatest,
                            flowCmd + "-execute"
                        );
                    });
                },
                () -> {
                    ledgerApi.getActiveContracts(SwapRequest.class).join();
                    ledgerApi.getActiveContracts(SwapReady.class).join();
                    ledgerApi.getActiveContracts(Token.class).join();
                },
                "ExecuteSwapDebug"
            ).join();

            result.put("success", true);
            result.put("receiptCid", receiptCid.getContractId);
            result.put("steps", steps);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Swap (debug) failed", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/atomic-swap")
    public ResponseEntity<?> atomicSwapDebug(
        @RequestBody Map<String, Object> body,
        @RequestHeader(value = "X-Party", required = false) String xParty
    ) {
        String poolId = String.valueOf(body.getOrDefault("poolId", ""));
        String inputSymbol = String.valueOf(body.getOrDefault("inputSymbol", ""));
        String outputSymbol = String.valueOf(body.getOrDefault("outputSymbol", ""));
        java.math.BigDecimal amountIn = new java.math.BigDecimal(String.valueOf(body.getOrDefault("amountIn", "0")));
        java.math.BigDecimal minOutput = new java.math.BigDecimal(String.valueOf(body.getOrDefault("minOutput", "0")));

        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();

        try {
            if (poolId.isBlank() || inputSymbol.isBlank() || outputSymbol.isBlank() || amountIn.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "poolId, inputSymbol, outputSymbol and amountIn are required"
                ));
            }

            // Resolve trader party from X-Party or fallback to APP_PROVIDER_PARTY
            String trader = (xParty != null && !xParty.isBlank())
                ? xParty
                : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
            if (trader.isBlank()) {
                return ResponseEntity.status(400).body(Map.of(
                    "success", false,
                    "error", "Missing X-Party and APP_PROVIDER_PARTY"
                ));
            }

            steps.add("Fetch ACS (trader view)");
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, trader).join();
            var tokens = ledgerApi.getActiveContractsForParty(Token.class, trader).join();

            // Poll up to ~3s for pool with ACTIVE canonicals
            Optional<LedgerApi.ActiveContract<Pool>> poolOpt = Optional.empty();
            for (int i = 0; i < 12 && poolOpt.isEmpty(); i++) {
                final java.util.Set<com.digitalasset.transcode.java.ContractId<Token>> activeTokenCids = tokens.stream()
                    .map(t -> t.contractId)
                    .collect(java.util.stream.Collectors.toSet());

                poolOpt = pools.stream()
                    .filter(p -> p.payload.getPoolId.equals(poolId))
                    .filter(p -> p.payload.getTokenACid.isPresent() && p.payload.getTokenBCid.isPresent())
                    .filter(p -> activeTokenCids.contains(p.payload.getTokenACid.get()) && activeTokenCids.contains(p.payload.getTokenBCid.get()))
                    .sorted((p1, p2) -> p2.payload.getReserveA.multiply(p2.payload.getReserveB)
                        .compareTo(p1.payload.getReserveA.multiply(p1.payload.getReserveB)))
                    .findFirst();

                if (poolOpt.isPresent()) break;
                try { Thread.sleep(250); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                pools = ledgerApi.getActiveContracts(Pool.class).join();
                tokens = ledgerApi.getActiveContracts(Token.class).join();
            }
            if (poolOpt.isEmpty()) {
                // Fallback: accept pool with positive reserves even if canonicals not yet visible
                poolOpt = pools.stream()
                    .filter(p -> p.payload.getPoolId.equals(poolId))
                    .filter(p -> p.payload.getReserveA.compareTo(java.math.BigDecimal.ZERO) > 0
                              && p.payload.getReserveB.compareTo(java.math.BigDecimal.ZERO) > 0)
                    .sorted((p1, p2) -> p2.payload.getReserveA.multiply(p2.payload.getReserveB)
                        .compareTo(p1.payload.getReserveA.multiply(p1.payload.getReserveB)))
                    .findFirst();
                if (poolOpt.isEmpty()) {
                    String ids = pools.stream().map(p -> p.payload.getPoolId).distinct()
                        .collect(java.util.stream.Collectors.joining(","));
                    logger.warn("Atomic swap: poolId={} not found. Available poolIds={} ({} pools)", poolId, ids, pools.size());
                    return ResponseEntity.status(404).body(Map.of(
                        "success", false,
                        "error", "Pool not found: " + poolId,
                        "availablePoolIds", ids
                    ));
                }
            }
            var pool = poolOpt.get();
            var poolPayload = pool.payload;
            String poolParty = poolPayload.getPoolParty.getParty;
            String poolOperator = poolPayload.getPoolOperator.getParty;

            // Find best trader input token
            var inputTokenOpt = tokens.stream()
                .filter(t -> t.payload.getSymbol.equals(inputSymbol) && t.payload.getOwner.getParty.equals(trader))
                .max((a, b) -> a.payload.getAmount.compareTo(b.payload.getAmount));
            if (inputTokenOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Input token not found for trader"));
            }
            var inputToken = inputTokenOpt.get();
            if (inputToken.payload.getAmount.compareTo(amountIn) < 0) {
                return ResponseEntity.status(422).body(Map.of("success", false, "error", "Insufficient input balance"));
            }

            steps.add("Execute atomic swap");
            var receiptCid = StaleAcsRetry.run(
                () -> {
                    // Re-select freshest pool and input token each attempt
                    var poolsNow = ledgerApi.getActiveContracts(Pool.class).join();
                    var tokensNow = ledgerApi.getActiveContracts(Token.class).join();

                    var poolNowOpt = poolsNow.stream()
                        .filter(p -> p.payload.getPoolId.equals(poolId))
                        .sorted((p1, p2) -> p2.payload.getReserveA.multiply(p2.payload.getReserveB)
                            .compareTo(p1.payload.getReserveA.multiply(p1.payload.getReserveB)))
                        .findFirst();
                    if (poolNowOpt.isEmpty()) {
                        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Pool not found: " + poolId));
                    }
                    var poolNow = poolNowOpt.get();
                    var poolPayloadNow = poolNow.payload;
                    String poolPartyNow = poolPayloadNow.getPoolParty.getParty;

                    var inputNowOpt = tokensNow.stream()
                        .filter(t -> t.payload.getSymbol.equals(inputSymbol) && t.payload.getOwner.getParty.equals(trader))
                        .max((a, b) -> a.payload.getAmount.compareTo(b.payload.getAmount));
                    if (inputNowOpt.isEmpty()) {
                        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Input token not found for trader"));
                    }
                    var inputNow = inputNowOpt.get();
                    if (inputNow.payload.getAmount.compareTo(amountIn) < 0) {
                        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Insufficient input balance"));
                    }

                    var deadlineNow = java.time.Instant.now().plusSeconds(300);
                    helvet_amm.amm.pool.Pool.AtomicSwap exec =
                        new helvet_amm.amm.pool.Pool.AtomicSwap(
                            new com.digitalasset.transcode.java.Party(trader),
                            inputNow.contractId,
                            inputSymbol,
                            amountIn,
                            outputSymbol,
                            minOutput.compareTo(java.math.BigDecimal.ZERO) > 0 ? minOutput : java.math.BigDecimal.ONE,
                            5000L,
                            deadlineNow
                        );

                    String cmd = java.util.UUID.randomUUID().toString();
                    return ledgerApi.exerciseAndGetResultWithParties(
                        poolNow.contractId,
                        exec,
                        cmd + "-poolAtomic",
                        java.util.List.of(trader, poolPartyNow), // both controllers
                        java.util.List.of(poolPartyNow),
                        java.util.List.of()
                    ).thenApply(tuple -> {
                        // Returns (ContractId<Token>, ContractId<Pool>)
                        return tuple.get_1; // return receipt-like token cid for response
                    });
                },
                () -> {
                    ledgerApi.getActiveContracts(Pool.class).join();
                    ledgerApi.getActiveContracts(Token.class).join();
                },
                "AtomicSwapDebug"
            ).join();

            result.put("success", true);
            result.put("receiptCid", receiptCid.getContractId);
            result.put("steps", steps);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/pool-parties")
    public ResponseEntity<?> getPoolParties(@RequestBody Map<String, String> body) {
        String poolId = body.getOrDefault("poolId", "");
        if (poolId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "Provide poolId"
            ));
        }

        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();
        try {
            steps.add("Locate pool");
            var pools = ledgerApi.getActiveContracts(Pool.class).join();
            var poolOpt = pools.stream()
                .filter(p -> p.payload.getPoolId.equals(poolId))
                .sorted((p1, p2) -> p2.payload.getReserveA.multiply(p2.payload.getReserveB)
                    .compareTo(p1.payload.getReserveA.multiply(p1.payload.getReserveB)))
                .findFirst();
            if (poolOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", "Pool not found: " + poolId
                ));
            }
            var pool = poolOpt.get();
            var p = pool.payload;
            Map<String, Object> parties = new HashMap<>();
            parties.put("operatorParty", p.getPoolOperator.getParty);
            parties.put("poolParty", p.getPoolParty.getParty);
            parties.put("ethIssuer", p.getIssuerA.getParty);
            parties.put("usdcIssuer", p.getIssuerB.getParty);
            parties.put("lpIssuer", p.getLpIssuer.getParty);
            parties.put("feeReceiver", p.getProtocolFeeReceiver.getParty);
            parties.put("poolCid", pool.contractId.getContractId);

            result.put("success", true);
            result.put("parties", parties);
            result.put("steps", steps);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }

    @GetMapping("/traces")
    public ResponseEntity<?> listSwapTraces() {
        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();
        try {
            steps.add("SwapTrace not available in current DAR");
            result.put("success", true);
            result.put("traces", java.util.List.of());
            result.put("count", 0);
            result.put("steps", steps);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }

    @GetMapping("/pool/template-id")
    public ResponseEntity<?> poolTemplateId(@RequestParam("cid") String poolCid,
                                            @RequestHeader(value = "X-Party", required = false) String xParty) {
        String party = (xParty != null && !xParty.isBlank()) ? xParty : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
        if (party.isBlank()) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Provide X-Party or APP_PROVIDER_PARTY"));
        try {
            var id = ledgerApi.getTemplateIdForPoolCid(party, poolCid).join();
            Map<String, Object> out = new HashMap<>();
            out.put("success", true);
            out.put("poolCid", poolCid);
            out.put("packageId", id.getPackageId());
            out.put("module", id.getModuleName());
            out.put("entity", id.getEntityName());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", e.getMessage(), "poolCid", poolCid));
        }
    }

    @PostMapping("/smoke-p0")
    public ResponseEntity<?> smokeP0(@RequestHeader(value = "X-Party", required = false) String xParty) {
        String party = (xParty != null && !xParty.isBlank()) ? xParty : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
        if (party.isBlank()) return ResponseEntity.status(400).body(java.util.Map.of("success", false, "error", "Provide X-Party or APP_PROVIDER_PARTY"));

        java.util.Map<String, Object> out = new java.util.HashMap<>();
        java.util.List<String> steps = new java.util.ArrayList<>();
        try {
            // Create pool (all roles = party)
            steps.add("create pool");
            String cmdCreate = java.util.UUID.randomUUID().toString();
            Pool pool = new Pool(
                new Party(party), new Party(party), new Party(party), new Party(party), new Party(party),
                "ETH", "USDC", 30L, "ETH-USDC",
                new RelTime(86400000000L), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.util.Optional.<ContractId<Token>>empty(), java.util.Optional.<ContractId<Token>>empty(), new Party(party),
                10000L,
                5000L,
                List.of()
                );
            var poolCid = ledgerApi.createAndGetCid(
                pool,
                java.util.List.of(party),
                java.util.List.of(),
                cmdCreate,
                helvet_amm.Identifiers.AMM_Pool__Pool
            ).join();
            out.put("poolCid", poolCid.getContractId);

            // Mint tokens for party
            steps.add("mint tokens");
            Token a = new Token(new Party(party), new Party(party), "ETH", new java.math.BigDecimal("1.0"));
            Token b = new Token(new Party(party), new Party(party), "USDC", new java.math.BigDecimal("2000.0"));
            var aCid = ledgerApi.createAndGetCid(a, java.util.List.of(party), java.util.List.of(), cmdCreate+"-a", helvet_amm.Identifiers.Token_Token__Token).join();
            var bCid = ledgerApi.createAndGetCid(b, java.util.List.of(party), java.util.List.of(), cmdCreate+"-b", helvet_amm.Identifiers.Token_Token__Token).join();
            out.put("tokenA", aCid.getContractId);
            out.put("tokenB", bCid.getContractId);

            // Add liquidity
            steps.add("add liquidity");
            var add = new Pool.AddLiquidity(new Party(party), aCid, bCid, new java.math.BigDecimal("0.1"), new java.math.BigDecimal("200"), new java.math.BigDecimal("0"), java.time.Instant.now().plusSeconds(600));
            ledgerApi.exerciseAndGetResultWithParties(poolCid, add, java.util.UUID.randomUUID().toString(), java.util.List.of(party, party), java.util.List.of(party)).join();

            // Swap
            steps.add("swap");
            // Re-read input token for latest cid (owned by party)
            var toks = ledgerApi.getActiveContractsForParty(Token.class, party).join();
            var input = toks.stream().filter(t -> t.payload.getSymbol.equals("ETH") && t.payload.getOwner.getParty.equals(party))
                .max(java.util.Comparator.comparing(t -> t.payload.getAmount)).orElseThrow();
            var exec = new Pool.AtomicSwap(new Party(party), input.contractId, "ETH", new java.math.BigDecimal("0.05"), "USDC", new java.math.BigDecimal("1"), 5000L, java.time.Instant.now().plusSeconds(600));
            ledgerApi.exerciseAndGetResultWithParties(poolCid, exec, java.util.UUID.randomUUID().toString(), java.util.List.of(party, party), java.util.List.of(party)).join();

            // Read back pool
            steps.add("read acs");
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
            java.util.List<String> cids = new java.util.ArrayList<>();
            for (var c : pools) cids.add(c.contractId.getContractId);
            out.put("poolsVisible", cids);

            out.put("success", true);
            out.put("steps", steps);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", e.getMessage());
            out.put("steps", steps);
            return ResponseEntity.status(500).body(out);
        }
    }

    @PostMapping("/list-pools")
    public ResponseEntity<?> listAllPools() {
        try {
            var pools = ledgerApi.getActiveContracts(Pool.class).join();
            java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
            for (var p : pools) {
                var pay = p.payload;
                list.add(java.util.Map.of(
                        "poolId", pay.getPoolId,
                        "reserveA", pay.getReserveA.toPlainString(),
                        "reserveB", pay.getReserveB.toPlainString(),
                        "symbolA", pay.getSymbolA,
                        "symbolB", pay.getSymbolB,
                        "poolCid", p.contractId.getContractId
                ));
            }
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/pools-for-party")
    public ResponseEntity<?> poolsForParty(@RequestParam("party") String party) {
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
            java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
            for (var p : pools) {
                var pay = p.payload;
                list.add(java.util.Map.of(
                        "poolId", pay.getPoolId,
                        "reserveA", pay.getReserveA.toPlainString(),
                        "reserveB", pay.getReserveB.toPlainString(),
                        "symbolA", pay.getSymbolA,
                        "symbolB", pay.getSymbolB,
                        "poolCid", p.contractId.getContractId
                ));
            }
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/party-pools")
    public ResponseEntity<?> partyPools(@RequestParam("party") String party) {
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
            java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
            for (var p : pools) {
                var pay = p.payload;
                list.add(java.util.Map.of(
                        "poolId", pay.getPoolId,
                        "reserveA", pay.getReserveA.toPlainString(),
                        "reserveB", pay.getReserveB.toPlainString(),
                        "symbolA", pay.getSymbolA,
                        "symbolB", pay.getSymbolB,
                        "poolCid", p.contractId.getContractId
                ));
            }
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/pools-for-party-lookup")
    public ResponseEntity<?> poolsForPartyPost(@RequestBody java.util.Map<String, String> body) {
        String party = body.getOrDefault("party", "");
        if (party.isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                "success", false,
                "error", "Provide party"
            ));
        }
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
            java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
            for (var p : pools) {
                var pay = p.payload;
                list.add(java.util.Map.of(
                        "poolId", pay.getPoolId,
                        "reserveA", pay.getReserveA.toPlainString(),
                        "reserveB", pay.getReserveB.toPlainString(),
                        "symbolA", pay.getSymbolA,
                        "symbolB", pay.getSymbolB,
                        "poolCid", p.contractId.getContractId
                ));
            }
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    private ResponseEntity<?> respondAddLiquidity(AddLiquidityService.AddLiquidityCommand command, String path) {
        Result<AddLiquidityResponse, DomainError> result = addLiquidityService.addLiquidity(command).join();
        if (result.isOk()) {
            AddLiquidityResponse response = result.getValueUnsafe();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            payload.put("poolId", command.poolId());
            payload.put("lpTokenCid", response.lpTokenCid());
            payload.put("newPoolCid", response.newPoolCid());
            payload.put("reserveA", response.reserveA());
            payload.put("reserveB", response.reserveB());
            return ResponseEntity.ok(payload);
        }
        return mapAddLiquidityError(result.getErrorUnsafe(), path);
    }

    private ResponseEntity<?> mapAddLiquidityError(DomainError error, String path) {
        HttpStatus status = DomainErrorStatusMapper.map(error);
        return ResponseEntity.status(status).body(Map.of(
                "success", false,
                "error", error.code(),
                "message", error.message(),
                "path", path
        ));
    }

    private String resolvePoolIdFromCid(String poolCid, String providerParty) {
        if (poolCid == null || poolCid.isBlank()) {
            return null;
        }
        List<LedgerApi.ActiveContract<Pool>> pools = ledgerApi.getActiveContracts(Pool.class).join();
        return pools.stream()
                .filter(p -> p.contractId.getContractId.equals(poolCid))
                .map(p -> p.payload.getPoolId)
                .findFirst()
                .orElse(null);
    }

}
