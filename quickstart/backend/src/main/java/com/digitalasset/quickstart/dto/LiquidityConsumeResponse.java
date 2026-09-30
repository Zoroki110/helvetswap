package com.digitalasset.quickstart.dto;

/**
 * Response DTO for devnet liquidity TI consumption.
 */
public class LiquidityConsumeResponse {
    public String requestId;
    public String poolCid;
    public String newPoolCid;
    public String providerParty;
    public String lpOwnerParty;
    public String tiCidA;
    public String tiCidB;
    public String amountA;
    public String amountB;
    public String lpMinted;
    public String newReserveA;
    public String newReserveB;
    public String ledgerUpdateId;
    public String executeStatus;

    public LiquidityConsumeResponse() {}
}

