// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

/**
 * ExecuteSwapResponse - Response from executing a swap
 */
public class ExecuteSwapResponse {
    public final String receiptCid;
    public final String trader;
    public final String inputSymbol;
    public final String outputSymbol;
    public final String inputAmount;
    public final String outputAmount;
    public final String executionTime;

    public ExecuteSwapResponse(
        String receiptCid,
        String trader,
        String inputSymbol,
        String outputSymbol,
        String inputAmount,
        String outputAmount,
        String executionTime
    ) {
        this.receiptCid = receiptCid;
        this.trader = trader;
        this.inputSymbol = inputSymbol;
        this.outputSymbol = outputSymbol;
        this.inputAmount = inputAmount;
        this.outputAmount = outputAmount;
        this.executionTime = executionTime;
    }
}
