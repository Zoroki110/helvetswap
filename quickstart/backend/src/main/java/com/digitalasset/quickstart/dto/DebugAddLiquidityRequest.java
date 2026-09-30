package com.digitalasset.quickstart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DebugAddLiquidityRequest {

    private String poolId;
    private String poolCid;
    private String amountA;
    private String amountB;
    private String minLPTokens;

    public String getPoolId() {
        return poolId;
    }

    public void setPoolId(String poolId) {
        this.poolId = poolId;
    }

    public String getPoolCid() {
        return poolCid;
    }

    public void setPoolCid(String poolCid) {
        this.poolCid = poolCid;
    }

    public String getAmountA() {
        return amountA;
    }

    public void setAmountA(String amountA) {
        this.amountA = amountA;
    }

    public String getAmountB() {
        return amountB;
    }

    public void setAmountB(String amountB) {
        this.amountB = amountB;
    }

    public String getMinLPTokens() {
        return minLPTokens;
    }

    public void setMinLPTokens(String minLPTokens) {
        this.minLPTokens = minLPTokens;
    }
}
