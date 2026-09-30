package com.digitalasset.quickstart.service;

import helvet_amm.amm.pool.Pool;
import helvet_amm.token.token.Token;
import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.TransactionOuterClass;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ContractNotActiveError;
import com.digitalasset.quickstart.common.errors.InputTokenStaleOrNotVisibleError;
import com.digitalasset.quickstart.common.errors.InsufficientBalanceError;
import com.digitalasset.quickstart.common.errors.LedgerVisibilityError;
import com.digitalasset.quickstart.common.errors.PoolEmptyError;
import com.digitalasset.quickstart.common.errors.PriceImpactTooHighError;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.ShowcasePoolResetResponse;
import com.digitalasset.quickstart.dto.SwapByCidResponse;
import com.digitalasset.quickstart.dto.WalletDrainResponse;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.service.LedgerAdapter;
import com.digitalasset.quickstart.service.TransactionHistoryService.TransactionHistoryEntry;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class ClearportxFlowService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClearportxFlowService.class);
    private static final BigDecimal MIN_LP_TOKENS = new BigDecimal("0.0000000001");

    private final LedgerApi ledgerApi;
    private final PoolDirectoryService directoryService;
    private final TxPacer txPacer;
    private final TransactionHistoryService historyService;
    private final LedgerAdapter ledgerAdapter;

    public ClearportxFlowService(
            LedgerApi ledgerApi,
            PoolDirectoryService directoryService,
            TxPacer txPacer,
            TransactionHistoryService historyService,
            LedgerAdapter ledgerAdapter
    ) {
        this.ledgerApi = ledgerApi;
        this.directoryService = directoryService;
        this.txPacer = txPacer;
        this.historyService = historyService;
        this.ledgerAdapter = ledgerAdapter;
    }

    @WithSpan
    public CompletableFuture<Result<ShowcasePoolResetResponse, DomainError>> resetShowcasePool(final ResetShowcasePoolCommand command) {
        try {
            List<String> steps = new ArrayList<>();
            BigDecimal cbtcAmount = command.cbtcAmount().setScale(10, RoundingMode.HALF_UP);
            BigDecimal ccAmount = determineCcAmount(command, cbtcAmount);
            if (ccAmount == null) {
                return completedError(new ValidationError("Provide ccAmount or pricePerCbtc", ValidationError.Type.REQUEST));
            }

            List<String> archived = List.of();
            if (command.archiveExisting()) {
                steps.add("archive-existing");
                archived = archivePoolsById(command.poolId(), command.operatorParty());
                steps.add("archived-" + archived.size());
            }

            steps.add("create-pool");
            Pool pool = new Pool(
                    new Party(command.operatorParty()),
                    new Party(command.operatorParty()),
                    new Party(command.operatorParty()),
                    new Party(command.operatorParty()),
                    new Party(command.operatorParty()),
                    "CBTC",
                    "CC",
                    30L,
                    command.poolId(),
                    new daml_stdlib_da_time_types.da.time.types.RelTime(86400000000L),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Optional.empty(),
                    Optional.empty(),
                    new Party(command.operatorParty()),
                    command.maxInBps(),
                    command.maxOutBps(),
                    List.of()
            );

            txPacer.awaitSlot(600);
            ContractId<Pool> poolCid = ledgerApi.createAndGetCid(
                    pool,
                    List.of(command.operatorParty()),
                    List.of(),
                    UUID.randomUUID().toString(),
                    helvet_amm.Identifiers.AMM_Pool__Pool
            ).join();

            steps.add("mint-liquidity");
            ContractId<Token> ccTokenCid = mintToken(command.operatorParty(), command.providerParty(), "CC", ccAmount);
            txPacer.awaitSlot(300);
            ContractId<Token> cbtcTokenCid = mintToken(command.operatorParty(), command.providerParty(), "CBTC", cbtcAmount);

            steps.add("add-liquidity");
            Pool.AddLiquidity addChoice = new Pool.AddLiquidity(
                    new Party(command.providerParty()),
                    cbtcTokenCid,
                    ccTokenCid,
                    cbtcAmount,
                    ccAmount,
                    MIN_LP_TOKENS,
                    Instant.now().plusSeconds(900)
            );

            List<String> actAs = List.of(command.providerParty(), command.operatorParty());
            txPacer.awaitSlot(1200);
            ledgerApi.exerciseAndGetTransaction(
                    poolCid,
                    addChoice,
                    UUID.randomUUID().toString(),
                    actAs,
                    actAs
            ).join();

            directoryService.update(command.poolId(), poolCid.getContractId, command.operatorParty());
            historyService.recordAddLiquidity(
                    command.poolId(),
                    poolCid.getContractId,
                    "CBTC",
                    "CC",
                    cbtcAmount,
                    ccAmount,
                    MIN_LP_TOKENS,
                    estimateLpMint(cbtcAmount, ccAmount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                    command.providerParty()
            );

            ShowcasePoolResetResponse response = new ShowcasePoolResetResponse(
                    command.poolId(),
                    poolCid.getContractId,
                    command.providerParty(),
                    ccAmount,
                    cbtcAmount,
                    ccAmount.divide(cbtcAmount, MathContext.DECIMAL64),
                    command.maxInBps(),
                    command.maxOutBps(),
                    archived,
                    steps
            );
            return CompletableFuture.completedFuture(Result.ok(response));
        } catch (DomainErrorException ex) {
            return completedError(ex.error);
        } catch (Exception ex) {
            LOGGER.error("Failed to reset showcase pool", ex);
            return completedError(mapLedgerException(ex));
        }
    }

    @WithSpan
    public CompletableFuture<Result<WalletDrainResponse, DomainError>> drainWallet(final WalletDrainCommand command) {
        try {
            List<LedgerApi.ActiveContract<Token>> tokens = ledgerApi.getActiveContractsForParty(Token.class, command.party()).join();
            tokens.sort(Comparator.comparing((LedgerApi.ActiveContract<Token> tok) -> tok.payload.getAmount).reversed());

            List<WalletDrainResponse.DrainedToken> drainedTokens = new ArrayList<>();
            BigDecimal totalReduced = BigDecimal.ZERO;

            for (LedgerApi.ActiveContract<Token> token : tokens) {
                Token payload = token.payload;
                if (!payload.getOwner.getParty.equals(command.party())) {
                    continue;
                }
                String symbol = payload.getSymbol.toUpperCase(Locale.ROOT);
                if (!command.symbols().isEmpty() && !command.symbols().contains(symbol)) {
                    continue;
                }
                BigDecimal amount = payload.getAmount;
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                List<WalletDrainResponse.DrainStep> steps = new ArrayList<>();
                BigDecimal remaining = amount;
                boolean archived = false;

                if (command.archive()) {
                    try {
                        txPacer.awaitSlot(200);
                        ledgerApi.exerciseAndGetResultWithParties(
                                token.contractId,
                                new Token.Archive(),
                                UUID.randomUUID().toString(),
                                List.of(payload.getIssuer.getParty),
                                List.of(payload.getIssuer.getParty)
                        ).join();
                        archived = true;
                        remaining = BigDecimal.ZERO;
                        steps.add(new WalletDrainResponse.DrainStep(
                                "archive",
                                token.contractId.getContractId,
                                amount,
                                null
                        ));
                    } catch (Exception archiveEx) {
                        steps.add(new WalletDrainResponse.DrainStep(
                                "archive_failed",
                                token.contractId.getContractId,
                                amount,
                                archiveEx.getMessage()
                        ));
                    }
                }

                totalReduced = totalReduced.add(amount.subtract(remaining.max(BigDecimal.ZERO)));
                drainedTokens.add(new WalletDrainResponse.DrainedToken(
                        symbol,
                        amount,
                        remaining.max(BigDecimal.ZERO),
                        archived,
                        steps
                ));
            }

            WalletDrainResponse response = new WalletDrainResponse(
                    command.party(),
                    command.floor(),
                    new ArrayList<>(command.symbols()),
                    drainedTokens.size(),
                    totalReduced,
                    drainedTokens
            );
            return CompletableFuture.completedFuture(Result.ok(response));
        } catch (Exception ex) {
            LOGGER.error("Failed to drain wallet", ex);
            return completedError(mapLedgerException(ex));
        }
    }

    @WithSpan
    public CompletableFuture<Result<SwapByCidResponse, DomainError>> swapByCid(final SwapByCidCommand command) {
        return resolvePoolContext(command.poolCid(), command.trader())
                .thenCompose(poolResult -> {
                    if (poolResult.isErr()) {
                        return completedError(poolResult.getErrorUnsafe());
                    }
                    PoolContext poolContext = poolResult.getValueUnsafe();
                    return selectInputToken(poolContext, command)
                            .thenCompose(tokenResult -> {
                                if (tokenResult.isErr()) {
                                    return completedError(tokenResult.getErrorUnsafe());
                                }
                                return attemptSwap(command, poolContext, tokenResult.getValueUnsafe(), false);
                            });
                });
    }

    private BigDecimal determineCcAmount(final ResetShowcasePoolCommand command, final BigDecimal cbtcAmount) throws DomainErrorException {
        if (command.ccAmount() != null) {
            return command.ccAmount();
        }
        if (command.pricePerCbtc() != null) {
            return command.pricePerCbtc().multiply(cbtcAmount, MathContext.DECIMAL64);
        }
        throw new DomainErrorException(new ValidationError("Unable to determine CC amount", ValidationError.Type.REQUEST));
    }

    private ContractId<Token> mintToken(final String issuer, final String owner, final String symbol, final BigDecimal amount) {
        Token token = new Token(new Party(issuer), new Party(owner), symbol, amount);
        txPacer.awaitSlot(300);
        return ledgerApi.createAndGetCid(
                token,
                List.of(issuer),
                List.of(),
                UUID.randomUUID().toString(),
                helvet_amm.Identifiers.Token_Token__Token
        ).join();
    }

    private CompletableFuture<Result<PoolContext, DomainError>> resolvePoolContext(final String poolCid, final String trader) {
        if (poolCid == null || poolCid.isBlank()) {
            return completedError(new ValidationError("poolCid is required", ValidationError.Type.REQUEST));
        }
        return ledgerApi.getActiveContractsForParty(Pool.class, trader)
                .thenApply(pools -> pools.stream()
                        .filter(p -> p.contractId.getContractId.equals(poolCid))
                        .findFirst()
                        .map(PoolContext::from)
                        .map(Result::<PoolContext, DomainError>ok)
                        .orElseGet(() -> Result.err(new PoolEmptyError("Pool not visible for trader: " + poolCid))));
    }

    private CompletableFuture<Result<LedgerApi.ActiveContract<Token>, DomainError>> selectInputToken(
            final PoolContext poolContext,
            final SwapByCidCommand command
    ) {
        return ledgerApi.getActiveContractsForParty(Token.class, command.trader())
                .thenApply(tokens -> {
                    String canonicalA = poolContext.canonicalTokenA();
                    String canonicalB = poolContext.canonicalTokenB();
                    Optional<LedgerApi.ActiveContract<Token>> candidate = tokens.stream()
                            .filter(t -> command.inputSymbol().equalsIgnoreCase(t.payload.getSymbol))
                            .filter(t -> command.trader().equals(t.payload.getOwner.getParty))
                            .filter(t -> {
                                String cid = t.contractId.getContractId;
                                return (canonicalA == null || !canonicalA.equals(cid)) && (canonicalB == null || !canonicalB.equals(cid));
                            })
                            .max(Comparator.comparing(t -> t.payload.getAmount));
                    if (candidate.isEmpty()) {
                        return Result.err(new InputTokenStaleOrNotVisibleError("Input token not visible for trader"));
                    }
                    LedgerApi.ActiveContract<Token> token = candidate.get();
                    if (token.payload.getAmount.compareTo(command.amountIn()) < 0) {
                        return Result.err(new InsufficientBalanceError("Insufficient " + command.inputSymbol() + " balance"));
                    }
                    return Result.ok(token);
                });
    }

    private BigDecimal resolveTokenAmount(final String tokenCid, final String party, final String symbol) {
        if (tokenCid == null || tokenCid.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            List<LedgerApi.ActiveContract<Token>> tokens = ledgerApi.getActiveContractsForParty(Token.class, party).join();
            for (LedgerApi.ActiveContract<Token> token : tokens) {
                if (token.contractId.getContractId.equals(tokenCid)) {
                    return token.payload.getAmount;
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("Failed to resolve token amount for {} {}: {}", symbol, tokenCid, ex.getMessage());
        }
        return BigDecimal.ZERO;
    }

    private CompletableFuture<Result<SwapByCidResponse, DomainError>> attemptSwap(
            final SwapByCidCommand command,
            final PoolContext poolContext,
            final LedgerApi.ActiveContract<Token> inputToken,
            final boolean mintedRetry
    ) {
        return performSwapTransaction(command, poolContext, inputToken, mintedRetry)
                .thenCompose(result -> {
                    if (result.isOk()) {
                        return CompletableFuture.completedFuture(result);
                    }
                    DomainError error = result.getErrorUnsafe();
                    if (!mintedRetry && shouldRetryWithFreshTraderToken(error, inputToken.contractId.getContractId)) {
                        LOGGER.warn("Detected CONTRACT_NOT_ACTIVE during swap. Minting fresh token and retrying once.");
                        return mintAndRetry(command, poolContext);
                    }
                    return completedError(error);
                });
    }

    private CompletableFuture<Result<SwapByCidResponse, DomainError>> performSwapTransaction(
            final SwapByCidCommand command,
            final PoolContext poolContext,
            final LedgerApi.ActiveContract<Token> inputToken,
            final boolean recovered
    ) {
        Pool.AtomicSwap atomicSwap = new Pool.AtomicSwap(
                new Party(command.trader()),
                inputToken.contractId,
                command.inputSymbol(),
                command.amountIn(),
                command.outputSymbol(),
                command.minOutput().compareTo(BigDecimal.ZERO) > 0 ? command.minOutput() : BigDecimal.ONE,
                5000L,
                Instant.now().plusSeconds(600)
        );

        txPacer.awaitSlot(1500);
        return ledgerApi.exerciseAndGetTransaction(
                        new ContractId<>(command.poolCid()),
                        atomicSwap,
                        UUID.randomUUID().toString(),
                        List.of(command.trader(), poolContext.poolParty()),
                        List.of(poolContext.poolParty(), command.trader())
                )
                .handle((txn, throwable) -> {
                    if (throwable != null) {
                        return Result.<SwapByCidResponse, DomainError>err(mapLedgerException(throwable));
                    }
                    String createdPoolCid = extractCreatedPoolCid(txn);
                    String outputTokenCid = extractOutputTokenCid(txn);
                    BigDecimal resolvedOutput = resolveTokenAmount(outputTokenCid, command.trader(), command.outputSymbol());
                    historyService.recordSwap(
                            poolContext.poolId(),
                            command.poolCid(),
                            command.inputSymbol(),
                            command.outputSymbol(),
                            command.amountIn(),
                            resolvedOutput.compareTo(BigDecimal.ZERO) > 0 ? resolvedOutput : command.minOutput(),
                            command.trader()
                    );
                    SwapByCidResponse response = new SwapByCidResponse(
                            command.poolCid(),
                            poolContext.poolId(),
                            outputTokenCid,
                            createdPoolCid,
                            txn.getCommandId(),
                            command.amountIn(),
                            command.minOutput(),
                            resolvedOutput,
                            recovered
                    );
                    return Result.ok(response);
                });
    }

    private CompletableFuture<Result<SwapByCidResponse, DomainError>> mintAndRetry(
            final SwapByCidCommand command,
            final PoolContext poolContext
    ) {
        Result<LedgerAdapter.TokenSide, DomainError> sideResult = determineTokenSide(poolContext, command.inputSymbol());
        if (sideResult.isErr()) {
            return completedError(sideResult.getErrorUnsafe());
        }
        return ledgerAdapter.mintToken(
                        poolContext.contract().payload,
                        sideResult.getValueUnsafe(),
                        command.trader(),
                        command.amountIn()
                )
                .thenCompose(tokenResult -> tokenResult.isErr()
                        ? completedError(tokenResult.getErrorUnsafe())
                        : attemptSwap(command, poolContext, tokenResult.getValueUnsafe(), true));
    }

    private boolean shouldRetryWithFreshTraderToken(
            final DomainError error,
            final String inputTokenCid
    ) {
        if (!(error instanceof ContractNotActiveError contractError)) {
            return false;
        }
        if (contractError.isTokenContract()) {
            return true;
        }
        String message = error.message();
        return message != null && !message.isBlank() && message.contains(inputTokenCid);
    }

    private Result<LedgerAdapter.TokenSide, DomainError> determineTokenSide(
            final PoolContext context,
            final String symbol
    ) {
        Pool payload = context.contract().payload;
        if (payload.getSymbolA.equalsIgnoreCase(symbol)) {
            return Result.ok(LedgerAdapter.TokenSide.A);
        }
        if (payload.getSymbolB.equalsIgnoreCase(symbol)) {
            return Result.ok(LedgerAdapter.TokenSide.B);
        }
        return Result.err(new ValidationError("Symbol " + symbol + " not supported by pool", ValidationError.Type.REQUEST));
    }

    private List<String> archivePoolsById(final String poolId, final String operator) {
        List<String> archived = new ArrayList<>();
        List<LedgerApi.ActiveContract<Pool>> pools = ledgerApi.getActiveContracts(Pool.class).join();
        for (LedgerApi.ActiveContract<Pool> pac : pools) {
            if (poolId.equalsIgnoreCase(pac.payload.getPoolId)) {
                txPacer.awaitSlot(400);
                ledgerApi.exerciseAndGetResultWithParties(
                        pac.contractId,
                        new Pool.Archive(),
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

    private static BigDecimal estimateLpMint(
            BigDecimal amountA,
            BigDecimal amountB,
            BigDecimal reserveA,
            BigDecimal reserveB,
            BigDecimal totalLp
    ) {
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

    private String extractOutputTokenCid(final TransactionOuterClass.Transaction txn) {
        try {
            for (EventOuterClass.Event event : txn.getEventsList()) {
                if (event.hasCreated()) {
                    EventOuterClass.CreatedEvent created = event.getCreated();
                    var tmpl = created.getTemplateId();
                    if ("Token.Token".equals(tmpl.getModuleName()) && "Token".equals(tmpl.getEntityName())) {
                        return created.getContractId();
                    }
                }
            }
        } catch (Exception ignore) {
            // best effort
        }
        return null;
    }

    private String extractCreatedPoolCid(final TransactionOuterClass.Transaction txn) {
        try {
            for (EventOuterClass.Event event : txn.getEventsList()) {
                if (event.hasCreated()) {
                    EventOuterClass.CreatedEvent created = event.getCreated();
                    var tmpl = created.getTemplateId();
                    if ("AMM.Pool".equals(tmpl.getModuleName()) && "Pool".equals(tmpl.getEntityName())) {
                        return created.getContractId();
                    }
                }
            }
        } catch (Exception ignore) {
            // best effort
        }
        return null;
    }

    private DomainError mapLedgerException(final Throwable throwable) {
        String message = exceptionText(throwable);
        String lower = message != null ? message.toLowerCase(Locale.ROOT) : "";
        if (lower.contains("contract_not_active") || lower.contains("contract_not_found")) {
            boolean tokenContract = lower.contains("token.token") || lower.contains("token_token__token");
            return new ContractNotActiveError(message, tokenContract);
        }
        if (lower.contains("pool not visible") || lower.contains("not visible for party")) {
            return new LedgerVisibilityError(message);
        }
        if (lower.contains("slippage") || lower.contains("price impact")) {
            return new PriceImpactTooHighError(message);
        }
        if (lower.contains("insufficient")) {
            return new InsufficientBalanceError(message);
        }
        return new UnexpectedError(message);
    }

    private static String exceptionText(final Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message != null ? message : root.toString();
    }

    private <T> CompletableFuture<Result<T, DomainError>> completedError(final DomainError error) {
        return CompletableFuture.completedFuture(Result.err(error));
    }

    public record ResetShowcasePoolCommand(
            String operatorParty,
            String providerParty,
            String poolId,
            BigDecimal ccAmount,
            BigDecimal cbtcAmount,
            BigDecimal pricePerCbtc,
            long maxInBps,
            long maxOutBps,
            boolean archiveExisting
    ) {}

    public record WalletDrainCommand(
            String party,
            Set<String> symbols,
            BigDecimal floor,
            boolean archive
    ) {}

    public record SwapByCidCommand(
            String trader,
            String poolCid,
            String poolId,
            String inputSymbol,
            String outputSymbol,
            BigDecimal amountIn,
            BigDecimal minOutput
    ) {}

    private record PoolContext(
            LedgerApi.ActiveContract<Pool> contract,
            String poolParty,
            String poolId,
            String canonicalTokenA,
            String canonicalTokenB
    ) {
        static PoolContext from(LedgerApi.ActiveContract<Pool> contract) {
            Pool payload = contract.payload;
            return new PoolContext(
                    contract,
                    payload.getPoolParty.getParty,
                    payload.getPoolId,
                    payload.getTokenACid.map(cid -> cid.getContractId).orElse(null),
                    payload.getTokenBCid.map(cid -> cid.getContractId).orElse(null)
            );
        }
    }

    private static final class DomainErrorException extends Exception {
        private final DomainError error;

        private DomainErrorException(DomainError error) {
            super(error.message());
            this.error = error;
        }
    }
}

