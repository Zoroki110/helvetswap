package com.digitalasset.quickstart.util;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for working with CompletableFutures
 */
public class Futures {
    private static final Logger logger = LoggerFactory.getLogger(Futures.class);

    /**
     * Retry a future operation with exponential backoff
     */
    public static <T> CompletableFuture<T> retry(
            Supplier<CompletableFuture<T>> supplier,
            int maxAttempts,
            long initialDelayMs) {

        return retryWithAttempt(supplier, maxAttempts, initialDelayMs, 1);
    }

    private static <T> CompletableFuture<T> retryWithAttempt(
            Supplier<CompletableFuture<T>> supplier,
            int maxAttempts,
            long delayMs,
            int attempt) {

        return supplier.get()
            .thenApply(CompletableFuture::completedFuture)
            .exceptionally(error -> {
                if (attempt >= maxAttempts) {
                    logger.warn("Max retry attempts reached ({}), failing", maxAttempts);
                    return CompletableFuture.<T>failedFuture(error);
                }

                logger.info("Attempt {} failed, retrying after {}ms: {}",
                    attempt, delayMs, error.getMessage());

                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.<T>failedFuture(e);
                }

                return retryWithAttempt(supplier, maxAttempts, delayMs * 2, attempt + 1);
            })
            .thenCompose(f -> f);
    }

    /**
     * Attempt an operation and return a Result instead of throwing exceptions
     */
    public static <T> CompletableFuture<Result<T>> attempt(
            Supplier<CompletableFuture<T>> supplier) {

        return supplier.get()
            .thenApply(value -> (Result<T>) Result.ok(value))
            .exceptionally(error -> {
                String message = error.getMessage();
                String code = "UNKNOWN_ERROR";

                // Extract error code from message if present
                if (message != null) {
                    if (message.contains("CONTRACT_NOT_FOUND")) {
                        code = "CONTRACT_NOT_FOUND";
                    } else if (message.contains("SLIPPAGE")) {
                        code = "SLIPPAGE";
                    } else if (message.contains("INSUFFICIENT_FUNDS")) {
                        code = "INSUFFICIENT_FUNDS";
                    } else if (message.contains("Pool not found")) {
                        code = "CONTRACT_NOT_FOUND";
                    }
                }

                return Result.err(code, message != null ? message : error.toString());
            });
    }
}