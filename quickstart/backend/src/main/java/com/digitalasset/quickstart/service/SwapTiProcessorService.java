package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.constants.SwapConstants;
import com.digitalasset.quickstart.dto.*;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionWithMemo;
import com.digitalasset.quickstart.service.TransferInstructionChoiceContextService.ChoiceContextResult;
import com.digitalasset.quickstart.store.SwapInspectStore;
import com.digitalasset.quickstart.validation.SwapValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import com.google.rpc.RequestInfo;
import com.google.rpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@Profile("prod")
public class SwapTiProcessorService {

    private static final Logger LOG = LoggerFactory.getLogger(SwapTiProcessorService.class);

    // ── Default retry constants (used when feature flags are OFF or as fallbacks) ──
    private static final int DEFAULT_MAX_INACTIVE_RETRIES = 11;
    private static final long DEFAULT_DEADLINE_HEADROOM_SECONDS = 30;

    // Exponential backoff cap: 2s, 4s, 8s, 15s, 20s, 30s, 30s, 30s, ...
    private static final long[] RETRY_DELAY_SECONDS = {2, 4, 8, 15, 20, 30, 30, 30, 30, 30, 30, 30};
    // When data is confirmed stale (Scan returned identical CIDs), use longer delays
    private static final long[] STALE_RETRY_DELAY_SECONDS = {5, 10, 15, 20, 25, 30, 30, 30, 30, 30, 30, 30};
    private static final double JITTER_FACTOR = 0.25; // +/-25% jitter
    private static final java.util.Random JITTER_RNG = new java.util.Random();

    private final TransferInstructionAcsQueryService tiQueryService;
    private final HoldingPoolService holdingPoolService;
    private final HoldingSelectorService holdingSelectorService;
    private final PayoutService payoutService;
    private final LedgerApi ledgerApi;
    private final AuthUtils authUtils;
    private final IdempotencyService idempotencyService;
    private final TransferInstructionChoiceContextService choiceContextService;
    private final SwapValidator swapValidator;
    private final TransactionHistoryService transactionHistoryService;
    private final OpenMiningRoundRefreshService omrRefreshService;
    private final SwapInspectStore inspectStore;

    // Per-requestId counter for the "Must be unlocked" retry-once-with-stale-cids strategy.
    // Cleared on terminal write (success or dead-set).
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> mustBeUnlockedAttempts =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired(required = false)
    private FeaturedAppMarkerService featuredAppMarkerService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${holdingpool.package-id:}")
    private String holdingPoolPackageId;

    // ── Part A: Configurable retry budget feature flags ──
    @Value("${clearportx.swap.retry.enabled:true}")
    private boolean retryEnabled;

    @Value("${clearportx.swap.retry.max-attempts:12}")
    private int retryMaxAttempts;

    @Value("${clearportx.swap.retry.max-wall-ms:300000}")
    private long retryMaxWallMs;

    @Value("${clearportx.swap.retry.backoff-max-ms:30000}")
    private long retryBackoffMaxMs;

    @Value("${clearportx.swap.retry.deadline-margin-ms:10000}")
    private long retryDeadlineMarginMs;

    // ── Part B: OMR participant-refresh feature flag ──
    @Value("${clearportx.swap.participant-refresh-omr.enabled:true}")
    private boolean omrRefreshEnabled;

    public SwapTiProcessorService(
            TransferInstructionAcsQueryService tiQueryService,
            HoldingPoolService holdingPoolService,
            HoldingSelectorService holdingSelectorService,
            PayoutService payoutService,
            LedgerApi ledgerApi,
            AuthUtils authUtils,
            IdempotencyService idempotencyService,
            TransferInstructionChoiceContextService choiceContextService,
            SwapValidator swapValidator,
            TransactionHistoryService transactionHistoryService,
            OpenMiningRoundRefreshService omrRefreshService,
            SwapInspectStore inspectStore
    ) {
        this.tiQueryService = tiQueryService;
        this.holdingPoolService = holdingPoolService;
        this.holdingSelectorService = holdingSelectorService;
        this.payoutService = payoutService;
        this.ledgerApi = ledgerApi;
        this.authUtils = authUtils;
        this.idempotencyService = idempotencyService;
        this.choiceContextService = choiceContextService;
        this.swapValidator = swapValidator;
        this.transactionHistoryService = transactionHistoryService;
        this.omrRefreshService = omrRefreshService;
        this.inspectStore = inspectStore;
    }

