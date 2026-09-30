package com.digitalasset.quickstart.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory completion store for swap requests.
 *
 * Records terminal outcomes (success / dead-set failure) and in-flight polling state
 * keyed by requestId. Read by /api/swap/inspect to surface a structured status to
 * the caller's wallet without needing to re-derive state from the ledger ACS.
 *
 * Lost on JVM restart by design — the inspect endpoint already falls through to
 * idempotency-cache + ACS lookup when the store has no record.
 */
@Component
public class SwapInspectStore {

    private static final Logger LOG = LoggerFactory.getLogger(SwapInspectStore.class);
    private static final Duration TTL = Duration.ofMinutes(30);

    public enum InspectStatus { SUCCESS, FAILED }

    public sealed interface State permits Polling, Completed {
        String requestId();
        Instant timestamp();
    }

    public record Polling(String requestId, Instant startedAt, int attempt) implements State {
        @Override public Instant timestamp() { return startedAt; }
    }

    public record Completed(
            String requestId,
            Instant completedAt,
            InspectStatus status,
            Optional<String> updateId,
            Optional<String> payoutCid,
            Optional<String> errorClass,
            Optional<String> errorMessage
    ) implements State {
        @Override public Instant timestamp() { return completedAt; }
    }

    private final Map<String, State> store = new ConcurrentHashMap<>();

    public void markPolling(String requestId, int attempt) {
        if (requestId == null || requestId.isBlank()) return;
        store.compute(requestId, (k, prev) -> {
            // Don't overwrite a Completed record with a stale Polling enqueue.
            if (prev instanceof Completed) return prev;
            return new Polling(requestId, Instant.now(), attempt);
        });
    }

    public void markCompleted(String requestId, String updateId, String payoutCid) {
        if (requestId == null || requestId.isBlank()) return;
        store.put(requestId, new Completed(
                requestId,
                Instant.now(),
                InspectStatus.SUCCESS,
                Optional.ofNullable(updateId),
                Optional.ofNullable(payoutCid),
                Optional.empty(),
                Optional.empty()));
        LOG.info("[SwapInspectStore] markCompleted requestId={} updateId={}", requestId, updateId);
    }

    public void markFailed(String requestId, String errorClass, String errorMessage) {
        if (requestId == null || requestId.isBlank()) return;
        store.put(requestId, new Completed(
                requestId,
                Instant.now(),
                InspectStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(errorClass),
                Optional.ofNullable(errorMessage)));
        LOG.info("[SwapInspectStore] markFailed requestId={} class={}", requestId, errorClass);
    }

    public Optional<State> get(String requestId) {
        if (requestId == null || requestId.isBlank()) return Optional.empty();
        return Optional.ofNullable(store.get(requestId));
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    public void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        int removed = 0;
        for (Iterator<Map.Entry<String, State>> it = store.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, State> e = it.next();
            if (e.getValue().timestamp().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOG.info("[SwapInspectStore] evicted {} expired entries", removed);
        }
    }

    public int size() {
        return store.size();
    }
}
