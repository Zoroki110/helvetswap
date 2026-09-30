// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * Strong idempotency key with SHA-256 body hash.
 *
 * Format: {method}:{path}:{party}:{bodyHash}
 *
 * Rules:
 * - Same key + same hash → return cached response
 * - Same key + different hash → 400 IDEMPOTENCY_KEY_BODY_MISMATCH
 * - Never cache 5xx responses
 */
public record IdempotencyKey(String method, String path, String party, String bodyHash) {

    /**
     * Create idempotency key from request.
     *
     * @param req HTTP request
     * @param party Canton party ID (from JWT)
     * @param body Request body bytes
     * @return Idempotency key
     */
    public static IdempotencyKey of(HttpServletRequest req, String party, byte[] body) {
        String bodyHash = Hex.encodeHexString(DigestUtils.sha256(body));
        return new IdempotencyKey(
            req.getMethod(),
            req.getRequestURI(),
            party != null ? party : "unauthenticated",
            bodyHash
        );
    }

    /**
     * Convert to cache key string.
     */
    public String toCacheKey() {
        return method + ":" + path + ":" + party + ":" + bodyHash;
    }

    @Override
    public String toString() {
        return toCacheKey();
    }
}
