package com.digitalasset.quickstart.service.poller;

import com.digitalasset.quickstart.dto.HoldingPoolResponse;
import com.digitalasset.quickstart.dto.LiquidityConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityConsumeResponse;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.HoldingPoolService;
import com.digitalasset.quickstart.service.LiquidityTiProcessorService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionWithMemo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("prod")
public class LiquidityPollerService {

    private static final Logger logger = LoggerFactory.getLogger(LiquidityPollerService.class);

    private final TransferInstructionAcsQueryService tiQueryService;
    private final LiquidityTiProcessorService liquidityService;
    private final HoldingPoolService holdingPoolService;
    private final AuthUtils authUtils;
    private final PollerRetryGuard guard = new PollerRetryGuard();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> recentlyCompleted = ConcurrentHashMap.newKeySet();

    @Value("${clearportx.liquidity.poller.enabled:true}")
    private boolean enabled;

    @Value("${clearportx.liquidity.poller.max-age-seconds:300}")
    private long maxAgeSeconds;

    public LiquidityPollerService(TransferInstructionAcsQueryService tiQueryService,
                                  LiquidityTiProcessorService liquidityService,
                                  HoldingPoolService holdingPoolService,
                                  AuthUtils authUtils,
                                  MeterRegistry meterRegistry) {
        this.tiQueryService = tiQueryService;
        this.liquidityService = liquidityService;
        this.holdingPoolService = holdingPoolService;
        this.authUtils = authUtils;
        if (meterRegistry != null) {
            Gauge.builder("liquidity_poller_dead_requests_total", guard, g -> g.listDead().size())
                    .description("Total dead requests in LiquidityPollerService retry guard")
                    .register(meterRegistry);
        }
    }

    public PollerRetryGuard getRetryGuard() {
        return guard;
    }

