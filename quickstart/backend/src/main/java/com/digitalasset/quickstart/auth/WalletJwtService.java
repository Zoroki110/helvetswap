package com.digitalasset.quickstart.auth;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WalletJwtService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletJwtService.class);
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] signingKey;
    private final long ttlSeconds;
    private final String issuer;
    private final ObjectMapper objectMapper;

    public WalletJwtService(
            @Value("${clearportx.auth.jwt-secret}") final String jwtSecret,
            @Value("${clearportx.auth.jwt-ttl-seconds:900}") final long ttlSeconds,
            @Value("${clearportx.auth.jwt-issuer:clearportx-backend}") final String issuer,
            final ObjectMapper objectMapper
    ) {
        this.signingKey = jwtSecret != null ? jwtSecret.getBytes(StandardCharsets.UTF_8) : null;
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : 900L;
        this.issuer = issuer != null && !issuer.isBlank() ? issuer : "clearportx-backend";
        this.objectMapper = objectMapper;
    }

    public Result<String, DomainError> issueToken(final String partyId, final String walletType) {
        if (signingKey == null || signingKey.length == 0) {
            LOGGER.error("JWT signing secret is not configured");
            return Result.err(new UnexpectedError("JWT signing secret is not configured"));
        }
        try {
            String token = buildToken(partyId, walletType);
            return Result.ok(token);
        } catch (NoSuchAlgorithmException | InvalidKeyException | JsonProcessingException e) {
            LOGGER.error("Failed to issue wallet JWT: {}", e.getMessage(), e);
            return Result.err(new UnexpectedError("Failed to issue authentication token"));
        }
    }

    private String buildToken(final String partyId, final String walletType)
            throws NoSuchAlgorithmException, InvalidKeyException, JsonProcessingException {
        Instant now = Instant.now();
        long issuedAt = now.getEpochSecond();
        long expiresAt = issuedAt + ttlSeconds;

        Map<String, Object> header = Map.of(
                "alg", "HS256",
                "typ", "JWT"
        );
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", partyId);
        claims.put("wallet", walletType);
        claims.put("iss", issuer);
        claims.put("iat", issuedAt);
        claims.put("exp", expiresAt);

        String encodedHeader = BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(header));
        String encodedClaims = BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
        String signingInput = encodedHeader + "." + encodedClaims;

        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
        byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = BASE64_URL_ENCODER.encodeToString(signature);

        return signingInput + "." + encodedSignature;
    }
}
