package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ValidationError;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared builder for {@link AddLiquidityService.AddLiquidityCommand} so every controller
 * applies the same validation and scaling semantics.
 */
public final class AddLiquidityCommandFactory {

    private AddLiquidityCommandFactory() {
    }

    public static Result<AddLiquidityService.AddLiquidityCommand, DomainError> from(
            final String providerParty,
            final String poolId,
            final BigDecimal amountA,
            final BigDecimal amountB,
            final BigDecimal minLpTokens,
            final String requestId
    ) {
        if (providerParty == null || providerParty.isBlank()) {
            return Result.err(new ValidationError("Provider party is required", ValidationError.Type.AUTHENTICATION));
        }
        if (poolId == null || poolId.isBlank()) {
            return Result.err(new ValidationError("poolId is required", ValidationError.Type.REQUEST));
        }
        if (amountA == null || amountB == null || minLpTokens == null) {
            return Result.err(new ValidationError("Amounts must be provided", ValidationError.Type.REQUEST));
        }
        try {
            BigDecimal scaledA = amountA.setScale(10, RoundingMode.DOWN);
            BigDecimal scaledB = amountB.setScale(10, RoundingMode.DOWN);
            BigDecimal scaledMinLp = minLpTokens.setScale(10, RoundingMode.DOWN);
            return Result.ok(new AddLiquidityService.AddLiquidityCommand(
                    poolId,
                    scaledA,
                    scaledB,
                    scaledMinLp,
                    providerParty,
                    requestId
            ));
        } catch (ArithmeticException ex) {
            return Result.err(new ValidationError("Amounts must support scale=10 precision", ValidationError.Type.REQUEST));
        }
    }
}
