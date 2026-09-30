// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.config;

import com.digitalasset.quickstart.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token bucket rate limiter for Canton Network traffic compliance.
 *
 * Traffic requirement: 0.5 TPS (transactions per second) GLOBAL across all nodes
 * Implementation: 0.4 TPS global limit (safely under requirement)
 *
 * ⚠️  CRITICAL LIMITATION - NOT CLUSTER-SAFE:
 * This in-memory rate limiter is LOCAL to a single JVM instance.
 *
 * Buckets:
 * 1. Global: 1 request per 2.5 seconds (0.4 TPS) across all parties (write endpoints)
 * 2. Per-party: 10 requests per minute (write endpoints)
 * 3. Per-IP: 30 RPM for public endpoints, 10 RPM for auth endpoints
 * 4. Per-requestId: 60 RPM for /api/swap/inspect (Stage G hotfix)
 *
 * RFC X-RateLimit-* headers emitted on every rate-limited response (Stage G).
 *
 * Returns HTTP 429 with Retry-After header when rate limit exceeded.
 */
@Configuration
@ConditionalOnProperty(name = "rate-limiter.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimiterConfig implements WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiterConfig.class);

    @Value("${rate-limiter.global-tps:0.4}")
    private double globalTps;

    @Value("${rate-limiter.per-party-rpm:10}")
    private int perPartyRpm;

    @Value("${rate-limiter.public-rpm-per-ip:30}")
    private int publicRpmPerIp;

    @Value("${rate-limiter.auth-rpm-per-ip:10}")
    private int authRpmPerIp;

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(Clock clock,
                                                     @Autowired(required = false) DistributedRateLimiter distributedLimiter) {
        long globalIntervalMs = (long) (1000.0 / globalTps);
        long perPartyIntervalMs = (long) (60000.0 / perPartyRpm);

        if (distributedLimiter != null) {
            logger.info("✓ Using DISTRIBUTED rate limiter (cluster-safe via Redis)");
        } else {
            logger.warn("⚠️  Rate limiter initialized: global={} TPS ({}ms), per-party={} RPM ({}ms)",
                globalTps, globalIntervalMs, perPartyRpm, perPartyIntervalMs);
            logger.warn("⚠️  WARNING: This rate limiter is LOCAL to this JVM instance only!");
            logger.warn("⚠️  For multi-pod deployments, set rate-limiter.distributed=true and configure Redis");
        }

        long publicIntervalMs = (long) (60000.0 / publicRpmPerIp);
        long authIntervalMs = (long) (60000.0 / authRpmPerIp);
        logger.warn("Per-requestId rate limiter initialized: rpm={}, refillIntervalMs={}, cache TTL={}min, max-size={}",
            RateLimitInterceptor.PER_REQUEST_ID_RPM,
            RateLimitInterceptor.PER_REQUEST_ID_REFILL_MS,
            RateLimitInterceptor.PER_REQUEST_ID_TTL_MS / 60000,
            RateLimitInterceptor.PER_REQUEST_ID_MAX_SIZE);
        return new RateLimitInterceptor(globalIntervalMs, perPartyIntervalMs, publicIntervalMs, authIntervalMs, globalTps, perPartyRpm, clock, distributedLimiter);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor(clock(), null));
    }

    /**
     * Interceptor that applies rate limiting to swap endpoints.
     */
    public class RateLimitInterceptor implements HandlerInterceptor {

        // ── Per-requestId bucket constants (Stage G) ──
        static final int PER_REQUEST_ID_RPM = 60;
        static final long PER_REQUEST_ID_REFILL_MS = 1000L;            // 1 token per 1000ms = 60 RPM
        static final long PER_REQUEST_ID_TTL_MS = 5L * 60L * 1000L;    // 5 min after last access
        static final int PER_REQUEST_ID_MAX_SIZE = 10_000;
        static final long CLEANUP_INTERVAL_S = 60L;

        private final long globalIntervalMs;
        private final long perPartyIntervalMs;
        private final long publicIntervalMs;
        private final long authIntervalMs;
        private final double globalTps;
        private final int perPartyRpm;
        private final Clock clock;
        private final DistributedRateLimiter distributedLimiter;

        // Global rate limiter state (local fallback)
        private final AtomicLong lastGlobalRequest = new AtomicLong(0);

        // Per-party rate limiter state (local fallback)
        private final Map<String, AtomicLong> partyLastRequest = new ConcurrentHashMap<>();

        // Per-IP rate limiter state for public and auth endpoints
        private final Map<String, AtomicLong> ipLastRequest = new ConcurrentHashMap<>();

        // Per-requestId token buckets (Stage G — for /api/swap/inspect)
        private final ConcurrentHashMap<String, TokenBucketEntry> requestIdBuckets = new ConcurrentHashMap<>();

        // Single-thread daemon executor for periodic TTL cleanup of requestIdBuckets
        private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-cleanup");
            t.setDaemon(true);
            return t;
        });

        public RateLimitInterceptor(long globalIntervalMs, long perPartyIntervalMs, long publicIntervalMs, long authIntervalMs, double globalTps, int perPartyRpm, Clock clock, DistributedRateLimiter distributedLimiter) {
            this.globalIntervalMs = globalIntervalMs;
            this.perPartyIntervalMs = perPartyIntervalMs;
            this.publicIntervalMs = publicIntervalMs;
            this.authIntervalMs = authIntervalMs;
            this.globalTps = globalTps;
            this.perPartyRpm = perPartyRpm;
            this.clock = clock;
            this.distributedLimiter = distributedLimiter;
        }

        @PostConstruct
        public void startCleanup() {
            cleanupExecutor.scheduleAtFixedRate(this::evictStaleBuckets,
                    CLEANUP_INTERVAL_S, CLEANUP_INTERVAL_S, TimeUnit.SECONDS);
            logger.info("rate-limit-cleanup scheduler started: interval={}s, ttl={}min, max-size={}",
                    CLEANUP_INTERVAL_S, PER_REQUEST_ID_TTL_MS / 60000, PER_REQUEST_ID_MAX_SIZE);
        }

        @PreDestroy
        public void stopCleanup() {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("rate-limit-cleanup scheduler stopped");
        }

        private void evictStaleBuckets() {
            long now = clock.millis();
            int sizeBefore = requestIdBuckets.size();
            int evicted = 0;
            Iterator<Map.Entry<String, TokenBucketEntry>> it = requestIdBuckets.entrySet().iterator();
            while (it.hasNext()) {
                TokenBucketEntry entry = it.next().getValue();
                if (now - entry.lastAccessMs.get() > PER_REQUEST_ID_TTL_MS) {
                    it.remove();
                    evicted++;
                }
            }
            if (evicted > 0) {
                logger.debug("rate-limit-cleanup: evicted {} stale requestId buckets ({} -> {})",
                        evicted, sizeBefore, requestIdBuckets.size());
            }
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            // Skip rate limiting on async re-dispatch (CompletableFuture completion)
            if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
                return true;
            }

            String path = request.getRequestURI();

            // Per-requestId rate limiting for /api/swap/inspect (Stage G)
            if (isPerRequestIdEndpoint(path)) {
                return handlePerRequestIdRateLimit(request, response, path);
            }

            // IP-based rate limiting for public and auth endpoints
            if (isPublicEndpoint(path) || isAuthEndpoint(path)) {
                String clientIp = resolveClientIp(request);
                long intervalMs = isAuthEndpoint(path) ? authIntervalMs : publicIntervalMs;
                int capacity = isAuthEndpoint(path) ? authRpmPerIp : publicRpmPerIp;
                String key = clientIp + ":" + (isAuthEndpoint(path) ? "auth" : "public");
                long now = clock.millis();
                long waitTime = getIpRateLimitWaitTime(key, now, intervalMs);
                if (waitTime > 0) {
                    int retryAfterSeconds = (int) Math.ceil(waitTime / 1000.0);
                    logger.warn("IP rate limit exceeded for {} {} from {}", request.getMethod(), path, clientIp);
                    setRateLimitHeaders(response, capacity, 0, (now + waitTime) / 1000);
                    writeRateLimitResponse(response, path, request, retryAfterSeconds);
                    return false;
                }
                setRateLimitHeaders(response, capacity, capacity - 1, (now + intervalMs) / 1000);
                return true;
            }

            // Party-based rate limiting for write operations
            if (!isRateLimitedEndpoint(path)) {
                return true;
            }

            String party = extractParty(request);

            // Use distributed limiter if available, otherwise fall back to local
            if (distributedLimiter != null) {
                return handleDistributedRateLimit(request, response, path, party);
            } else {
                return handleLocalRateLimit(request, response, path, party);
            }
        }

        /**
         * Stage G: per-requestId token bucket on /api/swap/inspect.
         * Each unique requestId gets its own 60 RPM bucket. Two wallets watching
         * two distinct swaps get two independent buckets — no NAT-stacking issue.
         */
        private boolean handlePerRequestIdRateLimit(HttpServletRequest request, HttpServletResponse response, String path) throws Exception {
            String requestId = request.getParameter("requestId");
            if (requestId == null || requestId.isBlank()) {
                // Defensive fallback: malformed inspect calls (no requestId) get rate-limited
                // via the legacy per-party/global path so bad clients still get throttled.
                return handleLocalRateLimit(request, response, path, extractParty(request));
            }

            long now = clock.millis();

            // Cache management: if at max-size and bucket is new, run sync eviction pass
            if (!requestIdBuckets.containsKey(requestId) && requestIdBuckets.size() >= PER_REQUEST_ID_MAX_SIZE) {
                evictStaleBuckets();
                if (requestIdBuckets.size() >= PER_REQUEST_ID_MAX_SIZE) {
                    logger.warn("per-requestId cache full ({} entries) — rejecting new requestId={}",
                            requestIdBuckets.size(), requestId);
                    setRateLimitHeaders(response, PER_REQUEST_ID_RPM, 0, (now + 5000L) / 1000);
                    writeRateLimitResponse(response, path, request, 5);
                    return false;
                }
            }

            TokenBucketEntry bucket = requestIdBuckets.computeIfAbsent(requestId,
                    k -> new TokenBucketEntry(PER_REQUEST_ID_RPM, PER_REQUEST_ID_REFILL_MS, now));

            boolean acquired = bucket.tryAcquire(now);
            int remaining = bucket.remaining();
            long resetEpoch = bucket.resetEpochSeconds(now);

            if (acquired) {
                setRateLimitHeaders(response, PER_REQUEST_ID_RPM, remaining, resetEpoch);
                return true;
            }
            // 429: bucket exhausted for this requestId
            int retryAfterSeconds = Math.max(1, (int) Math.ceil(PER_REQUEST_ID_REFILL_MS / 1000.0));
            logger.warn("Per-requestId rate limit exceeded for {} {} requestId={}",
                    request.getMethod(), path, requestId);
            setRateLimitHeaders(response, PER_REQUEST_ID_RPM, 0, resetEpoch);
            writeRateLimitResponse(response, path, request, retryAfterSeconds);
            return false;
        }

        /**
         * Stage G: emit RFC-style rate-limit headers on EVERY rate-limited response.
         * - X-RateLimit-Limit: bucket capacity
         * - X-RateLimit-Remaining: tokens remaining (0 on 429)
         * - X-RateLimit-Reset: epoch seconds when bucket fully refills
         */
        private static void setRateLimitHeaders(HttpServletResponse response, int capacity, int remaining, long resetEpochSeconds) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, remaining)));
            response.setHeader("X-RateLimit-Reset", String.valueOf(resetEpochSeconds));
        }

        private static String resolveClientIp(HttpServletRequest request) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }

        private long getIpRateLimitWaitTime(String key, long now, long intervalMs) {
            AtomicLong lastRequest = ipLastRequest.computeIfAbsent(key, k -> new AtomicLong(0));
            long lastRequestTime = lastRequest.get();
            long timeSinceLastRequest = now - lastRequestTime;
            logger.debug("Rate-limit check: key={}, now={}, last={}, since={}ms, interval={}ms",
                    key, now, lastRequestTime, timeSinceLastRequest, intervalMs);
            if (timeSinceLastRequest >= 0 && timeSinceLastRequest < intervalMs) {
                return intervalMs - timeSinceLastRequest;
            }
            if (lastRequest.compareAndSet(lastRequestTime, now)) {
                return 0;
            }
            return getIpRateLimitWaitTime(key, now, intervalMs);
        }

        private boolean isPerRequestIdEndpoint(String path) {
            return path.equals("/api/swap/inspect");
        }

        private boolean isPublicEndpoint(String path) {
            return path.equals("/api/pools") ||
                   path.startsWith("/api/holding-pools") ||
                   path.startsWith("/api/system/");
        }

        private boolean isAuthEndpoint(String path) {
            return path.startsWith("/api/auth/");
        }

        private boolean handleDistributedRateLimit(HttpServletRequest request, HttpServletResponse response, String path, String party) throws Exception {
            var now = clock.instant();

            if (!distributedLimiter.tryAcquire(party, now)) {
                int retryAfterSeconds = distributedLimiter.getRetryAfterSeconds(party, now);

                logger.warn("⚠️  Distributed rate limit exceeded for {} {}, retry in {}s",
                    request.getMethod(), path, retryAfterSeconds);

                setRateLimitHeaders(response, perPartyRpm, 0, now.plusSeconds(retryAfterSeconds).getEpochSecond());
                writeRateLimitResponse(response, path, request, retryAfterSeconds);
                return false;
            }

            logger.debug("✓ Distributed rate limit check passed for {} {}", request.getMethod(), path);
            setRateLimitHeaders(response, perPartyRpm, perPartyRpm - 1, now.plusSeconds(60).getEpochSecond());
            return true;
        }

        private boolean handleLocalRateLimit(HttpServletRequest request, HttpServletResponse response, String path, String party) throws Exception {
            long now = clock.millis();  // Use injected Clock for testability

            // Check BOTH global and per-party limits, take the MAXIMUM wait time
            long globalWaitTime = getGlobalRateLimitWaitTime(now);
            long partyWaitTime = party != null ? getPartyRateLimitWaitTime(party, now) : 0;

            long maxWaitTime = Math.max(globalWaitTime, partyWaitTime);

            // Capacity reflects the binding constraint: per-party if a party was identified, else global (capacity 1).
            int capacity = party != null ? perPartyRpm : 1;
            long resetEpoch = (now + Math.max(maxWaitTime, party != null ? perPartyIntervalMs : globalIntervalMs)) / 1000;

            if (maxWaitTime > 0) {
                int retryAfterSeconds = (int) Math.ceil(maxWaitTime / 1000.0);
                logger.warn("⚠️  Local rate limit exceeded for {} {}, retry in {}s",
                    request.getMethod(), path, retryAfterSeconds);
                setRateLimitHeaders(response, capacity, 0, resetEpoch);
                writeRateLimitResponse(response, path, request, retryAfterSeconds);
                return false;
            }

            logger.debug("✓ Rate limit check passed for {} {}", request.getMethod(), path);
            setRateLimitHeaders(response, capacity, Math.max(0, capacity - 1), resetEpoch);
            return true;
        }

        private void writeRateLimitResponse(HttpServletResponse response, String path, HttpServletRequest request, int retryAfterSeconds) throws Exception {
            ErrorResponse errorResponse = new ErrorResponse(
                "RATE_LIMIT_EXCEEDED",
                "Rate limit exceeded",
                HttpStatus.TOO_MANY_REQUESTS.value(),
                path
            );
            errorResponse.setRetryAfter(retryAfterSeconds);

            String requestId = request.getHeader("X-Request-ID");
            if (requestId != null) {
                errorResponse.setRequestId(requestId);
            }

            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setHeader("Vary", "Origin");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json; charset=UTF-8");

            String responseBody = new ObjectMapper().writeValueAsString(errorResponse);
            response.getWriter().write(responseBody);
            response.getWriter().flush();
            response.flushBuffer();
        }

        private boolean isRateLimitedEndpoint(String path) {
            // Rate-limit write operations that create ledger transactions
            return path.startsWith("/api/swap/") ||
                   path.startsWith("/api/liquidity/") ||
                   path.startsWith("/api/init/");
        }

        /**
         * Get time to wait (in ms) before next global request is allowed.
         * Returns 0 if request can proceed immediately.
         */
        private long getGlobalRateLimitWaitTime(long now) {
            long lastRequest = lastGlobalRequest.get();
            long timeSinceLastRequest = now - lastRequest;

            if (timeSinceLastRequest < globalIntervalMs) {
                return globalIntervalMs - timeSinceLastRequest;
            }

            // Try to claim the token
            if (lastGlobalRequest.compareAndSet(lastRequest, now)) {
                return 0; // Success - request can proceed
            }

            // CAS failed - someone else claimed it, retry
            return getGlobalRateLimitWaitTime(now);
        }

        /**
         * Check global rate limit using token bucket algorithm.
         * Allows 1 request every globalIntervalMs milliseconds.
         * @deprecated Use getGlobalRateLimitWaitTime instead
         */
        @Deprecated
        private boolean checkGlobalRateLimit(long now) {
            return getGlobalRateLimitWaitTime(now) == 0;
        }

        /**
         * Get time to wait (in ms) before next request is allowed for a specific party.
         * Returns 0 if request can proceed immediately.
         */
        private long getPartyRateLimitWaitTime(String party, long now) {
            AtomicLong lastRequest = partyLastRequest.computeIfAbsent(party, k -> new AtomicLong(0));
            long lastRequestTime = lastRequest.get();
            long timeSinceLastRequest = now - lastRequestTime;

            if (timeSinceLastRequest < perPartyIntervalMs) {
                return perPartyIntervalMs - timeSinceLastRequest;
            }

            // Try to claim the token
            if (lastRequest.compareAndSet(lastRequestTime, now)) {
                return 0; // Success - request can proceed
            }

            // CAS failed - someone else claimed it, retry
            return getPartyRateLimitWaitTime(party, now);
        }

        /**
         * Check per-party rate limit using token bucket algorithm.
         * Allows perPartyRpm requests per minute for each party.
         * @deprecated Use getPartyRateLimitWaitTime instead
         */
        @Deprecated
        private boolean checkPartyRateLimit(String party, long now) {
            return getPartyRateLimitWaitTime(party, now) == 0;
        }

        private String extractParty(HttpServletRequest request) {
            // Extract party from JWT claims or request attributes
            // This will be set by the OAuth2 security filter
            Object partyAttribute = request.getAttribute("party");
            if (partyAttribute != null) {
                return partyAttribute.toString();
            }

            // Fallback: extract from Authorization header (JWT)
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // Party will be available from SecurityContext in production
                // For now, use a default party for testing
                return "default-party";
            }

            return null;
        }

        /**
         * Per-requestId token bucket entry (Stage G).
         * Each /api/swap/inspect requestId gets its own bucket. Refill is continuous:
         * 1 token per refillIntervalMs, capped at capacity tokens.
         *
         * Synchronized methods are fine because contention is per-bucket only.
         */
        static class TokenBucketEntry {
            final int capacity;
            final long refillIntervalMs;
            // Mutable fields guarded by `this` lock (synchronized methods)
            long tokens;
            long lastRefillMs;
            // Volatile read for cleanup pass (eviction reads without holding lock)
            final AtomicLong lastAccessMs;

            TokenBucketEntry(int capacity, long refillIntervalMs, long now) {
                this.capacity = capacity;
                this.refillIntervalMs = refillIntervalMs;
                this.tokens = capacity;          // start full
                this.lastRefillMs = now;
                this.lastAccessMs = new AtomicLong(now);
            }

            synchronized boolean tryAcquire(long now) {
                refill(now);
                lastAccessMs.set(now);
                if (tokens >= 1) {
                    tokens--;
                    return true;
                }
                return false;
            }

            synchronized int remaining() {
                return (int) tokens;
            }

            synchronized long resetEpochSeconds(long now) {
                refill(now);
                long missing = capacity - tokens;
                if (missing <= 0) return now / 1000L;
                long fullRefillMs = lastRefillMs + missing * refillIntervalMs;
                return fullRefillMs / 1000L;
            }

            // Caller must hold `this` lock.
            private void refill(long now) {
                long elapsed = now - lastRefillMs;
                if (elapsed >= refillIntervalMs) {
                    long tokensToAdd = elapsed / refillIntervalMs;
                    tokens = Math.min(capacity, tokens + tokensToAdd);
                    lastRefillMs += tokensToAdd * refillIntervalMs;
                }
            }
        }
    }
}
