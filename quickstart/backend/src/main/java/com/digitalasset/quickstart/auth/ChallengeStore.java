package com.digitalasset.quickstart.auth;

import com.digitalasset.quickstart.auth.AuthErrors.AuthChallengeExpired;
import com.digitalasset.quickstart.auth.AuthErrors.AuthChallengeNotFound;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class ChallengeStore {

    public record Challenge(String challengeId, String partyId, String challengeValue, long expiresAtMillis, String sourceIp) { }

    private static final long TTL_MILLIS = 2 * 60 * 1000L;
    private static final int CHALLENGE_BYTES = 32;

    private final ConcurrentHashMap<String, Challenge> store = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public Result<Challenge, DomainError> createChallenge(final String partyId, final String sourceIp) {
        Objects.requireNonNull(partyId, "partyId");
        String challengeId = UUID.randomUUID().toString();
        String value = mintChallengeValue();
        long expiresAt = System.currentTimeMillis() + TTL_MILLIS;
        Challenge challenge = new Challenge(challengeId, partyId, value, expiresAt, sourceIp);
        store.put(challengeId, challenge);
        return Result.ok(challenge);
    }

    public Result<Challenge, DomainError> createChallenge(final String partyId) {
        return createChallenge(partyId, null);
    }

    public Result<Challenge, DomainError> get(final String challengeId, final String sourceIp) {
        Challenge challenge = store.get(challengeId);
        if (challenge == null) {
            return Result.err(new AuthChallengeNotFound("Challenge not found"));
        }
        if (isExpired(challenge)) {
            store.remove(challengeId);
            return Result.err(new AuthChallengeExpired("Challenge expired"));
        }
        if (challenge.sourceIp() != null && sourceIp != null && !challenge.sourceIp().equals(sourceIp)) {
            store.remove(challengeId);
            return Result.err(new AuthChallengeNotFound("Challenge not found"));
        }
        return Result.ok(challenge);
    }

    public Result<Challenge, DomainError> get(final String challengeId) {
        return get(challengeId, null);
    }

    public Result<Void, DomainError> delete(final String challengeId) {
        Challenge removed = store.remove(challengeId);
        if (removed == null) {
            return Result.err(new AuthChallengeNotFound("Challenge not found"));
        }
        return Result.ok(null);
    }

    private boolean isExpired(final Challenge challenge) {
        return challenge.expiresAtMillis() <= System.currentTimeMillis();
    }

    private String mintChallengeValue() {
        byte[] bytes = new byte[CHALLENGE_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
