// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

/**
 * AddLiquidityResponse - Response from adding liquidity
 */
public class AddLiquidityResponse {
    public final String lpTokenCid;
    public final String newPoolCid;
    public final String reserveA;
    public final String reserveB;
    public final String lpAmount;

    public AddLiquidityResponse(String lpTokenCid, String newPoolCid, String reserveA, String reserveB, String lpAmount) {
        this.lpTokenCid = lpTokenCid;
        this.newPoolCid = newPoolCid;
        this.reserveA = reserveA;
        this.reserveB = reserveB;
        this.lpAmount = lpAmount;
    }

    public String lpTokenCid() {
        return lpTokenCid;
    }

    public String newPoolCid() {
        return newPoolCid;
    }

    public String reserveA() {
        return reserveA;
    }

    public String reserveB() {
        return reserveB;
    }

    public String lpAmount() {
        return lpAmount;
    }
}
