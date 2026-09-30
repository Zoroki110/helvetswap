package com.digitalasset.quickstart.dto;

public class HoldingPoolResponse {
    public String contractId;
    public String poolId;
    public String status;
    public HoldingPoolCreateRequest.InstrumentRef instrumentA;
    public HoldingPoolCreateRequest.InstrumentRef instrumentB;
    public Integer feeBps;
    public String createdAt;
    public String reserveAmountA;
    public String reserveAmountB;
    public String lockedAmountA;
    public String lockedAmountB;
    public String lpSupply;
    public boolean archived;

    public HoldingPoolResponse() {}

    public HoldingPoolResponse(String contractId, String status,
                               HoldingPoolCreateRequest.InstrumentRef instrumentA,
                               HoldingPoolCreateRequest.InstrumentRef instrumentB,
                               Integer feeBps,
                               String createdAt,
                               String reserveAmountA,
                               String reserveAmountB,
                               String lockedAmountA,
                               String lockedAmountB,
                               String lpSupply,
                               boolean archived) {
        this.contractId = contractId;
        this.status = status;
        this.instrumentA = instrumentA;
        this.instrumentB = instrumentB;
        this.feeBps = feeBps;
        this.createdAt = createdAt;
        this.reserveAmountA = reserveAmountA;
        this.reserveAmountB = reserveAmountB;
        this.lockedAmountA = lockedAmountA;
        this.lockedAmountB = lockedAmountB;
        this.lpSupply = lpSupply;
        this.archived = archived;
    }
}
