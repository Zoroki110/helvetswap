// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

/**
 * PrepareSwapResponse - Response from preparing a swap
 */
public class PrepareSwapResponse {
    public final String swapReadyCid;
    public final String poolInputTokenCid;
    public final String inputSymbol;
    public final String outputSymbol;
    public final String inputAmount;
    public final String minOutput;

    public PrepareSwapResponse(
        String swapReadyCid,
        String poolInputTokenCid,
        String inputSymbol,
        String outputSymbol,
        String inputAmount,
        String minOutput
    ) {
        this.swapReadyCid = swapReadyCid;
        this.poolInputTokenCid = poolInputTokenCid;
        this.inputSymbol = inputSymbol;
        this.outputSymbol = outputSymbol;
        this.inputAmount = inputAmount;
        this.minOutput = minOutput;
    }
}
