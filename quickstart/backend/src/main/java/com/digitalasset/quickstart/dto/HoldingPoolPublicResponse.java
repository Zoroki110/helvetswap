package com.digitalasset.quickstart.dto;

public record HoldingPoolPublicResponse(
        String poolId,
        String status,
        String instrumentASymbol,
        String instrumentBSymbol,
        Integer feeBps,
        String reserveAmountA,
        String reserveAmountB,
        String lpSupply
) {
    public static HoldingPoolPublicResponse fromFull(HoldingPoolResponse full) {
        String symbolA = full.instrumentA != null ? full.instrumentA.id : null;
        String symbolB = full.instrumentB != null ? full.instrumentB.id : null;
        return new HoldingPoolPublicResponse(
                full.poolId,
                full.status,
                symbolA,
                symbolB,
                full.feeBps,
                full.reserveAmountA,
                full.reserveAmountB,
                full.lpSupply
        );
    }
}
