// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import helvet_amm.token.token.Token;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.transcode.java.ContractId;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * TokenMergeService - Automatically consolidates fragmented tokens
 * 
 * After swaps, users accumulate many small token contracts.
 * This service merges them into ONE contract per symbol automatically.
 */
@Service
public class TokenMergeService {
    private static final Logger logger = LoggerFactory.getLogger(TokenMergeService.class);
    private final LedgerApi ledger;

    public TokenMergeService(LedgerApi ledger) {
        this.ledger = ledger;
    }

    /**
     * Merge all tokens of a specific symbol for a party into ONE contract
     * Called automatically after each swap to prevent fragmentation
     */
    @WithSpan
    public CompletableFuture<ContractId<Token>> mergeAllTokens(String party, String symbol) {
        logger.info("Auto-merging {} tokens for party: {}", symbol, party);

        return ledger.getActiveContracts(Token.class)
            .thenCompose(allTokens -> {
                // Filter tokens for this party and symbol
                List<LedgerApi.ActiveContract<Token>> matchingTokens = allTokens.stream()
                    .filter(t -> t.payload.getOwner.getParty.equals(party))
                    .filter(t -> t.payload.getSymbol.equals(symbol))
                    .collect(Collectors.toList());

                if (matchingTokens.size() <= 1) {
                    logger.debug("No merge needed for {} - {} token(s)", symbol, matchingTokens.size());
                    return CompletableFuture.completedFuture(
                        matchingTokens.isEmpty() ? null : matchingTokens.get(0).contractId
                    );
                }

                logger.info("Merging {} {} tokens into 1 contract", matchingTokens.size(), symbol);

                // Sort by amount descending (keep largest as base)
                matchingTokens.sort((a, b) -> 
                    b.payload.getAmount.compareTo(a.payload.getAmount)
                );

                return mergeTokensSequentially(matchingTokens, party);
            })
            .exceptionally(ex -> {
                logger.error("Failed to merge {} tokens: {}", symbol, ex.getMessage());
                return null; // Don't fail the whole swap if merge fails
            });
    }

    /**
     * Merge tokens sequentially: base + token1 -> merged1, merged1 + token2 -> merged2, etc.
     */
    private CompletableFuture<ContractId<Token>> mergeTokensSequentially(
            List<LedgerApi.ActiveContract<Token>> tokens,
            String party
    ) {
        ContractId<Token> baseCid = tokens.get(0).contractId;
        CompletableFuture<ContractId<Token>> result = CompletableFuture.completedFuture(baseCid);
        
        for (int i = 1; i < tokens.size(); i++) {
            final ContractId<Token> otherCid = tokens.get(i).contractId;
            
            result = result.thenCompose(currentBase -> {
                Token.Merge mergeChoice = new Token.Merge(otherCid);
                String commandId = UUID.randomUUID().toString();
                
                return ledger.exerciseAndGetResult(currentBase, mergeChoice, commandId);
            });
        }

        return result.thenApply(finalCid -> {
            logger.info("✅ Merged {} tokens into 1: {}", tokens.size(), 
                finalCid.getContractId.substring(0, 16) + "...");
            return finalCid;
        });
    }
}
