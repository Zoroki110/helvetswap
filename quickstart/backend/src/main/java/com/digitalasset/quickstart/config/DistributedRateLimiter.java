// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Distributed rate limiter using Redis sliding window algorithm.
 *
 * CLUSTER-SAFE: Works across multiple pods/replicas.
 *
 * Algorithm:
 * - Global: 2 requests per 5 seconds (0.4 TPS) - sliding window across 5 buckets
 * - Per-party: 10 requests per minute (0.167 TPS) - sliding window across 60 buckets
 *
 * Redis keys:
 * - rl:global:{epoch_second} → counter, TTL 5s
 * - rl:party:{party}:{epoch_second} → counter, TTL 60s
 *
 * Retry-After calculation:
 * - Time until next available bucket (max of global/party)
 *
 * Configuration:
 * - rate-limiter.distributed=true (use Redis)
 * - rate-limiter.distributed=false (use local in-memory, NOT cluster-safe)
 *
 * Requirements:
 * - Redis 6+ with connection configured via spring.data.redis.url
 */
@Component
@ConditionalOnProperty(name = "rate-limiter.distributed", havingValue = "true")
public class DistributedRateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(DistributedRateLimiter.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    // Rate limits
    private final int globalMaxPer5s = 2;     // 0.4 TPS = 2 requests per 5 seconds
    private final int partyMaxPerMin = 10;    // 10 requests per minute per party

    public DistributedRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
        logger.info("✓ Distributed rate limiter initialized (Redis): global=2/5s, party=10/min");
        logger.info("✓ Cluster-safe rate limiting enabled");
    }

    /**
     * Try to acquire permission for a request.
     *
     * @param party Canton party ID (null for unauthenticated requests)
     * @param now Current timestamp
     * @return true if request allowed, false if rate limited
     */
    public boolean tryAcquire(String party, Instant now) {
        long epochSecond = now.getEpochSecond();

        // Check global rate limit (sliding window: sum of last 5 seconds)
        long globalCount = 0;
        for (int i = 0; i < 5; i++) {
            globalCount += getCounter("rl:global:" + (epochSecond - i));
        }

        if (globalCount >= globalMaxPer5s) {
            logger.debug("Global rate limit exceeded: {} >= {}", globalCount, globalMaxPer5s);
            return false;
        }

        // Check per-party rate limit (sliding window: sum of last 60 seconds)
        if (party != null) {
            long partyCount = 0;
            for (int i = 0; i < 60; i++) {
                partyCount += getCounter("rl:party:" + party + ":" + (epochSecond - i));
            }

            if (partyCount >= partyMaxPerMin) {
                logger.debug("Per-party rate limit exceeded for {}: {} >= {}", party, partyCount, partyMaxPerMin);
                return false;
            }
        }

        // Consume tokens (increment counters)
        incrementCounter("rl:global:" + epochSecond, 5);
        if (party != null) {
            incrementCounter("rl:party:" + party + ":" + epochSecond, 60);
        }

        return true;
    }

    /**
     * Calculate Retry-After duration in seconds.
     *
     * Returns time until next available bucket (max of global/party limits).
     *
     * @param party Canton party ID (null for unauthenticated)
     * @param now Current timestamp
     * @return seconds to wait before retrying (0 if can proceed immediately)
     */
    public int getRetryAfterSeconds(String party, Instant now) {
        long epochSecond = now.getEpochSecond();

        // Calculate global wait time
        int globalWaitSeconds = 0;
        long globalCount = 0;
        for (int i = 0; i < 5; i++) {
            globalCount += getCounter("rl:global:" + (epochSecond - i));
        }
        if (globalCount >= globalMaxPer5s) {
            // Need to wait until oldest bucket expires
            globalWaitSeconds = 5;  // Wait for full window to clear
        }

        // Calculate per-party wait time
        int partyWaitSeconds = 0;
        if (party != null) {
            long partyCount = 0;
            for (int i = 0; i < 60; i++) {
                partyCount += getCounter("rl:party:" + party + ":" + (epochSecond - i));
            }
            if (partyCount >= partyMaxPerMin) {
                // Need to wait until oldest bucket expires
                partyWaitSeconds = 60;  // Wait for full window to clear
            }
        }

        // Return maximum wait time
        return Math.max(globalWaitSeconds, partyWaitSeconds);
    }

    /**
     * Get counter value from Redis.
     *
     * @param key Redis key
     * @return counter value (0 if key doesn't exist)
     */
    private long getCounter(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value != null ? Long.parseLong(value) : 0;
        } catch (Exception ex) {
            logger.warn("Failed to read Redis counter {}: {}", key, ex.getMessage());
            return 0;
        }
    }

    /**
     * Increment counter in Redis with TTL.
     *
     * @param key Redis key
     * @param ttlSeconds TTL in seconds
     */
    private void incrementCounter(String key, int ttlSeconds) {
        try {
            Long newValue = redis.opsForValue().increment(key);
            // Set TTL only on first increment (when value is 1)
            if (newValue != null && newValue == 1L) {
                redis.expire(key, Duration.ofSeconds(ttlSeconds));
            }
        } catch (Exception ex) {
            logger.error("Failed to increment Redis counter {}: {}", key, ex.getMessage());
        }
    }

    /**
     * Get rate limit configuration.
     */
    public int getGlobalMaxPer5s() {
        return globalMaxPer5s;
    }

    public int getPartyMaxPerMin() {
        return partyMaxPerMin;
    }
}
