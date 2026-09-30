package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.ErrorMapper;
import com.digitalasset.quickstart.dto.HoldingPoolResponse;
import com.digitalasset.quickstart.dto.SwapConsumeRequest;
import com.digitalasset.quickstart.dto.SwapConsumeResponse;
import com.digitalasset.quickstart.dto.SwapIntentLookupResponse;
import com.digitalasset.quickstart.dto.SwapTransferInstructionResponse;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.HoldingPoolService;
import com.digitalasset.quickstart.service.SwapIntentAcsQueryService;
import com.digitalasset.quickstart.service.IdempotencyService;
import com.digitalasset.quickstart.service.SwapTiProcessorService;
import com.digitalasset.quickstart.service.TransactionHistoryService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionWithMemo;
import com.digitalasset.quickstart.store.SwapInspectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devnet/swap")
public class DevNetSwapController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetSwapController.class);
    private final SwapTiProcessorService swapService;
    private final TransferInstructionAcsQueryService tiQueryService;
    private final SwapIntentAcsQueryService swapIntentQueryService;
    private final HoldingPoolService holdingPoolService;
    private final TransactionHistoryService transactionHistoryService;
    private final IdempotencyService idempotencyService;
    private final SwapInspectStore inspectStore;
    private final AuthUtils authUtils;
    private final ObjectMapper mapper = new ObjectMapper();

    public DevNetSwapController(final SwapTiProcessorService swapService,
                                final TransferInstructionAcsQueryService tiQueryService,
                                final SwapIntentAcsQueryService swapIntentQueryService,
                                final HoldingPoolService holdingPoolService,
                                final TransactionHistoryService transactionHistoryService,
                                final IdempotencyService idempotencyService,
                                final SwapInspectStore inspectStore,
                                final AuthUtils authUtils) {
        this.swapService = swapService;
        this.tiQueryService = tiQueryService;
        this.swapIntentQueryService = swapIntentQueryService;
        this.holdingPoolService = holdingPoolService;
        this.transactionHistoryService = transactionHistoryService;
        this.idempotencyService = idempotencyService;
        this.inspectStore = inspectStore;
        this.authUtils = authUtils;
    }

    /**
     * POST /api/devnet/swap/consume
     * Consume inbound swap TI, execute swap, and create payout.
     */
    @PostMapping("/consume")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<SwapConsumeResponse>>> consume(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SwapConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = request != null ? request.requestId : null;
        if (requestId == null || requestId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond("swap-consume", Result.err(validationError("requestId is required", "requestId"))));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }
        logger.info("[DevNetSwap] POST /swap/consume requestId={}", requestId);
        return swapService.consume(request)
                .thenApply(result -> {
                    if (result.isOk()) {
                        recordHistoryFromConsume(result.getValueUnsafe());
                    }
                    return respond(requestId, result);
                });
    }

    /**
     * GET /api/devnet/swap/transfer-instruction?requestId=...
     * Resolve inbound TI by requestId (memo) for the operator.
     */
    @GetMapping("/transfer-instruction")
    public ResponseEntity<ApiResponse<SwapTransferInstructionResponse>> getTransferInstruction(
            @RequestParam("requestId") String requestId,
            HttpServletRequest httpRequest
    ) {
        String op = authUtils.getAppProviderPartyId();
        if (requestId == null || requestId.isBlank()) {
            return respond(requestId, Result.err(validationError("requestId is required", "requestId")));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }

        Result<List<TransferInstructionWithMemo>, com.digitalasset.quickstart.common.DomainError> res =
                tiQueryService.listForReceiverWithMemo(op);
        if (res.isErr()) {
            return respond(requestId, Result.err(new ApiError(
                    ErrorCode.INTERNAL,
                    "Failed to query pending TIs",
                    Map.of("cause", res.getErrorUnsafe().message()),
                    false,
                    null,
                    null
            )));
        }

        TransferInstructionWithMemo match = res.getValueUnsafe().stream()
                .filter(row -> requestId.equals(extractRequestId(row.memo())))
                .findFirst()
                .orElse(null);

        if (match == null) {
            return respond(requestId, Result.err(new ApiError(
                    ErrorCode.NOT_FOUND,
                    "No inbound TI found for requestId",
                    Map.of("requestId", requestId),
                    false,
                    null,
                    null
            )));
        }

        TransferInstructionDto ti = match.transferInstruction();
        SwapTransferInstructionResponse payload = new SwapTransferInstructionResponse();
        payload.requestId = requestId;
        payload.contractId = ti.contractId();
        payload.instrumentAdmin = ti.admin();
        payload.instrumentId = ti.instrumentId();
        payload.amount = ti.amount();
        payload.executeBefore = ti.executeBefore() != null ? ti.executeBefore().toString() : null;
        payload.memo = match.memo();
        return respond(requestId, Result.ok(payload));
    }

    /**
     * GET /api/devnet/swap/intent?requestId=...
     * Resolve SwapIntent cid by requestId (memo -> TI -> SwapIntent).
     */
    @GetMapping("/intent")
    public ResponseEntity<ApiResponse<SwapIntentLookupResponse>> getIntentByRequestId(
            @RequestParam("requestId") String requestId,
            HttpServletRequest httpRequest
    ) {
        String op = authUtils.getAppProviderPartyId();
        if (requestId == null || requestId.isBlank()) {
            return respond(requestId, Result.err(validationError("requestId is required", "requestId")));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }

        Result<List<TransferInstructionWithMemo>, com.digitalasset.quickstart.common.DomainError> res =
                tiQueryService.listForReceiverWithMemo(op);
        if (res.isErr()) {
            return respond(requestId, Result.err(new ApiError(
                    ErrorCode.INTERNAL,
                    "Failed to query pending TIs",
                    Map.of("cause", res.getErrorUnsafe().message()),
                    false,
                    null,
                    null
            )));
        }

        TransferInstructionWithMemo match = res.getValueUnsafe().stream()
                .filter(row -> requestId.equals(extractRequestId(row.memo())))
                .findFirst()
                .orElse(null);
        if (match == null) {
            return respond(requestId, Result.err(new ApiError(
                    ErrorCode.NOT_FOUND,
                    "No inbound TI found for requestId",
                    Map.of("requestId", requestId),
                    false,
                    null,
                    null
            )));
        }

        String tiCid = match.transferInstruction().contractId();
        var intent = swapIntentQueryService.findByTransferInstructionCid(op, tiCid);
        if (intent.isEmpty()) {
            return respond(requestId, Result.err(new ApiError(
                    ErrorCode.NOT_FOUND,
                    "SWAP_INTENT_NOT_FOUND",
                    Map.of("requestId", requestId, "tiCid", tiCid),
                    false,
                    null,
                    null
            )));
        }

        SwapIntentLookupResponse payload = new SwapIntentLookupResponse();
        payload.requestId = requestId;
        payload.transferInstructionCid = tiCid;
        payload.intentCid = intent.get().contractId();
        return respond(requestId, Result.ok(payload));
    }

    /**
     * GET /api/devnet/swap/inspect?requestId=...
     * Returns inbound TI + SwapIntent visibility + pool status.
     */
    @GetMapping("/inspect")
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<Map<String, Object>>>> inspect(
            @RequestParam("requestId") String requestId,
            HttpServletRequest httpRequest
    ) {
        String op = authUtils.getAppProviderPartyId();
        if (requestId == null || requestId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond(requestId, Result.err(validationError("requestId is required", "requestId"))));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }

        // Check if swap was already processed (by poller or previous consume call)
        Object cached = idempotencyService.checkIdempotency(requestId);
        if (cached instanceof SwapConsumeResponse cachedResponse) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("requestId", requestId);
            payload.put("swap", Map.of("status", "completed", "cached", true));
            payload.put("consume", Map.of("ok", true, "amountIn", cachedResponse.amountIn, "amountOut", cachedResponse.amountOut));
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond(requestId, Result.ok(payload)));
        }

        // Phase 3F.9: structured inspect-completion-store lookup. Layered above the
        // existing idempotency-cache + ACS branches: completed/failed states return
        // immediately; polling state seeds the payload then merges with TI/intent/pool
        // resolution below.
        java.util.Optional<SwapInspectStore.State> inspectState = inspectStore.get(requestId);
        Map<String, Object> seedPayload = null;
        if (inspectState.isPresent()) {
            SwapInspectStore.State s = inspectState.get();
            if (s instanceof SwapInspectStore.Completed c) {
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("requestId", requestId);
                if (c.status() == SwapInspectStore.InspectStatus.SUCCESS) {
                    payload.put("status", "complete");
                    c.updateId().ifPresent(v -> payload.put("updateId", v));
                    c.payoutCid().ifPresent(v -> payload.put("payoutTransferOfferCid", v));
                    payload.put("completedAt", c.completedAt().toString());
                } else {
                    payload.put("status", "failed");
                    c.errorClass().ifPresent(v -> payload.put("errorClass", v));
                    c.errorMessage().ifPresent(v -> payload.put("errorMessage", v));
                    payload.put("deadsetAt", c.completedAt().toString());
                }
                return java.util.concurrent.CompletableFuture.completedFuture(
                        respond(requestId, Result.ok(payload)));
            }
            if (s instanceof SwapInspectStore.Polling p) {
                seedPayload = new java.util.LinkedHashMap<>();
                seedPayload.put("status", "polling");
                seedPayload.put("attempt", p.attempt());
                seedPayload.put("startedAt", p.startedAt().toString());
            }
        }

        Result<List<TransferInstructionWithMemo>, com.digitalasset.quickstart.common.DomainError> res =
                tiQueryService.listForReceiverWithMemo(op);
        if (res.isErr()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond(requestId, Result.err(new ApiError(
                            ErrorCode.INTERNAL,
                            "Failed to query pending TIs",
                            Map.of("cause", res.getErrorUnsafe().message()),
                            false,
                            null,
                            null
                    )))
            );
        }

        TransferInstructionWithMemo match = res.getValueUnsafe().stream()
                .filter(row -> requestId.equals(extractRequestId(row.memo())))
                .findFirst()
                .orElse(null);
        if (match == null) {
            // TI consumed — check the poller's in-memory completion store
            var completed = com.digitalasset.quickstart.service.SwapPollerService.getCompletionRecord(requestId);
            if (completed.isPresent()) {
                var rec = completed.get();
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("requestId", requestId);
                payload.put("swap", Map.of(
                    "status", "completed",
                    "amountIn", rec.amountIn(),
                    "amountOut", rec.amountOut(),
                    "direction", rec.direction() != null ? rec.direction() : "",
                    "completedAt", rec.completedAt().toString()
                ));
                payload.put("consume", Map.of("ok", true, "amountIn", rec.amountIn(), "amountOut", rec.amountOut()));
                return java.util.concurrent.CompletableFuture.completedFuture(
                        respond(requestId, Result.ok(payload)));
            }
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond(requestId, Result.err(new ApiError(
                            ErrorCode.NOT_FOUND,
                            "No inbound TI found for requestId",
                            Map.of("requestId", requestId),
                            false,
                            null,
                            null
                    )))
            );
        }

        String tiCid = match.transferInstruction().contractId();
        var intent = swapIntentQueryService.findByTransferInstructionCid(op, tiCid);
        String intentCid = intent.map(SwapIntentAcsQueryService.SwapIntentDto::contractId).orElse(null);
        String memoRaw = match.memo();

        // If we have a Polling seed from the inspect store, layer the existing
        // TI/intent/pool fields on top so the response shape stays additive.
        Map<String, Object> payload = (seedPayload != null)
                ? seedPayload
                : new java.util.LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("inboundTiCid", tiCid);
        payload.put("swapIntentCid", intentCid);
        payload.put("memoRaw", memoRaw);

        String poolCid = null;
        try {
            var node = mapper.readTree(memoRaw == null ? "" : memoRaw);
            var poolNode = node.get("poolCid");
            if (poolNode != null) {
                poolCid = poolNode.asText();
                payload.put("poolCid", poolCid);
            }
        } catch (Exception e) {
            payload.put("memoParseError", e.getMessage());
        }

        if (poolCid == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(respond(requestId, Result.ok(payload)));
        }

        return holdingPoolService.getByContractId(poolCid)
                .thenApply(poolResult -> {
                    if (poolResult.isOk()) {
                        var pool = poolResult.getValueUnsafe();
                        payload.put("poolStatus", pool.status);
                        payload.put("reserveAmountA", pool.reserveAmountA);
                        payload.put("reserveAmountB", pool.reserveAmountB);
                    } else {
                        payload.put("poolError", poolResult.getErrorUnsafe().message());
                    }
                    return respond(requestId, Result.ok(payload));
                });
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(String requestId, Result<T, ApiError> result) {
        ResponseEntity<ApiResponse<T>> response;
        if (result.isOk()) {
            response = ResponseEntity.ok(ApiResponse.success(requestId, result.getValueUnsafe()));
        } else {
            ApiError error = result.getErrorUnsafe();
            logger.error("[DevNetSwap] requestId={} failed code={} message={} details={}",
                    requestId, error.code, error.message, error.details);
            response = ResponseEntity.status(ErrorMapper.toHttpStatus(error.code))
                    .body(ApiResponse.failure(requestId, error));
        }
        return ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.DEVNET);
    }

    private ApiError validationError(String message, String field) {
        return new ApiError(
                ErrorCode.VALIDATION,
                message,
                Map.of("field", field),
                false,
                null,
                null
        );
    }

    private String extractRequestId(String memoRaw) {
        if (memoRaw == null || memoRaw.isBlank()) {
            return null;
        }
        try {
            var node = mapper.readTree(memoRaw);
            var rid = node.get("requestId");
            return rid != null ? rid.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void recordHistoryFromConsume(SwapConsumeResponse response) {
        if (response == null || response.requestId == null || response.requestId.isBlank()) {
            return;
        }
        if (response.poolCid == null || response.poolCid.isBlank()) {
            return;
        }
        holdingPoolService.getByContractId(response.poolCid)
                .thenAccept(poolResult -> {
                    if (poolResult.isErr()) {
                        return;
                    }
                    HoldingPoolResponse pool = poolResult.getValueUnsafe();
                    boolean aToB = isAtoB(response.direction);
                    String inputSymbol = displaySymbol(aToB ? pool.instrumentA.id : pool.instrumentB.id);
                    String outputSymbol = displaySymbol(aToB ? pool.instrumentB.id : pool.instrumentA.id);
                    BigDecimal amountIn = parseDecimal(response.amountIn);
                    BigDecimal amountOut = parseDecimal(response.amountOut);
                    String actor = response.receiverParty != null && !response.receiverParty.isBlank()
                            ? response.receiverParty
                            : authUtils.getAppProviderPartyId();
                    transactionHistoryService.recordSwap(
                            response.requestId,
                            pool.contractId,
                            pool.contractId,
                            inputSymbol,
                            outputSymbol,
                            amountIn,
                            amountOut,
                            actor
                    );
                });
    }

    private boolean isAtoB(String direction) {
        if (direction == null) {
            return true;
        }
        String normalized = direction.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        return "A2B".equals(normalized) || "ATOB".equals(normalized);
    }

    private String displaySymbol(String instrumentId) {
        if (instrumentId == null) {
            return "";
        }
        if ("AMULET".equalsIgnoreCase(instrumentId.trim())) {
            return "CC";
        }
        return instrumentId.trim();
    }

    private BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}

