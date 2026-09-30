package com.digitalasset.quickstart.service;

import helvet_amm.token.token.Token;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.MintTokensResponse;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class TokenMintService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenMintService.class);

    private final LedgerApi ledgerApi;

    public TokenMintService(LedgerApi ledgerApi) {
        this.ledgerApi = ledgerApi;
    }

    public CompletableFuture<Result<MintTokensResponse, DomainError>> mintTokens(final MintTokensCommand command) {
        try {
            List<String> steps = new ArrayList<>();
            List<Map<String, String>> minted = new ArrayList<>();

            steps.add("Minting token " + command.symbolA());
            ContractId<Token> tokenA = mintToken(
                    command.issuerParty(),
                    command.ownerParty(),
                    command.symbolA(),
                    command.amountA()
            );
            minted.add(Map.of(
                    "symbol", command.symbolA(),
                    "contractId", tokenA.getContractId
            ));

            steps.add("Minting token " + command.symbolB());
            ContractId<Token> tokenB = mintToken(
                    command.issuerParty(),
                    command.ownerParty(),
                    command.symbolB(),
                    command.amountB()
            );
            minted.add(Map.of(
                    "symbol", command.symbolB(),
                    "contractId", tokenB.getContractId
            ));

            LOGGER.info("Minted tokens {} and {} for {}", command.symbolA(), command.symbolB(), command.ownerParty());
            return CompletableFuture.completedFuture(Result.ok(new MintTokensResponse(minted, steps)));
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(Result.err(new ValidationError(ex.getMessage(), ValidationError.Type.REQUEST)));
        } catch (Exception ex) {
            LOGGER.error("Token minting failed", ex);
            return CompletableFuture.completedFuture(Result.err(new UnexpectedError(ex.getMessage())));
        }
    }

    private ContractId<Token> mintToken(final String issuer, final String owner, final String symbol, final BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive for " + symbol);
        }
        Token token = new Token(new Party(issuer), new Party(owner), symbol, amount);
        return ledgerApi.createAndGetCid(
                token,
                List.of(issuer),
                List.of(),
                UUID.randomUUID().toString(),
                helvet_amm.Identifiers.Token_Token__Token
        ).join();
    }

    public record MintTokensCommand(
            String issuerParty,
            String ownerParty,
            String symbolA,
            BigDecimal amountA,
            String symbolB,
            BigDecimal amountB
    ) {}
}

