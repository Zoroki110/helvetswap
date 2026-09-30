// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistributedRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private Clock fixedClock;
    private DistributedRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2025-01-01T12:00:00Z"), ZoneId.of("UTC"));
        when(redis.opsForValue()).thenReturn(valueOps);
        rateLimiter = new DistributedRateLimiter(redis, fixedClock);
    }

    @Test
    void testGlobalRateLimit_allowsUnderLimit() {
        // Simulate empty buckets (under limit)
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.increment(anyString())).thenReturn(1L);

        boolean allowed = rateLimiter.tryAcquire(null, fixedClock.instant());

        assertThat(allowed).isTrue();
        verify(valueOps, atLeastOnce()).increment(startsWith("rl:global:"));
    }

    @Test
    void testGlobalRateLimit_deniesOverLimit() {
        // Simulate 2 requests already in sliding window (at limit)
        when(valueOps.get(startsWith("rl:global:"))).thenReturn("2");

        boolean allowed = rateLimiter.tryAcquire(null, fixedClock.instant());

        assertThat(allowed).isFalse();
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    void testPerPartyRateLimit_deniesOverLimit() {
        // Global OK but party at limit
        when(valueOps.get(startsWith("rl:global:"))).thenReturn(null);
        when(valueOps.get(startsWith("rl:party:trader1:"))).thenReturn("10");

        boolean allowed = rateLimiter.tryAcquire("trader1", fixedClock.instant());

        assertThat(allowed).isFalse();
    }

    @Test
    void testRetryAfterSeconds_returnsCorrectValue() {
        // At global limit
        when(valueOps.get(startsWith("rl:global:"))).thenReturn("2");

        int retryAfter = rateLimiter.getRetryAfterSeconds(null, fixedClock.instant());

        assertThat(retryAfter).isEqualTo(5); // Wait for 5-second window to clear
    }

    @Test
    void testRetryAfterSeconds_returnsMax() {
        // Both limits hit
        when(valueOps.get(startsWith("rl:global:"))).thenReturn("2");
        when(valueOps.get(startsWith("rl:party:trader1:"))).thenReturn("10");

        int retryAfter = rateLimiter.getRetryAfterSeconds("trader1", fixedClock.instant());

        assertThat(retryAfter).isEqualTo(60); // Max of 5s and 60s
    }
}
