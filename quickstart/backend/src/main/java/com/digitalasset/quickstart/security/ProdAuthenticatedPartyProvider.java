package com.digitalasset.quickstart.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Production implementation of AuthenticatedPartyProvider.
 * Extracts the Canton party ID directly from the JWT "sub" claim,
 * which is set to the full party ID by the wallet auth flow.
 */
@Profile("prod")
@Component
public class ProdAuthenticatedPartyProvider implements AuthenticatedPartyProvider {

    private static final Logger logger = LoggerFactory.getLogger(ProdAuthenticatedPartyProvider.class);

    @Override
    public Optional<String> getParty() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        logger.debug("Authenticated party from JWT sub: {}", subject);
        return Optional.of(subject);
    }

    @Override
    public String getPartyOrFail() {
        return getParty().orElseThrow(() ->
            new IllegalStateException("No authenticated party - JWT sub claim is missing or empty")
        );
    }
}
