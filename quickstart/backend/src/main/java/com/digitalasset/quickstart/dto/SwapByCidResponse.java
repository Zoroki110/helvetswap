package com.digitalasset.quickstart.dto;

import java.math.BigDecimal;

public record SwapByCidResponse(
        String poolCid,
        String poolId,
        String outputTokenCid,
        String newPoolCid,
        String commandId,
        BigDecimal amountIn,
        BigDecimal minOutput,
        BigDecimal resolvedOutput,
        boolean recovered
) {}

