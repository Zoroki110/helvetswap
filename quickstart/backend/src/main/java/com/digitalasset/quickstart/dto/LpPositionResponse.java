package com.digitalasset.quickstart.dto;

/**
 * DTO for LP positions (non token-standard).
 */
public class LpPositionResponse {
    public String poolId;
    public String poolCid;
    public String lpBalance;
    public Long shareBps;
    public String reserveA;
    public String reserveB;
    public String updatedAt;

    public LpPositionResponse() {}

    public LpPositionResponse(
            String poolId,
            String poolCid,
            String lpBalance,
            Long shareBps,
            String reserveA,
            String reserveB,
            String updatedAt
    ) {
        this.poolId = poolId;
        this.poolCid = poolCid;
        this.lpBalance = lpBalance;
        this.shareBps = shareBps;
        this.reserveA = reserveA;
        this.reserveB = reserveB;
        this.updatedAt = updatedAt;
    }
}

