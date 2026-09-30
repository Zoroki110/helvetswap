package com.digitalasset.quickstart.dto;

/**
 * Response DTO for devnet remove-liquidity consumption.
 */
public class LiquidityRemoveConsumeResponse {
    public String requestId;
    public String poolCid;
    public String lpCid;
    public String receiverParty;
    public String lpBurnAmount;
    public String outAmountA;
    public String outAmountB;
    public String payoutStatusA;
    public String payoutStatusB;
    public String payoutCidA;
    public String payoutCidB;
    public String payoutFactoryIdA;
    public String payoutFactoryIdB;
    public String newReserveA;
    public String newReserveB;
    public String ledgerUpdateId;
    public String executeStatus;

    public LiquidityRemoveConsumeResponse() {}
}

