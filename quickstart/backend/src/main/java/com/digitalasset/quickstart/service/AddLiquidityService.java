package com.digitalasset.quickstart.service;

import helvet_amm.amm.pool.Pool;
import helvet_amm.token.token.Token;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ContractNotActiveError;
import com.digitalasset.quickstart.common.errors.InputTokenStaleOrNotVisibleError;
import com.digitalasset.quickstart.common.errors.InsufficientBalanceError;
import com.digitalasset.quickstart.common.errors.PoolEmptyError;
import com.digitalasset.quickstart.dto.AddLiquidityResponse;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.util.AmmMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

@Service
public class AddLiquidityService {

    private static final Logger LOG = LoggerFactory.getLogger(AddLiquidityService.class);

    private final LedgerApi ledgerApi;
    private final LedgerAdapter ledgerAdapter;
    private final TxPacer txPacer;
    private final TransactionHistoryService historyService;

    @Autowired(required = false)
    private FeaturedAppMarkerService featuredAppMarkerService;

    public AddLiquidityService(
            final LedgerApi ledgerApi,
            final LedgerAdapter ledgerAdapter,
            final TxPacer txPacer,
            final TransactionHistoryService historyService
    ) {
        this.ledgerApi = ledgerApi;
        this.ledgerAdapter = ledgerAdapter;
        this.txPacer = txPacer;
        this.historyService = historyService;
    }

    public CompletableFuture<Result<AddLiquidityResponse, DomainError>> addLiquidity(final AddLiquidityCommand command) {
        return ledgerApi.getActiveContractsForParty(Pool.class, command.providerParty())
                .thenCompose(pools -> {
                    Result<LedgerApi.ActiveContract<Pool>, DomainError> poolResult = resolvePool(pools, command.poolId());
                    if (poolResult.isErr()) {
                        return CompletableFuture.completedFuture(Result.<AddLiquidityResponse, DomainError>err(poolResult.getErrorUnsafe()));
                    }
                    LedgerApi.ActiveContract<Pool> pool = poolResult.getValueUnsafe();
                    return ledgerApi.getActiveContractsForParty(Token.class, command.providerParty())
                            .thenCompose(tokens -> {
                                Result<TokenSelection, DomainError> selectionResult = selectTokens(tokens, pool, command);
                                if (selectionResult.isErr()) {
                                    return CompletableFuture.completedFuture(Result.<AddLiquidityResponse, DomainError>err(selectionResult.getErrorUnsafe()));
                                }
                                return attemptAddLiquidity(command, pool, selectionResult.getValueUnsafe(), false);
                            });
                });
    }

    private CompletableFuture<Result<AddLiquidityResponse, DomainError>> attemptAddLiquidity(
            final AddLiquidityCommand command,
            final LedgerApi.ActiveContract<Pool> pool,
            final TokenSelection tokens,
            final boolean mintedRetry
    ) {
        return ledgerAdapter.exerciseAddLiquidity(
                        pool,
                        command.providerParty(),
                        tokens.tokenA(),
                        tokens.tokenB(),
                        command.amountA(),
                        command.amountB(),
                        command.minLpTokens()
                )
                .thenCompose(result -> {
                    if (result.isOk()) {
                        AddLiquidityResponse response = toResponse(pool.payload, command, result.getValueUnsafe());
                        recordAddLiquidity(pool, command, response);
                        if (featuredAppMarkerService != null) {
                            featuredAppMarkerService.createMarker(command.requestId(), "LIQUIDITY_ADD",
                                    command.poolId() + ":" + command.amountA() + "/" + command.amountB());
                        }
                        return CompletableFuture.completedFuture(Result.ok(response));
                    }
                    DomainError error = result.getErrorUnsafe();
                    if (!mintedRetry && shouldRetryWithFreshTokens(error, tokens)) {
                        LOG.warn("Detected CONTRACT_NOT_ACTIVE during add-liquidity. Minting fresh tokens and retrying once.");
                        return mintAndRetry(command, pool);
                    }
                    return completedError(error);
                });
    }

