// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart;

import com.digitalasset.quickstart.security.AuthenticatedPartyProvider;
import com.digitalasset.quickstart.security.Auth;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Optional;

/**
 * Test configuration that provides mock beans for dependencies
 * that are not needed in tests but are required by Spring autowiring.
 */
@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public AuthenticatedPartyProvider mockAuthenticatedPartyProvider() {
        // Return a stub implementation for testing
        return new AuthenticatedPartyProvider() {
            @Override
            public Optional<String> getParty() {
                return Optional.of("test-party");
            }

            @Override
            public String getPartyOrFail() {
                return "test-party";
            }
        };
    }

    @Bean
    @Primary
    public Auth mockAuth() {
        // Return SHARED_SECRET for test environment
        return Auth.SHARED_SECRET;
    }
}
