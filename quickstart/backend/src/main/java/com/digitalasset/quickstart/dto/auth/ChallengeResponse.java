package com.digitalasset.quickstart.dto.auth;

import java.time.Instant;

public record ChallengeResponse(String challengeId, String challenge, Instant expiresAt) { }