    @WithSpan
    public CompletableFuture<Result<SwapConsumeResponse, ApiError>> consume(final SwapConsumeRequest request) {
        if (request == null || request.requestId == null || request.requestId.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("requestId is required", "requestId")));
        }

        Object cached = idempotencyService.checkIdempotency(request.requestId);
        if (cached instanceof SwapConsumeResponse cachedResponse) {
            return CompletableFuture.completedFuture(Result.ok(cachedResponse));
        }

        if (holdingPoolPackageId == null || holdingPoolPackageId.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("holdingpool.package-id is not configured", "holdingpool.package-id")));
        }

        String operator = authUtils.getAppProviderPartyId();
        long maxAgeSeconds = request.maxAgeSeconds != null && request.maxAgeSeconds > 0
                ? request.maxAgeSeconds
                : 7200L;

        Result<List<TransferInstructionWithMemo>, DomainError> pending = tiQueryService.listForReceiverWithMemo(operator);
        if (pending.isErr()) {
            return CompletableFuture.completedFuture(Result.err(domainError("Failed to query pending TIs", pending.getErrorUnsafe())));
        }

        Result<Selection, ApiError> selected = selectCandidate(pending.getValueUnsafe(), request.requestId, maxAgeSeconds, operator);
        if (selected.isErr()) {
            return CompletableFuture.completedFuture(Result.err(selected.getErrorUnsafe()));
        }

        Selection chosen = selected.getValueUnsafe();
        Candidate candidate = chosen.candidate;
        SwapMemo memo = candidate.memo;
        String memoRaw = candidate.memoRaw;
        TransferInstructionDto ti = candidate.transfer;

        Result<SwapMemo, ApiError> memoValidation = validateMemo(memo, request.requestId, chosen.matchedRequestId);
        if (memoValidation.isErr()) {
            return CompletableFuture.completedFuture(Result.err(memoValidation.getErrorUnsafe()));
        }

        if (memo.receiverParty != null && ti.sender() != null && !memo.receiverParty.equals(ti.sender())) {
            return CompletableFuture.completedFuture(Result.err(preconditionError(
                    "memo.receiverParty does not match inbound TI sender",
                    Map.of("memoReceiverParty", memo.receiverParty, "tiSender", ti.sender())
            )));
        }

        Instant deadline;
        try {
            deadline = Instant.parse(memo.deadline);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(Result.err(validationError("memo.deadline is invalid", "deadline")));
        }

        Instant now = Instant.now();
        if (now.isAfter(deadline)) {
            ApiError err = preconditionError(
                    "Swap deadline has expired",
                    Map.of("deadline", memo.deadline, "now", now.toString())
            );
            inspectStore.markFailed(request.requestId, "SWAP_DEADLINE_EXPIRED", err.message);
            mustBeUnlockedAttempts.remove(request.requestId);
            return CompletableFuture.completedFuture(Result.err(err));
        }

        return holdingPoolService.getByContractId(memo.poolCid)
                .thenCompose(poolResult -> {
                    if (poolResult.isErr()) {
                        return completedError(domainError("Pool not found or not visible", poolResult.getErrorUnsafe()));
                    }
                    HoldingPoolResponse pool = poolResult.getValueUnsafe();
                    if (pool.status == null || !"active".equalsIgnoreCase(pool.status)) {
                        return completedError(preconditionError("Pool is not active", Map.of("status", pool.status)));
                    }

                    String normalizedDirection = SwapDirection.normalizeMemo(memo.direction);
                    SwapDirection direction = SwapDirection.fromNormalized(normalizedDirection);
                    if (direction == null) {
                        return completedError(validationError("memo.direction is invalid", "direction"));
                    }

                    HoldingPoolCreateRequest.InstrumentRef inputInstrument = direction == SwapDirection.A2B ? pool.instrumentA : pool.instrumentB;
                    HoldingPoolCreateRequest.InstrumentRef outputInstrument = direction == SwapDirection.A2B ? pool.instrumentB : pool.instrumentA;

                    if (inputInstrument == null || outputInstrument == null) {
                        return completedError(preconditionError("Pool instruments are missing", Map.of("poolCid", memo.poolCid)));
                    }

                    if (!matchesInstrument(ti, inputInstrument)) {
                        return completedError(preconditionError(
                                "Inbound TI instrument does not match swap direction",
                                Map.of("tiInstrumentAdmin", ti.admin(), "tiInstrumentId", ti.instrumentId(),
                                        "expectedAdmin", inputInstrument.admin, "expectedId", inputInstrument.id,
                                        "direction", memo.direction)
                        ));
                    }

                    BigDecimal amountIn;
                    BigDecimal minOut;
                    try {
                        amountIn = normalizeAmount(ti.amount());
                        minOut = normalizeAmount(memo.minOut);
                    } catch (Exception e) {
                        return completedError(validationError("amount format is invalid", "amount"));
                    }

                    try {
                        swapValidator.validateInputAmount(amountIn);
                        swapValidator.validateMinOutput(minOut);
                    } catch (Exception e) {
                        return completedError(validationError(e.getMessage(), "amountIn"));
                    }

                    BigDecimal amountOut = computeOutputAmount(pool, direction, amountIn);
                    if (amountOut.compareTo(minOut) < 0) {
                        ApiError slipErr = preconditionError(
                                "Output amount below minimum",
                                Map.of("amountOut", amountOut.toPlainString(), "minOut", minOut.toPlainString())
                        );
                        inspectStore.markFailed(request.requestId, "SLIPPAGE_BELOW_MIN_OUT", slipErr.message);
                        mustBeUnlockedAttempts.remove(request.requestId);
                        return completedError(slipErr);
                    }

                    BigDecimal availableOut = getAvailableOut(pool, direction);
                    if (amountOut.compareTo(availableOut) > 0) {
                        return completedError(preconditionError(
                                "Insufficient available liquidity for output",
                                Map.of("amountOut", amountOut.toPlainString(), "availableOut", availableOut.toPlainString())
                        ));
                    }

                    HoldingSelectRequest selectRequest = new HoldingSelectRequest(
                            operator,
                            outputInstrument.admin,
                            outputInstrument.id,
                            amountOut,
                            0,
                            0
                    );

                    return holdingSelectorService.selectHoldingOnce(selectRequest)
                            .thenCompose(holdingSelection -> {
                                if (!holdingSelection.found()) {
                                    return completedError(preconditionError(
                                            "No output holding found for payout",
                                            Map.of("admin", outputInstrument.admin, "id", outputInstrument.id, "minAmount", amountOut.toPlainString())
                                    ));
                                }

                                String outputHoldingCid = holdingSelection.holdingCid();

                                return prepareAndExecuteSwap(
                                        request, ti, pool, operator, inputInstrument,
                                        outputInstrument, outputHoldingCid, direction,
                                        normalizedDirection, minOut, amountIn, amountOut,
                                        deadline, memo, memoRaw, 0, Collections.emptySet(),
                                        Instant.now(), false)
                                        .thenCompose(bundleResult -> {
                                            if (bundleResult.isErr()) {
                                                return completedError(bundleResult.getErrorUnsafe());
                                            }
                                            SwapExecutionBundle bundle = bundleResult.getValueUnsafe();
                                            String updateId = extractUpdateId(bundle.response());
                                            if (updateId == null || updateId.isBlank()) {
                                                return completedError(ApiError.of(ErrorCode.INTERNAL, "ExecuteSwap returned no updateId"));
                                            }
                                            PayoutOutcomeInfo payoutOutcome = extractPayoutOutcome(bundle.response());
                                            if (payoutOutcome == null) {
                                                return completedError(ApiError.of(ErrorCode.INTERNAL, "ExecuteSwap returned no payout outcome"));
                                            }
                                            if (!payoutOutcome.completed && (payoutOutcome.payoutCid == null || payoutOutcome.payoutCid.isBlank())) {
                                                return completedError(ApiError.of(ErrorCode.INTERNAL, "ExecuteSwap returned no payoutCid"));
                                            }
                                            SwapConsumeResponse response = new SwapConsumeResponse();
                                            response.requestId = request.requestId;
                                            response.inboundTiCid = ti.contractId();
                                            response.intentCid = null;
                                            response.swapIntentCid = null;
                                            response.poolCid = memo.poolCid;
                                            response.direction = memo.direction;
                                            response.amountIn = amountIn.toPlainString();
                                            response.amountOut = amountOut.toPlainString();
                                            response.minOut = minOut.toPlainString();
                                            response.executeSwapLedgerUpdateId = updateId;
                                            response.executeSwapStatus = "SUCCEEDED";
                                            response.payoutCid = payoutOutcome.payoutCid;
                                            response.payoutExecuteBefore = deadline.toString();
                                            response.payoutFactoryId = bundle.payoutPlan().factoryCid();
                                            response.payoutDisclosedContractsCount = bundle.payoutPlan().disclosedContracts().size();
                                            response.payoutStatus = payoutOutcome.completed ? "COMPLETED" : "CREATED";
                                            response.nextAction = payoutOutcome.completed ? "NONE" : "ACCEPT_PAYOUT_IN_LOOP";
                                            response.receiverParty = memo.receiverParty;

                                            try {
                                                String inputSymbol = direction == SwapDirection.A2B
                                                        ? displaySymbol(pool.instrumentA.id)
                                                        : displaySymbol(pool.instrumentB.id);
                                                String outputSymbol = direction == SwapDirection.A2B
                                                        ? displaySymbol(pool.instrumentB.id)
                                                        : displaySymbol(pool.instrumentA.id);
                                                transactionHistoryService.recordSwap(
                                                        request.requestId,
                                                        pool.contractId,
                                                        pool.contractId,
                                                        inputSymbol,
                                                        outputSymbol,
                                                        amountIn,
                                                        amountOut,
                                                        memo.receiverParty
                                                );
                                            } catch (Exception e) {
                                                LOG.warn("[SwapConsume] Failed to record transaction history: {}", e.getMessage());
                                            }

                                            if (featuredAppMarkerService != null) {
                                                String inId = inputInstrument.id;
                                                String outId = outputInstrument.id;
                                                featuredAppMarkerService.createMarker(request.requestId, "SWAP",
                                                        pool.contractId + ":" + inId + "/" + outId + ":" + amountIn);
                                            }

                                            idempotencyService.registerSuccess(request.requestId, request.requestId, updateId, response);
                                            inspectStore.markCompleted(request.requestId, updateId, payoutOutcome.payoutCid);
                                            mustBeUnlockedAttempts.remove(request.requestId);
                                            return CompletableFuture.completedFuture(Result.ok(response));
                                        });
                            });
                });
    }

    private Result<Selection, ApiError> selectCandidate(
            List<TransferInstructionWithMemo> items,
            String requestId,
            long maxAgeSeconds,
            String receiverParty
    ) {
        Instant cutoff = Instant.now().minusSeconds(maxAgeSeconds);
        List<Candidate> candidates = new ArrayList<>();
        List<String> invalidMemoCids = new ArrayList<>();

        for (TransferInstructionWithMemo row : items) {
            TransferInstructionDto ti = row.transferInstruction();
            if (ti == null || ti.contractId() == null) continue;
            if (receiverParty != null && !receiverParty.equals(ti.receiver())) continue;

            String memoRaw = row.memo();
            if (memoRaw == null || memoRaw.isBlank()) {
                invalidMemoCids.add(ti.contractId());
                continue;
            }

            SwapMemo memo = parseMemo(memoRaw);
            if (memo == null) {
                if (requestId != null && memoRaw.contains(requestId)) {
                    return Result.err(validationError("memo JSON is invalid", "memo"));
                }
                invalidMemoCids.add(ti.contractId());
                continue;
            }
            if (memo.poolCid == null || memo.direction == null) continue;
            candidates.add(new Candidate(ti, memo, memoRaw));
        }

        Optional<Candidate> match = candidates.stream()
                .filter(c -> requestId.equals(c.memo.requestId))
                .max(Comparator.comparing(c -> c.transfer.executeBefore()));
        if (match.isPresent()) {
            return Result.ok(new Selection(match.get(), true));
        }

        Optional<Selection> fallback = candidates.stream()
                .filter(c -> c.transfer.executeBefore() != null && c.transfer.executeBefore().isAfter(cutoff))
                .max(Comparator.comparing(c -> c.transfer.executeBefore()))
                .map(c -> new Selection(c, false));
        if (fallback.isPresent()) {
            return Result.ok(fallback.get());
        }

        if (!invalidMemoCids.isEmpty()) {
            return Result.err(new ApiError(
                    ErrorCode.VALIDATION,
                    "memo JSON is invalid or missing",
                    Map.of("contractIds", invalidMemoCids),
                    false,
                    null,
                    null
            ));
        }

        return Result.err(preconditionError(
                "Inbound TransferInstruction not found for requestId (may be consumed already)",
                Map.of("requestId", requestId)
        ));
    }

    private SwapMemo parseMemo(String memoRaw) {
        if (memoRaw == null || memoRaw.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(memoRaw, SwapMemo.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse swap memo JSON: {}", e.getMessage());
            return null;
        }
    }

    private Result<SwapMemo, ApiError> validateMemo(SwapMemo memo, String requestId, boolean requireRequestIdMatch) {
        if (memo == null) {
            return Result.err(validationError("Swap memo is missing", "memo"));
        }
        if (memo.requestId == null || memo.requestId.isBlank()) {
            return Result.err(validationError("memo.requestId is required", "requestId"));
        } else if (requireRequestIdMatch && !memo.requestId.equals(requestId)) {
            return Result.err(preconditionError(
                    "requestId does not match memo",
                    Map.of("requestId", requestId, "memoRequestId", memo.requestId)
            ));
        }
        if (memo.poolCid == null || memo.poolCid.isBlank()) {
            return Result.err(validationError("memo.poolCid is required", "poolCid"));
        }
        if (memo.direction == null || memo.direction.isBlank()) {
            return Result.err(validationError("memo.direction is required", "direction"));
        }
        if (memo.minOut == null || memo.minOut.isBlank()) {
            return Result.err(validationError("memo.minOut is required", "minOut"));
        }
        if (memo.receiverParty == null || memo.receiverParty.isBlank()) {
            return Result.err(validationError("memo.receiverParty is required", "receiverParty"));
        }
        if (memo.deadline == null || memo.deadline.isBlank()) {
            return Result.err(validationError("memo.deadline is required", "deadline"));
        }
        return Result.ok(memo);
    }

    private boolean matchesInstrument(TransferInstructionDto ti, HoldingPoolCreateRequest.InstrumentRef expected) {
        return expected != null
                && expected.admin != null
                && expected.id != null
                && expected.admin.equals(ti.admin())
                && expected.id.equals(ti.instrumentId());
    }

    private BigDecimal computeOutputAmount(HoldingPoolResponse pool, SwapDirection direction, BigDecimal amountIn) {
        BigDecimal reserveIn = direction == SwapDirection.A2B
                ? normalizeAmount(pool.reserveAmountA)
                : normalizeAmount(pool.reserveAmountB);
        BigDecimal reserveOut = direction == SwapDirection.A2B
                ? normalizeAmount(pool.reserveAmountB)
                : normalizeAmount(pool.reserveAmountA);

        BigDecimal feeRate = pool.feeBps != null
                ? new BigDecimal(pool.feeBps).movePointLeft(4).setScale(SwapConstants.SCALE, RoundingMode.DOWN)
                : SwapConstants.FEE_RATE;

        BigDecimal inputAfterFee = amountIn.multiply(BigDecimal.ONE.subtract(feeRate), MathContext.DECIMAL64)
                .setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        BigDecimal numerator = inputAfterFee.multiply(reserveOut, MathContext.DECIMAL64);
        BigDecimal denominator = reserveIn.add(inputAfterFee);
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        return numerator.divide(denominator, SwapConstants.SCALE, RoundingMode.DOWN);
    }

    private BigDecimal getAvailableOut(HoldingPoolResponse pool, SwapDirection direction) {
        BigDecimal reserveOut = direction == SwapDirection.A2B
                ? normalizeAmount(pool.reserveAmountB)
                : normalizeAmount(pool.reserveAmountA);
        BigDecimal lockedOut = direction == SwapDirection.A2B
                ? normalizeAmount(pool.lockedAmountB)
                : normalizeAmount(pool.lockedAmountA);
        BigDecimal available = reserveOut.subtract(lockedOut);
        if (available.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        return available.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
    }

    private CompletableFuture<Result<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse, ApiError>> executeSwapFromTransferInstruction(
            String poolCid,
            String transferInstructionCid,
            String outputHoldingCid,
            SwapDirection direction,
            BigDecimal minOutput,
            Instant expiresAt,
            String recipient,
            String payoutFactoryCid,
            ValueOuterClass.Record payoutExtraArgs,
            String payoutMemo,
            ValueOuterClass.Record acceptExtraArgs,
            List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> disclosedContracts,
            String synchronizerId
    ) {
        ValueOuterClass.Record choiceArgs = ValueOuterClass.Record.newBuilder()
                .addFields(recordField("transferInstructionCid", contractIdValue(transferInstructionCid)))
                .addFields(recordField("outputHoldingCid", contractIdValue(outputHoldingCid)))
                .addFields(recordField("direction", swapDirectionValue(direction)))
                .addFields(recordField("minOutput", numericValue(minOutput)))
                .addFields(recordField("expiresAt", timestampValue(expiresAt)))
                .addFields(recordField("recipient", partyValue(recipient)))
                .addFields(recordField("payoutFactoryCid", contractIdValue(payoutFactoryCid)))
                .addFields(recordField("payoutExtraArgs", recordValue(payoutExtraArgs)))
                .addFields(recordField("payoutMemo", textValue(payoutMemo)))
                .addFields(recordField("acceptExtraArgs", optionalValue(recordValue(acceptExtraArgs))))
                .build();

        return ledgerApi.exerciseRawWithLabel(
                        "ExecuteSwapFromTransferInstructionV2",
                        holdingPoolTemplateId(),
                        poolCid,
                        "ExecuteSwapFromTransferInstructionV2",
                        choiceArgs,
                        List.of(authUtils.getAppProviderPartyId()),
                        List.of(),
                        disclosedContracts,
                        synchronizerId
                )
                .handle((resp, throwable) -> {
                    if (throwable != null) {
                        // ── D8.1-LOG: capture full Canton ErrorInfo before downstream collapses to .message ──
                        String errorCategory = "";
                        String definiteAnswer = "";
                        String traceId = "";
                        String grpcCode = "";
                        Throwable current = throwable;
                        while (current != null) {
                            if (current instanceof StatusRuntimeException sre) {
                                grpcCode = sre.getStatus().getCode().name();
                                try {
                                    Status detailedStatus = StatusProto.fromThrowable(sre);
                                    if (detailedStatus != null) {
                                        for (Any detail : detailedStatus.getDetailsList()) {
                                            if (detail.is(ErrorInfo.class)) {
                                                try {
                                                    ErrorInfo info = detail.unpack(ErrorInfo.class);
                                                    if (errorCategory.isEmpty()) {
                                                        errorCategory = info.getMetadataOrDefault("category", "");
                                                    }
                                                    if (definiteAnswer.isEmpty()) {
                                                        definiteAnswer = info.getMetadataOrDefault("definite_answer", "");
                                                    }
                                                } catch (InvalidProtocolBufferException ignored) { /* fall through */ }
                                            }
                                            if (detail.is(RequestInfo.class)) {
                                                try {
                                                    RequestInfo req = detail.unpack(RequestInfo.class);
                                                    if (traceId.isEmpty()) {
                                                        traceId = req.getRequestId();
                                                    }
                                                } catch (InvalidProtocolBufferException ignored) { /* fall through */ }
                                            }
                                        }
                                    }
                                } catch (Exception ignored) { /* belt-and-suspenders; never block the failure path */ }
                                break;
                            }
                            current = current.getCause();
                        }
                        LOG.error("[V2-ERROR-INFO] step=ExecuteSwapFromTransferInstructionV2 grpcCode={} errorCategory={} definiteAnswer={} traceId={} cause={}",
                                grpcCode, errorCategory, definiteAnswer, traceId, throwable.getMessage());
                        return Result.err(ApiError.of(ErrorCode.LEDGER_REJECTED, throwable.getMessage()));
                    }
                    return Result.ok(resp);
                });
    }

    private CompletableFuture<Result<SwapExecutionBundle, ApiError>> prepareAndExecuteSwap(
            SwapConsumeRequest request,
            TransferInstructionDto ti,
            HoldingPoolResponse pool,
            String operator,
            HoldingPoolCreateRequest.InstrumentRef inputInstrument,
            HoldingPoolCreateRequest.InstrumentRef outputInstrument,
            String outputHoldingCid,
            SwapDirection direction,
            String normalizedDirection,
            BigDecimal minOut,
            BigDecimal amountIn,
            BigDecimal amountOut,
            Instant deadline,
            SwapMemo memo,
            String memoRaw,
            int attempt,
            Set<String> previousDisclosedCids,
            Instant wallClockStart,
            boolean isOmrResubmit
    ) {
        LOG.info("[SwapConsume] DIAG tiCid={} inputAdmin={} inputInstrumentId={} attempt={}",
                ti.contractId(), inputInstrument.admin, inputInstrument.id, attempt);

        Result<ChoiceContextResult, ApiError> choiceCtx =
                choiceContextService.resolveDisclosedContracts(ti.contractId(), inputInstrument.admin, request.requestId, previousDisclosedCids);
        if (choiceCtx.isErr()) {
            return CompletableFuture.completedFuture(Result.err(choiceCtx.getErrorUnsafe()));
        }
        ChoiceContextResult ctx = choiceCtx.getValueUnsafe();

        Result<PayoutService.TransferFactoryPlan, ApiError> payoutPlanResult =
                payoutService.prepareTransferFactory(
                        outputInstrument.admin,
                        outputInstrument.id,
                        outputHoldingCid,
                        memo.receiverParty,
                        amountOut,
                        deadline,
                        memoRaw,
                        "swap-payout-" + request.requestId
                );
        if (payoutPlanResult.isErr()) {
            return CompletableFuture.completedFuture(Result.err(
                    annotateNoSynchronizer(payoutPlanResult.getErrorUnsafe(),
                            "PreparePayout",
                            List.of(operator),
                            List.of(operator),
                            null)));
        }
        PayoutService.TransferFactoryPlan payoutPlan = payoutPlanResult.getValueUnsafe();

        List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> mergedDisclosed =
                mergeDisclosed(ctx.disclosedContracts(), payoutPlan.disclosedContracts());
        String synchronizerId = firstNonBlank(ctx.synchronizerId(), payoutPlan.synchronizerId());

        // Phase 3F.10 Step A — pre-emptive OMR safety-margin substitution.
        // Filter scan's OMR list locally (opensAt <= now AND latest opensAt AND
        // targetClosesAt - now > 30s) and substitute the chosen OMR into the
        // disclosed list before the choice exercise. Avoids the rotation-drift
        // "Must be unlocked" race when the registry returns a stale OMR.
        final List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> disclosed;
        try {
            disclosed = omrRefreshService.applySafetyMarginFromScan(
                    mergedDisclosed, synchronizerId, request.requestId).get();
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof OmrSafetyMarginFailException safety) {
                ApiError err = preconditionError(
                        safety.getMessage(),
                        Map.of("errorClass", OmrSafetyMarginFailException.ERROR_CLASS,
                                "requestId", request.requestId)
                );
                inspectStore.markFailed(request.requestId,
                        OmrSafetyMarginFailException.ERROR_CLASS, err.message);
                mustBeUnlockedAttempts.remove(request.requestId);
                return CompletableFuture.completedFuture(Result.err(err));
            }
            throw new RuntimeException(ex);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }

        LOG.info("[SwapConsume] merged disclosed contracts count={}", disclosed.size());
        disclosed.forEach(dc ->
            LOG.info("[SwapConsume] disclosed cid={} templateId={}",
                dc.getContractId(),
                dc.getTemplateId().getModuleName() + ":" + dc.getTemplateId().getEntityName()));

        // Round pre-flight (best-effort, fail-open) — surfaces the OMR cids the participant
        // is about to exercise against, so cross-referencing with scan's /round-of-latest-data
        // can pinpoint rotation-drift sub-mechanisms after the fact. Round-number extraction
        // is deferred (would need OMR daml-codegen).
        List<String> omrCids = disclosed.stream()
                .filter(dc -> "Splice.Round".equals(dc.getTemplateId().getModuleName())
                           && "OpenMiningRound".equals(dc.getTemplateId().getEntityName()))
                .map(dc -> dc.getContractId())
                .toList();
        LOG.info("[SwapPreflight] requestId={} omrCount={} omrCids={} synchronizerId={}",
                request.requestId, omrCids.size(), omrCids, synchronizerId);

        Set<String> currentDisclosedCids = new HashSet<>();
        for (var dc : disclosed) {
            currentDisclosedCids.add(dc.getContractId());
        }

        boolean dataIsStale = !previousDisclosedCids.isEmpty() && currentDisclosedCids.equals(previousDisclosedCids);
        if (dataIsStale) {
            LOG.warn("[SwapConsume] Scan returned identical stale disclosed contracts, CIDs unchanged across attempts, attempt={}", attempt);
        } else if (!previousDisclosedCids.isEmpty()) {
            Set<String> changed = new HashSet<>(currentDisclosedCids);
            changed.removeAll(previousDisclosedCids);
            LOG.info("[SwapConsume] Scan returned fresh disclosed contracts, {} CIDs changed, attempt={}", changed.size(), attempt);
        }

        LOG.info("[SwapConsume] requestId={} operator={} inboundTiCid={} poolCid={} amountIn={} amountOut={} directionRaw={} directionNormalized={} damlDirection={} acceptContextKeys={} actAs=[{}] readAs=[{}] attempt={}",
                request.requestId,
                operator,
                ti.contractId(),
                pool.contractId,
                amountIn.toPlainString(),
                amountOut.toPlainString(),
                memo.direction,
                normalizedDirection,
                direction.damlConstructor,
                ctx.contextKeyCount(),
                operator,
                "",
                attempt);

        return executeSwapFromTransferInstruction(
                        pool.contractId,
                        ti.contractId(),
                        outputHoldingCid,
                        direction,
                        minOut,
                        deadline,
                        memo.receiverParty,
                        payoutPlan.factoryCid(),
                        payoutPlan.extraArgs(),
                        memoRaw,
                        ctx.extraArgs(),
                        disclosed,
                        synchronizerId
                )
                .thenCompose(executeResult -> {
                    if (executeResult.isErr()) {
                        ApiError error = executeResult.getErrorUnsafe();

                        // ── Phase 3F.9 dead-set classifiers ──
                        if (isMustBeUnlockedError(error)) {
                            int attempts = mustBeUnlockedAttempts.merge(request.requestId, 1, Integer::sum);
                            if (attempts == 1) {
                                LOG.warn("[SwapPoller] Must-be-unlocked detected requestId={} attempt={}, retrying once with stale-cids ({} cids)",
                                        request.requestId, attempts, currentDisclosedCids.size());
                                return prepareAndExecuteSwap(request, ti, pool, operator, inputInstrument,
                                        outputInstrument, outputHoldingCid, direction, normalizedDirection,
                                        minOut, amountIn, amountOut, deadline, memo, memoRaw,
                                        attempt + 1, currentDisclosedCids, wallClockStart, false);
                            }
                            return deadSetAndReturnWithUserMessage(request.requestId, "AMM_LOCKED_TI_REJECTED",
                                    "AMM rejected locked TransferInstruction. The wallet's swap submission delivered a locked Amulet; the swap choice precondition requires unlocked input. This is a known DAR-level limitation (atomic-unlock upgrade pending). Re-attempting will not help — wait for the DAR upgrade.",
                                    error, "ExecuteSwapFromTransferInstruction", operator, synchronizerId, attempts);
                        }
                        if (isDeadlineExpiredErrorPostExecute(error)) {
                            return deadSetAndReturn(request.requestId, "SWAP_DEADLINE_EXPIRED", error,
                                    "ExecuteSwapFromTransferInstruction", operator, synchronizerId, 1);
                        }
                        if (isContractNotFoundError(error, ti.contractId())) {
                            return deadSetAndReturn(request.requestId, "TI_ARCHIVED", error,
                                    "ExecuteSwapFromTransferInstruction", operator, synchronizerId, 1);
                        }
                        if (isOutputBelowMinOutError(error)) {
                            return deadSetAndReturn(request.requestId, "SLIPPAGE_BELOW_MIN_OUT", error,
                                    "ExecuteSwapFromTransferInstruction", operator, synchronizerId, 1);
                        }

                        if (isInactiveContractsError(error)) {
                            return handleInactiveContractsError(
                                    error, request, ti, pool, operator, inputInstrument,
                                    outputInstrument, outputHoldingCid, direction, normalizedDirection,
                                    minOut, amountIn, amountOut, deadline, memo, memoRaw,
                                    attempt, currentDisclosedCids, wallClockStart, isOmrResubmit,
                                    dataIsStale, disclosed, synchronizerId,
                                    payoutPlan, ctx.extraArgs());
                        }

                        // Non-INACTIVE_CONTRACTS error: fail fast
                        return CompletableFuture.completedFuture(Result.err(
                                annotateNoSynchronizer(error,
                                        "ExecuteSwapFromTransferInstruction",
                                        List.of(operator),
                                        List.of(),
                                        synchronizerId)));
                    }
                    return CompletableFuture.completedFuture(
                            Result.ok(new SwapExecutionBundle(
                                    executeResult.getValueUnsafe(), payoutPlan, synchronizerId)));
                });
    }

    /**
     * Handle INACTIVE_CONTRACTS error with two-phase mitigation:
     *   Phase 1 (Part B): OMR participant-refresh fast-path — if the stale contract is an OpenMiningRound,
     *                      query local participant ACS for the current OMR and resubmit immediately.
     *   Phase 2 (Part A): Bounded retry with configurable backoff — re-fetch from Scan after sleep.
     */
    private CompletableFuture<Result<SwapExecutionBundle, ApiError>> handleInactiveContractsError(
            ApiError error,
            SwapConsumeRequest request,
            TransferInstructionDto ti,
            HoldingPoolResponse pool,
            String operator,
            HoldingPoolCreateRequest.InstrumentRef inputInstrument,
            HoldingPoolCreateRequest.InstrumentRef outputInstrument,
            String outputHoldingCid,
            SwapDirection direction,
            String normalizedDirection,
            BigDecimal minOut,
            BigDecimal amountIn,
            BigDecimal amountOut,
            Instant deadline,
            SwapMemo memo,
            String memoRaw,
            int attempt,
            Set<String> currentDisclosedCids,
            Instant wallClockStart,
            boolean isOmrResubmit,
            boolean dataIsStale,
            List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> disclosed,
            String synchronizerId,
            PayoutService.TransferFactoryPlan payoutPlan,
            ValueOuterClass.Record acceptExtraArgs
    ) {
        Instant now = Instant.now();
        long remainingSec = Duration.between(now, deadline).getSeconds();
        long wallElapsedMs = Duration.between(wallClockStart, now).toMillis();

        // Effective retry parameters (configurable via feature flags)
        int effectiveMaxRetries = retryEnabled ? retryMaxAttempts : DEFAULT_MAX_INACTIVE_RETRIES;
        long effectiveDeadlineMarginSec = retryEnabled ? (retryDeadlineMarginMs / 1000) : DEFAULT_DEADLINE_HEADROOM_SECONDS;
        long effectiveMaxWallMs = retryEnabled ? retryMaxWallMs : 300_000L;

        Set<String> inactiveCids = parseInactiveContractIds(error);
        LOG.warn("[SwapConsume] INACTIVE_CONTRACTS attempt={} {} data remainingSec={} wallElapsedMs={} inactiveCids={} isOmrResubmit={}",
                attempt, dataIsStale ? "STALE" : "fresh", remainingSec, wallElapsedMs, inactiveCids, isOmrResubmit);

        // ── Guard: max retries ──
        if (attempt >= effectiveMaxRetries) {
            LOG.error("[SwapConsume] RetryBudget exhausted: attempt={} maxRetries={} remainingSec={}",
                    attempt, effectiveMaxRetries, remainingSec);
            inspectStore.markFailed(request.requestId, "INACTIVE_CONTRACTS_MAX_RETRIES_EXHAUSTED", error.message);
            mustBeUnlockedAttempts.remove(request.requestId);
            return CompletableFuture.completedFuture(Result.err(
                    annotateInactiveContracts(error, attempt, remainingSec, "MAX_RETRIES_EXHAUSTED")));
        }

        // ── Guard: deadline margin ──
        if (remainingSec <= effectiveDeadlineMarginSec) {
            LOG.error("[SwapConsume] RetryBudget deadline too close: remainingSec={} margin={}s attempt={}",
                    remainingSec, effectiveDeadlineMarginSec, attempt);
            inspectStore.markFailed(request.requestId, "INACTIVE_CONTRACTS_DEADLINE_EXCEEDED", error.message);
            mustBeUnlockedAttempts.remove(request.requestId);
            return CompletableFuture.completedFuture(Result.err(
                    annotateInactiveContracts(error, attempt, remainingSec, "DEADLINE_EXCEEDED")));
        }

        // ── Guard: max wall clock ──
        if (wallElapsedMs >= effectiveMaxWallMs) {
            LOG.error("[SwapConsume] RetryBudget wall clock exceeded: wallElapsedMs={} maxWallMs={} attempt={}",
                    wallElapsedMs, effectiveMaxWallMs, attempt);
            inspectStore.markFailed(request.requestId, "INACTIVE_CONTRACTS_WALL_CLOCK_EXCEEDED", error.message);
            mustBeUnlockedAttempts.remove(request.requestId);
            return CompletableFuture.completedFuture(Result.err(
                    annotateInactiveContracts(error, attempt, remainingSec, "WALL_CLOCK_EXCEEDED")));
        }

        // ── Phase 1 (Part B): OMR participant-refresh fast-path ──
        // Only attempt once per cycle (skip if this was already an OMR resubmit)
        if (omrRefreshEnabled && !isOmrResubmit) {
            return omrRefreshService.refreshOpenMiningRound(disclosed, inactiveCids, synchronizerId, operator)
                    .thenCompose(refreshResult -> {
                        if (refreshResult.replaced()) {
                            LOG.info("[OMR-Refresh] Fast-path resubmit: old={} new={} attempt={}",
                                    abbreviate(refreshResult.oldCid()), abbreviate(refreshResult.newCid()), attempt);

                            // Immediately resubmit with refreshed disclosed contracts and SAME extraArgs
                            // (no sleep, no Scan re-fetch)
                            return executeSwapFromTransferInstruction(
                                    pool.contractId,
                                    ti.contractId(),
                                    outputHoldingCid,
                                    direction,
                                    minOut,
                                    deadline,
                                    memo.receiverParty,
                                    payoutPlan.factoryCid(),
                                    payoutPlan.extraArgs(),
                                    memoRaw,
                                    acceptExtraArgs,
                                    refreshResult.updatedDisclosed(),
                                    synchronizerId
                            ).thenCompose(resubmitResult -> {
                                if (resubmitResult.isErr()) {
                                    ApiError resubmitError = resubmitResult.getErrorUnsafe();
                                    if (isInactiveContractsError(resubmitError)) {
                                        LOG.warn("[OMR-Refresh] Resubmit still got INACTIVE_CONTRACTS, falling back to retry loop");
                                        // Fall through to Part A retry with sleep
                                        return doRetryWithBackoff(error, request, ti, pool, operator, inputInstrument,
                                                outputInstrument, outputHoldingCid, direction, normalizedDirection,
                                                minOut, amountIn, amountOut, deadline, memo, memoRaw,
                                                attempt, currentDisclosedCids, wallClockStart, dataIsStale,
                                                remainingSec, effectiveDeadlineMarginSec);
                                    }
                                    // Different error: fail fast
                                    return CompletableFuture.completedFuture(Result.<SwapExecutionBundle, ApiError>err(
                                            annotateNoSynchronizer(resubmitError,
                                                    "ExecuteSwapFromTransferInstruction-OMR-Resubmit",
                                                    List.of(operator), List.of(), synchronizerId)));
                                }
                                // OMR refresh resubmit succeeded!
                                LOG.info("[OMR-Refresh] Fast-path resubmit SUCCEEDED on attempt={}", attempt);
                                return CompletableFuture.completedFuture(
                                        Result.<SwapExecutionBundle, ApiError>ok(new SwapExecutionBundle(
                                                resubmitResult.getValueUnsafe(), payoutPlan, synchronizerId)));
                            });
                        }
                        // OMR not replaced, fall through to Part A
                        LOG.debug("[OMR-Refresh] No replacement made, falling back to retry loop");
                        return doRetryWithBackoff(error, request, ti, pool, operator, inputInstrument,
                                outputInstrument, outputHoldingCid, direction, normalizedDirection,
                                minOut, amountIn, amountOut, deadline, memo, memoRaw,
                                attempt, currentDisclosedCids, wallClockStart, dataIsStale,
                                remainingSec, effectiveDeadlineMarginSec);
                    });
        }

        // No OMR refresh (disabled or already tried) — go straight to Part A retry
        return doRetryWithBackoff(error, request, ti, pool, operator, inputInstrument,
                outputInstrument, outputHoldingCid, direction, normalizedDirection,
                minOut, amountIn, amountOut, deadline, memo, memoRaw,
                attempt, currentDisclosedCids, wallClockStart, dataIsStale,
                remainingSec, effectiveDeadlineMarginSec);
    }

    /**
     * Part A: Retry with configurable exponential backoff, bounded by deadline and wall clock.
     */
    private CompletableFuture<Result<SwapExecutionBundle, ApiError>> doRetryWithBackoff(
            ApiError error,
            SwapConsumeRequest request,
            TransferInstructionDto ti,
            HoldingPoolResponse pool,
            String operator,
            HoldingPoolCreateRequest.InstrumentRef inputInstrument,
            HoldingPoolCreateRequest.InstrumentRef outputInstrument,
            String outputHoldingCid,
            SwapDirection direction,
            String normalizedDirection,
            BigDecimal minOut,
            BigDecimal amountIn,
            BigDecimal amountOut,
            Instant deadline,
            SwapMemo memo,
            String memoRaw,
            int attempt,
            Set<String> currentDisclosedCids,
            Instant wallClockStart,
            boolean dataIsStale,
            long remainingSec,
            long effectiveDeadlineMarginSec
    ) {
        long baseDelaySec = dataIsStale
                ? STALE_RETRY_DELAY_SECONDS[Math.min(attempt, STALE_RETRY_DELAY_SECONDS.length - 1)]
                : RETRY_DELAY_SECONDS[Math.min(attempt, RETRY_DELAY_SECONDS.length - 1)];

        // Cap backoff at configured max
        long maxBackoffSec = retryEnabled ? (retryBackoffMaxMs / 1000) : 30;
        if (baseDelaySec > maxBackoffSec) {
            baseDelaySec = maxBackoffSec;
        }

        if (!sleepWithDeadlineCheck(baseDelaySec, deadline, effectiveDeadlineMarginSec)) {
            LOG.error("[SwapConsume] RetryBudget sleep would exceed deadline, aborting: attempt={}", attempt);
            inspectStore.markFailed(request.requestId, "INACTIVE_CONTRACTS_DEADLINE_EXCEEDED_DURING_BACKOFF", error.message);
            mustBeUnlockedAttempts.remove(request.requestId);
            return CompletableFuture.completedFuture(Result.err(
                    annotateInactiveContracts(error, attempt, remainingSec, "DEADLINE_EXCEEDED_DURING_BACKOFF")));
        }

        // Check wall clock again after sleep
        long wallElapsedAfterSleep = Duration.between(wallClockStart, Instant.now()).toMillis();
        long effectiveMaxWallMs = retryEnabled ? retryMaxWallMs : 300_000L;
        if (wallElapsedAfterSleep >= effectiveMaxWallMs) {
            LOG.error("[SwapConsume] RetryBudget wall clock exceeded after sleep: wallElapsedMs={} maxWallMs={}", wallElapsedAfterSleep, effectiveMaxWallMs);
            inspectStore.markFailed(request.requestId, "INACTIVE_CONTRACTS_WALL_CLOCK_EXCEEDED", error.message);
            mustBeUnlockedAttempts.remove(request.requestId);
            return CompletableFuture.completedFuture(Result.err(
                    annotateInactiveContracts(error, attempt, remainingSec, "WALL_CLOCK_EXCEEDED")));
        }

        LOG.info("[SwapConsume] RetryBudget retrying: attempt={} wallElapsedMs={} remainingSec={}",
                attempt + 1, wallElapsedAfterSleep, Duration.between(Instant.now(), deadline).getSeconds());

        return prepareAndExecuteSwap(request, ti, pool, operator, inputInstrument,
                outputInstrument, outputHoldingCid, direction, normalizedDirection,
                minOut, amountIn, amountOut, deadline, memo, memoRaw,
                attempt + 1, currentDisclosedCids, wallClockStart, false);
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        if (s.length() <= 30) return s;
        return s.substring(0, 15) + "..." + s.substring(s.length() - 10);
    }

    private static boolean isInactiveContractsError(ApiError error) {
        return error != null && error.message != null
                && error.message.contains("INACTIVE_CONTRACTS");
    }

    static boolean isMustBeUnlockedError(ApiError error) {
        return error != null && error.message != null
                && error.message.contains("Must be unlocked");
    }

    static boolean isDeadlineExpiredErrorPostExecute(ApiError error) {
        // Matches AMM daml-level reject (post-exercise). The pre-execute check at consume()
        // returns directly without going through prepareAndExecuteSwap, so this classifier
        // only fires on the post-execute path.
        return error != null && error.message != null
                && error.message.contains("Swap deadline has expired");
    }

    static boolean isContractNotFoundError(ApiError error, String tiCid) {
        if (error == null || error.message == null) return false;
        if (!error.message.contains("CONTRACT_NOT_FOUND")) return false;
        // Tighten: dead-set only when the *TI* cid is the missing contract.
        Set<String> missing = parseInactiveContractIds(error);
        return missing.isEmpty() || missing.contains(tiCid);
    }

    static boolean isOutputBelowMinOutError(ApiError error) {
        return error != null && error.message != null
                && error.message.contains("Output amount below minimum");
    }

    private CompletableFuture<Result<SwapExecutionBundle, ApiError>> deadSetAndReturn(
            String requestId, String errorClass, ApiError error,
            String step, String operator, String synchronizerId, int attempts
    ) {
        LOG.error("[SwapPoller] DEAD-SET requestId={} class={} attempts={} message={}",
                requestId, errorClass, attempts, error.message);
        inspectStore.markFailed(requestId, errorClass, error.message);
        mustBeUnlockedAttempts.remove(requestId);
        Map<String, Object> details = new LinkedHashMap<>();
        if (error.details != null) details.putAll(error.details);
        details.put("deadSetClass", errorClass);
        details.put("deadSetAttempts", attempts);
        ApiError annotated = new ApiError(
                error.code, error.message, details, false,
                error.grpcStatus, error.grpcDescription);
        return CompletableFuture.completedFuture(Result.err(
                annotateNoSynchronizer(annotated, step, List.of(operator), List.of(), synchronizerId)));
    }

    private CompletableFuture<Result<SwapExecutionBundle, ApiError>> deadSetAndReturnWithUserMessage(
            String requestId, String errorClass, String userFacingMessage, ApiError error,
            String step, String operator, String synchronizerId, int attempts
    ) {
        LOG.error("[SwapPoller] DEAD-SET requestId={} class={} attempts={} message={}",
                requestId, errorClass, attempts, error.message);
        inspectStore.markFailed(requestId, errorClass, userFacingMessage);
        mustBeUnlockedAttempts.remove(requestId);
        Map<String, Object> details = new LinkedHashMap<>();
        if (error.details != null) details.putAll(error.details);
        details.put("deadSetClass", errorClass);
        details.put("deadSetAttempts", attempts);
        details.put("userFacingMessage", userFacingMessage);
        ApiError annotated = new ApiError(
                error.code, error.message, details, false,
                error.grpcStatus, error.grpcDescription);
        return CompletableFuture.completedFuture(Result.err(
                annotateNoSynchronizer(annotated, step, List.of(operator), List.of(), synchronizerId)));
    }

    /**
     * Sleep with jitter, but never past the deadline.
     * Returns false if the deadline would be exceeded (caller should stop retrying).
     */
    private static boolean sleepWithDeadlineCheck(long baseDelaySec, Instant deadline, long headroomSec) {
        Instant now = Instant.now();
        long remainingSec = Duration.between(now, deadline).getSeconds();
        long headroom = headroomSec;

        if (remainingSec <= headroom) {
            LOG.info("[SwapConsume] Not enough headroom to retry: remainingSec={} headroom={}", remainingSec, headroom);
            return false;
        }

        // Apply jitter: baseDelay * (1 +/- JITTER_FACTOR)
        double jitter = 1.0 + (JITTER_RNG.nextDouble() * 2 - 1) * JITTER_FACTOR;
        long jitteredMs = (long) (baseDelaySec * 1000.0 * jitter);

        // Cap at (remaining - headroom) so we never sleep past deadline
        long maxSleepMs = (remainingSec - headroom) * 1000L;
        long actualSleepMs = Math.min(jitteredMs, maxSleepMs);

        if (actualSleepMs <= 0) {
            return false;
        }

        LOG.info("[SwapConsume] Sleeping {}ms before retry (base={}s, jittered={}ms, maxAllowed={}ms, deadline={})",
                actualSleepMs, baseDelaySec, jitteredMs, maxSleepMs, deadline);
        try {
            Thread.sleep(actualSleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /**
     * Best-effort parse of contract IDs from INACTIVE_CONTRACTS error message.
     * Canton format: "INACTIVE_CONTRACTS(...): ... contracts: <cid1>, <cid2>"
     */
    private static Set<String> parseInactiveContractIds(ApiError error) {
        Set<String> cids = new HashSet<>();
        if (error == null) return cids;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[0-9a-f]{64}::[0-9a-f]{64}(?:#\\d+)?");
        if (error.message != null) {
            java.util.regex.Matcher m = pattern.matcher(error.message);
            while (m.find()) {
                cids.add(m.group());
            }
        }
        if (error.grpcDescription != null) {
            java.util.regex.Matcher m = pattern.matcher(error.grpcDescription);
            while (m.find()) {
                cids.add(m.group());
            }
        }
        return cids;
    }

    private ApiError annotateInactiveContracts(ApiError error, int attempt, long remainingSec, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (error.details != null) {
            details.putAll(error.details);
        }
        details.put("retryAttempt", attempt);
        details.put("maxRetries", retryEnabled ? retryMaxAttempts : DEFAULT_MAX_INACTIVE_RETRIES);
        details.put("remainingSeconds", remainingSec);
        details.put("stopReason", reason);
        return new ApiError(
                error.code,
                error.message,
                details,
                error.retryable,
                error.grpcStatus,
                error.grpcDescription
        );
    }

    private Result<PayoutResponse, ApiError> createPayout(
            HoldingPoolCreateRequest.InstrumentRef outputInstrument,
            String receiverParty,
            BigDecimal amountOut,
            String memo,
            Instant deadline,
            String requestId
    ) {
        PayoutRequest payout = new PayoutRequest();
        payout.receiverParty = receiverParty;
        payout.amount = amountOut.setScale(SwapConstants.SCALE, RoundingMode.DOWN).toPlainString();
        payout.memo = memo;

        long remainingSeconds = Duration.between(Instant.now(), deadline).getSeconds();
        if (remainingSeconds <= 0) {
            return Result.err(preconditionError("Swap deadline expired before payout", Map.of("deadline", deadline.toString())));
        }
        payout.executeBeforeSeconds = Math.min(remainingSeconds, 7200L);

        if ("Amulet".equalsIgnoreCase(outputInstrument.id)) {
            return payoutService.createAmuletPayout(payout, "swap-payout-amulet-" + requestId);
        }
        if ("CBTC".equalsIgnoreCase(outputInstrument.id)) {
            return payoutService.createCbtcPayout(payout, "swap-payout-cbtc-" + requestId);
        }
        return Result.err(validationError("Unsupported output instrument: " + outputInstrument.id, "instrumentId"));
    }

    private ValueOuterClass.Identifier holdingPoolTemplateId() {
        return ValueOuterClass.Identifier.newBuilder()
                .setPackageId(holdingPoolPackageId)
                .setModuleName("AMM.HoldingPool")
                .setEntityName("HoldingPool")
                .build();
    }

    private ValueOuterClass.Value contractIdValue(String cid) {
        return ValueOuterClass.Value.newBuilder().setContractId(cid).build();
    }

    private ValueOuterClass.Value partyValue(String party) {
        return ValueOuterClass.Value.newBuilder().setParty(party).build();
    }

    private ValueOuterClass.Value textValue(String text) {
        return ValueOuterClass.Value.newBuilder().setText(text).build();
    }

    private ValueOuterClass.Value recordValue(ValueOuterClass.Record record) {
        return ValueOuterClass.Value.newBuilder().setRecord(record).build();
    }

    private ValueOuterClass.Value optionalValue(ValueOuterClass.Value value) {
        ValueOuterClass.Optional.Builder ob = ValueOuterClass.Optional.newBuilder();
        if (value != null) {
            ob.setValue(value);
        }
        return ValueOuterClass.Value.newBuilder().setOptional(ob.build()).build();
    }

    private ValueOuterClass.Value numericValue(BigDecimal value) {
        String raw = value.setScale(SwapConstants.SCALE, RoundingMode.DOWN).toPlainString();
        return ValueOuterClass.Value.newBuilder().setNumeric(raw).build();
    }

    private ValueOuterClass.Value timestampValue(Instant instant) {
        long micros = instant.toEpochMilli() * 1000L;
        return ValueOuterClass.Value.newBuilder().setTimestamp(micros).build();
    }

    private ValueOuterClass.Value swapDirectionValue(SwapDirection direction) {
        String constructor = direction.damlConstructor;
        return ValueOuterClass.Value.newBuilder()
                .setEnum(ValueOuterClass.Enum.newBuilder()
                        .setConstructor(constructor)
                        .build())
                .build();
    }


    private ValueOuterClass.RecordField recordField(String label, ValueOuterClass.Value value) {
        return ValueOuterClass.RecordField.newBuilder().setLabel(label).setValue(value).build();
    }

    private List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> mergeDisclosed(
            List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> a,
            List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> b
    ) {
        Map<String, com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> byCid = new LinkedHashMap<>();
        if (a != null) {
            for (var dc : a) {
                if (dc != null && !dc.getContractId().isBlank()) {
                    byCid.putIfAbsent(dc.getContractId(), dc);
                }
            }
        }
        if (b != null) {
            for (var dc : b) {
                if (dc != null && !dc.getContractId().isBlank()) {
                    byCid.putIfAbsent(dc.getContractId(), dc);
                }
            }
        }
        return new ArrayList<>(byCid.values());
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private BigDecimal normalizeAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        return new BigDecimal(value).setScale(SwapConstants.SCALE, RoundingMode.DOWN);
    }

    private String displaySymbol(String instrumentId) {
        if (instrumentId == null) {
            return "";
        }
        if ("Amulet".equalsIgnoreCase(instrumentId.trim())) {
            return "CC";
        }
        return instrumentId.trim();
    }

    private String extractUpdateId(CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp) {
        if (resp == null || !resp.hasTransaction()) return null;
        return resp.getTransaction().getUpdateId();
    }

    private PayoutOutcomeInfo extractPayoutOutcome(CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp) {
        if (resp == null || !resp.hasTransaction()) return null;
        for (var ev : resp.getTransaction().getEventsList()) {
            if (!ev.hasExercised()) continue;
            String choice = ev.getExercised().getChoice();
            if (!"ExecuteSwapFromTransferInstruction".equals(choice)
                    && !"ExecuteSwapFromTransferInstructionV2".equals(choice)) {
                continue;
            }
            var result = ev.getExercised().getExerciseResult();
            if (!result.hasRecord()) continue;
            var rec = result.getRecord();
            if (rec.getFieldsCount() < 2) continue;
            var payoutVal = rec.getFields(1).getValue();
            if (payoutVal.hasContractId()) {
                return new PayoutOutcomeInfo(payoutVal.getContractId(), false);
            }
            if (payoutVal.hasVariant()) {
                var variant = payoutVal.getVariant();
                String ctor = variant.getConstructor();
                if ("PayoutCompleted".equals(ctor)) {
                    return new PayoutOutcomeInfo(null, true);
                }
                if ("PayoutPending".equals(ctor)) {
                    ValueOuterClass.Value inner = variant.getValue();
                    if (inner.hasContractId()) {
                        return new PayoutOutcomeInfo(inner.getContractId(), false);
                    }
                }
            }
            if (payoutVal.hasOptional()) {
                var opt = payoutVal.getOptional();
                if (opt.hasValue() && opt.getValue().hasContractId()) {
                    return new PayoutOutcomeInfo(opt.getValue().getContractId(), false);
                }
            }
        }
        return null;
    }


    private CompletableFuture<Result<SwapConsumeResponse, ApiError>> completedError(ApiError err) {
        return CompletableFuture.completedFuture(Result.err(err));
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

    private ApiError preconditionError(String message, Map<String, Object> details) {
        return new ApiError(
                ErrorCode.PRECONDITION_FAILED,
                message,
                details,
                false,
                null,
                null
        );
    }

    private ApiError domainError(String message, DomainError err) {
        return new ApiError(
                ErrorCode.INTERNAL,
                message,
                Map.of("cause", err.message(), "code", err.code()),
                false,
                null,
                null
        );
    }

    private ApiError annotateNoSynchronizer(ApiError error,
                                            String step,
                                            List<String> actAs,
                                            List<String> readAs,
                                            String synchronizerId) {
        if (error == null || error.message == null) {
            return error;
        }
        if (!error.message.contains("NO_SYNCHRONIZER_ON_WHICH_ALL_SUBMITTERS_CAN_SUBMIT")) {
            return error;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (error.details != null) {
            details.putAll(error.details);
        }
        details.put("step", step);
        details.put("actAs", actAs);
        details.put("readAs", readAs);
        details.put("synchronizerId", synchronizerId);
        return new ApiError(
                error.code,
                error.message,
                details,
                error.retryable,
                error.grpcStatus,
                error.grpcDescription
        );
    }

    private record Candidate(TransferInstructionDto transfer, SwapMemo memo, String memoRaw) { }
    private record Selection(Candidate candidate, boolean matchedRequestId) { }
    private record PayoutOutcomeInfo(String payoutCid, boolean completed) { }
    private record SwapExecutionBundle(
            CommandServiceOuterClass.SubmitAndWaitForTransactionResponse response,
            PayoutService.TransferFactoryPlan payoutPlan,
            String synchronizerId
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class SwapMemo {
        public int v;
        public String requestId;
        public String poolCid;
        public String direction;
        public String minOut;
        public String receiverParty;
        public String deadline;
    }

    private enum SwapDirection {
        A2B("AtoB"),
        B2A("BtoA");

        private final String damlConstructor;

        SwapDirection(String damlConstructor) {
            this.damlConstructor = damlConstructor;
        }

        static String normalizeMemo(String memoDirection) {
            if (memoDirection == null) return null;
            String norm = memoDirection.trim().toUpperCase();
            if (norm.isBlank()) return null;
            String cleaned = norm.replaceAll("[^A-Z0-9]", "");
            return switch (cleaned) {
                case "A2B", "ATOB" -> "A2B";
                case "B2A", "BTOA" -> "B2A";
                default -> cleaned;
            };
        }

        static SwapDirection fromNormalized(String normalizedDirection) {
            if (normalizedDirection == null) return null;
            return switch (normalizedDirection) {
                case "A2B" -> A2B;
                case "B2A" -> B2A;
                default -> null;
            };
        }
    }

    
}

