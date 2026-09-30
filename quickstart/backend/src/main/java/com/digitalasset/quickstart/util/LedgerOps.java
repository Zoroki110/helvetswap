package com.digitalasset.quickstart.util;

import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.transcode.java.Template;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Helper class for common ledger operations
 */
public class LedgerOps {
    private final LedgerApi ledgerApi;

    public LedgerOps(LedgerApi ledgerApi) {
        this.ledgerApi = ledgerApi;
    }

    /**
     * Get active contracts for a specific party
     */
    public <T extends Template> CompletableFuture<List<LedgerApi.ActiveContract<T>>> acsForParty(
            Class<T> clazz, String party) {
        return ledgerApi.getActiveContractsForParty(clazz, party);
    }
}