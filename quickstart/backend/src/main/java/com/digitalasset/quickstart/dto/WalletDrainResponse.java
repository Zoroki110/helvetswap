package com.digitalasset.quickstart.dto;

import java.math.BigDecimal;
import java.util.List;

public record WalletDrainResponse(
        String party,
        BigDecimal floor,
        List<String> symbols,
        int totalContracts,
        BigDecimal totalDrained,
        List<DrainedToken> details
) {

    public record DrainedToken(
            String symbol,
            BigDecimal originalAmount,
            BigDecimal remainingAmount,
            boolean archived,
            List<DrainStep> steps
    ) {}

    public record DrainStep(String action, String contractId, BigDecimal amount, String error) {}
}