    @Scheduled(fixedDelayString = "${clearportx.liquidity.poller.interval-ms:5000}")
    public void pollForLiquidity() {
        if (!enabled) return;

        try {
            String operator = authUtils.getAppProviderPartyId();
            var tiResult = tiQueryService.listForReceiverWithMemo(operator);
            if (!tiResult.isOk()) {
                logger.debug("[LiquidityPoller] Failed to query TIs: {}", tiResult.getErrorUnsafe().getMessage());
                return;
            }

            for (TransferInstructionWithMemo tiWithMemo : tiResult.getValueUnsafe()) {
                String memo = tiWithMemo.memo();
                if (memo == null || memo.isBlank()) continue;

                String requestId = null;
                try {
                    JsonNode memoJson = mapper.readTree(memo);
                    if (!memoJson.has("kind") || !"addLiquidity".equals(memoJson.get("kind").asText())) {
                        continue;
                    }
                    if (!memoJson.has("requestId") || !memoJson.has("poolCid")) continue;
                    requestId = memoJson.get("requestId").asText();
                    if (requestId == null || requestId.isBlank()) continue;

                    if (inFlight.contains(requestId) || recentlyCompleted.contains(requestId)) continue;
                    if (guard.isDead(requestId)) continue;

                    if (memoJson.has("deadline")) {
                        String deadlineStr = memoJson.get("deadline").asText();
                        if (deadlineStr != null && !deadlineStr.isBlank()) {
                            try {
                                java.time.Instant deadline = java.time.Instant.parse(deadlineStr);
                                if (java.time.Instant.now().isAfter(deadline)) {
                                    guard.markDead(requestId, "AddLiquidity deadline has expired");
                                    continue;
                                }
                            } catch (java.time.format.DateTimeParseException ignored) {
                                // tolerate malformed deadline; let downstream consume() handle it
                            }
                        }
                    }

                    String poolCid = memoJson.get("poolCid").asText();
                    String poolId = memoJson.has("poolId") ? memoJson.get("poolId").asText() : null;

                    String resolvedPoolCid = resolvePoolCid(poolCid, poolId, requestId);
                    if (resolvedPoolCid == null) {
                        continue; // markDead already invoked inside resolvePoolCid
                    }

                    inFlight.add(requestId);
                    logger.info("[LiquidityPoller] Processing addLiquidity requestId={} poolCid={}", requestId, resolvedPoolCid);

                    LiquidityConsumeRequest request = new LiquidityConsumeRequest();
                    request.requestId = requestId;
                    request.poolCid = resolvedPoolCid;
                    request.maxAgeSeconds = maxAgeSeconds;

                    final String requestIdFinal = requestId;
                    liquidityService.consume(request)
                            .thenAccept(result -> {
                                inFlight.remove(requestIdFinal);
                                if (result.isOk()) {
                                    LiquidityConsumeResponse response = result.getValueUnsafe();
                                    guard.clear(requestIdFinal);
                                    recentlyCompleted.add(requestIdFinal);
                                    logger.info("[LiquidityPoller] addLiquidity completed requestId={} newPoolCid={} lpMinted={}",
                                            requestIdFinal, response.newPoolCid, response.lpMinted);
                                } else {
                                    String errorMessage = result.getErrorUnsafe().message;
                                    String errorCode = result.getErrorUnsafe().code != null
                                            ? result.getErrorUnsafe().code.name()
                                            : "";
                                    PollerRetryGuard.Classification cls = guard.classify(errorCode, errorMessage);
                                    logger.warn("[LiquidityPoller] addLiquidity failed requestId={} cls={} err={}",
                                            requestIdFinal, cls, errorMessage);
                                    guard.recordAttempt(requestIdFinal);
                                    if (guard.shouldGiveUp(requestIdFinal, cls)) {
                                        guard.markDead(requestIdFinal, errorMessage);
                                        recentlyCompleted.add(requestIdFinal);
                                    }
                                }
                            })
                            .exceptionally(ex -> {
                                inFlight.remove(requestIdFinal);
                                logger.error("[LiquidityPoller] addLiquidity exception requestId={}", requestIdFinal, ex);
                                guard.recordAttempt(requestIdFinal);
                                return null;
                            });

                } catch (Exception e) {
                    logger.warn("[LiquidityPoller] Failed to parse memo (requestId={}): {}", requestId, memo, e);
                }
            }
        } catch (Exception e) {
            logger.error("[LiquidityPoller] Poll cycle failed", e);
        }
    }

    private String resolvePoolCid(String poolCid, String poolId, String requestId) {
        if (poolCid == null || poolCid.isBlank()) {
            guard.markDead(requestId, "PoolIdUnresolvable: poolCid missing in memo");
            return null;
        }
        try {
            var direct = holdingPoolService.getByContractId(poolCid).join();
            if (direct.isOk()) {
                return poolCid;
            }
        } catch (Exception ignored) {
            // fall through to poolId-based resolve
        }
        if (poolId == null || poolId.isBlank()) {
            guard.markDead(requestId, "PoolIdUnresolvable: poolId missing in memo (after CONTRACT_NOT_FOUND on poolCid)");
            return null;
        }
        try {
            var refresh = holdingPoolService.resolveActiveByPoolId(poolId).join();
            if (refresh.isOk()) {
                HoldingPoolResponse fresh = refresh.getValueUnsafe();
                if (fresh.contractId != null && !fresh.contractId.isBlank()) {
                    logger.info("[LiquidityPoller] poolCid refreshed requestId={} stalePoolCid={} freshPoolCid={}",
                            requestId, poolCid, fresh.contractId);
                    return fresh.contractId;
                }
            }
        } catch (Exception e) {
            logger.warn("[LiquidityPoller] resolveActiveByPoolId failed requestId={} poolId={}", requestId, poolId, e);
        }
        guard.markDead(requestId, "PoolIdUnresolvable: resolveActiveByPoolId returned no active pool");
        return null;
    }

    @Scheduled(fixedDelay = 600000)
    public void cleanupCompleted() {
        if (recentlyCompleted.size() > 1000) {
            recentlyCompleted.clear();
            logger.info("[LiquidityPoller] Cleared completed set");
        }
    }
}
