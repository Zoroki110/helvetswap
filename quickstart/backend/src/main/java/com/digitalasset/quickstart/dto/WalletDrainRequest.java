package com.digitalasset.quickstart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WalletDrainRequest {
    private String party;
    private List<String> symbols;
    private String floorAmount;
    private Boolean archive;

    public String getParty() {
        return party;
    }

    public void setParty(String party) {
        this.party = party;
    }

    public List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols;
    }

    public String getFloorAmount() {
        return floorAmount;
    }

    public void setFloorAmount(String floorAmount) {
        this.floorAmount = floorAmount;
    }

    public Boolean getArchive() {
        return archive;
    }

    public void setArchive(Boolean archive) {
        this.archive = archive;
    }
}