    private CompletableFuture<Result<AddLiquidityResponse, DomainError>> mintAndRetry(
            final AddLiquidityCommand command,
            final LedgerApi.ActiveContract<Pool> pool
    ) {
        return mintFreshTokens(pool, command)
                .thenCompose(selectionResult -> selectionResult.isErr()
                        ? completedError(selectionResult.getErrorUnsafe())
                        : attemptAddLiquidity(command, pool, selectionResult.getValueUnsafe(), true));
    }

    private CompletableFuture<Result<TokenSelection, DomainError>> mintFreshTokens(
            final LedgerApi.ActiveContract<Pool> pool,
            final AddLiquidityCommand command
    ) {
        CompletableFuture<Result<LedgerApi.ActiveContract<Token>, DomainError>> tokenAFuture =
                mintFreshToken(pool, LedgerAdapter.TokenSide.A, command);
        CompletableFuture<Result<LedgerApi.ActiveContract<Token>, DomainError>> tokenBFuture =
                mintFreshToken(pool, LedgerAdapter.TokenSide.B, command);
        BiFunction<Result<LedgerApi.ActiveContract<Token>, DomainError>, Result<LedgerApi.ActiveContract<Token>, DomainError>, Result<TokenSelection, DomainError>> combineTokens =
                (aResult, bResult) -> {
                    if (aResult.isErr()) {
                        return Result.err(aResult.getErrorUnsafe());
                    }
                    if (bResult.isErr()) {
                        return Result.err(bResult.getErrorUnsafe());
                    }
                    txPacer.awaitSlot(1200);
                    return Result.ok(new TokenSelection(aResult.getValueUnsafe(), bResult.getValueUnsafe()));
                };
        return tokenAFuture.thenCombine(tokenBFuture, combineTokens);
    }

    private CompletableFuture<Result<LedgerApi.ActiveContract<Token>, DomainError>> mintFreshToken(
            final LedgerApi.ActiveContract<Pool> pool,
            final LedgerAdapter.TokenSide side,
            final AddLiquidityCommand command
    ) {
        BigDecimal amount = side == LedgerAdapter.TokenSide.A ? command.amountA() : command.amountB();
        return ledgerAdapter.mintToken(pool.payload, side, command.providerParty(), amount);
    }

    private boolean shouldRetryWithFreshTokens(
            final DomainError error,
            final TokenSelection tokens
    ) {
        if (!(error instanceof ContractNotActiveError contractError)) {
            return false;
        }
        if (contractError.isTokenContract()) {
            return true;
        }
        String message = error.message();
        if (message == null || message.isBlank()) {
            return false;
        }
        String tokenAId = tokens.tokenA().contractId.getContractId;
        String tokenBId = tokens.tokenB().contractId.getContractId;
        return message.contains(tokenAId) || message.contains(tokenBId);
    }

    private CompletableFuture<Result<AddLiquidityResponse, DomainError>> completedError(final DomainError error) {
        return CompletableFuture.completedFuture(Result.err(error));
    }

    private Result<LedgerApi.ActiveContract<Pool>, DomainError> resolvePool(
            final List<LedgerApi.ActiveContract<Pool>> pools,
            final String poolId
    ) {
        return pools.stream()
                .filter(p -> p.payload.getPoolId.equals(poolId))
                .findFirst()
                .map(Result::<LedgerApi.ActiveContract<Pool>, DomainError>ok)
                .orElseGet(() -> Result.err(new PoolEmptyError("Pool not found or archived: " + poolId)));
    }

