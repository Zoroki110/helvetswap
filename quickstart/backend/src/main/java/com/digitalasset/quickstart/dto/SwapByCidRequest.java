package com.digitalasset.quickstart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SwapByCidRequest {
    private String poolCid;
    private String poolId;
    private String inputSymbol;
    private String outputSymbol;
    private String amountIn;
    private String minOutput;

    public String getPoolCid() {
        return poolCid;
    }

    public void setPoolCid(String poolCid) {
        this.poolCid = poolCid;
    }

    public String getPoolId() {
        return poolId;
    }

    public void setPoolId(String poolId) {
        this.poolId = poolId;
    }

    public String getInputSymbol() {
        return inputSymbol;
    }

    public void setInputSymbol(String inputSymbol) {
        this.inputSymbol = inputSymbol;
    }

    public String getOutputSymbol() {
        return outputSymbol;
    }

    public void setOutputSymbol(String outputSymbol) {
        this.outputSymbol = outputSymbol;
    }

    public String getAmountIn() {
        return amountIn;
    }

    public void setAmountIn(String amountIn) {
        this.amountIn = amountIn;
    }

    public String getMinOutput() {
        return minOutput;
    }

    public void setMinOutput(String minOutput) {
        this.minOutput = minOutput;
    }
}

