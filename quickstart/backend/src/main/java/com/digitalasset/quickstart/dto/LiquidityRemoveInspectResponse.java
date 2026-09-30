package com.digitalasset.quickstart.dto;

/**
 * Inspect response for remove-liquidity (devnet).
 */
public class LiquidityRemoveInspectResponse {
    public String requestId;
    public String poolCid;
    public String poolId;
    public String poolStatus;
    public HoldingPoolCreateRequest.InstrumentRef instrumentA;
    public HoldingPoolCreateRequest.InstrumentRef instrumentB;
    public String reserveA;
    public String reserveB;
    public String lpSupply;
    public String lpCid;
    public String lpOwner;
    public String lpBalance;
    public String lpBurnAmount;
    public String shareBps;
    public String outAmountA;
    public String outAmountB;
    public boolean alreadyConsumed;

    public LiquidityRemoveInspectResponse() {}
}

