package com.digitalasset.quickstart.dto;

import java.math.BigDecimal;

/**
 * Legacy Token.Token balance representation for Phase 3.6 (pre-CIP-0056).
 */
public record TokenBalanceDto(
        String symbol,
        BigDecimal amount,
        int decimals,
        String instrumentId
) {
}
