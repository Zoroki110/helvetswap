package com.digitalasset.quickstart.util;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * AMM math helpers shared across services.
 */
public final class AmmMath {

    private AmmMath() {
    }

    public static BigDecimal estimateLpMint(
            final BigDecimal amountA,
            final BigDecimal amountB,
            final BigDecimal reserveA,
            final BigDecimal reserveB,
            final BigDecimal totalLpSupply
    ) {
        BigDecimal safeAmountA = amountA != null ? amountA : BigDecimal.ZERO;
        BigDecimal safeAmountB = amountB != null ? amountB : BigDecimal.ZERO;
        if (safeAmountA.compareTo(BigDecimal.ZERO) <= 0 || safeAmountB.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        boolean bootstrap = isZero(totalLpSupply) || isZero(reserveA) || isZero(reserveB);
        if (bootstrap) {
            BigDecimal product = safeAmountA.multiply(safeAmountB, MathContext.DECIMAL64);
            if (product.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            double sqrt = Math.sqrt(product.doubleValue());
            return BigDecimal.valueOf(sqrt);
        }

        BigDecimal shareA = safeAmountA.multiply(totalLpSupply, MathContext.DECIMAL64)
                .divide(reserveA, MathContext.DECIMAL64);
        BigDecimal shareB = safeAmountB.multiply(totalLpSupply, MathContext.DECIMAL64)
                .divide(reserveB, MathContext.DECIMAL64);
        return shareA.min(shareB).max(BigDecimal.ZERO);
    }

    private static boolean isZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0;
    }
}
