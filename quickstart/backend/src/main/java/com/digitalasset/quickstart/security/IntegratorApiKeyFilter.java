package com.digitalasset.quickstart.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class IntegratorApiKeyFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(IntegratorApiKeyFilter.class);
    private static final String HEADER_NAME = "X-Integrator-Key";
    private static final String PATH_PREFIX = "/api/integrator/";
    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_PER_WINDOW = 60;
    private static final int BURST = 20;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> requestLog = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> burstLog = new ConcurrentHashMap<>();

    @Value("${INTEGRATOR_API_KEY:}")
    private String configuredKey;

    @PostConstruct
    void init() {
        if (configuredKey == null || configuredKey.isBlank()) {
            logger.warn("Integrator API disabled — INTEGRATOR_API_KEY not set");
        } else {
            logger.info("Integrator API enabled");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (configuredKey == null || configuredKey.isBlank()) {
            logger.info("integrator endpoint=disabled path={}", request.getServletPath());
            writeJson(response, HttpStatus.SERVICE_UNAVAILABLE, Map.of("error", "integrator api disabled"));
            return;
        }

        String provided = request.getHeader(HEADER_NAME);
        if (provided == null || provided.isBlank()) {
            logger.info("integrator header=missing path={}", request.getServletPath());
            writeJson(response, HttpStatus.UNAUTHORIZED, Map.of("error", "missing X-Integrator-Key header"));
            return;
        }

        byte[] expectedBytes = configuredKey.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
            logger.info("integrator header=invalid path={}", request.getServletPath());
            writeJson(response, HttpStatus.UNAUTHORIZED, Map.of("error", "invalid integrator key"));
            return;
        }

        String bucketKey = sha256Prefix(configuredKey);
        long now = System.currentTimeMillis();
        if (!checkRate(bucketKey, now)) {
            int retryAfter = computeRetryAfterSeconds(bucketKey, now);
            logger.info("integrator header=present rate=limited retryAfter={}s path={}", retryAfter, request.getServletPath());
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            writeJson(response, HttpStatus.TOO_MANY_REQUESTS, Map.of(
                    "error", "rate limited",
                    "retryAfter", retryAfter
            ));
            return;
        }

        logger.info("integrator header=present path={}", request.getServletPath());
        chain.doFilter(request, response);
    }

    private boolean checkRate(String bucketKey, long now) {
        ConcurrentLinkedDeque<Long> window = requestLog.computeIfAbsent(bucketKey, k -> new ConcurrentLinkedDeque<>());
        ConcurrentLinkedDeque<Long> burstWindow = burstLog.computeIfAbsent(bucketKey, k -> new ConcurrentLinkedDeque<>());

        synchronized (window) {
            evictOlderThan(window, now - WINDOW_MS);
            evictOlderThan(burstWindow, now - 1_000L);

            if (burstWindow.size() >= BURST) {
                return false;
            }
            if (window.size() >= MAX_PER_WINDOW) {
                return false;
            }
            window.addLast(now);
            burstWindow.addLast(now);
            return true;
        }
    }

    private int computeRetryAfterSeconds(String bucketKey, long now) {
        ConcurrentLinkedDeque<Long> window = requestLog.get(bucketKey);
        if (window == null || window.isEmpty()) {
            return 1;
        }
        Long oldest = window.peekFirst();
        if (oldest == null) {
            return 1;
        }
        long waitMs = (oldest + WINDOW_MS) - now;
        return (int) Math.max(1L, Math.min(60L, (waitMs + 999L) / 1000L));
    }

    private void evictOlderThan(ConcurrentLinkedDeque<Long> deque, long cutoff) {
        Long head;
        while ((head = deque.peekFirst()) != null && head < cutoff) {
            deque.pollFirst();
        }
    }

    private static String sha256Prefix(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "default";
        }
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, Map<String, ?> body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