    private Result<TokenSelection, DomainError> selectTokens(
            final List<LedgerApi.ActiveContract<Token>> tokens,
            final LedgerApi.ActiveContract<Pool> pool,
            final AddLiquidityCommand command
    ) {
        Pool payload = pool.payload;
        String owner = command.providerParty();
        String canonicalA = payload.getTokenACid.map(cid -> cid.getContractId).orElse(null);
        String canonicalB = payload.getTokenBCid.map(cid -> cid.getContractId).orElse(null);

        LedgerApi.ActiveContract<Token> tokenA = tokens.stream()
                .filter(t -> t.payload.getSymbol.equals(payload.getSymbolA))
                .filter(t -> t.payload.getOwner.getParty.equals(owner))
                .filter(t -> canonicalA == null || !canonicalA.equals(t.contractId.getContractId))
                .max(Comparator.comparing(t -> t.payload.getAmount))
                .orElse(null);

        LedgerApi.ActiveContract<Token> tokenB = tokens.stream()
                .filter(t -> t.payload.getSymbol.equals(payload.getSymbolB))
                .filter(t -> t.payload.getOwner.getParty.equals(owner))
                .filter(t -> canonicalB == null || !canonicalB.equals(t.contractId.getContractId))
                .max(Comparator.comparing(t -> t.payload.getAmount))
                .orElse(null);

        if (tokenA == null) {
            return Result.err(new InputTokenStaleOrNotVisibleError("Token " + payload.getSymbolA + " not visible for " + owner));
        }
        if (tokenB == null) {
            return Result.err(new InputTokenStaleOrNotVisibleError("Token " + payload.getSymbolB + " not visible for " + owner));
        }

        if (tokenA.payload.getAmount.compareTo(command.amountA()) < 0) {
            return Result.err(new InsufficientBalanceError(
                    "Insufficient " + payload.getSymbolA + ": have " + tokenA.payload.getAmount + ", need " + command.amountA()));
        }
        if (tokenB.payload.getAmount.compareTo(command.amountB()) < 0) {
            return Result.err(new InsufficientBalanceError(
                    "Insufficient " + payload.getSymbolB + ": have " + tokenB.payload.getAmount + ", need " + command.amountB()));
        }

        return Result.ok(new TokenSelection(tokenA, tokenB));
    }

    private AddLiquidityResponse toResponse(
            final Pool poolPayload,
            final AddLiquidityCommand command,
            final LedgerAdapter.AddLiquidityResult result
    ) {
        String reserveA = poolPayload.getReserveA.add(command.amountA()).toPlainString();
        String reserveB = poolPayload.getReserveB.add(command.amountB()).toPlainString();
        String lpAmount = AmmMath.estimateLpMint(
                command.amountA(),
                command.amountB(),
                poolPayload.getReserveA,
                poolPayload.getReserveB,
                poolPayload.getTotalLPSupply
        )
        .max(BigDecimal.ZERO)
        .setScale(10, RoundingMode.DOWN)
        .toPlainString();
        return new AddLiquidityResponse(
                result.lpTokenCid().getContractId,
                result.newPoolCid().getContractId,
                reserveA,
                reserveB,
                lpAmount
        );
    }

    private void recordAddLiquidity(
            final LedgerApi.ActiveContract<Pool> pool,
            final AddLiquidityCommand command,
            final AddLiquidityResponse response
    ) {
        try {
            BigDecimal lpMinted = new BigDecimal(response.lpAmount());
            historyService.recordAddLiquidity(
                    pool.payload.getPoolId,
                    pool.contractId.getContractId,
                    pool.payload.getSymbolA,
                    pool.payload.getSymbolB,
                    command.amountA(),
                    command.amountB(),
                    command.minLpTokens(),
                    lpMinted,
                    command.providerParty()
            );
        } catch (Exception ex) {
            LOG.warn("Failed to record add liquidity history: {}", ex.getMessage());
        }
    }

    public record AddLiquidityCommand(
            String poolId,
            BigDecimal amountA,
            BigDecimal amountB,
            BigDecimal minLpTokens,
            String providerParty,
            String requestId
    ) {}

    private record TokenSelection(
            LedgerApi.ActiveContract<Token> tokenA,
            LedgerApi.ActiveContract<Token> tokenB
    ) {}
}
