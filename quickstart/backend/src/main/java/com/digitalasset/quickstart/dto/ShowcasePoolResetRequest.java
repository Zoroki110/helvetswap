package com.digitalasset.quickstart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ShowcasePoolResetRequest {
    private String poolId;
    private String providerParty;
    private String ccAmount;
    private String cbtcAmount;
    private String pricePerCbtc;
    private Long maxInBps;
    private Long maxOutBps;
    private Boolean archiveExisting;

    public String getPoolId() {
        return poolId;
    }

    public void setPoolId(String poolId) {
        this.poolId = poolId;
    }

    public String getProviderParty() {
        return providerParty;
    }

    public void setProviderParty(String providerParty) {
        this.providerParty = providerParty;
    }

    public String getCcAmount() {
        return ccAmount;
    }

    public void setCcAmount(String ccAmount) {
        this.ccAmount = ccAmount;
    }

    public String getCbtcAmount() {
        return cbtcAmount;
    }

    public void setCbtcAmount(String cbtcAmount) {
        this.cbtcAmount = cbtcAmount;
    }

    public String getPricePerCbtc() {
        return pricePerCbtc;
    }

    public void setPricePerCbtc(String pricePerCbtc) {
        this.pricePerCbtc = pricePerCbtc;
    }

    public Long getMaxInBps() {
        return maxInBps;
    }

    public void setMaxInBps(Long maxInBps) {
        this.maxInBps = maxInBps;
    }

    public Long getMaxOutBps() {
        return maxOutBps;
    }

    public void setMaxOutBps(Long maxOutBps) {
        this.maxOutBps = maxOutBps;
    }

    public Boolean getArchiveExisting() {
        return archiveExisting;
    }

    public void setArchiveExisting(Boolean archiveExisting) {
        this.archiveExisting = archiveExisting;
    }
}

