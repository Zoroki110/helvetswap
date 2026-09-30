// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.utility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Utility for polling PQS with exponential backoff until data is available.
 *
 * This is a temporary reliability layer to handle PQS eventual consistency.
 * The north star is to migrate to Ledger/JSON API for authoritative reads.
 */
public class PqsSyncUtil {

    private static final Logger logger = LoggerFactory.getLogger(PqsSyncUtil.class);

    /**
     * Poll PQS until a condition is met, with exponential backoff and jitter.
     *
     * @param fetch Function that attempts to fetch data from PQS
     * @param good Predicate that checks if the data is acceptable
     * @param maxWait Maximum time to wait before timing out
     * @param description Description of what we're waiting for (for logging)
     * @return The fetched data
     * @throws TimeoutException if PQS doesn't sync within maxWait
     * @throws InterruptedException if thread is interrupted while waiting
     */
    public static <T> T pollPqsUntil(
            Supplier<Optional<T>> fetch,
            Predicate<T> good,
            Duration maxWait,
            String description
    ) throws TimeoutException, InterruptedException {
        long startMs = System.currentTimeMillis();
        long delayMs = 250;  // Start at 250ms
        long deadline = System.nanoTime() + maxWait.toNanos();
        int attempt = 0;

        logger.info("Polling PQS for: {} (max wait: {})", description, maxWait);

        while (System.nanoTime() < deadline) {
            attempt++;
            Optional<T> maybe = fetch.get();

            if (maybe.isPresent() && good.test(maybe.get())) {
                long totalWaitMs = System.currentTimeMillis() - startMs;
                logger.info("✅ PQS sync complete for: {} (attempts: {}, wait_ms: {})",
                        description, attempt, totalWaitMs);

                // Emit metrics for observability
                logMetrics("pqs_wait_success", description, attempt, totalWaitMs);
                return maybe.get();
            }

            // Calculate sleep time with exponential backoff + jitter
            long jitter = ThreadLocalRandom.current().nextLong(0, 250);
            long sleepMs = Math.min(delayMs, 2000) + jitter;  // Cap at 2s + jitter

            logger.debug("PQS not ready for: {}, waiting {}ms (attempt: {})",
                    description, sleepMs, attempt);

            Thread.sleep(sleepMs);
            delayMs = Math.min(delayMs * 2, 2000);  // Exponential backoff, capped at 2s
        }

        long totalWaitMs = System.currentTimeMillis() - startMs;
        String errorMsg = String.format("❌ PQS timeout for: %s (attempts: %d, wait_ms: %d, max_wait: %s)",
                description, attempt, totalWaitMs, maxWait);
        logger.error(errorMsg);

        // Emit timeout metric
        logMetrics("pqs_wait_timeout", description, attempt, totalWaitMs);

        throw new TimeoutException(errorMsg);
    }

    /**
     * Log metrics for observability (Grafana/Prometheus).
     * Format: pqs_wait_metric{description="...", attempts=N, wait_ms=N}
     */
    private static void logMetrics(String metric, String description, int attempts, long waitMs) {
        logger.info("METRIC: {}{{description=\"{}\", attempts={}, wait_ms={}}}",
                metric, description, attempts, waitMs);
    }

    /**
     * Poll PQS with default timeout of 30 seconds.
     */
    public static <T> T pollPqsUntil(
            Supplier<Optional<T>> fetch,
            Predicate<T> good,
            String description
    ) throws TimeoutException, InterruptedException {
        return pollPqsUntil(fetch, good, Duration.ofSeconds(30), description);
    }
}
