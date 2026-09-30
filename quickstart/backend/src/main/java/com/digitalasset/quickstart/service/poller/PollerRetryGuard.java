package com.digitalasset.quickstart.service.poller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PollerRetryGuard {
    private static final Logger LOG = LoggerFactory.getLogger(PollerRetryGuard.class);

    public enum Classification { PERMANENT, TRANSIENT, DEADLINE, MISSING_TIS }

    private static final long[] TRANSIENT_GENERAL_DELAYS_MS = {5_000L, 15_000L, 45_000L};
    private static final int TRANSIENT_GENERAL_MAX_ATTEMPTS = TRANSIENT_GENERAL_DELAYS_MS.length;

    private static final long[] TRANSIENT_THROTTLE_DELAYS_MS = {10_000L, 60_000L};
    private static final int TRANSIENT_THROTTLE_MAX_ATTEMPTS = TRANSIENT_THROTTLE_DELAYS_MS.length;

    private static final long[] DEFAULT_FALLBACK_DELAYS_MS = {5_000L, 30_000L};
    private static final int DEFAULT_FALLBACK_MAX_ATTEMPTS = DEFAULT_FALLBACK_DELAYS_MS.length;

    // Fee-charged 2-leg addLiquidity: the 2nd inbound transfer can settle minutes after the 1st
    // (Loop pending-gas). "Missing inbound TIs" is retried patiently until both legs land, rather
    // than dead-lettered after the generic 3 transient tries. MainNet-only (DevNet has no fees).
    private static final long MISSING_TIS_PATIENCE_MS = 15L * 60L * 1000L;

    static final class AttemptState {
        int attempts;
        long firstFailureTs;
        boolean dead;
        String errorClass;
        String errorMessage;
        String deadReason;
    }

    private final ConcurrentHashMap<String, AttemptState> states = new ConcurrentHashMap<>();

    public void recordAttempt(String requestId) {
        if (requestId == null) {
            return;
        }
        states.compute(requestId, (k, prev) -> {
            AttemptState s = prev == null ? new AttemptState() : prev;
            if (s.attempts == 0) {
                s.firstFailureTs = System.currentTimeMillis();
            }
            s.attempts += 1;
            return s;
        });
    }

    public Classification classify(String errorClass, String errorMessage) {
        String klass = errorClass == null ? "" : errorClass;
        String msg = errorMessage == null ? "" : errorMessage;

        if (msg.contains("Must be unlocked")
                || msg.contains("AddLiquidity deadline has expired")
                || msg.contains("Swap deadline has expired")
                || msg.contains("RemoveLiquidity deadline has expired")
                || msg.contains("Output below minOut")
                || msg.contains("Holding insufficient")
                || msg.contains("insufficient-funds")
                || klass.contains("PERMISSION_DENIED")
                || klass.contains("UNAUTHORIZED")
                || msg.contains("PERMISSION_DENIED")
                || msg.contains("UNAUTHORIZED")
                || msg.contains("DAR")
                || msg.contains("package vetting")) {
            return Classification.PERMANENT;
        }

        if (msg.contains("CONTRACT_NOT_FOUND") && msg.contains("inbound TI")) {
            return Classification.PERMANENT;
        }

        // Both inbound legs not yet present. For fee-charged adds the 2nd leg can land minutes
        // later, so wait patiently (see shouldGiveUp) rather than treating it as a quick transient.
        if (msg.contains("Missing inbound TIs")) {
            return Classification.MISSING_TIS;
        }

        if (klass.contains("RESOURCE_EXHAUSTED")
                || klass.contains("DEADLINE_EXCEEDED")
                || msg.contains("RESOURCE_EXHAUSTED")
                || msg.contains("DEADLINE_EXCEEDED")) {
            return Classification.TRANSIENT;
        }

        if (msg.contains("CONTRACT_NOT_FOUND")
                || klass.contains("UNAVAILABLE")
                || msg.contains("UNAVAILABLE")
                || msg.contains("scan SV transient")
                || msg.contains("5")) {
            return Classification.TRANSIENT;
        }

        return Classification.TRANSIENT;
    }

    public void markDead(String requestId, String reason) {
        if (requestId == null) {
            return;
        }
        states.compute(requestId, (k, prev) -> {
            AttemptState s = prev == null ? new AttemptState() : prev;
            s.dead = true;
            s.deadReason = reason;
            if (s.firstFailureTs == 0L) {
                s.firstFailureTs = System.currentTimeMillis();
            }
            LOG.info("[PollerRetryGuard] markDead requestId={} reason={}", requestId, reason);
            return s;
        });
    }

    public boolean isDead(String requestId) {
        if (requestId == null) {
            return false;
        }
        AttemptState s = states.get(requestId);
        return s != null && s.dead;
    }

    public long nextDelayMs(String requestId) {
        if (requestId == null) {
            return DEFAULT_FALLBACK_DELAYS_MS[0];
        }
        AttemptState s = states.get(requestId);
        int attemptsSoFar = s == null ? 0 : s.attempts;

        if (attemptsSoFar <= 0) {
            return TRANSIENT_GENERAL_DELAYS_MS[0];
        }
        if (attemptsSoFar < TRANSIENT_GENERAL_MAX_ATTEMPTS) {
            return TRANSIENT_GENERAL_DELAYS_MS[attemptsSoFar];
        }
        return TRANSIENT_GENERAL_DELAYS_MS[TRANSIENT_GENERAL_MAX_ATTEMPTS - 1];
    }

    public int attempts(String requestId) {
        if (requestId == null) {
            return 0;
        }
        AttemptState s = states.get(requestId);
        return s == null ? 0 : s.attempts;
    }

    public boolean shouldGiveUp(String requestId, Classification cls) {
        int n = attempts(requestId);
        switch (cls) {
            case PERMANENT:
            case DEADLINE:
                return true;
            case MISSING_TIS: {
                // Wait for the slow second leg of a fee-charged add up to the patience window,
                // then give up. Time-based (not attempt-count) since poll spacing varies.
                AttemptState s = states.get(requestId);
                long first = (s == null || s.firstFailureTs == 0L) ? System.currentTimeMillis() : s.firstFailureTs;
                return System.currentTimeMillis() - first > MISSING_TIS_PATIENCE_MS;
            }
            case TRANSIENT:
                return n >= TRANSIENT_GENERAL_MAX_ATTEMPTS;
            default:
                return n >= DEFAULT_FALLBACK_MAX_ATTEMPTS;
        }
    }

    public void clear(String requestId) {
        if (requestId == null) {
            return;
        }
        states.remove(requestId);
    }

    public List<DeadEntry> listDead() {
        List<DeadEntry> out = new ArrayList<>();
        for (Map.Entry<String, AttemptState> e : states.entrySet()) {
            AttemptState s = e.getValue();
            if (s.dead) {
                out.add(new DeadEntry(e.getKey(), s.firstFailureTs, s.errorClass, s.deadReason != null ? s.deadReason : s.errorMessage));
            }
        }
        return out;
    }

    public int sizeForTest() {
        return states.size();
    }

    public record DeadEntry(String requestId, long firstFailureTs, String errorClass, String errorMessage) {}
}
