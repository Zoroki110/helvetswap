// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

/**
 * Token DTO for frontend consumption
 * Represents a fungible token with symbol, name, amount, and owner
 */
public class TokenDTO {
    public final String symbol;
    public final String name;
    public final int decimals;
    public final String amount;
    public final String owner;

    public TokenDTO(String symbol, String name, int decimals, String amount, String owner) {
        this.symbol = symbol;
        this.name = name;
        this.decimals = decimals;
        this.amount = amount;
        this.owner = owner;
    }
}
