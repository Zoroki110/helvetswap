// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

/**
 * Pool DTO for frontend consumption
 * Represents a liquidity pool with token info, reserves, LP supply, and fee
 */
public class PoolDTO {
    public final String poolId;
    public final TokenInfoDTO tokenA;
    public final TokenInfoDTO tokenB;
    public final String reserveA;
    public final String reserveB;
    public final String totalLPSupply;
    public final String feeRate;
    public final String volume24h;  // NEW: 24h trading volume

    // For backward compatibility with old API calls
    @Deprecated
    public final String symbolA;
    @Deprecated
    public final String symbolB;

    public PoolDTO(String poolId, TokenInfoDTO tokenA, TokenInfoDTO tokenB,
                   String reserveA, String reserveB,
                   String totalLPSupply, String feeRate, String volume24h) {
        this.poolId = poolId;
        this.tokenA = tokenA;
        this.tokenB = tokenB;
        this.reserveA = reserveA;
        this.reserveB = reserveB;
        this.totalLPSupply = totalLPSupply;
        this.feeRate = feeRate;
        this.volume24h = volume24h;
        // Backward compatibility
        this.symbolA = tokenA.symbol;
        this.symbolB = tokenB.symbol;
    }

    // Constructor with default volume24h for backward compatibility
    public PoolDTO(String poolId, TokenInfoDTO tokenA, TokenInfoDTO tokenB,
                   String reserveA, String reserveB,
                   String totalLPSupply, String feeRate) {
        this(poolId, tokenA, tokenB, reserveA, reserveB, totalLPSupply, feeRate, "0");
    }

    /**
     * Nested DTO for token information
     */
    public static class TokenInfoDTO {
        public final String symbol;
        public final String name;
        public final int decimals;

        public TokenInfoDTO(String symbol, String name, int decimals) {
            this.symbol = symbol;
            this.name = name;
            this.decimals = decimals;
        }
    }
}
