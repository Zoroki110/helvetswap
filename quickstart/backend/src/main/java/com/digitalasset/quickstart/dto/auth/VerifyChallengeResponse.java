package com.digitalasset.quickstart.dto.auth;

public record VerifyChallengeResponse(
        String token,
        String partyId
) { }

