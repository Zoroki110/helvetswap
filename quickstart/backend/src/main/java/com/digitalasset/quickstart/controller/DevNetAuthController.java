package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.security.devnet.DevNetAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Authentication Controller for DevNet
 *
 * Provides endpoints to verify authentication and party resolution.
 * This helps debug authentication issues and validate user-to-party mappings.
 */
@RestController
@RequestMapping("/api")
@Profile("devnet")
@CrossOrigin(origins = "*")  // Allow all origins for DevNet testing
public class DevNetAuthController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetAuthController.class);

    @Autowired
    private DevNetAuthService authService;

    /**
     * GET /api/whoami
     *
     * Returns information about the currently authenticated user.
     * Reads X-User or X-Party headers to determine the party.
     *
     * Headers:
     * - X-User: Username (alice, bob, app-provider) - resolved via config
     * - X-Party: Full party ID - direct override (for debugging)
     *
     * Response:
     * {
     *   "authenticated": true,
     *   "username": "alice",
     *   "partyId": "Alice-9cefe94d::1220...",
     *   "mode": "header"
     * }
     */
    @GetMapping("/whoami")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> whoami(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User", required = false) String username,
            @RequestHeader(value = "X-Party", required = false) String partyOverride
    ) {
        Map<String, Object> response = new HashMap<>();
        response.put("mode", authService.getMode());

        // Priority 1: X-Party header (direct override for debugging)
        if (partyOverride != null && !partyOverride.isBlank()) {
            logger.info("Using X-Party header override: {}", partyOverride);
            response.put("authenticated", true);
            response.put("partyId", partyOverride);
            response.put("source", "X-Party header (override)");
            return ResponseEntity.ok(response);
        }

        // Priority 2: X-User header (username resolved via config)
        if (username != null && !username.isBlank()) {
            var partyId = authService.resolvePartyId(username);
            if (partyId.isPresent()) {
                logger.info("Resolved X-User '{}' to party: {}", username, partyId.get());
                response.put("authenticated", true);
                response.put("username", username);
                response.put("partyId", partyId.get());
                response.put("source", "X-User header (mapped)");
                return ResponseEntity.ok(response);
            } else {
                logger.warn("X-User '{}' not found in user-party map", username);
                response.put("authenticated", false);
                response.put("error", "Username not found in mapping");
                response.put("username", username);
                response.put("availableUsers", authService.getAllMappings().keySet());
                return ResponseEntity.status(401).body(response);
            }
        }

        // No authentication headers provided
        logger.warn("/api/whoami called without X-User or X-Party headers");
        response.put("authenticated", false);
        response.put("error", "No X-User or X-Party header provided");
        response.put("hint", "Add 'X-User: alice' or 'X-Party: <full-party-id>' header");
        response.put("availableUsers", authService.getAllMappings().keySet());
        return ResponseEntity.status(401).body(response);
    }

    /**
     * GET /api/auth/users
     *
     * Returns the list of available users and their party mappings.
     * Useful for debugging and verifying configuration.
     */
    @GetMapping("/auth/users")
    @PreAuthorize("@partyGuard.isPoolParty(#jwt)")
    public ResponseEntity<Map<String, Object>> listUsers(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> response = new HashMap<>();
        response.put("mode", authService.getMode());
        response.put("users", authService.getAllMappings());
        response.put("count", authService.getAllMappings().size());
        return ResponseEntity.ok(response);
    }
}
