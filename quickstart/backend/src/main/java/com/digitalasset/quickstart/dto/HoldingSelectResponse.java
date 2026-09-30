package com.digitalasset.quickstart.dto;

import java.math.BigDecimal;

/**
 * Response DTO for UTXO selection of Token Standard holdings.
 */
public record HoldingSelectResponse(
        boolean found,
        String holdingCid,
        String instrumentAdmin,
        String instrumentId,
        BigDecimal amount,
        String owner,
        int attempts,
        long elapsedMs,
        int totalHoldingsScanned,
        int matchingHoldingsFound,
        String selectionRule,
        String error
) {
    public static HoldingSelectResponse success(
            String holdingCid,
            String instrumentAdmin,
            String instrumentId,
            BigDecimal amount,
            String owner,
            int attempts,
            long elapsedMs,
            int totalHoldingsScanned,
            int matchingHoldingsFound
    ) {
        return new HoldingSelectResponse(
                true,
                holdingCid,
                instrumentAdmin,
                instrumentId,
                amount,
                owner,
                attempts,
                elapsedMs,
                totalHoldingsScanned,
                matchingHoldingsFound,
                "smallest-amount-first",
                null
        );
    }

    public static HoldingSelectResponse notFound(
            int attempts,
            long elapsedMs,
            int totalHoldingsScanned,
            int matchingHoldingsFound,
            String error
    ) {
        return new HoldingSelectResponse(
                false,
                null,
                null,
                null,
                null,
                null,
                attempts,
                elapsedMs,
                totalHoldingsScanned,
                matchingHoldingsFound,
                "smallest-amount-first",
                error
        );
    }
}
