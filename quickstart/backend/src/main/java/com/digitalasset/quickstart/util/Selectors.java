package com.digitalasset.quickstart.util;

import com.digitalasset.quickstart.ledger.LedgerApi;
import helvet_amm.amm.pool.Pool;
import helvet_amm.token.token.Token;
import java.util.List;
import java.util.Optional;
import java.util.Comparator;

/**
 * Utility methods for selecting contracts from lists
 */
public class Selectors {

    /**
     * Find a pool by its ID
     */
    public static Optional<LedgerApi.ActiveContract<Pool>> poolById(
            List<LedgerApi.ActiveContract<Pool>> pools, String poolId) {
        return pools.stream()
            .filter(p -> p.payload.getPoolId.equals(poolId))
            .findFirst();
    }

    /**
     * Find the best token (highest amount) for a given symbol and owner
     */
    public static Optional<LedgerApi.ActiveContract<Token>> bestToken(
            List<LedgerApi.ActiveContract<Token>> tokens,
            String symbol,
            String owner) {
        return tokens.stream()
            .filter(t -> t.payload.getSymbol.equals(symbol))
            .filter(t -> t.payload.getOwner.getParty.equals(owner))
            .max(Comparator.comparing(t -> t.payload.getAmount));
    }
}