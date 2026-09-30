package com.digitalasset.quickstart.security.devnet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DevNet Authentication Service
 *
 * Provides simple header-based authentication for development and testing.
 * Maps usernames from X-User header to full Canton party IDs.
 *
 * Security model:
 * - X-User header contains username (alice, bob, app-provider)
 * - Service resolves username to full party ID via configuration
 * - X-Party header can override for debugging (takes precedence)
 *
 * This is suitable for DevNet testing but should NOT be used in production.
 * Production should use OAuth2/JWT with proper token validation.
 */
@Service
@Profile("devnet")
@ConfigurationProperties(prefix = "clearportx.auth")
public class DevNetAuthService {

    private static final Logger logger = LoggerFactory.getLogger(DevNetAuthService.class);

    private String mode = "header"; // header or oauth
    private Map<String, String> userPartyMap = new HashMap<>();

    public void setMode(String mode) {
        this.mode = mode;
        logger.info("DevNet auth mode set to: {}", mode);
    }

    public void setUserPartyMap(Map<String, String> userPartyMap) {
        this.userPartyMap = userPartyMap;
        logger.info("Loaded user-party mappings for {} users", userPartyMap.size());
        userPartyMap.forEach((user, party) ->
            logger.debug("  {} -> {}", user, party)
        );
    }

    public String getMode() {
        return mode;
    }

    /**
     * Resolve a username to its full Canton party ID
     *
     * @param username Username from X-User header (e.g., "alice", "bob")
     * @return Full party ID if mapping exists
     */
    public Optional<String> resolvePartyId(String username) {
        if (username == null || username.isBlank()) {
            logger.warn("Cannot resolve party: username is null or blank");
            return Optional.empty();
        }

        String partyId = userPartyMap.get(username.toLowerCase());
        if (partyId == null) {
            logger.warn("No party mapping found for username: {}", username);
            logger.debug("Available users: {}", userPartyMap.keySet());
            return Optional.empty();
        }

        logger.debug("Resolved username '{}' to party: {}", username, partyId);
        return Optional.of(partyId);
    }

    /**
     * Get all available usernames
     */
    public Map<String, String> getAllMappings() {
        return new HashMap<>(userPartyMap);
    }

    /**
     * Check if a username exists in the mapping
     */
    public boolean hasUser(String username) {
        return username != null && userPartyMap.containsKey(username.toLowerCase());
    }
}
