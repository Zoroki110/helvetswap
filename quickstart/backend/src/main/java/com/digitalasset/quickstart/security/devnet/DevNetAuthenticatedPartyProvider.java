package com.digitalasset.quickstart.security.devnet;

import com.digitalasset.quickstart.security.AuthenticatedPartyProvider;
import com.digitalasset.quickstart.service.PartyRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * DevNet implementation of AuthenticatedPartyProvider.
 * Resolves Canton party from JWT token with multiple fallback strategies:
 * 1) X-Party header override (for testing)
 * 2) "canton_party" claim from JWT
 * 3) "preferred_username" mapped via PartyRegistryService
 * 4) Static fallback from config
 *
 * All party names/IDs are resolved to fully-qualified party IDs via PartyRegistryService.
 */
@Profile("devnet")
@Component
public class DevNetAuthenticatedPartyProvider implements AuthenticatedPartyProvider {

    private static final Logger logger = LoggerFactory.getLogger(DevNetAuthenticatedPartyProvider.class);

    private final PartyRegistryService partyRegistry;

    @Value("${security.canton.static-party:PoolOperator}")
    private String staticParty;

    public DevNetAuthenticatedPartyProvider(PartyRegistryService partyRegistry) {
        this.partyRegistry = partyRegistry;
    }

    @Override
    public Optional<String> getParty() {
        try {
            // 0) Check for X-Party header override (for testing)
            HttpServletRequest request = getCurrentRequest();
            if (request != null) {
                String headerParty = request.getHeader("X-Party");
                if (StringUtils.hasText(headerParty)) {
                    logger.info("X-Party header detected: {}", headerParty);
                    Optional<String> resolved = partyRegistry.resolve(headerParty);
                    if (resolved.isPresent()) {
                        logger.info("Resolved party from X-Party header: {} -> {}", headerParty, resolved.get());
                        return resolved;
                    } else {
                        logger.warn("Failed to resolve X-Party header: {}", headerParty);
                    }
                }
            }

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
                // No JWT authentication - use static party
                Optional<String> resolved = partyRegistry.resolve(staticParty);
                logger.debug("No JWT auth - using static party: {} -> {}", staticParty, resolved.orElse("UNRESOLVED"));
                return resolved;
            }

            // 1) Try explicit canton_party claim
            String cantonParty = asText(jwt.getClaim("canton_party"));
            if (StringUtils.hasText(cantonParty)) {
                Optional<String> resolved = partyRegistry.resolve(cantonParty);
                if (resolved.isPresent()) {
                    logger.debug("Resolved party from canton_party claim: {} -> {}", cantonParty, resolved.get());
                    return resolved;
                }
            }

            // 2) Try mapping from username via registry
            String username = asText(jwt.getClaim("preferred_username"));
            if (!StringUtils.hasText(username)) {
                username = jwt.getSubject();
            }
            if (StringUtils.hasText(username)) {
                Optional<String> resolved = partyRegistry.resolve(username);
                if (resolved.isPresent()) {
                    logger.debug("Resolved party from username: {} -> {}", username, resolved.get());
                    return resolved;
                }
            }

            // 3) Static fallback
            Optional<String> resolved = partyRegistry.resolve(staticParty);
            logger.debug("Using static party fallback: {} -> {}", staticParty, resolved.orElse("UNRESOLVED"));
            return resolved;

        } catch (Exception e) {
            logger.error("Error resolving party", e);
            return partyRegistry.resolve(staticParty);
        }
    }

    @Override
    public String getPartyOrFail() {
        return getParty().orElseThrow(() ->
            new IllegalStateException(
                "Cannot resolve Canton party - no JWT authentication, no canton_party claim, " +
                "no username, and no static fallback configured. " +
                "Set security.canton.static-party in application-devnet.yml"
            )
        );
    }

    private HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String asText(Object o) {
        return (o instanceof String s) ? s : null;
    }
}
