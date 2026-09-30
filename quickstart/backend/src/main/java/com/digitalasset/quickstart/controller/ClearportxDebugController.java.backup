package com.digitalasset.quickstart.controller;

import clearportx_amm_production_gv.amm.pool.Pool;
import clearportx_amm_production_gv.token.token.Token;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.transcode.java.Party;
import com.digitalasset.transcode.java.ContractId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.digitalasset.quickstart.service.PoolDirectoryService;
import com.digitalasset.quickstart.service.TxPacer;

@RestController
@RequestMapping(value = "/api/clearportx/debug", produces = MediaType.APPLICATION_JSON_VALUE)
public class ClearportxDebugController {

    @Autowired
    private LedgerApi ledgerApi;
    @Autowired
    private PoolDirectoryService directory;
    @Autowired
    private TxPacer txPacer;

    @GetMapping("/whoami")
    public ResponseEntity<?> whoami(@RequestHeader(value = "X-Party", required = false) String xParty) {
        String party = (xParty != null && !xParty.isBlank()) ? xParty : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
        return ResponseEntity.ok(Map.of("resolvedParty", party));
    }

    @GetMapping("/party-acs")
    public ResponseEntity<?> partyAcs(
            @RequestParam(value = "template", required = false) String template,
            @RequestHeader(value = "X-Party", required = false) String xParty
    ) {
        String party = (xParty != null && !xParty.isBlank()) ? xParty : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
        if (party.isBlank()) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Provide X-Party or APP_PROVIDER_PARTY"));
        try {
            if (template == null || template.isBlank() || template.equals("AMM.Pool.Pool")) {
                List<LedgerApi.ActiveContract<Pool>> pools = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
                List<String> cids = new ArrayList<>();
                for (var c : pools) cids.add(c.contractId.getContractId);
                return ResponseEntity.ok(Map.of("success", true, "party", party, "count", pools.size(), "cids", cids));
            } else if (template.equals("Token.Token.Token")) {
                List<LedgerApi.ActiveContract<Token>> toks = ledgerApi.getActiveContractsForParty(Token.class, party).join();
                List<String> cids = new ArrayList<>();
                for (var c : toks) cids.add(c.contractId.getContractId);
                return ResponseEntity.ok(Map.of("success", true, "party", party, "count", toks.size(), "cids", cids));
            }
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Unknown template: " + template));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping(value = "/create-pool-single", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createPoolSingle(@RequestBody Map<String, Object> reqBody,
                                              @RequestHeader(value = "X-Party", required = false) String xParty) {
        String party = (xParty != null && !xParty.isBlank()) ? xParty : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
        if (party.isBlank()) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Provide X-Party or APP_PROVIDER_PARTY"));
        String poolId = String.valueOf(reqBody.getOrDefault("poolId", "ETH-USDC-P0"));
        boolean bootstrap = Boolean.parseBoolean(String.valueOf(reqBody.getOrDefault("bootstrapTokens", "true")));

        Map<String, Object> out = new HashMap<>();
        List<String> steps = new ArrayList<>();
        try {
            if (bootstrap) {
                txPacer.awaitSlot(2500);
                steps.add("mint ETH");
                Token eth = new Token(new Party(party), new Party(party), "ETH", new BigDecimal("1.0"));
                ContractId<Token> ethCid = ledgerApi.createAndGetCid(
                        eth,
                        List.of(party),
                        List.of(),
                    UUID.randomUUID().toString(),
                    clearportx_amm_production_gv.Identifiers.Token_Token__Token
                ).join();
                out.put("ethTokenCid", ethCid.getContractId);

                steps.add("mint USDC");
                Token usdc = new Token(new Party(party), new Party(party), "USDC", new BigDecimal("200000.0"));
                ContractId<Token> usdcCid = ledgerApi.createAndGetCid(
                        usdc,
                        List.of(party),
                        List.of(),
                    UUID.randomUUID().toString(),
                    clearportx_amm_production_gv.Identifiers.Token_Token__Token
                ).join();
                out.put("usdcTokenCid", usdcCid.getContractId);
            }

            steps.add("create pool");
            txPacer.awaitSlot(2500);
            Pool pool = new Pool(
                    new Party(party), new Party(party), new Party(party), new Party(party), new Party(party),
                    "ETH", "USDC", 30L, poolId,
                    new daml_stdlib_da_time_types.da.time.types.RelTime(86400000000L),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    Optional.empty(), Optional.empty(), new Party(party), 10000L, 5000L, java.util.List.of()
            );
            ContractId<Pool> poolCid = ledgerApi.createAndGetCid(
                    pool,
                    List.of(party),
                    List.of(),
                    UUID.randomUUID().toString(),
                    clearportx_amm_production_gv.Identifiers.AMM_Pool__Pool
            ).join();
            out.put("poolCid", poolCid.getContractId);
            // Update directory
            directory.update(poolId, poolCid.getContractId, party);

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

    @PostMapping(value = "/add-liquidity-by-cid", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addLiquidityByCid(@RequestBody Map<String, Object> body,
                                               @RequestHeader(value = "X-Party") String xParty) {
        String poolCidStr = String.valueOf(body.getOrDefault("poolCid", ""));
        BigDecimal amountA = new BigDecimal(String.valueOf(body.getOrDefault("amountA", "0")));
        BigDecimal amountB = new BigDecimal(String.valueOf(body.getOrDefault("amountB", "0")));
        BigDecimal minLP = new BigDecimal(String.valueOf(body.getOrDefault("minLPTokens", "0")));
        try {
            List<LedgerApi.ActiveContract<Pool>> pools = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
            var maybe = pools.stream().filter(p -> p.contractId.getContractId.equals(poolCidStr)).findFirst();
            if (maybe.isEmpty()) return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool not visible for party"));
            var pool = maybe.get();
            var pay = pool.payload;

            // Pre-flight: if canonical tokens referenced by this Pool are not alive for poolParty,
            // switch to a visible Pool instance for the same poolId whose canonicals are alive.
            try {
                String poolPartyStr = pay.getPoolParty.getParty;
                List<LedgerApi.ActiveContract<Token>> poolPartyToks = ledgerApi.getActiveContractsForParty(Token.class, poolPartyStr).join();
                java.util.Set<String> alive = new java.util.HashSet<>();
                for (var t : poolPartyToks) alive.add(t.contractId.getContractId);
                boolean aAlive = pay.getTokenACid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
                boolean bAlive = pay.getTokenBCid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
                if (!(aAlive && bAlive)) {
                    List<LedgerApi.ActiveContract<Pool>> visPools = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
                    for (var cand : visPools) {
                        var pp = cand.payload;
                        if (!pp.getPoolId.equals(pay.getPoolId)) continue;
                        String ppParty = pp.getPoolParty.getParty;
                        List<LedgerApi.ActiveContract<Token>> toks = ledgerApi.getActiveContractsForParty(Token.class, ppParty).join();
                        java.util.Set<String> alive2 = new java.util.HashSet<>();
                        for (var t : toks) alive2.add(t.contractId.getContractId);
                        boolean okA = pp.getTokenACid.map(cid -> alive2.contains(cid.getContractId)).orElse(false);
                        boolean okB = pp.getTokenBCid.map(cid -> alive2.contains(cid.getContractId)).orElse(false);
                        if (okA && okB) {
                            pool = cand;
                            pay = pp;
                            break;
                        }
                    }
                }
            } catch (Exception ignore) {
                // best-effort freshness
            }

            List<LedgerApi.ActiveContract<Token>> tokens = ledgerApi.getActiveContractsForParty(Token.class, xParty).join();
            final String symA = pay.getSymbolA;
            final String symB = pay.getSymbolB;
            var tokA = tokens.stream().filter(t -> t.payload.getSymbol.equals(symA) && t.payload.getOwner.getParty.equals(xParty))
                    .max(Comparator.comparing(t -> t.payload.getAmount)).orElse(null);
            var tokB = tokens.stream().filter(t -> t.payload.getSymbol.equals(symB) && t.payload.getOwner.getParty.equals(xParty))
                    .max(Comparator.comparing(t -> t.payload.getAmount)).orElse(null);
            // Dev fallback: mint fresh tokens if missing or insufficient
            if (tokA == null || (tokA.payload.getAmount.compareTo(amountA) < 0)) {
                Token freshA = new Token(new Party(xParty), new Party(xParty), pay.getSymbolA, amountA);
                tokA = new LedgerApi.ActiveContract<>(
                        ledgerApi.createAndGetCid(freshA, List.of(xParty), List.of(), UUID.randomUUID().toString(), clearportx_amm_production_gv.Identifiers.Token_Token__Token).join(),
                        freshA
                );
            }
            if (tokB == null || (tokB.payload.getAmount.compareTo(amountB) < 0)) {
                Token freshB = new Token(new Party(xParty), new Party(xParty), pay.getSymbolB, amountB);
                tokB = new LedgerApi.ActiveContract<>(
                        ledgerApi.createAndGetCid(freshB, List.of(xParty), List.of(), UUID.randomUUID().toString(), clearportx_amm_production_gv.Identifiers.Token_Token__Token).join(),
                        freshB
                );
            }

            var deadline = Instant.now().plusSeconds(600);
            Pool.AddLiquidity choice = new Pool.AddLiquidity(
                    new Party(xParty),
                    new ContractId<>(tokA.contractId.getContractId),
                    new ContractId<>(tokB.contractId.getContractId),
                    amountA,
                    amountB,
                    minLP,
                    deadline
            );

            String cmdIdAdd = UUID.randomUUID().toString();
            txPacer.awaitSlot(2500);
            // Authorize with all controllers for AddLiquidity: provider (xParty), poolParty, lpIssuer
            var actAs = List.of(
                    xParty,
                    pay.getPoolParty.getParty,
                    pay.getLpIssuer.getParty,
                    pay.getIssuerA.getParty,
                    pay.getIssuerB.getParty
            );
            var readAs = List.of(
                    xParty,
                    pay.getPoolParty.getParty,
                    pay.getLpIssuer.getParty,
                    pay.getIssuerA.getParty,
                    pay.getIssuerB.getParty
            );
            var txTree = ledgerApi.exerciseAndGetTransactionTree(
                    pool.contractId,
                    choice,
                    cmdIdAdd,
                    actAs,
                    readAs
            ).join();

            // Attempt to resolve the freshest pool by verifying canonical tokens exist (avoid stale pools)
            // Retry ACS briefly to absorb archive+recreate lag
            List<LedgerApi.ActiveContract<Pool>> poolsNow = null;
            final String targetPoolId = pay.getPoolId;
            for (int i = 0; i < 8; i++) {
                poolsNow = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
                if (poolsNow.stream().anyMatch(pac -> pac.payload.getPoolId.equals(targetPoolId))) break;
                sleepQuiet(300);
            }
            // Prefer created AMM.Pool:Pool CID from tx tree if present
            String createdPoolCid = extractCreatedPoolCid(txTree);
            if (createdPoolCid != null) {
                // Wait up to ~3s for ACS to show the new pool for this party
                boolean visible = waitForPoolCidVisible(xParty, createdPoolCid, 10, 300);
                if (!visible) {
                    return ResponseEntity.status(409).body(Map.of(
                            "success", false,
                            "error", "POOL_NOT_VISIBLE_FOR_PARTY_REFRESH",
                            "retry_after_ms", 300,
                            "newPoolCid", createdPoolCid,
                            "commandId", cmdIdAdd
                    ));
                }
                // Read-your-write barrier: wait for ledger end past this tx
                long targetOffset = txTree.getOffset();
                for (int i = 0; i < 20; i++) {
                    long end = ledgerApi.getLedgerEndOffset().join();
                    if (end >= targetOffset) break;
                    sleepQuiet(200);
                }
            }
            List<LedgerApi.ActiveContract<Token>> toksForPool = ledgerApi.getActiveContractsForParty(Token.class, pay.getPoolParty.getParty).join();
            java.util.function.Predicate<LedgerApi.ActiveContract<Pool>> hasAliveCanonicals = pac -> {
                var pp = pac.payload;
                boolean hasA = pp.getTokenACid
                        .map(cid -> toksForPool.stream().anyMatch(t -> t.contractId.getContractId.equals(cid.getContractId)))
                        .orElse(false);
                boolean hasB = pp.getTokenBCid
                        .map(cid -> toksForPool.stream().anyMatch(t -> t.contractId.getContractId.equals(cid.getContractId)))
                        .orElse(false);
                return hasA && hasB;
            };
            var freshest = poolsNow.stream()
                    .filter(pac -> pac.payload.getPoolId.equals(targetPoolId))
                    .filter(hasAliveCanonicals)
                    .findFirst()
                    .orElse(pool);
            directory.update(targetPoolId, (createdPoolCid != null ? createdPoolCid : freshest.contractId.getContractId), pay.getPoolParty.getParty);
            if (!freshest.contractId.getContractId.equals(pool.contractId.getContractId)) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "poolCid", pool.contractId.getContractId,
                        "newPoolCid", createdPoolCid != null ? createdPoolCid : freshest.contractId.getContractId,
                        "commandId", cmdIdAdd
                ));
            }
            return ResponseEntity.ok(Map.of("success", true, "poolCid", pool.contractId.getContractId, "commandId", cmdIdAdd));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping(value = "/swap-by-cid", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> swapByCid(@RequestBody Map<String, Object> body,
                                       @RequestHeader(value = "X-Party") String xParty) {
        String poolCidStr = String.valueOf(body.getOrDefault("poolCid", ""));
        String poolIdOpt = String.valueOf(body.getOrDefault("poolId", ""));
        String inputSymbol = String.valueOf(body.getOrDefault("inputSymbol", ""));
        String outputSymbol = String.valueOf(body.getOrDefault("outputSymbol", ""));
        BigDecimal amountIn = new BigDecimal(String.valueOf(body.getOrDefault("amountIn", "0")));
        BigDecimal minOutput = new BigDecimal(String.valueOf(body.getOrDefault("minOutput", "1")));
        try {
            // Resolve correct poolParty by fetching the pool payload by the provided poolCid
            String poolPartyStr;
            final String[] canonRef = new String[2];
            {
                var poolsForParty = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
                var match = poolsForParty.stream().filter(p -> p.contractId.getContractId.equals(poolCidStr)).findFirst();
                if (match.isPresent()) {
                    var payload = match.get().payload;
                    poolPartyStr = payload.getPoolParty.getParty;
                    canonRef[0] = payload.getTokenACid.map(cid -> cid.getContractId).orElse(null);
                    canonRef[1] = payload.getTokenBCid.map(cid -> cid.getContractId).orElse(null);
                } else {
                    // Fallback: resolve via operator (APP_PROVIDER_PARTY) to avoid trader visibility gating
                    String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", xParty);
                    var poolsForOp = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
                    var matchOp = poolsForOp.stream().filter(p -> p.contractId.getContractId.equals(poolCidStr)).findFirst();
                    if (matchOp.isEmpty()) {
                        return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool not found for operator or trader"));
                    }
                    var payload = matchOp.get().payload;
                    poolPartyStr = payload.getPoolParty.getParty;
                    canonRef[0] = payload.getTokenACid.map(cid -> cid.getContractId).orElse(null);
                    canonRef[1] = payload.getTokenBCid.map(cid -> cid.getContractId).orElse(null);
                }
            }

            var tokens = ledgerApi.getActiveContractsForParty(Token.class, xParty).join();
            var inputTok = tokens.stream()
                    .filter(t -> t.payload.getSymbol.equals(inputSymbol) && t.payload.getOwner.getParty.equals(xParty))
                    .filter(t -> { // don't reuse pool canonical tokens as trader input
                        String cid = t.contractId.getContractId;
                        String ca = canonRef[0];
                        String cb = canonRef[1];
                        return (ca == null || !ca.equals(cid)) && (cb == null || !cb.equals(cid));
                    })
                    .max(Comparator.comparing(t -> t.payload.getAmount)).orElse(null);
            if (inputTok == null) return ResponseEntity.status(409).body(Map.of("success", false, "error", "INPUT_TOKEN_STALE_OR_NOT_VISIBLE: refresh & retry"));
            if (inputTok.payload.getAmount.compareTo(amountIn) < 0) return ResponseEntity.status(422).body(Map.of("success", false, "error", "Insufficient input"));

            // Emit a pre-swap trace as a separate transaction for diagnostics
            try {
                txPacer.awaitSlot(800);
                Pool.EmitTrace emit = new Pool.EmitTrace(
                        "PRE_SWAP",
                        new Party(xParty),
                        inputTok.contractId,
                        inputSymbol,
                        outputSymbol,
                        amountIn,
                        minOutput.compareTo(BigDecimal.ZERO) > 0 ? minOutput : BigDecimal.ONE
                );
                ledgerApi.exerciseAndGetResultWithParties(
                        new ContractId<Pool>(poolCidStr),
                        emit,
                        UUID.randomUUID().toString(),
                        List.of(poolPartyStr),                 // controller is poolParty
                        List.of(poolPartyStr, xParty)          // visible to pool and trader
                ).join();
            } catch (Exception ignoreTrace) {
                // Best-effort; do not block the swap
            }

            var deadline = Instant.now().plusSeconds(600);
            Pool.AtomicSwap choice = new Pool.AtomicSwap(
                    new Party(xParty),
                    inputTok.contractId,
                    inputSymbol,
                    amountIn,
                    outputSymbol,
                    minOutput.compareTo(BigDecimal.ZERO) > 0 ? minOutput : BigDecimal.ONE,
                    5000L,
                    deadline
            );

            // Execute with pacing; do not depend on ACS visibility for immediate follow-up
            String cmdId = UUID.randomUUID().toString();
            txPacer.awaitSlot(3500);
            try {
                ledgerApi.exerciseAndGetResultWithParties(
                        new ContractId<Pool>(poolCidStr),
                        choice,
                        cmdId,
                        List.of(xParty, poolPartyStr),
                        List.of(poolPartyStr, xParty)
                ).join();
            } catch (Exception first) {
                String msg = exceptionText(first);
                // Prefer CONTRACT_NOT_ACTIVE handling over generic NOT_FOUND
                if (msg.contains("CONTRACT_NOT_ACTIVE")) {
                    // Attempt one automatic recovery: reselect a fresh input token (or mint one) and retry once
                    try {
                        txPacer.awaitSlot(2200);
                        var freshTokens = ledgerApi.getActiveContractsForParty(Token.class, xParty).join();
                        var freshInputOpt = freshTokens.stream()
                                .filter(t -> t.payload.getSymbol.equals(inputSymbol) && t.payload.getOwner.getParty.equals(xParty))
                                .filter(t -> t.payload.getAmount.compareTo(amountIn) >= 0)
                                .max(Comparator.comparing(t -> t.payload.getAmount));
                        LedgerApi.ActiveContract<Token> freshInput = freshInputOpt.orElse(null);
                        if (freshInput == null) {
                            // Mint a small fresh input token and retry
                            Token freshMint = new Token(new Party(xParty), new Party(xParty), inputSymbol,
                                    amountIn.max(new BigDecimal("1.0")));
                            ContractId<Token> mintedCid = ledgerApi.createAndGetCid(
                                    freshMint,
                                    List.of(xParty),
                                    List.of(),
                                    UUID.randomUUID().toString(),
                                    clearportx_amm_production_gv.Identifiers.Token_Token__Token
                            ).join();
                            freshInput = new LedgerApi.ActiveContract<>(mintedCid, freshMint);
                            txPacer.awaitSlot(2200);
                        }
                        // Retry once with the freshly selected/minted token
                        Pool.AtomicSwap retryChoice = new Pool.AtomicSwap(
                                new Party(xParty),
                                freshInput.contractId,
                                inputSymbol,
                                amountIn,
                                outputSymbol,
                                minOutput.compareTo(BigDecimal.ZERO) > 0 ? minOutput : BigDecimal.ONE,
                                5000L,
                                Instant.now().plusSeconds(600)
                        );
                        ledgerApi.exerciseAndGetResultWithParties(
                                new ContractId<Pool>(poolCidStr),
                                retryChoice,
                                UUID.randomUUID().toString(),
                                List.of(xParty, poolPartyStr),
                                List.of(poolPartyStr, xParty)
                        ).join();
                        return ResponseEntity.ok(Map.of("success", true, "recovered", true));
                    } catch (Exception second) {
                        return ResponseEntity.status(409).body(Map.of(
                                "success", false,
                                "error", "CONTRACT_NOT_ACTIVE",
                                "diagnostics", Map.of(
                                        "firstMessage", msg,
                                        "secondMessage", exceptionText(second),
                                        "poolCid", poolCidStr,
                                        "poolId", poolIdOpt,
                                        "actAs", List.of(xParty, poolPartyStr),
                                        "readAs", List.of(poolPartyStr, xParty),
                                        "inputSymbol", inputSymbol,
                                        "outputSymbol", outputSymbol,
                                        "amountIn", amountIn.toPlainString(),
                                        "minOutput", minOutput.toPlainString()
                                )
                        ));
                    }
                } else if (msg.contains("NOT_FOUND") || msg.contains("CONTRACT_NOT_FOUND")) {
                    return ResponseEntity.status(409).body(Map.of(
                            "success", false,
                            "error", "STALE_OR_NOT_VISIBLE_REFRESH",
                            "retry_after_ms", 2200
                    ));
                }
                // Map DAML slippage assertion to 422 (client error)
                if (msg.contains("Min output not met")) {
                    return ResponseEntity.status(422).body(Map.of(
                            "success", false,
                            "error", "SLIPPAGE_MIN_OUTPUT_NOT_MET",
                            "message", "Min output not met (slippage)"
                    ));
                }
                if (msg.contains("CONTRACT_NOT_ACTIVE")) {
                    // Diagnostics: capture which CID went inactive and surrounding context
                    String inactiveCid = extractInactiveCid(msg);
                    Map<String, Object> diag = new LinkedHashMap<>();
                    diag.put("stage", "first-attempt");
                    diag.put("errorMessage", msg);
                    diag.put("inactiveCid", inactiveCid);
                    diag.put("poolCid", poolCidStr);
                    diag.put("poolId", poolIdOpt);
                    diag.put("actAs", List.of(xParty, poolPartyStr));
                    diag.put("readAs", List.of(poolPartyStr, xParty));
                    diag.put("inputTokCid", inputTok.contractId.getContractId);
                    diag.put("inputSymbol", inputSymbol);
                    diag.put("outputSymbol", outputSymbol);
                    diag.put("amountIn", amountIn.toPlainString());
                    diag.put("minOutput", minOutput.toPlainString());
                    diag.put("canonA", canonRef[0]);
                    diag.put("canonB", canonRef[1]);
                    return ResponseEntity.status(409).body(Map.of(
                            "success", false,
                            "error", "CONTRACT_NOT_ACTIVE",
                            "diagnostics", diag
                    ));
                } else if (msg.contains("Price impact too high")) {
                    return ResponseEntity.status(422).body(Map.of("success", false, "error", "PRICE_IMPACT_TOO_HIGH"));
                } else if (msg.contains("UNHANDLED_EXCEPTION/DA.Exception.AssertionFailed:AssertionFailed: Input reserve must be positive")) {
                    return ResponseEntity.status(409).body(Map.of("success", false, "error", "POOL_EMPTY: add liquidity first"));
                } else {
                    throw first;
                }
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/directory")
    public ResponseEntity<?> directorySnapshot() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "lastUpdated", directory.lastUpdated().toString(),
                "mapping", directory.snapshot()
        ));
    }

    @GetMapping("/pool/fetch-cid")
    public ResponseEntity<?> fetchPoolByCid(@RequestParam("cid") String poolCid,
                                            @RequestHeader(value = "X-Party") String xParty) {
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
            boolean visible = pools.stream().anyMatch(p -> p.contractId.getContractId.equals(poolCid));
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "party", xParty,
                    "poolCid", poolCid,
                    "visible", visible
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping(value = "/pool/grant-visibility", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> grantVisibility(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Party") String xParty) {
        // Guard under debug profile (simple env check)
        String profiles = System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "");
        if (!profiles.contains("debug")) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Endpoint disabled (requires debug profile)"));
        }
        String poolCidStr = String.valueOf(body.getOrDefault("poolCid", ""));
        String newObserver = String.valueOf(body.getOrDefault("newObserver", ""));
        String poolIdOpt = String.valueOf(body.getOrDefault("poolId", ""));
        if (poolCidStr.isBlank() || newObserver.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Missing poolCid or newObserver"));
        }
        try {
            txPacer.awaitSlot(2500);
            String cmdId = UUID.randomUUID().toString();
            // Resolve poolOperator without requiring trader visibility.
            // In P0/DevNet, poolOperator == APP_PROVIDER_PARTY.
            String poolOperator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", xParty);
            Pool.GrantVisibility choice = new Pool.GrantVisibility(new com.digitalasset.transcode.java.Party(newObserver));
            ContractId<Pool> newCid;
            try {
                newCid = ledgerApi.exerciseAndGetResultWithParties(
                        new ContractId<>(poolCidStr),
                        choice,
                        cmdId,
                        List.of(poolOperator),
                        List.of(poolOperator, newObserver)
                ).join();
            } catch (Exception first) {
                // Retry once by resolving freshest pool for this poolId (if provided)
                if (poolIdOpt != null && !poolIdOpt.isBlank()) {
                    var poolsOp = ledgerApi.getActiveContractsForParty(Pool.class, poolOperator).join();
                    // Prefer candidates with alive canonicals for poolParty
                    java.util.List<LedgerApi.ActiveContract<Pool>> candidates = new java.util.ArrayList<>();
                    for (var p : poolsOp) {
                        if (!p.payload.getPoolId.equals(poolIdOpt)) continue;
                        candidates.add(p);
                    }
                    candidates.sort((a, b) -> b.payload.getReserveA.multiply(b.payload.getReserveB)
                            .compareTo(a.payload.getReserveA.multiply(a.payload.getReserveB)));
                    ContractId<Pool> granted = null;
                    for (var cand : candidates) {
                        var pay = cand.payload;
                        String pp = pay.getPoolParty.getParty;
                        var toks = ledgerApi.getActiveContractsForParty(Token.class, pp).join();
                        java.util.Set<String> alive = new java.util.HashSet<>();
                        for (var t : toks) alive.add(t.contractId.getContractId);
                        boolean aAlive = pay.getTokenACid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
                        boolean bAlive = pay.getTokenBCid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
                        if (!(aAlive && bAlive)) continue;
                        granted = ledgerApi.exerciseAndGetResultWithParties(
                                cand.contractId,
                                choice,
                                UUID.randomUUID().toString(),
                                List.of(poolOperator),
                                List.of(poolOperator, newObserver)
                        ).join();
                        break;
                    }
                    if (granted == null) throw first;
                    newCid = granted;
                } else {
                    throw first;
                }
            }
            if (poolIdOpt != null && !poolIdOpt.isBlank()) {
                directory.update(poolIdOpt, newCid.getContractId, xParty);
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "newPoolCid", newCid.getContractId,
                    "commandId", cmdId
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/pool-by-cid")
    public ResponseEntity<?> poolByCid(@RequestParam("cid") String poolCid,
                                       @RequestHeader(value = "X-Party") String xParty) {
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
            var maybe = pools.stream().filter(p -> p.contractId.getContractId.equals(poolCid)).findFirst();
            if (maybe.isEmpty()) return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool not visible for party"));
            var pay = maybe.get().payload;
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "poolCid", poolCid,
                    "poolId", pay.getPoolId,
                    "symbolA", pay.getSymbolA,
                    "symbolB", pay.getSymbolB,
                    "reserveA", pay.getReserveA.toPlainString(),
                    "reserveB", pay.getReserveB.toPlainString(),
                    "tokenACid", pay.getTokenACid.map(cid -> cid.getContractId).orElse(null),
                    "tokenBCid", pay.getTokenBCid.map(cid -> cid.getContractId).orElse(null)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static String extractInactiveCid(String message) {
        if (message == null) return null;
        String key1 = "inactive contract ";
        int i = message.indexOf(key1);
        if (i >= 0) {
            int start = i + key1.length();
            int end = message.indexOf(' ', start);
            if (end < 0) end = message.indexOf(')', start);
            if (end < 0) end = message.length();
            return message.substring(start, end);
        }
        String key2 = "Contract could not be found with id ";
        int j = message.indexOf(key2);
        if (j >= 0) {
            int start = j + key2.length();
            int end = message.indexOf(' ', start);
            if (end < 0) end = message.length();
            return message.substring(start, end);
        }
        return null;
    }

    private static String exceptionText(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            String part = cur.getMessage();
            if (part == null || part.isBlank()) {
                part = cur.toString();
            }
            sb.append(part);
            if (cur.getCause() != null) sb.append(" | ");
            cur = cur.getCause();
        }
        return sb.toString();
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static String extractCreatedPoolCid(com.daml.ledger.api.v2.TransactionOuterClass.TransactionTree txTree) {
        try {
            java.util.Map<Integer, com.daml.ledger.api.v2.TransactionOuterClass.TreeEvent> events = txTree.getEventsByIdMap();
            for (com.daml.ledger.api.v2.TransactionOuterClass.TreeEvent ev : events.values()) {
                if (ev.hasCreated()) {
                    var created = ev.getCreated();
                    var tmpl = created.getTemplateId();
                    // Match by module/entity to avoid package hash issues
                    if ("AMM.Pool".equals(tmpl.getModuleName()) && "Pool".equals(tmpl.getEntityName())) {
                        return created.getContractId();
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private boolean waitForPoolCidVisible(String party, String poolCid, int attempts, long delayMs) {
        for (int i = 0; i < attempts; i++) {
            List<LedgerApi.ActiveContract<Pool>> acs = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
            boolean found = acs.stream().anyMatch(pac -> pac.contractId.getContractId.equals(poolCid));
            if (found) return true;
            sleepQuiet(delayMs);
        }
        return false;
    }
}


