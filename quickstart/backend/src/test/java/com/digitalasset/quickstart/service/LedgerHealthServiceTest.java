// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.pqs.Pqs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerHealthServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private Pqs pqs;

    @Mock
    private LedgerReader ledgerReader;

    private LedgerHealthService healthService;

    @BeforeEach
    void setUp() {
        healthService = new LedgerHealthService(jdbcTemplate, pqs, ledgerReader);
    }

    @Test
    void testHealthStatus_completesUnder200ms() {
        // Mock fast PQS response
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(1000L);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(java.util.List.of("clearportx-amm"));

        long start = System.currentTimeMillis();
        CompletableFuture<Map<String, Object>> future = healthService.getHealthStatus();
        Map<String, Object> health = future.join();
        long duration = System.currentTimeMillis() - start;

        assertThat(duration).isLessThan(200);
        assertThat(health).containsKey("status");
    }

    @Test
    void testHealthStatus_omitsPqsOffsetOnTimeout() {
        // Mock slow PQS query that will timeout
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
            .thenAnswer(invocation -> {
                Thread.sleep(300); // Exceeds 200ms timeout
                return 1000L;
            });

        Map<String, Object> health = healthService.getHealthStatus().join();

        // Should complete without pqsOffset field
        assertThat(health).doesNotContainKey("pqsOffset");
        assertThat(health).containsKey("status");
    }

    @Test
    void testHealthStatus_returnsEnvironmentAndVersion() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(java.util.List.of("clearportx-amm"));

        Map<String, Object> health = healthService.getHealthStatus().join();

        assertThat(health).containsKeys("environment", "applicationVersion");
    }
}
