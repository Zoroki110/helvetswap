package com.digitalasset.quickstart.security;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtAuthService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] signingKey;
    private final String expectedIssuer;
    private final ObjectMapper objectMapper;

    public JwtAuthService(
            @Value("${clearportx.auth.jwt-secret}") final String jwtSecret,
            @Value("${clearportx.auth.jwt-issuer:clearportx-backend}") final String issuer,
            final ObjectMapper objectMapper
    ) {
        this.signingKey = jwtSecret != null ? jwtSecret.getBytes(StandardCharsets.UTF_8) : new byte[0];
        this.expectedIssuer = issuer;
        this.objectMapper = objectMapper;
    }

    public Result<AuthenticatedUser, DomainError> authenticate(final String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return missingToken();
        }
        if (!authorizationHeader.startsWith("Bearer ")) {
            return missingToken();
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return missingToken();
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return invalidToken("Invalid JWT format");
        }

        try {
            byte[] payloadBytes = decode(parts[1]);
            byte[] signatureBytes = decode(parts[2]);

            byte[] computedSignature = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(signatureBytes, computedSignature)) {
                return invalidToken("Invalid JWT signature");
            }

            Map<String, Object> payload = objectMapper.readValue(payloadBytes, new TypeReference<>() { });
            String issuer = stringValue(payload.get("iss"));
            if (expectedIssuer != null && !expectedIssuer.isBlank() && issuer != null && !issuer.equals(expectedIssuer)) {
                return invalidToken("JWT issuer mismatch");
            }

            String partyId = stringValue(payload.get("sub"));
            if (partyId == null || partyId.isBlank()) {
                return invalidToken("JWT missing subject");
            }

            String walletType = stringValue(payload.getOrDefault("wallet", "unknown"));
            Instant issuedAt = instantValue(payload.get("iat"));
            Instant expiresAt = instantValue(payload.get("exp"));

            if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
                return invalidToken("JWT expired");
            }

            return Result.ok(new AuthenticatedUser(partyId, walletType, issuedAt, expiresAt));
        } catch (Exception ex) {
            return invalidToken("Unable to parse JWT: " + ex.getMessage());
        }
    }

    private byte[] decode(final String section) {
        return Base64.getUrlDecoder().decode(section);
    }

    private byte[] sign(final String data) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private Result<AuthenticatedUser, DomainError> missingToken() {
        return Result.err(new ValidationError("Authorization token required", ValidationError.Type.AUTHENTICATION));
    }

    private Result<AuthenticatedUser, DomainError> invalidToken(final String message) {
        return Result.err(new ValidationError(message, ValidationError.Type.AUTHENTICATION));
    }

    private String stringValue(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        return String.valueOf(value);
    }

    private Instant instantValue(final Object value) {
        if (value == null) {
            return null;
        }
        long epochSeconds;
        if (value instanceof Number number) {
            epochSeconds = number.longValue();
        } else {
            try {
                epochSeconds = Long.parseLong(value.toString());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return Instant.ofEpochSecond(epochSeconds);
    }

    public record AuthenticatedUser(String partyId, String walletType, Instant issuedAt, Instant expiresAt) { }
}
