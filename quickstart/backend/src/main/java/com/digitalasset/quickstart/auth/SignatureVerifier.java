package com.digitalasset.quickstart.auth;

import com.digitalasset.quickstart.auth.AuthErrors.AuthInvalidSignature;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SignatureVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(SignatureVerifier.class);

    /**
     * Verify that {@code signatureBase64} is a valid Ed25519 signature of {@code message}
     * produced by the private key corresponding to {@code publicKeyBase64}.
     *
     * Canton wallets use Ed25519 key pairs. The public key and signature are
     * expected as URL-safe or standard Base64-encoded bytes.
     */
    public Result<Void, DomainError> verify(
            final String publicKeyBase64,
            final String signatureBase64,
            final String message) {
        if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            return Result.err(new AuthInvalidSignature("Public key is required for signature verification"));
        }
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return Result.err(new AuthInvalidSignature("Signature is required"));
        }
        if (message == null || message.isBlank()) {
            return Result.err(new AuthInvalidSignature("Challenge message is required"));
        }

        try {
            byte[] publicKeyBytes = decodeFlexible(publicKeyBase64);
            byte[] signatureBytes = decodeFlexible(signatureBase64);
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

            // Ed25519 public keys are 32 bytes raw. If 32 bytes, wrap in X509 structure.
            // If longer, assume already X509-encoded.
            PublicKey publicKey;
            if (publicKeyBytes.length == 32) {
                publicKey = rawEd25519ToPublicKey(publicKeyBytes);
            } else {
                KeyFactory kf = KeyFactory.getInstance("Ed25519");
                publicKey = kf.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            }

            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(messageBytes);

            if (!sig.verify(signatureBytes)) {
                LOGGER.warn("Signature verification failed for provided public key");
                return Result.err(new AuthInvalidSignature("Invalid wallet signature"));
            }

            return Result.ok(null);
        } catch (Exception e) {
            LOGGER.warn("Signature verification error: {}", e.getMessage());
            return Result.err(new AuthInvalidSignature("Invalid wallet signature"));
        }
    }

    private static PublicKey rawEd25519ToPublicKey(byte[] raw) throws Exception {
        // Ed25519 X.509 SubjectPublicKeyInfo prefix for a 32-byte key
        byte[] x509Prefix = new byte[] {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        };
        byte[] x509Key = new byte[x509Prefix.length + raw.length];
        System.arraycopy(x509Prefix, 0, x509Key, 0, x509Prefix.length);
        System.arraycopy(raw, 0, x509Key, x509Prefix.length, raw.length);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePublic(new X509EncodedKeySpec(x509Key));
    }

    /**
     * Decode a string that may be hex or Base64 (standard or URL-safe).
     */
    private static byte[] decodeFlexible(String encoded) {
        String cleaned = encoded.trim();
        // If it looks like hex (only hex chars, even length), decode as hex
        if (cleaned.matches("^[0-9a-fA-F]+$") && cleaned.length() % 2 == 0) {
            return hexToBytes(cleaned);
        }
        // Otherwise try Base64 (URL-safe first, then standard)
        try {
            return Base64.getUrlDecoder().decode(cleaned);
        } catch (IllegalArgumentException e) {
            return Base64.getDecoder().decode(cleaned);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
