// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * AddLiquidityRequest - Request to add liquidity to a pool
 */
public class AddLiquidityRequest {
    // Optional client-stable id for retry idempotency + activity-marker dedup.
    // Matches the pattern in LiquidityConsumeRequest / LiquidityRemoveConsumeRequest /
    // SwapConsumeRequest. When null/blank the server falls back to a generated UUID at
    // the call site (LiquidityController) so legacy clients keep working.
    public String requestId;

    @NotBlank(message = "poolId is required")
    public String poolId;

    @NotNull(message = "amountA is required")
    @DecimalMin(value = "0.0000000001", message = "amountA must be at least 0.0000000001")
    public BigDecimal amountA;

    @NotNull(message = "amountB is required")
    @DecimalMin(value = "0.0000000001", message = "amountB must be at least 0.0000000001")
    public BigDecimal amountB;

    @NotNull(message = "minLPTokens is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "minLPTokens must be non-negative")
    public BigDecimal minLPTokens;

    // Default constructor for Jackson
    public AddLiquidityRequest() {}

    public AddLiquidityRequest(String poolId, BigDecimal amountA, BigDecimal amountB, BigDecimal minLPTokens) {
        this.poolId = poolId;
        this.amountA = amountA;
        this.amountB = amountB;
        this.minLPTokens = minLPTokens;
    }
}
