// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

/**
 * AtomicSwapResponse - Response from atomic swap execution
 */
public class AtomicSwapResponse {
    public final String outputTokenCid;
    public final String trader;
    public final String inputSymbol;
    public final String outputSymbol;
    public final String inputAmount;
    public final String outputAmount;
    public final String executionTime;

    public AtomicSwapResponse(
        String outputTokenCid,
        String trader,
        String inputSymbol,
        String outputSymbol,
        String inputAmount,
        String outputAmount,
        String executionTime
    ) {
        this.outputTokenCid = outputTokenCid;
        this.trader = trader;
        this.inputSymbol = inputSymbol;
        this.outputSymbol = outputSymbol;
        this.inputAmount = inputAmount;
        this.outputAmount = outputAmount;
        this.executionTime = executionTime;
    }
}
