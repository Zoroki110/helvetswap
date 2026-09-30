package com.digitalasset.quickstart.dto;

import java.math.BigDecimal;
import java.util.List;

public record ShowcasePoolResetResponse(
        String poolId,
        String poolCid,
        String providerParty,
        BigDecimal ccAmount,
        BigDecimal cbtcAmount,
        BigDecimal pricePerCbtc,
        long maxInBps,
        long maxOutBps,
        List<String> archivedPoolCids,
        List<String> steps
) {}

