package com.digitalasset.quickstart.dto;

/**
 * Request DTO for devnet swap TI consumption.
 */
public class SwapConsumeRequest {
    public String requestId;
    public Long maxAgeSeconds; // optional fallback window

    public SwapConsumeRequest() {}
}

