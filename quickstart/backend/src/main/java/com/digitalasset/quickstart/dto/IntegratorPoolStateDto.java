package com.digitalasset.quickstart.dto;

public record IntegratorPoolStateDto(
        String poolId,
        String status,
        String instrumentASymbol,
        String instrumentBSymbol,
        Integer feeBps,
        String reserveAmountA,
        String reserveAmountB,
        String lpSupply,
        String poolCid
) {
    public static IntegratorPoolStateDto fromFull(HoldingPoolResponse full) {
        String symbolA = full.instrumentA != null ? full.instrumentA.id : null;
        String symbolB = full.instrumentB != null ? full.instrumentB.id : null;
        return new IntegratorPoolStateDto(
                full.poolId,
                full.status,
                symbolA,
                symbolB,
                full.feeBps,
                full.reserveAmountA,
                full.reserveAmountB,
                full.lpSupply,
                full.contractId
        );
    }
}
