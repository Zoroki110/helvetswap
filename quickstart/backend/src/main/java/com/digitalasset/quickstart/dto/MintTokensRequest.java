package com.digitalasset.quickstart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MintTokensRequest {
    private String issuerParty;
    private String ownerParty;
    private String symbolA;
    private String amountA;
    private String symbolB;
    private String amountB;

    public String getIssuerParty() {
        return issuerParty;
    }

    public void setIssuerParty(String issuerParty) {
        this.issuerParty = issuerParty;
    }

    public String getOwnerParty() {
        return ownerParty;
    }

    public void setOwnerParty(String ownerParty) {
        this.ownerParty = ownerParty;
    }

    public String getSymbolA() {
        return symbolA;
    }

    public void setSymbolA(String symbolA) {
        this.symbolA = symbolA;
    }

    public String getAmountA() {
        return amountA;
    }

    public void setAmountA(String amountA) {
        this.amountA = amountA;
    }

    public String getSymbolB() {
        return symbolB;
    }

    public void setSymbolB(String symbolB) {
        this.symbolB = symbolB;
    }

    public String getAmountB() {
        return amountB;
    }

    public void setAmountB(String amountB) {
        this.amountB = amountB;
    }
}

