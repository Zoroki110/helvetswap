package com.digitalasset.quickstart.dto;

/**
 * Request DTO for devnet liquidity TI consumption.
 */
public class LiquidityConsumeRequest {
    public String requestId;
    public String poolCid;
    public Long maxAgeSeconds; // optional fallback window

    public LiquidityConsumeRequest() {}
}

