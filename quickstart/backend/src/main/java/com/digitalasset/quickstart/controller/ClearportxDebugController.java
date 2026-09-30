package com.digitalasset.quickstart.controller;

import helvet_amm.amm.pool.Pool;
import helvet_amm.token.token.Token;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.AddLiquidityResponse;
import com.digitalasset.quickstart.dto.DebugAddLiquidityRequest;
import com.digitalasset.quickstart.service.AddLiquidityCommandFactory;
import com.digitalasset.quickstart.service.AddLiquidityService;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.util.Futures;
import com.digitalasset.quickstart.security.JwtAuthService;
import com.digitalasset.quickstart.security.JwtAuthService.AuthenticatedUser;
import com.digitalasset.transcode.java.Party;
import com.digitalasset.transcode.java.ContractId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import com.digitalasset.quickstart.service.PoolDirectoryService;
import com.digitalasset.quickstart.service.TransactionHistoryService;
import com.digitalasset.quickstart.service.TxPacer;
import com.digitalasset.quickstart.service.ResolveGrantService;

@Profile("local")
@RestController
@RequestMapping(value = "/api/clearportx/debug", produces = MediaType.APPLICATION_JSON_VALUE)
public class ClearportxDebugController {

    private static final BigDecimal MIN_LP_TOKENS = new BigDecimal("0.0000000001");
    private static final Logger logger = LoggerFactory.getLogger(ClearportxDebugController.class);

