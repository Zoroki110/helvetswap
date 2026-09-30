// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * StaleAcsRetry - Retry wrapper for CONTRACT_NOT_FOUND errors
 *
 * Automatically refreshes ACS snapshot once and retries on CONTRACT_NOT_FOUND.
 * This handles race conditions where contracts are archived between validation and execution.
 *
 * Pattern: Try → Refresh ACS → Retry → Fail with 409
 */
public final class StaleAcsRetry {
    private static final Logger logger = LoggerFactory.getLogger(StaleAcsRetry.class);

    private StaleAcsRetry() {
        // Utility class - no instances
    }

    /**
     * Run an operation with automatic retry on CONTRACT_NOT_FOUND
     *
     * @param operation The operation to execute (e.g., ledger.exerciseAndGetResult)
     * @param refreshAcs Callback to refresh ACS snapshot at ledger end
     * @param context Description for logging (e.g., "AddLiquidity")
     * @param <T> Result type
     * @return CompletableFuture with operation result
     */
    public static <T> CompletableFuture<T> run(
        Supplier<CompletableFuture<T>> operation,
        Runnable refreshAcs,
        String context
    ) {
        return operation.get()
            .exceptionallyCompose(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                String errorMessage = cause.getMessage();

                // Check if this is a CONTRACT_NOT_FOUND error
                if (errorMessage != null && errorMessage.contains("CONTRACT_NOT_FOUND")) {
                    logger.warn("{}: CONTRACT_NOT_FOUND detected, refreshing ACS and retrying once", context);

                    // Refresh ACS snapshot
                    try {
                        refreshAcs.run();
                    } catch (Exception refreshEx) {
                        logger.error("{}: Failed to refresh ACS: {}", context, refreshEx.getMessage());
                        // Continue with retry anyway
                    }

                    // Retry once
                    return operation.get()
                        .exceptionallyCompose(ex2 -> {
                            Throwable cause2 = ex2.getCause() != null ? ex2.getCause() : ex2;
                            String errorMessage2 = cause2.getMessage();

                            if (errorMessage2 != null && errorMessage2.contains("CONTRACT_NOT_FOUND")) {
                                logger.error("{}: CONTRACT_NOT_FOUND after retry - contract definitively stale", context);
                                // Surface as 409 CONFLICT to client
                                return CompletableFuture.failedFuture(
                                    new ContractNotFoundException(context + ": Contract changed; refresh and retry")
                                );
                            }

                            // Different error on retry - propagate it
                            return CompletableFuture.failedFuture(ex2);
                        });
                }

                // Not a CONTRACT_NOT_FOUND error - propagate original exception
                return CompletableFuture.failedFuture(ex);
            });
    }

    /**
     * Custom exception for CONTRACT_NOT_FOUND after retry
     * Controllers should map this to HTTP 409 CONFLICT
     */
    public static class ContractNotFoundException extends RuntimeException {
        public ContractNotFoundException(String message) {
            super(message);
        }
    }
}
