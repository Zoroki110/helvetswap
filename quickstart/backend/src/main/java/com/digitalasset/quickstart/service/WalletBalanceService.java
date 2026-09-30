package com.digitalasset.quickstart.service;

import helvet_amm.token.token.Token;
import com.digitalasset.quickstart.auth.PartyValidationService;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.LedgerVisibilityError;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.dto.TokenBalanceDto;
import com.digitalasset.quickstart.ledger.LedgerApi;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WalletBalanceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletBalanceService.class);
    private static final int LEGACY_TOKEN_DECIMALS = 10;

    private final LedgerApi ledgerApi;
    private final PartyValidationService partyValidationService;

    public WalletBalanceService(final LedgerApi ledgerApi,
                                final PartyValidationService partyValidationService) {
        this.ledgerApi = ledgerApi;
        this.partyValidationService = partyValidationService;
    }

    @WithSpan
    public CompletableFuture<Result<List<TokenBalanceDto>, DomainError>> getLegacyBalances(final String partyId) {
        Result<String, DomainError> validated = partyValidationService.normalize(partyId);
        if (validated.isErr()) {
            return completedError(validated.getErrorUnsafe());
        }
        String normalized = validated.getValueUnsafe();
        try {
            return ledgerApi
                    .getActiveContractsForParty(Token.class, normalized)
                    .thenApply(this::mapToDto)
                    .exceptionally(ex -> Result.err(mapLedgerError(ex)));
        } catch (Exception ex) {
            return completedError(mapLedgerError(ex));
        }
    }

    private Result<List<TokenBalanceDto>, DomainError> mapToDto(final List<LedgerApi.ActiveContract<Token>> contracts) {
        Map<String, BigDecimal> aggregated = new LinkedHashMap<>();
        for (LedgerApi.ActiveContract<Token> contract : contracts) {
            Token payload = contract.payload;
            if (payload == null || payload.getSymbol == null || payload.getAmount == null) {
                continue;
            }
            String symbol = payload.getSymbol.trim().toUpperCase(Locale.ROOT);
            aggregated.merge(symbol, payload.getAmount, BigDecimal::add);
        }

        List<TokenBalanceDto> balances = aggregated.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> new TokenBalanceDto(
                        entry.getKey(),
                        normalize(entry.getValue()),
                        LEGACY_TOKEN_DECIMALS,
                        null
                ))
                .toList();

        return Result.ok(balances);
    }

    private BigDecimal normalize(final BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        try {
            return amount.setScale(LEGACY_TOKEN_DECIMALS, RoundingMode.DOWN);
        } catch (ArithmeticException ex) {
            LOGGER.warn("Failed to normalize token amount: {}", ex.getMessage());
            return amount;
        }
    }

    private DomainError mapLedgerError(final Throwable throwable) {
        Throwable root = unwrap(throwable);
        String message = root.getMessage();
        if (message != null) {
            String lowered = message.toLowerCase(Locale.ROOT);
            if (lowered.contains("not visible") || lowered.contains("permission")) {
                return new LedgerVisibilityError(message);
            }
        }
        return new UnexpectedError(message != null ? message : root.toString());
    }

    private Throwable unwrap(final Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private CompletableFuture<Result<List<TokenBalanceDto>, DomainError>> completedError(final DomainError error) {
        return CompletableFuture.completedFuture(Result.err(error));
    }
}
