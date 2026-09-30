package com.digitalasset.quickstart.service;

import helvet_amm.Identifiers;
import helvet_amm.amm.pool.Pool;
import helvet_amm.lptoken.lptoken.LPToken;
import helvet_amm.token.token.Token;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ContractNotActiveError;
import com.digitalasset.quickstart.common.errors.InsufficientBalanceError;
import com.digitalasset.quickstart.common.errors.LedgerVisibilityError;
import com.digitalasset.quickstart.common.errors.PriceImpactTooHighError;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class LedgerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerAdapter.class);

    private final LedgerApi ledgerApi;

    public LedgerAdapter(final LedgerApi ledgerApi) {
        this.ledgerApi = ledgerApi;
    }

    @CircuitBreaker(name = "ledgerApi", fallbackMethod = "exerciseAddLiquidityFallback")
    public CompletableFuture<Result<AddLiquidityResult, DomainError>> exerciseAddLiquidity(
            final LedgerApi.ActiveContract<Pool> pool,
            final String providerParty,
            final LedgerApi.ActiveContract<Token> tokenA,
            final LedgerApi.ActiveContract<Token> tokenB,
            final BigDecimal amountA,
            final BigDecimal amountB,
            final BigDecimal minLpTokens
    ) {
        Pool payload = pool.payload;
        Instant deadline = Instant.now().plusSeconds(600);
        Pool.AddLiquidity choice = new Pool.AddLiquidity(
                new Party(providerParty),
                new ContractId<>(tokenA.contractId.getContractId),
                new ContractId<>(tokenB.contractId.getContractId),
                amountA,
                amountB,
                minLpTokens,
                deadline
        );

        List<String> actAs = List.of(
                providerParty,
                payload.getPoolParty.getParty,
                payload.getLpIssuer.getParty
        );
        List<String> readAs = List.of(payload.getPoolParty.getParty);
        String commandId = UUID.randomUUID().toString();

        return ledgerApi.exerciseAndGetResultWithParties(
                        pool.contractId,
                        choice,
                        commandId,
                        actAs,
                        readAs
                )
                .handle((result, throwable) -> {
                    if (throwable != null) {
                        return Result.<AddLiquidityResult, DomainError>err(mapLedgerException(throwable));
                    }
                    return Result.ok(new AddLiquidityResult(result.get_1, result.get_2));
                });
    }

    @CircuitBreaker(name = "ledgerApi", fallbackMethod = "mintTokenFallback")
    public CompletableFuture<Result<LedgerApi.ActiveContract<Token>, DomainError>> mintToken(
            final Pool pool,
            final TokenSide side,
            final String owner,
            final BigDecimal amount
    ) {
        Party issuer = side == TokenSide.A ? pool.getIssuerA : pool.getIssuerB;
        String symbol = side == TokenSide.A ? pool.getSymbolA : pool.getSymbolB;
        Token template = new Token(issuer, new Party(owner), symbol, amount);
        String commandId = UUID.randomUUID().toString();

        return ledgerApi.createAndGetCid(
                        template,
                        List.of(issuer.getParty),
                        List.of(),
                        commandId,
                        Identifiers.Token_Token__Token
                )
                .handle((cid, throwable) -> {
                    if (throwable != null) {
                        return Result.<LedgerApi.ActiveContract<Token>, DomainError>err(new UnexpectedError(exceptionText(throwable)));
                    }
                    return Result.ok(new LedgerApi.ActiveContract<>(cid, template));
                });
    }

    private DomainError mapLedgerException(final Throwable throwable) {
        String message = exceptionText(throwable);
        LOG.warn("Ledger exception detected: {}", message);
        String lc = message.toLowerCase();
        if (lc.contains("contract_not_active") || lc.contains("contract_not_found")) {
            ValueOuterClass.Identifier templateIdentifier = extractTemplateIdentifier(throwable);
            boolean tokenContract = templateIdentifier != null
                    ? isTokenTemplate(templateIdentifier)
                    : looksLikeTokenContractMessage(lc);
            return new ContractNotActiveError(message, tokenContract);
        }
        if (lc.contains("pool not visible") || lc.contains("not visible for party")) {
            return new LedgerVisibilityError(message);
        }
        if (lc.contains("slippage") || lc.contains("price impact")) {
            return new PriceImpactTooHighError(message);
        }
        if (lc.contains("insufficient")) {
            return new InsufficientBalanceError(message);
        }
        return new UnexpectedError(message);
    }

    private ValueOuterClass.Identifier extractTemplateIdentifier(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof StatusRuntimeException statusRuntimeException) {
                try {
                    Status status = StatusProto.fromThrowable(statusRuntimeException);
                    if (status != null) {
                        for (Any detail : status.getDetailsList()) {
                            if (detail.is(ValueOuterClass.Identifier.class)) {
                                return detail.unpack(ValueOuterClass.Identifier.class);
                            }
                        }
                    }
                } catch (InvalidProtocolBufferException ignored) {
                    // Ignore structured parsing issues and fall back to message-based detection.
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean isTokenTemplate(final ValueOuterClass.Identifier identifier) {
        return "Token.Token".equals(identifier.getModuleName()) && "Token".equals(identifier.getEntityName());
    }

    private boolean looksLikeTokenContractMessage(final String lowerCaseMessage) {
        // TODO: replace message sniffing once TemplateIds are exposed directly in ledger error envelopes.
        return lowerCaseMessage.contains("token.token") || lowerCaseMessage.contains("token_token__token");
    }

    private static String exceptionText(final Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message != null ? message : root.toString();
    }

    // Circuit breaker fallback methods
    @SuppressWarnings("unused")
    private CompletableFuture<Result<AddLiquidityResult, DomainError>> exerciseAddLiquidityFallback(
            LedgerApi.ActiveContract<Pool> pool, String providerParty,
            LedgerApi.ActiveContract<Token> tokenA, LedgerApi.ActiveContract<Token> tokenB,
            BigDecimal amountA, BigDecimal amountB, BigDecimal minLpTokens, Exception e) {
        LOG.error("Circuit breaker open for exerciseAddLiquidity", e);
        return CompletableFuture.completedFuture(
                Result.err(new com.digitalasset.quickstart.common.errors.ServiceUnavailableException("Canton ledger temporarily unavailable", 30)));
    }

    @SuppressWarnings("unused")
    private CompletableFuture<Result<LedgerApi.ActiveContract<Token>, DomainError>> mintTokenFallback(
            Pool pool, TokenSide side, String owner, BigDecimal amount, Exception e) {
        LOG.error("Circuit breaker open for mintToken", e);
        return CompletableFuture.completedFuture(
                Result.err(new com.digitalasset.quickstart.common.errors.ServiceUnavailableException("Canton ledger temporarily unavailable", 30)));
    }

    public record AddLiquidityResult(ContractId<LPToken> lpTokenCid, ContractId<Pool> newPoolCid) { }

    public enum TokenSide {
        A, B
    }
}
