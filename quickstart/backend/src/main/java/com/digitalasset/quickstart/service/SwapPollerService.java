package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.dto.SwapConsumeRequest;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.poller.PollerRetryGuard;
import com.digitalasset.quickstart.store.SwapInspectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Profile("prod")
public class SwapPollerService {

    private static final Logger logger = LoggerFactory.getLogger(SwapPollerService.class);
    private final TransferInstructionAcsQueryService tiQueryService;
    private final SwapTiProcessorService swapService;
    private final AuthUtils authUtils;
    private final PollerRetryGuard guard = new PollerRetryGuard();
    private final SwapInspectStore inspectStore;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> recentlyCompleted = ConcurrentHashMap.newKeySet();
    private static final Map<String, SwapCompletionRecord> completedSwaps = new ConcurrentHashMap<>();

    public record SwapCompletionRecord(
        String requestId,
        String amountIn,
        String amountOut,
        String direction,
        Instant completedAt
    ) {}

    public static Optional<SwapCompletionRecord> getCompletionRecord(String requestId) {
        return Optional.ofNullable(completedSwaps.get(requestId));
    }

    @Value("${clearportx.swap.poller.enabled:true}")
    private boolean enabled;

    @Value("${clearportx.swap.poller.max-age-seconds:300}")
    private long maxAgeSeconds;

    public SwapPollerService(TransferInstructionAcsQueryService tiQueryService,
                             SwapTiProcessorService swapService,
                             AuthUtils authUtils,
                             MeterRegistry meterRegistry,
                             SwapInspectStore inspectStore) {
        this.tiQueryService = tiQueryService;
        this.swapService = swapService;
        this.authUtils = authUtils;
        this.inspectStore = inspectStore;
        if (meterRegistry != null) {
            Gauge.builder("swap_poller_dead_requests_total", guard, g -> g.listDead().size())
                    .description("Total dead requests in SwapPollerService retry guard")
                    .register(meterRegistry);
        }
    }

    public PollerRetryGuard getRetryGuard() {
        return guard;
    }

    @Scheduled(fixedDelayString = "${clearportx.swap.poller.interval-ms:5000}")
    public void pollForSwaps() {
        if (!enabled) return;

        try {
            String operator = authUtils.getAppProviderPartyId();
            var tiResult = tiQueryService.listForReceiverWithMemo(operator);
            if (!tiResult.isOk()) {
                logger.debug("[SwapPoller] Failed to query TIs: {}", tiResult.getErrorUnsafe().getMessage());
                return;
            }
            var tisWithMemo = tiResult.getValueUnsafe();

            for (var tiWithMemo : tisWithMemo) {
                String memo = tiWithMemo.memo();
                if (memo == null || memo.isBlank()) continue;

                try {
                    JsonNode memoJson = mapper.readTree(memo);
                    String requestId = memoJson.has("requestId") ? memoJson.get("requestId").asText() : null;
                    if (requestId == null || requestId.isBlank()) continue;

                    if (inFlight.contains(requestId) || recentlyCompleted.contains(requestId)) continue;
                    if (guard.isDead(requestId)) continue;

                    if (!memoJson.has("poolCid") || !memoJson.has("direction") || !memoJson.has("minOut")) continue;

                    inFlight.add(requestId);
                    inspectStore.markPolling(requestId, 0);
                    logger.info("[SwapPoller] Processing swap requestId={}", requestId);

                    SwapConsumeRequest request = new SwapConsumeRequest();
                    request.requestId = requestId;
                    request.maxAgeSeconds = maxAgeSeconds;

                    swapService.consume(request)
                        .thenAccept(result -> {
                            inFlight.remove(requestId);
                            if (result.isOk()) {
                                recentlyCompleted.add(requestId);
                                var val = result.getValueUnsafe();
                                completedSwaps.put(requestId, new SwapCompletionRecord(
                                    requestId,
                                    val.amountIn,
                                    val.amountOut,
                                    val.direction,
                                    Instant.now()
                                ));
                                logger.info("[SwapPoller] Swap completed requestId={} amountIn={} amountOut={}",
                                    requestId, val.amountIn, val.amountOut);
                            } else {
                                String errorMessage = result.getErrorUnsafe().message;
                                String errorCode = result.getErrorUnsafe().code != null
                                        ? result.getErrorUnsafe().code.name()
                                        : "";
                                // Settled-elsewhere race: the synchronous /api/swap/consume may have
                                // archived the inbound TI (CONTRACT_NOT_FOUND) or locked it
                                // (LOCAL_VERDICT_LOCKED_CONTRACTS) — the swap already settled, this is
                                // not a real failure. Do NOT dead-set; just suppress further polling.
                                if (errorMessage != null
                                        && (errorMessage.contains("CONTRACT_NOT_FOUND")
                                            || errorMessage.contains("LOCKED_CONTRACTS"))) {
                                    logger.info("[SwapPoller] requestId={} already settled elsewhere (sync consume); skipping",
                                        requestId);
                                    recentlyCompleted.add(requestId);
                                    guard.clear(requestId);
                                } else {
                                    PollerRetryGuard.Classification cls = guard.classify(errorCode, errorMessage);
                                    logger.warn("[SwapPoller] Swap failed requestId={} cls={} err={}",
                                        requestId, cls, errorMessage);
                                    guard.recordAttempt(requestId);
                                    if (guard.shouldGiveUp(requestId, cls)) {
                                        guard.markDead(requestId, errorMessage);
                                        recentlyCompleted.add(requestId);
                                    }
                                }
                                // If consume() reached a terminal state in the inspect store
                                // (success cache hit OR dead-set), stop re-ticking on this requestId.
                                inspectStore.get(requestId)
                                        .filter(s -> s instanceof SwapInspectStore.Completed)
                                        .ifPresent(s -> recentlyCompleted.add(requestId));
                            }
                        })
                        .exceptionally(ex -> {
                            inFlight.remove(requestId);
                            logger.error("[SwapPoller] Swap exception requestId={}", requestId, ex);
                            inspectStore.markFailed(requestId, "POLLER_EXCEPTION",
                                    ex != null ? ex.getMessage() : "unknown");
                            recentlyCompleted.add(requestId);
                            return null;
                        });

                } catch (Exception e) {
                    logger.warn("[SwapPoller] Failed to parse memo: {}", memo, e);
                }
            }
        } catch (Exception e) {
            logger.error("[SwapPoller] Poll cycle failed", e);
        }
    }

    @Scheduled(fixedDelay = 600000)
    public void cleanupCompleted() {
        if (recentlyCompleted.size() > 1000) {
            recentlyCompleted.clear();
            logger.info("[SwapPoller] Cleared completed set");
        }
        Instant cutoff = Instant.now().minusSeconds(600);
        completedSwaps.entrySet().removeIf(e -> e.getValue().completedAt().isBefore(cutoff));
    }
}
