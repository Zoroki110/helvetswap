package com.digitalasset.quickstart.auth;

import com.digitalasset.quickstart.common.DomainError;

public final class AuthErrors {

    private AuthErrors() {}

    public static final class AuthChallengeNotFound extends DomainError {
        public AuthChallengeNotFound(final String details) {
            super("AUTH_CHALLENGE_NOT_FOUND", details, 404);
        }
    }

    public static final class AuthChallengeExpired extends DomainError {
        public AuthChallengeExpired(final String details) {
            super("AUTH_CHALLENGE_EXPIRED", details, 410);
        }
    }

    public static final class AuthInvalidSignature extends DomainError {
        public AuthInvalidSignature(final String details) {
            super("AUTH_INVALID_SIGNATURE", details, 401);
        }
    }
}
