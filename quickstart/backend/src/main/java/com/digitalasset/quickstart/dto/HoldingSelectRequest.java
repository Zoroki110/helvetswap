package com.digitalasset.quickstart.dto;

import java.math.BigDecimal;

/**
 * Request DTO for UTXO selection of Token Standard holdings.
 *
 * Selection rule: smallest holding that satisfies minAmount (greedy-smallest).
 * If multiple holdings have the same amount, the one with the lexicographically
 * smallest contractId is selected (deterministic).
 */
public record HoldingSelectRequest(
        String ownerParty,
        String instrumentAdmin,
        String instrumentId,
        BigDecimal minAmount,
        Integer timeoutSeconds,
        Integer pollIntervalMs
) {
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_POLL_INTERVAL_MS = 2000;

    public int getTimeoutSeconds() {
        return timeoutSeconds != null ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    }

    public int getPollIntervalMs() {
        return pollIntervalMs != null ? pollIntervalMs : DEFAULT_POLL_INTERVAL_MS;
    }

    public BigDecimal getMinAmount() {
        return minAmount != null ? minAmount : BigDecimal.ZERO;
    }
}
