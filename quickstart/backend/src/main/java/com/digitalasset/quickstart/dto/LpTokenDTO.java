package com.digitalasset.quickstart.dto;

/**
 * DTO representing an LP token position owned by a party.
 */
public class LpTokenDTO {
    public final String poolId;
    public final String amount;
    public final String contractId;
    public final String owner;

    public LpTokenDTO(String poolId, String amount, String contractId, String owner) {
        this.poolId = poolId;
        this.amount = amount;
        this.contractId = contractId;
        this.owner = owner;
    }
}
