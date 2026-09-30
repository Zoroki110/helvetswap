// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * PrepareSwapRequest - Request to prepare a swap (create SwapRequest + execute PrepareSwap)
 */
public class PrepareSwapRequest {
    @NotBlank(message = "poolId is required")
    public String poolId;

    @NotBlank(message = "inputSymbol is required")
    public String inputSymbol;

    @NotNull(message = "inputAmount is required")
    @DecimalMin(value = "0.0000000001", message = "inputAmount must be at least 0.0000000001")
    public BigDecimal inputAmount;

    @NotBlank(message = "outputSymbol is required")
    public String outputSymbol;

    @NotNull(message = "minOutput is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "minOutput must be non-negative")
    public BigDecimal minOutput;

    @NotNull(message = "maxPriceImpactBps is required")
    @Min(value = 0, message = "maxPriceImpactBps must be at least 0")
    @Max(value = 5000, message = "maxPriceImpactBps must not exceed 5000 (50%)")
    public Integer maxPriceImpactBps;  // e.g., 100 = 1%

    // Default constructor for Jackson
    public PrepareSwapRequest() {}

    public PrepareSwapRequest(
        String poolId,
        String inputSymbol,
        BigDecimal inputAmount,
        String outputSymbol,
        BigDecimal minOutput,
        Integer maxPriceImpactBps
    ) {
        this.poolId = poolId;
        this.inputSymbol = inputSymbol;
        this.inputAmount = inputAmount;
        this.outputSymbol = outputSymbol;
        this.minOutput = minOutput;
        this.maxPriceImpactBps = maxPriceImpactBps;
    }
}
