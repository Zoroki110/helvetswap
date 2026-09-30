// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * PartyGuard - Authorization guard for method-level security
 *
 * Verifies JWT subject matches required party roles for mutation endpoints
 */
@Component("partyGuard")
public class PartyGuard {
    private static final Logger logger = LoggerFactory.getLogger(PartyGuard.class);
    private final AuthUtils authUtils;

    public PartyGuard(AuthUtils authUtils) {
        this.authUtils = authUtils;
    }

    /**
     * Check if JWT subject is the liquidity provider (app_provider for now)
     */
    public boolean isLiquidityProvider(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            logger.warn("isLiquidityProvider: JWT or subject is null");
            return false;
        }

        String subject = jwt.getSubject();
        String appProviderParty = authUtils.getAppProviderPartyId();

        boolean isAllowed = subject.equals(appProviderParty);
        if (!isAllowed) {
            logger.warn("isLiquidityProvider denied: subject={}, required={}", subject, appProviderParty);
        }

        return isAllowed;
    }

    /**
     * Check if JWT subject is the pool party (app_provider for now)
     */
    public boolean isPoolParty(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            logger.warn("isPoolParty: JWT or subject is null");
            return false;
        }

        String subject = jwt.getSubject();
        String appProviderParty = authUtils.getAppProviderPartyId();

        boolean isAllowed = subject.equals(appProviderParty);
        if (!isAllowed) {
            logger.warn("isPoolParty denied: subject={}, required={}", subject, appProviderParty);
        }

        return isAllowed;
    }

    /**
     * Check if JWT subject is authenticated (any valid party)
     */
    public boolean isAuthenticated(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            logger.warn("isAuthenticated: JWT or subject is null");
            return false;
        }

        return true;
    }
}
