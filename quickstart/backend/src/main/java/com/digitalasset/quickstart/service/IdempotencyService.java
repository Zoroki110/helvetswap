// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.constants.SwapConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotency service to prevent duplicate swap executions.
 *
 * Uses in-memory cache with TTL to track processed idempotency keys.
 * In production, replace with Redis or distributed cache.
 */
@Service
public class IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyService.class);

    /**
     * Cache entry for idempotency tracking.
     */
    private static class IdempotencyEntry {
        final String commandId;
        final String transactionId;
        final Instant expiresAt;
        final Object response;

        IdempotencyEntry(String commandId, String transactionId, Object response) {
            this.commandId = commandId;
            this.transactionId = transactionId;
            this.response = response;
            this.expiresAt = Instant.now().plusSeconds(SwapConstants.IDEMPOTENCY_CACHE_DURATION_SECONDS);
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    // In-memory cache: idempotencyKey → entry
    private final Map<String, IdempotencyEntry> cache = new ConcurrentHashMap<>();

    /**
     * Check if idempotency key has been processed before.
     *
     * @param idempotencyKey unique key from client
     * @return cached response if exists, null otherwise
     */
    public Object checkIdempotency(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return null;  // No idempotency key provided
        }

        IdempotencyEntry entry = cache.get(idempotencyKey);

        if (entry == null) {
            return null;  // First request with this key
        }

        if (entry.isExpired()) {
            cache.remove(idempotencyKey);
            logger.debug("Idempotency key expired and removed: {}", idempotencyKey);
            return null;
        }

        logger.info("Idempotent request detected - returning cached response for key: {}", idempotencyKey);
        return entry.response;
    }

    /**
     * Check idempotency with body-hash validation.
     *
     * Same key + same hash → return cached response
     * Same key + different hash → throw IDEMPOTENCY_KEY_BODY_MISMATCH exception
     *
     * @param baseKey base idempotency key (from header)
     * @param bodyHash SHA-256 hash of request body
     * @return cached response if exists with matching hash, null if first request
     * @throws ResponseStatusException if same key with different body hash
     */
    public Object checkIdempotencyWithBodyHash(String baseKey, String bodyHash) {
        if (baseKey == null || baseKey.trim().isEmpty()) {
            return null;  // No idempotency key provided
        }

        // Check if base key exists in cache with ANY body hash
        String fullKey = baseKey + ":" + bodyHash;
        IdempotencyEntry entry = cache.get(fullKey);

        if (entry != null) {
            if (entry.isExpired()) {
                cache.remove(fullKey);
                logger.debug("Idempotency key expired and removed: {}", fullKey);
                return null;
            }
            logger.info("Idempotent request detected - returning cached response for key: {}", fullKey);
            return entry.response;
        }

        // Check if base key exists with DIFFERENT body hash
        for (String cachedKey : cache.keySet()) {
            if (cachedKey.startsWith(baseKey + ":") && !cachedKey.equals(fullKey)) {
                IdempotencyEntry existingEntry = cache.get(cachedKey);
                if (existingEntry != null && !existingEntry.isExpired()) {
                    logger.error("Idempotency key body mismatch - same key with different body: {} vs {}",
                        fullKey, cachedKey);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "IDEMPOTENCY_KEY_BODY_MISMATCH: Same idempotency key used with different request body");
                }
            }
        }

        return null;  // First request with this key
    }

    /**
     * Register successful operation with idempotency key.
     *
     * Never cache 5xx responses.
     *
     * @param idempotencyKey unique key from client
     * @param commandId Canton command ID
     * @param transactionId Canton transaction ID (if available)
     * @param response response to cache
     * @param statusCode HTTP status code
     */
    public void registerSuccess(String idempotencyKey, String commandId, String transactionId, Object response, int statusCode) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return;  // No idempotency tracking without key
        }

        // Never cache 5xx responses
        if (statusCode >= 500) {
            logger.debug("Skipping cache for 5xx response: {} (status={})", idempotencyKey, statusCode);
            return;
        }

        IdempotencyEntry entry = new IdempotencyEntry(commandId, transactionId, response);
        cache.put(idempotencyKey, entry);

        logger.info("Registered idempotency key: {} → commandId: {}, txId: {}, status: {}",
            idempotencyKey, commandId, transactionId != null ? transactionId : "N/A", statusCode);
    }

    /**
     * Backward compatibility - assumes 2xx status.
     */
    public void registerSuccess(String idempotencyKey, String commandId, String transactionId, Object response) {
        registerSuccess(idempotencyKey, commandId, transactionId, response, 200);
    }

    /**
     * Validate idempotency key format.
     *
     * @param idempotencyKey key to validate
     * @throws ResponseStatusException if key is invalid
     */
    public void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return;  // Optional header
        }

        if (idempotencyKey.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Idempotency key cannot be empty");
        }

        if (idempotencyKey.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Idempotency key too long (max 255 characters)");
        }

        // Validate characters (alphanumeric + hyphens + underscores)
        if (!idempotencyKey.matches("^[a-zA-Z0-9\\-_]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Idempotency key contains invalid characters (only alphanumeric, -, _ allowed)");
        }
    }

    /**
     * Clear expired entries from cache (cleanup task).
     */
    public void cleanupExpired() {
        int removed = 0;
        for (Map.Entry<String, IdempotencyEntry> entry : cache.entrySet()) {
            if (entry.getValue().isExpired()) {
                cache.remove(entry.getKey());
                removed++;
            }
        }

        if (removed > 0) {
            logger.info("Cleaned up {} expired idempotency entries", removed);
        }
    }

    /**
     * Get cache size (for monitoring).
     */
    public int getCacheSize() {
        return cache.size();
    }

    /**
     * Clear all cache entries (for testing).
     */
    public void clearCache() {
        cache.clear();
        logger.warn("Idempotency cache cleared");
    }
}
