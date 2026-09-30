// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * PartyMappingService - Maps OAuth2 JWT subjects to Canton party IDs
 *
 * This service resolves the mismatch between:
 * - JWT subject (OAuth2 user ID): "1a36eb86-4ccc-4ec6-b7b7-caa08b354989"
 * - Canton party ID: "app_provider_quickstart-root-1::1220..."
 */
@Service
public class PartyMappingService {
    private static final Logger logger = LoggerFactory.getLogger(PartyMappingService.class);

    private final AuthUtils authUtils;

    public PartyMappingService(AuthUtils authUtils) {
        this.authUtils = authUtils;
    }

    /**
     * Map JWT subject to Canton party ID
     *
     * For now, all JWT subjects map to the app_provider party.
     * In production, this would look up the mapping from a database or config.
     *
     * @param jwtSubject OAuth2 user ID from JWT
     * @return Canton party ID
     */
    public String mapJwtSubjectToParty(String jwtSubject) {
        // For the app-provider backend service account, always return app_provider party
        String appProviderParty = authUtils.getAppProviderPartyId();

        if (appProviderParty != null && !appProviderParty.isEmpty()) {
            logger.debug("Mapped JWT subject {} to Canton party {}", jwtSubject, appProviderParty);
            return appProviderParty;
        }

        // Fallback: return JWT subject as-is (won't work but better than null)
        logger.warn("APP_PROVIDER_PARTY not configured, using JWT subject as party: {}", jwtSubject);
        return jwtSubject;
    }
}
