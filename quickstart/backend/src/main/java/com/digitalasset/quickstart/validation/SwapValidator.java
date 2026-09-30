// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.validation;

import com.digitalasset.quickstart.constants.SwapConstants;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Input validator for swap operations with hardened caps and security checks.
 */
@Component
public class SwapValidator {

    /**
     * Validate swap input amount.
     *
     * @throws ResponseStatusException if amount is invalid
     */
    public void validateInputAmount(BigDecimal inputAmount) {
        if (inputAmount == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "inputAmount is required");
        }

        if (inputAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "inputAmount must be positive, got: " + inputAmount);
        }

        if (inputAmount.compareTo(SwapConstants.MIN_SWAP_AMOUNT) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "inputAmount too small (dust attack prevention): " + inputAmount +
                " < minimum " + SwapConstants.MIN_SWAP_AMOUNT);
        }
    }

    /**
     * Validate min output amount.
     *
     * @throws ResponseStatusException if amount is invalid
     */
    public void validateMinOutput(BigDecimal minOutput) {
        if (minOutput == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "minOutput is required");
        }

        if (minOutput.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "minOutput cannot be negative, got: " + minOutput);
        }
    }

    /**
     * Validate max price impact with hardened cap (max 10% = 1000 bps).
     *
     * @throws ResponseStatusException if price impact is invalid
     */
    public void validateMaxPriceImpact(Integer maxPriceImpactBps) {
        if (maxPriceImpactBps == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "maxPriceImpactBps is required");
        }

        if (maxPriceImpactBps < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "maxPriceImpactBps cannot be negative, got: " + maxPriceImpactBps);
        }

        if (maxPriceImpactBps > SwapConstants.MAX_PRICE_IMPACT_BPS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "maxPriceImpactBps exceeds maximum allowed: " + maxPriceImpactBps +
                " > " + SwapConstants.MAX_PRICE_IMPACT_BPS + " bps (10%)");
        }

        if (maxPriceImpactBps > SwapConstants.BPS_100_PERCENT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "maxPriceImpactBps cannot exceed 100%: " + maxPriceImpactBps +
                " > " + SwapConstants.BPS_100_PERCENT + " bps");
        }
    }

    /**
     * Validate and cap deadline to maximum 60 minutes.
     *
     * @param deadlineSeconds requested deadline in seconds
     * @return capped deadline (max 60 minutes)
     */
    public long validateAndCapDeadline(long deadlineSeconds) {
        if (deadlineSeconds <= 0) {
            return SwapConstants.DEFAULT_DEADLINE_SECONDS;
        }

        if (deadlineSeconds > SwapConstants.MAX_DEADLINE_SECONDS) {
            // Cap to maximum instead of rejecting
            return SwapConstants.MAX_DEADLINE_SECONDS;
        }

        return deadlineSeconds;
    }

    /**
     * Calculate deadline instant with capped duration.
     *
     * @param requestedSeconds requested deadline in seconds (0 = default)
     * @return deadline instant (max 60 minutes from now)
     */
    public Instant calculateDeadline(long requestedSeconds) {
        long cappedSeconds = validateAndCapDeadline(requestedSeconds);
        return Instant.now().plusSeconds(cappedSeconds);
    }

    /**
     * Validate token symbols are different.
     *
     * @throws ResponseStatusException if symbols are the same
     */
    public void validateTokenPair(String inputSymbol, String outputSymbol) {
        if (inputSymbol == null || inputSymbol.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "inputSymbol is required");
        }

        if (outputSymbol == null || outputSymbol.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "outputSymbol is required");
        }

        if (inputSymbol.equals(outputSymbol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "inputSymbol and outputSymbol must be different, both are: " + inputSymbol);
        }
    }

    /**
     * Validate swap amount relative to pool reserves (max 50% of reserve).
     *
     * @throws ResponseStatusException if swap would drain pool
     */
    public void validateSwapRatio(BigDecimal inputAmount, BigDecimal poolReserve) {
        if (poolReserve == null || poolReserve.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Pool has no liquidity");
        }

        BigDecimal swapRatio = inputAmount.divide(poolReserve, SwapConstants.SCALE, java.math.RoundingMode.HALF_UP);

        if (swapRatio.compareTo(SwapConstants.MAX_SWAP_RATIO) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Swap amount too large: " + inputAmount +
                " exceeds 50% of pool reserve (" + poolReserve + ")");
        }
    }

    /**
     * Validate deadline has not expired.
     *
     * @throws ResponseStatusException if deadline has passed
     */
    public void validateDeadlineNotExpired(Instant deadline) {
        if (deadline == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "deadline is required");
        }

        Instant now = Instant.now();
        if (now.isAfter(deadline)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Swap deadline has expired: " + deadline +
                " (now: " + now + ")");
        }
    }

    /**
     * Validate actual output meets minimum requirement.
     *
     * @throws ResponseStatusException if output is below minimum
     */
    public void validateMinOutputMet(BigDecimal actualOutput, BigDecimal minOutput) {
        if (actualOutput.compareTo(minOutput) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Actual output (" + actualOutput +
                ") is less than minimum required (" + minOutput + ")");
        }
    }

    /**
     * Validate price impact is within user's tolerance.
     *
     * @throws ResponseStatusException if price impact exceeds limit
     */
    public void validatePriceImpact(int actualImpactBps, int maxAllowedBps) {
        if (actualImpactBps > maxAllowedBps) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Price impact (" + actualImpactBps +
                " bps) exceeds maximum allowed (" + maxAllowedBps + " bps)");
        }
    }
}