    @Autowired
    private LedgerApi ledgerApi;
    @Autowired
    private PoolDirectoryService directory;
    @Autowired
    private TxPacer txPacer;
    @Autowired
    private TransactionHistoryService transactionHistoryService;
    @Autowired
    private ResolveGrantService resolveGrantService;
    @Autowired
    private AddLiquidityService addLiquidityService;
    @Autowired
    private JwtAuthService jwtAuthService;

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
        String canonicalA = null;
        String canonicalB = null;
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
            final String msg = String.valueOf(e.getMessage());
            // Structured error mapping for easier client handling
            if (msg.contains("CONTRACT_NOT_FOUND") || msg.contains("CONTRACT_NOT_ACTIVE")) {
                return ResponseEntity.status(409).body(Map.of(
                        "success", false,
                        "error", "STALE_OR_NOT_VISIBLE_REFRESH",
                        "message", "Pool or tokens changed; refresh and retry",
                        "details", msg,
                        "retry_after_ms", 350
                ));
            }
            if (msg.contains("Insufficient") || msg.contains("insufficient")) {
                return ResponseEntity.status(422).body(Map.of(
                        "success", false,
                        "error", "INSUFFICIENT_BALANCE",
                        "message", msg
                ));
            }
            if (msg.contains("Pool not visible for party")) {
                return ResponseEntity.status(409).body(Map.of(
                        "success", false,
                        "error", "POOL_NOT_VISIBLE_FOR_PARTY_REFRESH",
                        "message", "Pool not visible for party; refresh ACS",
                        "details", msg,
                        "retry_after_ms", 300
                ));
            }
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "INTERNAL", "message", msg));
        }
    }

    @PostMapping(value = "/create-pool-single", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createPoolSingle(@RequestBody Map<String, Object> reqBody,
                                              @RequestHeader(value = "X-Party", required = false) String xParty) {
        // Disabled to avoid constructor/signature mismatches across DAR variants.
        // Use PoolCreationController.createPoolDirect or external scripts instead.
        return ResponseEntity.status(501).body(Map.of(
                "success", false,
                "error", "create-pool-single disabled",
                "message", "Use /api/debug/create-pool-direct or existing pool resolution"
        ));
    }

    @PostMapping(value = "/add-liquidity-by-cid", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addLiquidityByCid(@RequestBody DebugAddLiquidityRequest body,
                                               @RequestHeader(value = "X-Party", required = false) String xParty,
                                               @RequestHeader(value = "Authorization", required = false) String authorization) {
        Result<String, DomainError> providerResult = resolveProviderParty(xParty, authorization);
        if (providerResult.isErr()) {
            return mapAddLiquidityError(providerResult.getErrorUnsafe());
        }
        Result<AddLiquidityService.AddLiquidityCommand, DomainError> commandResult = buildAddLiquidityCommand(providerResult.getValueUnsafe(), body);
        if (commandResult.isErr()) {
            return mapAddLiquidityError(commandResult.getErrorUnsafe());
        }
        AddLiquidityService.AddLiquidityCommand command = commandResult.getValueUnsafe();
        Result<AddLiquidityResponse, DomainError> result = addLiquidityService.addLiquidity(command).join();
        if (result.isOk()) {
            return ResponseEntity.ok(toAddLiquiditySuccess(command, result.getValueUnsafe()));
        }
        return mapAddLiquidityError(result.getErrorUnsafe());
    }


    @GetMapping(value = "/wallet/raw-tokens", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rawWalletTokens(@RequestParam("party") String party) {
        try {
            List<LedgerApi.ActiveContract<Token>> toks = ledgerApi.getActiveContractsForParty(Token.class, party).join();
            List<Map<String, String>> payload = new ArrayList<>();
            for (var t : toks) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("cid", t.contractId.getContractId);
                entry.put("symbol", t.payload.getSymbol);
                entry.put("amount", t.payload.getAmount.toPlainString());
                entry.put("owner", t.payload.getOwner.getParty);
                payload.add(entry);
            }
            return ResponseEntity.ok(payload);
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
        return ResponseEntity.status(501).body(Map.of("success", false, "error", "GrantVisibility not available in current bindings"));
    }

    @GetMapping("/pool-by-cid")
    public ResponseEntity<?> poolByCid(@RequestParam("cid") String poolCid,
                                       @RequestHeader(value = "X-Party") String xParty) {
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
            var maybe = pools.stream().filter(p -> p.contractId.getContractId.equals(poolCid)).findFirst();
            if (maybe.isEmpty()) return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool not visible for party"));
            var pay = maybe.get().payload;
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("success", true);
            payload.put("poolCid", poolCid);
            payload.put("poolId", pay.getPoolId);
            payload.put("symbolA", pay.getSymbolA);
            payload.put("symbolB", pay.getSymbolB);
            payload.put("reserveA", pay.getReserveA.toPlainString());
            payload.put("reserveB", pay.getReserveB.toPlainString());
            payload.put("tokenACid", pay.getTokenACid.map(cid -> cid.getContractId).orElse(null));
            payload.put("tokenBCid", pay.getTokenBCid.map(cid -> cid.getContractId).orElse(null));
            payload.put("maxInBps", pay.getMaxInBps);
            payload.put("maxOutBps", pay.getMaxOutBps);
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/pools-for-party/{partyId}")
    public ResponseEntity<?> poolsForParty(@PathVariable("partyId") String partyId) {
        if (partyId == null || partyId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "partyId is required"
            ));
        }
        try {
            List<LedgerApi.ActiveContract<Pool>> pools =
                    ledgerApi.getActiveContractsForParty(Pool.class, partyId).join();
            List<Map<String, Object>> payload = new ArrayList<>(pools.size());
            for (var pac : pools) {
                Pool pool = pac.payload;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("poolId", pool.getPoolId);
                entry.put("contractId", pac.contractId.getContractId);
                entry.put("poolParty", pool.getPoolParty.getParty);
                entry.put("lpIssuer", pool.getLpIssuer.getParty);
                payload.add(entry);
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "partyId", partyId,
                    "count", payload.size(),
                    "pools", payload
            ));
        } catch (Exception e) {
            logger.error("Failed to load pools for party {}", partyId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/token-by-cid")
    public ResponseEntity<?> tokenByCid(@RequestParam("cid") String tokenCid,
                                        @RequestHeader(value = "X-Party") String xParty) {
        try {
            var toks = ledgerApi.getActiveContractsForParty(Token.class, xParty).join();
            var maybe = toks.stream().filter(t -> t.contractId.getContractId.equals(tokenCid)).findFirst();
            if (maybe.isEmpty()) {
                // Fallback via operator view
                String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", xParty);
                var toksOp = ledgerApi.getActiveContractsForParty(Token.class, operator).join();
                maybe = toksOp.stream().filter(t -> t.contractId.getContractId.equals(tokenCid)).findFirst();
                if (maybe.isEmpty()) {
                    return ResponseEntity.status(404).body(Map.of("success", false, "error", "Token not visible for party"));
                }
            }
            var t = maybe.get().payload;
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "contractId", tokenCid,
                    "symbol", t.getSymbol,
                    "amount", t.getAmount.toPlainString(),
                    "owner", t.getOwner.getParty
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping(value = "/create-pool-gv", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createPoolGv(@RequestBody Map<String, Object> req,
                                          @RequestHeader(value = "X-Party", required = false) String xParty) {
        try {
            String operator = String.valueOf(req.getOrDefault("operatorParty", xParty));
            String poolParty = String.valueOf(req.getOrDefault("poolParty", xParty));
            String ethIssuer = String.valueOf(req.getOrDefault("ethIssuer", xParty));
            String usdcIssuer = String.valueOf(req.getOrDefault("usdcIssuer", xParty));
            String lpIssuer = String.valueOf(req.getOrDefault("lpIssuer", xParty));
            String feeReceiver = String.valueOf(req.getOrDefault("feeReceiver", xParty));
            String poolId = String.valueOf(req.getOrDefault("poolId", "ETH-USDC"));
            boolean bootstrap = Boolean.parseBoolean(String.valueOf(req.getOrDefault("bootstrapTokens", "true")));

            if (operator.isBlank() || poolParty.isBlank() || ethIssuer.isBlank() || usdcIssuer.isBlank() || lpIssuer.isBlank() || feeReceiver.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Missing parties"));
            }

            // Optionally mint canonical tokens for poolParty (issuers mint to poolParty)
            if (bootstrap) {
                Token eth = new Token(new Party(ethIssuer), new Party(poolParty), "ETH", new BigDecimal("10.0"));
                Token usdc = new Token(new Party(usdcIssuer), new Party(poolParty), "USDC", new BigDecimal("25000.0"));
                txPacer.awaitSlot(800);
                Futures.retry(
                        () -> ledgerApi.createAndGetCid(
                                eth,
                                List.of(ethIssuer),
                                List.of(),
                                UUID.randomUUID().toString(),
                                helvet_amm.Identifiers.Token_Token__Token),
                        6, 600).join();
                txPacer.awaitSlot(800);
                Futures.retry(
                        () -> ledgerApi.createAndGetCid(
                                usdc,
                                List.of(usdcIssuer),
                                List.of(),
                                UUID.randomUUID().toString(),
                                helvet_amm.Identifiers.Token_Token__Token),
                        6, 600).join();
            }

            // Create Pool
            Pool pool = new Pool(
                    new Party(operator),
                    new Party(poolParty),
                    new Party(lpIssuer),
                    new Party(ethIssuer),
                    new Party(usdcIssuer),
                    "ETH", "USDC", 30L, poolId,
                    new daml_stdlib_da_time_types.da.time.types.RelTime(86400000000L),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    Optional.<ContractId<Token>>empty(), Optional.<ContractId<Token>>empty(),
                    new Party(feeReceiver), 10000L, 5000L, List.of()
                );
            txPacer.awaitSlot(1000);
            var poolCid = Futures.retry(
                    () -> ledgerApi.createAndGetCid(
                            pool,
                            List.of(operator, poolParty),
                            List.of(),
                            UUID.randomUUID().toString(),
                            helvet_amm.Identifiers.AMM_Pool__Pool),
                    6, 600).join();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "poolCid", poolCid.getContractId,
                    "poolId", poolId
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private BigDecimal resolveTokenAmount(String tokenCid, String partyHint) {
        if (tokenCid == null || tokenCid.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            var tokens = ledgerApi.getActiveContractsForParty(Token.class, partyHint).join();
            for (var token : tokens) {
                if (token.contractId.getContractId.equals(tokenCid)) {
                    return token.payload.getAmount;
                }
            }
            String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", partyHint);
            var opTokens = ledgerApi.getActiveContractsForParty(Token.class, operator).join();
            for (var token : opTokens) {
                if (token.contractId.getContractId.equals(tokenCid)) {
                    return token.payload.getAmount;
                }
            }
        } catch (Exception ignore) {
        }
        return BigDecimal.ZERO;
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

    private List<String> archivePoolsById(String poolId, String operator) {
        List<String> archived = new ArrayList<>();
        List<LedgerApi.ActiveContract<Pool>> pools = ledgerApi.getActiveContracts(Pool.class).join();
        for (var pac : pools) {
            if (poolId.equalsIgnoreCase(pac.payload.getPoolId)) {
                Pool.Archive archive = new Pool.Archive();
                txPacer.awaitSlot(600);
                ledgerApi.exerciseAndGetResultWithParties(
                        pac.contractId,
                        archive,
                        UUID.randomUUID().toString(),
                        List.of(operator),
                        List.of(operator),
                        List.of()
                ).join();
                archived.add(pac.contractId.getContractId);
            }
        }
        return archived;
    }

    private ContractId<Token> mintToken(String issuer, String owner, String symbol, BigDecimal amount) {
        Token token = new Token(new Party(issuer), new Party(owner), symbol, amount);
        txPacer.awaitSlot(600);
        return ledgerApi.createAndGetCid(
                token,
                List.of(issuer),
                Collections.emptyList(),
                UUID.randomUUID().toString(),
                helvet_amm.Identifiers.Token_Token__Token
        ).join();
    }

    private static long clampBps(Long requested, long defaultValue) {
        long value = requested != null ? requested : defaultValue;
        if (value < 1L) return 1L;
        if (value > 10000L) return 10000L;
        return value;
    }

    private static BigDecimal parseAmountOrDefault(String raw, String fallback) {
        String candidate = (raw == null || raw.isBlank()) ? fallback : raw;
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("Amount is required");
        }
        return new BigDecimal(candidate.trim(), MathContext.DECIMAL64).setScale(10, RoundingMode.HALF_UP);
    }

    private boolean isStaleContractError(String msg) {
        return msg != null && (msg.contains("CONTRACT_NOT_FOUND") || msg.contains("CONTRACT_NOT_ACTIVE"));
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // Canton 3.4.7: Changed from TransactionTree to flat Transaction
    private static String extractCreatedPoolCid(com.daml.ledger.api.v2.TransactionOuterClass.Transaction txn) {
        try {
            // Canton 3.4.7: Flat event list instead of event map
            java.util.List<com.daml.ledger.api.v2.EventOuterClass.Event> events = txn.getEventsList();
            for (com.daml.ledger.api.v2.EventOuterClass.Event ev : events) {
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

    private static LpTokenCreated extractLpTokenCreated(com.daml.ledger.api.v2.TransactionOuterClass.Transaction txn) {
        try {
            java.util.List<com.daml.ledger.api.v2.EventOuterClass.Event> events = txn.getEventsList();
            for (com.daml.ledger.api.v2.EventOuterClass.Event ev : events) {
                if (ev.hasCreated()) {
                    var created = ev.getCreated();
                    var tmpl = created.getTemplateId();
                    if ("LPToken.LPToken".equals(tmpl.getModuleName()) && "LPToken".equals(tmpl.getEntityName())) {
                        java.math.BigDecimal amount = java.math.BigDecimal.ZERO;
                        if (created.hasCreateArguments()) {
                            var fields = created.getCreateArguments().getFieldsList();
                            if (fields.size() >= 4) {
                                var amountValue = fields.get(3).getValue();
                                if (amountValue.hasNumeric()) {
                                    amount = new java.math.BigDecimal(amountValue.getNumeric());
                                }
                            }
                        }
                        return new LpTokenCreated(created.getContractId(), amount);
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private record LpTokenCreated(String cid, java.math.BigDecimal amount) { }


    private static BigDecimal estimateLpMint(BigDecimal amountA, BigDecimal amountB, BigDecimal reserveA, BigDecimal reserveB, BigDecimal totalLp) {
        if (totalLp == null || totalLp.compareTo(BigDecimal.ZERO) == 0
                || reserveA == null || reserveA.compareTo(BigDecimal.ZERO) == 0
                || reserveB == null || reserveB.compareTo(BigDecimal.ZERO) == 0) {
            double product = amountA.multiply(amountB).doubleValue();
            double sqrt = product <= 0 ? 0 : Math.sqrt(product);
            return BigDecimal.valueOf(sqrt);
        }
        BigDecimal shareA = amountA.multiply(totalLp).divide(reserveA, MathContext.DECIMAL64);
        BigDecimal shareB = amountB.multiply(totalLp).divide(reserveB, MathContext.DECIMAL64);
        return shareA.min(shareB);
    }

    private Map<String, Object> toAddLiquiditySuccess(
            final AddLiquidityService.AddLiquidityCommand command,
            final AddLiquidityResponse response
    ) {
        updateDirectoryAndHistory(command, response);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("poolId", command.poolId());
        payload.put("lpTokenCid", response.lpTokenCid());
        payload.put("newPoolCid", response.newPoolCid());
        payload.put("reserveA", response.reserveA());
        payload.put("reserveB", response.reserveB());
        payload.put("lpAmount", response.lpAmount());
        return payload;
    }

    private void updateDirectoryAndHistory(
            final AddLiquidityService.AddLiquidityCommand command,
            final AddLiquidityResponse response
    ) {
        String candidatePoolCid = response.newPoolCid();
        if (candidatePoolCid == null || candidatePoolCid.isBlank()) {
            return;
        }
        findPoolContract(candidatePoolCid, command.providerParty()).ifPresent(pool -> {
            Pool payload = pool.payload;
            directory.update(command.poolId(), pool.contractId.getContractId, payload.getPoolParty.getParty);
            BigDecimal estimatedLp = estimateLpMint(
                    command.amountA(),
                    command.amountB(),
                    payload.getReserveA,
                    payload.getReserveB,
                    payload.getTotalLPSupply
            );
            transactionHistoryService.recordAddLiquidity(
                    command.poolId(),
                    pool.contractId.getContractId,
                    payload.getSymbolA,
                    payload.getSymbolB,
                    command.amountA(),
                    command.amountB(),
                    command.minLpTokens(),
                    estimatedLp,
                    command.providerParty()
            );
        });
    }

    private Optional<LedgerApi.ActiveContract<Pool>> findPoolContract(final String poolCid, final String providerParty) {
        List<LedgerApi.ActiveContract<Pool>> providerPools = ledgerApi.getActiveContractsForParty(Pool.class, providerParty).join();
        Optional<LedgerApi.ActiveContract<Pool>> match = providerPools.stream()
                .filter(p -> p.contractId.getContractId.equals(poolCid))
                .findFirst();
        if (match.isPresent()) {
            return match;
        }
        String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", providerParty);
        List<LedgerApi.ActiveContract<Pool>> operatorPools = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
        return operatorPools.stream()
                .filter(p -> p.contractId.getContractId.equals(poolCid))
                .findFirst();
    }

    private Result<String, DomainError> resolveProviderParty(final String headerParty, final String authorizationHeader) {
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            Result<AuthenticatedUser, DomainError> authResult = jwtAuthService.authenticate(authorizationHeader);
            if (authResult.isOk()) {
                return Result.ok(authResult.getValueUnsafe().partyId());
            }
            return Result.err(authResult.getErrorUnsafe());
        }
        if (headerParty == null || headerParty.isBlank()) {
            return Result.err(new ValidationError("Provide X-Party header or Authorization token", ValidationError.Type.AUTHENTICATION));
        }
        return Result.ok(headerParty);
    }

    private Result<AddLiquidityService.AddLiquidityCommand, DomainError> buildAddLiquidityCommand(
            final String providerParty,
            final DebugAddLiquidityRequest request
    ) {
        Result<String, DomainError> poolIdResult = resolvePoolIdForRequest(request, providerParty);
        if (poolIdResult.isErr()) {
            return Result.err(poolIdResult.getErrorUnsafe());
        }
        Result<BigDecimal, DomainError> amountAResult = parseDecimal(request.getAmountA(), "amountA");
        if (amountAResult.isErr()) {
            return Result.err(amountAResult.getErrorUnsafe());
        }
        Result<BigDecimal, DomainError> amountBResult = parseDecimal(request.getAmountB(), "amountB");
        if (amountBResult.isErr()) {
            return Result.err(amountBResult.getErrorUnsafe());
        }
        Result<BigDecimal, DomainError> minLpResult = parseDecimal(request.getMinLPTokens(), "minLPTokens");
        if (minLpResult.isErr()) {
            return Result.err(minLpResult.getErrorUnsafe());
        }
        String commandId = UUID.randomUUID().toString();
        return AddLiquidityCommandFactory.from(
                providerParty,
                poolIdResult.getValueUnsafe(),
                amountAResult.getValueUnsafe(),
                amountBResult.getValueUnsafe(),
                minLpResult.getValueUnsafe(),
                commandId
        );
    }

    private Result<String, DomainError> resolvePoolIdForRequest(
            final DebugAddLiquidityRequest request,
            final String providerParty
    ) {
        if (request.getPoolId() != null && !request.getPoolId().isBlank()) {
            return Result.ok(request.getPoolId().trim());
        }
        String poolCid = request.getPoolCid();
        if (poolCid == null || poolCid.isBlank()) {
            return Result.err(new ValidationError("poolId is required", ValidationError.Type.REQUEST));
        }
        return findPoolContract(poolCid.trim(), providerParty)
                .map(pool -> Result.<String, DomainError>ok(pool.payload.getPoolId))
                .orElseGet(() -> Result.err(new ValidationError("Unable to resolve poolId from poolCid", ValidationError.Type.REQUEST)));
    }

    private Result<BigDecimal, DomainError> parseDecimal(final String raw, final String fieldName) {
        if (raw == null || raw.isBlank()) {
            return Result.err(new ValidationError(fieldName + " is required", ValidationError.Type.REQUEST));
        }
        try {
            return Result.ok(new BigDecimal(raw.trim()));
        } catch (NumberFormatException ex) {
            return Result.err(new ValidationError("Invalid " + fieldName, ValidationError.Type.REQUEST));
        }
    }

    private ResponseEntity<?> mapAddLiquidityError(final DomainError error) {
        HttpStatus status = DomainErrorStatusMapper.map(error);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", error.code());
        body.put("message", error.message());
        return ResponseEntity.status(status).body(body);
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

    /**
     * Simple E2E swap test - mints fresh trader token and executes swap
     * This endpoint tests the complete Canton 3.4.7 integration:
     * 1. Query pool via getActiveContracts (EventFormat)
     * 2. Create trader token via createAndGetCid (TransactionFormat)
     * 3. Execute AtomicSwap via exerciseAndGetTransaction (flat Transaction)
     */
    @PostMapping(value = "/e2e-swap-test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> e2eSwapTest(@RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "X-Party") String xParty) {
        String poolId = String.valueOf(body.getOrDefault("poolId", ""));
        String inputSymbol = String.valueOf(body.getOrDefault("inputSymbol", ""));
        String outputSymbol = String.valueOf(body.getOrDefault("outputSymbol", ""));
        BigDecimal amountIn = new BigDecimal(String.valueOf(body.getOrDefault("amountIn", "1.0")));
        BigDecimal minOutput = new BigDecimal(String.valueOf(body.getOrDefault("minOutput", "0.0001")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testName", "E2E Canton 3.4.7 Swap Test");
        List<String> steps = new ArrayList<>();

        try {
            // Step 1: Find pool by poolId
            steps.add("Finding pool: " + poolId);
            String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", xParty);
            List<LedgerApi.ActiveContract<Pool>> pools = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
            LedgerApi.ActiveContract<Pool> poolContract = pools.stream()
                    .filter(p -> p.payload.getPoolId.equals(poolId))
                    .max(Comparator.comparing(p -> p.payload.getReserveA.multiply(p.payload.getReserveB)))
                    .orElse(null);
            if (poolContract == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool not found: " + poolId, "steps", steps));
            }
            steps.add("✓ Found pool: " + poolContract.contractId.getContractId.substring(0, 20) + "...");
            result.put("poolCid", poolContract.contractId.getContractId);
            result.put("reservesBefore", Map.of(
                    poolContract.payload.getSymbolA, poolContract.payload.getReserveA.toPlainString(),
                    poolContract.payload.getSymbolB, poolContract.payload.getReserveB.toPlainString()
            ));

            // Step 2: Mint fresh trader token WITH CORRECT ISSUER
            // Token issuer MUST match pool's issuer for merge compatibility
            boolean inputIsA = inputSymbol.equals(poolContract.payload.getSymbolA);
            String correctIssuer = inputIsA
                    ? poolContract.payload.getIssuerA.getParty
                    : poolContract.payload.getIssuerB.getParty;
            steps.add("Minting " + amountIn + " " + inputSymbol + " (issuer: " + correctIssuer.substring(0, 20) + "...)");

            Token traderToken = new Token(new Party(correctIssuer), new Party(xParty), inputSymbol, amountIn);
            ContractId<Token> traderTokenCid = ledgerApi.createAndGetCid(
                    traderToken,
                    List.of(correctIssuer),  // actAs issuer, not trader
                    List.of(),
                    UUID.randomUUID().toString(),
                    helvet_amm.Identifiers.Token_Token__Token
            ).join();
            steps.add("✓ Minted token: " + traderTokenCid.getContractId.substring(0, 20) + "...");
            result.put("traderTokenCid", traderTokenCid.getContractId);
            result.put("tokenIssuer", correctIssuer);
            txPacer.awaitSlot(2000);

            // Step 3: Execute AtomicSwap
            steps.add("Executing AtomicSwap: " + amountIn + " " + inputSymbol + " → " + outputSymbol);
            String poolPartyStr = poolContract.payload.getPoolParty.getParty;
            Pool.AtomicSwap swapChoice = new Pool.AtomicSwap(
                    new Party(xParty),
                    traderTokenCid,
                    inputSymbol,
                    amountIn,
                    outputSymbol,
                    minOutput,
                    5000L, // 50% max price impact
                    Instant.now().plusSeconds(600)
            );
            String cmdId = UUID.randomUUID().toString();
            txPacer.awaitSlot(3000);

            com.daml.ledger.api.v2.TransactionOuterClass.Transaction txn = ledgerApi.exerciseAndGetTransaction(
                    poolContract.contractId,
                    swapChoice,
                    cmdId,
                    List.of(xParty, poolPartyStr),
                    List.of(poolPartyStr, xParty)
            ).join();

            steps.add("✓ Swap transaction committed: " + txn.getUpdateId());
            result.put("transactionId", txn.getUpdateId());

            // Step 4: Parse results from flat Transaction
            String outputTokenCid = null;
            String newPoolCid = null;
            for (com.daml.ledger.api.v2.EventOuterClass.Event evt : txn.getEventsList()) {
                if (evt.hasCreated()) {
                    var created = evt.getCreated();
                    var tmpl = created.getTemplateId();
                    if ("Token.Token".equals(tmpl.getModuleName()) && "Token".equals(tmpl.getEntityName())) {
                        // This is the output token for trader
                        outputTokenCid = created.getContractId();
                    }
                    if ("AMM.Pool".equals(tmpl.getModuleName()) && "Pool".equals(tmpl.getEntityName())) {
                        newPoolCid = created.getContractId();
                    }
                }
            }
            result.put("outputTokenCid", outputTokenCid);
            result.put("newPoolCid", newPoolCid);

            // Step 5: Verify new pool reserves
            if (newPoolCid != null) {
                steps.add("Verifying updated reserves...");
                txPacer.awaitSlot(1500);
                List<LedgerApi.ActiveContract<Pool>> updatedPools = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
                String finalPoolCid = newPoolCid;
                LedgerApi.ActiveContract<Pool> newPool = updatedPools.stream()
                        .filter(p -> p.contractId.getContractId.equals(finalPoolCid))
                        .findFirst().orElse(null);
                if (newPool != null) {
                    result.put("reservesAfter", Map.of(
                            newPool.payload.getSymbolA, newPool.payload.getReserveA.toPlainString(),
                            newPool.payload.getSymbolB, newPool.payload.getReserveB.toPlainString()
                    ));
                    steps.add("✓ Reserves updated successfully");
                }
            }

            result.put("success", true);
            result.put("steps", steps);
            result.put("message", "E2E swap test completed successfully!");
            return ResponseEntity.ok(result);

        } catch (Exception ex) {
            String errorMsg = exceptionText(ex);
            steps.add("✗ Error: " + errorMsg);
            result.put("success", false);
            result.put("error", errorMsg);
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }
}
