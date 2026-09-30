package com.digitalasset.quickstart.dto;

/**
 * Request DTO for devnet remove-liquidity consumption.
 */
public class LiquidityRemoveConsumeRequest {
    public String requestId;
    public String poolCid;
    public String lpCid;
    public String receiverParty;
    public String lpBurnAmount;
    public String minOutA;
    public String minOutB;
    public String deadlineIso;

    public LiquidityRemoveConsumeRequest() {}
}

