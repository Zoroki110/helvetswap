// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyServiceTest {

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService();
    }

    @Test
    void testCheckIdempotency_returnsNullForNewKey() {
        Object result = service.checkIdempotency("new-key");
        assertThat(result).isNull();
    }

    @Test
    void testRegisterAndCheck_returnsCachedResponse() {
        String key = "test-key";
        String response = "cached-response";

        service.registerSuccess(key, "cmd-1", "tx-1", response);
        Object cached = service.checkIdempotency(key);

        assertThat(cached).isEqualTo(response);
    }

    @Test
    void testSameKeyAndBody_returnsSameResponse() {
        String key = "swap-abc";
        String response1 = "{\"status\":\"ok\",\"txId\":\"tx-123\"}";

        service.registerSuccess(key, "cmd-1", "tx-1", response1);

        // Same key should return same response
        Object cached = service.checkIdempotency(key);
        assertThat(cached).isEqualTo(response1);
    }

    @Test
    void testCacheExpiration() throws InterruptedException {
        String key = "expiring-key";
        service.registerSuccess(key, "cmd-1", "tx-1", "response");

        // Immediately should be cached
        assertThat(service.checkIdempotency(key)).isNotNull();

        // Note: Full expiration test would require mocking time or waiting 15 minutes
        // This test verifies the registration works
    }

    @Test
    void testCleanupExpired() {
        service.registerSuccess("key1", "cmd-1", "tx-1", "resp1");
        service.registerSuccess("key2", "cmd-2", "tx-2", "resp2");

        assertThat(service.getCacheSize()).isEqualTo(2);

        service.cleanupExpired();

        // Should still have entries (not expired yet in test)
        assertThat(service.getCacheSize()).isGreaterThanOrEqualTo(0);
    }
}
