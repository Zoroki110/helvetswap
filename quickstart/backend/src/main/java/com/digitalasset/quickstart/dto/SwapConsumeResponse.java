package com.digitalasset.quickstart.dto;

/**
 * Response DTO for devnet swap TI consumption.
 */
public class SwapConsumeResponse {
    public String requestId;
    public String inboundTiCid;
    public String intentCid;
    public String swapIntentCid;
    public String poolCid;
    public String direction;
    public String amountIn;
    public String amountOut;
    public String minOut;
    public String executeSwapLedgerUpdateId;
    public String executeSwapStatus;
    public String payoutCid;
    public String payoutExecuteBefore;
    public String payoutFactoryId;
    public Integer payoutDisclosedContractsCount;
    public String payoutStatus;
    public String nextAction;
    public String receiverParty;

    public SwapConsumeResponse() {}
}

