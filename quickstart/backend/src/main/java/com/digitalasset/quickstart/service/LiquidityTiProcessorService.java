package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.constants.SwapConstants;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.HoldingPoolCreateRequest;
import com.digitalasset.quickstart.dto.HoldingPoolResponse;
import com.digitalasset.quickstart.dto.LiquidityConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityConsumeResponse;
import com.digitalasset.quickstart.dto.LiquidityInspectResponse;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionWithMemo;
import com.digitalasset.quickstart.service.TransferInstructionChoiceContextService.ChoiceContextResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("prod")
public class LiquidityTiProcessorService {

    private static final Logger LOG = LoggerFactory.getLogger(LiquidityTiProcessorService.class);

    private final TransferInstructionAcsQueryService tiQueryService;
    private final HoldingPoolService holdingPoolService;
    private final TransferInstructionChoiceContextService choiceContextService;
    private final LedgerApi ledgerApi;
    private final AuthUtils authUtils;
    private final IdempotencyService idempotencyService;
    private final TransactionHistoryService transactionHistoryService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${holdingpool.package-id:}")
    private String holdingPoolPackageId;
    @Value("${liquidity.consume.wait-ms:15000}")
    private long consumeWaitMs;
    @Value("${liquidity.consume.poll-ms:1000}")
    private long consumePollMs;

    public LiquidityTiProcessorService(
            TransferInstructionAcsQueryService tiQueryService,
            HoldingPoolService holdingPoolService,
            TransferInstructionChoiceContextService choiceContextService,
            LedgerApi ledgerApi,
            AuthUtils authUtils,
            IdempotencyService idempotencyService,
            TransactionHistoryService transactionHistoryService
    ) {
        this.tiQueryService = tiQueryService;
        this.holdingPoolService = holdingPoolService;
        this.choiceContextService = choiceContextService;
        this.ledgerApi = ledgerApi;
        this.authUtils = authUtils;
        this.idempotencyService = idempotencyService;
        this.transactionHistoryService = transactionHistoryService;
    }

    @WithSpan
    public CompletableFuture<Result<LiquidityConsumeResponse, ApiError>> consume(final LiquidityConsumeRequest request) {
        if (request == null || request.requestId == null || request.requestId.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("requestId is required", "requestId")));
        }
        if (request.poolCid == null || request.poolCid.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("poolCid is required", "poolCid")));
        }
        Object cached = idempotencyService.checkIdempotency(request.requestId);
        if (cached instanceof LiquidityConsumeResponse cachedResponse) {
            return CompletableFuture.completedFuture(Result.ok(cachedResponse));
        }
        if (holdingPoolPackageId == null || holdingPoolPackageId.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("holdingpool.package-id is not configured", "holdingpool.package-id")));
        }

        String operator = authUtils.getAppProviderPartyId();
        long maxAgeSeconds = request.maxAgeSeconds != null && request.maxAgeSeconds > 0
                ? request.maxAgeSeconds
                : 7200L;

        return holdingPoolService.getByContractId(request.poolCid)
                .thenCompose(poolResult -> {
                    if (poolResult.isErr()) {
                        return completedError(domainError("Pool not found or not visible", poolResult.getErrorUnsafe()));
                    }
                    HoldingPoolResponse pool = poolResult.getValueUnsafe();
                    if (pool.status == null || !"active".equalsIgnoreCase(pool.status)) {
                        return completedError(preconditionError("Pool is not active", Map.of("status", pool.status)));
                    }
                    if (pool.instrumentA == null || pool.instrumentB == null) {
                        return completedError(preconditionError("Pool instruments are missing", Map.of("poolCid", request.poolCid)));
                    }

                    Result<PairSelection, ApiError> selection = awaitPairSelection(
                            operator,
                            request.requestId,
                            request.poolCid,
                            pool,
                            maxAgeSeconds
                    );
                    if (selection.isErr()) {
                        return completedError(selection.getErrorUnsafe());
                    }
                    PairSelection picked = selection.getValueUnsafe();
                    Candidate a = picked.candidateA;
                    Candidate b = picked.candidateB;

                    Instant deadline = picked.deadline;
                    BigDecimal amountA = picked.amountA;
                    BigDecimal amountB = picked.amountB;

                    Result<ChoiceContextResult, ApiError> ctxAResult =
                            choiceContextService.resolveDisclosedContracts(a.transfer.contractId(), pool.instrumentA.admin, request.requestId);
                    if (ctxAResult.isErr()) {
                        return completedError(ctxAResult.getErrorUnsafe());
                    }
                    Result<ChoiceContextResult, ApiError> ctxBResult =
                            choiceContextService.resolveDisclosedContracts(b.transfer.contractId(), pool.instrumentB.admin, request.requestId);
                    if (ctxBResult.isErr()) {
                        return completedError(ctxBResult.getErrorUnsafe());
                    }
                    ChoiceContextResult ctxA = ctxAResult.getValueUnsafe();
                    ChoiceContextResult ctxB = ctxBResult.getValueUnsafe();

                    List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> disclosed =
                            mergeDisclosed(ctxA.disclosedContracts(), ctxB.disclosedContracts());
                    String synchronizerId = firstNonBlank(ctxA.synchronizerId(), ctxB.synchronizerId());

                    String poolIdText = (pool.poolId != null && !pool.poolId.isBlank()) ? pool.poolId : request.poolCid;
                    LOG.info("[LiquidityConsume] requestId={} operator={} poolCid={} poolIdText={} tiA={} tiB={} amountA={} amountB={} provider={} ctxAKeys={} ctxBKeys={} actAs=[{}]",
                            request.requestId,
                            operator,
                            request.poolCid,
                            poolIdText,
                            a.transfer.contractId(),
                            b.transfer.contractId(),
                            amountA.toPlainString(),
                            amountB.toPlainString(),
                            picked.providerParty,
                            ctxA.contextKeyCount(),
                            ctxB.contextKeyCount(),
                            operator);

                    ValueOuterClass.Record choiceArgs = ValueOuterClass.Record.newBuilder()
                            .addFields(recordField("provider", partyValue(picked.providerParty)))
                            .addFields(recordField("tiCidA", contractIdValue(a.transfer.contractId())))
                            .addFields(recordField("tiCidB", contractIdValue(b.transfer.contractId())))
                            .addFields(recordField("amountA", numericValue(amountA)))
                            .addFields(recordField("amountB", numericValue(amountB)))
                            .addFields(recordField("deadline", timestampValue(deadline)))
                            .addFields(recordField("poolIdText", textValue(poolIdText)))
                            .addFields(recordField("acceptExtraArgsA", optionalValue(recordValue(ctxA.extraArgs()))))
                            .addFields(recordField("acceptExtraArgsB", optionalValue(recordValue(ctxB.extraArgs()))))
                            .build();

                    return ledgerApi.exerciseRawWithLabel(
                                    "AddLiquidityFromTransferInstructionsV1",
                                    holdingPoolTemplateId(),
                                    request.poolCid,
                                    "AddLiquidityFromTransferInstructionsV1",
                                    choiceArgs,
                                    List.of(operator),
                                    List.of(),
                                    disclosed,
                                    synchronizerId
                            )
                            .handle((resp, throwable) -> {
                                if (throwable != null) {
                                    return Result.<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse, ApiError>err(
                                            annotateNoSynchronizer(ApiError.of(ErrorCode.LEDGER_REJECTED, throwable.getMessage()),
                                                    "AddLiquidityFromTransferInstructionsV1",
                                                    List.of(operator),
                                                    List.of(),
                                                    synchronizerId));
                                }
                                return Result.<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse, ApiError>ok(resp);
                            })
                            .thenCompose((Result<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse, ApiError> result) -> {
                                if (result.isErr()) {
                                    return completedError(result.getErrorUnsafe());
                                }
                                CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp = result.getValueUnsafe();
                                String updateId = extractUpdateId(resp);
                                LiquidityOutcome outcome = extractLiquidityOutcome(resp);
                                if (updateId == null || updateId.isBlank()) {
                                    return completedError(ApiError.of(ErrorCode.INTERNAL, "AddLiquidity returned no updateId"));
                                }
                                if (outcome == null || outcome.newPoolCid == null) {
                                    return completedError(ApiError.of(ErrorCode.INTERNAL, "AddLiquidity returned no result"));
                                }

                                LiquidityConsumeResponse response = new LiquidityConsumeResponse();
                                response.requestId = request.requestId;
                                response.poolCid = request.poolCid;
                                response.newPoolCid = outcome.newPoolCid;
                                response.providerParty = picked.providerParty;
                                response.lpOwnerParty = picked.providerParty;
                                response.lpOwnerParty = picked.providerParty;
                                response.tiCidA = a.transfer.contractId();
                                response.tiCidB = b.transfer.contractId();
                                response.amountA = amountA.toPlainString();
                                response.amountB = amountB.toPlainString();
                                response.lpMinted = outcome.lpMinted.toPlainString();
                                response.newReserveA = outcome.newReserveA.toPlainString();
                                response.newReserveB = outcome.newReserveB.toPlainString();
                                response.ledgerUpdateId = updateId;
                                response.executeStatus = "SUCCEEDED";

                                try {
                                    transactionHistoryService.recordAddLiquidity(
                                            outcome.newPoolCid,
                                            outcome.newPoolCid,
                                            displaySymbol(pool.instrumentA.id),
                                            displaySymbol(pool.instrumentB.id),
                                            amountA,
                                            amountB,
                                            BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN),
                                            outcome.lpMinted,
                                            picked.providerParty
                                    );
                                } catch (Exception e) {
                                    LOG.warn("[LiquidityConsume] Failed to record history: {}", e.getMessage());
                                }

                                idempotencyService.registerSuccess(request.requestId, request.requestId, updateId, response);
                                return CompletableFuture.completedFuture(Result.ok(response));
                            });
                });
    }

    @WithSpan
    public CompletableFuture<Result<LiquidityInspectResponse, ApiError>> inspect(final String requestId, final String poolCid) {
        if (requestId == null || requestId.isBlank()) {
            return CompletableFuture.completedFuture(Result.err(validationError("requestId is required", "requestId")));
        }
        Object cached = idempotencyService.checkIdempotency(requestId);
        boolean alreadyProcessed = cached instanceof LiquidityConsumeResponse;

        String operator = authUtils.getAppProviderPartyId();
        Result<List<TransferInstructionWithMemo>, DomainError> pending = tiQueryService.listForReceiverWithMemo(operator);
        if (pending.isErr()) {
            return CompletableFuture.completedFuture(Result.err(domainError("Failed to query pending TIs", pending.getErrorUnsafe())));
        }

        List<Candidate> candidates = candidatesForRequest(pending.getValueUnsafe(), requestId, operator);
        LiquidityInspectResponse response = new LiquidityInspectResponse();
        response.requestId = requestId;
        response.poolCid = poolCid;
        response.alreadyProcessed = alreadyProcessed;
        response.foundTis = toInboundTiInfos(candidates);
        response.foundInstruments = toInstrumentInfos(foundInstrumentKeys(candidates));

        String resolvedPoolCid = poolCid;
        if ((resolvedPoolCid == null || resolvedPoolCid.isBlank()) && !candidates.isEmpty()) {
            for (Candidate candidate : candidates) {
                if (candidate.memo != null && candidate.memo.poolCid != null && !candidate.memo.poolCid.isBlank()) {
                    resolvedPoolCid = candidate.memo.poolCid;
                    break;
                }
            }
        }
        response.poolCid = resolvedPoolCid;

        if (resolvedPoolCid == null || resolvedPoolCid.isBlank()) {
            return CompletableFuture.completedFuture(Result.ok(response));
        }

        return holdingPoolService.getByContractId(resolvedPoolCid)
                .thenApply(poolResult -> {
                    if (poolResult.isOk()) {
                        HoldingPoolResponse pool = poolResult.getValueUnsafe();
                        response.poolStatus = pool.status;
                        InstrumentKey requiredA = instrumentKey(pool.instrumentA);
                        InstrumentKey requiredB = instrumentKey(pool.instrumentB);
                        List<InstrumentKey> required = List.of(requiredA, requiredB);
                        response.instrumentA = toInstrumentInfo(pool.instrumentA);
                        response.instrumentB = toInstrumentInfo(pool.instrumentB);
                        response.requiredInstruments = toInstrumentInfos(required);
                        response.missingInstruments = toInstrumentInfos(missingInstrumentKeys(required, foundInstrumentKeys(candidates)));

                        Map<InstrumentKey, Candidate> latestByInstrument = latestByInstrument(candidates, required);
                        Candidate a = latestByInstrument.get(requiredA);
                        Candidate b = latestByInstrument.get(requiredB);
                        if (a != null) {
                            response.tiCidA = a.transfer.contractId();
                        }
                        if (b != null) {
                            response.tiCidB = b.transfer.contractId();
                        }

                        String senderA = a != null ? a.transfer.sender() : null;
                        String senderB = b != null ? b.transfer.sender() : null;
                        if (senderA != null && senderB != null && senderA.equals(senderB)) {
                            response.providerParty = senderA;
                        } else if (senderA != null && senderB == null) {
                            response.providerParty = senderA;
                        } else if (senderB != null && senderA == null) {
                            response.providerParty = senderB;
                        }

                        response.memoRaw = rowMemo(a, b);
                        Instant deadline = earliestDeadline(
                                parseDeadline(a != null && a.memo != null ? a.memo.deadline : null),
                                parseDeadline(b != null && b.memo != null ? b.memo.deadline : null)
                        );
                        response.deadline = deadline != null ? deadline.toString() : null;
                        response.deadlineExpired = deadline != null && Instant.now().isAfter(deadline);
                    } else {
                        response.poolStatus = "unknown";
                    }
                    return Result.ok(response);
                });
    }

    private Result<PairSelection, ApiError> selectPair(
            List<TransferInstructionWithMemo> items,
            String requestId,
            String poolCid,
            HoldingPoolResponse pool,
            long maxAgeSeconds,
            String operator
    ) {
        List<Candidate> candidates = candidatesForRequest(items, requestId, operator);
        InstrumentKey requiredA = instrumentKey(pool.instrumentA);
        InstrumentKey requiredB = instrumentKey(pool.instrumentB);
        List<InstrumentKey> required = List.of(requiredA, requiredB);
        if (requiredA == null || requiredB == null) {
            return Result.err(preconditionError("Pool instruments are missing", Map.of("poolCid", poolCid)));
        }
        if (candidates.isEmpty()) {
            return Result.err(missingInboundTisError(requestId, poolCid, required, candidates));
        }
        Map<InstrumentKey, Candidate> latestByInstrument = latestByInstrument(candidates, required);
        Candidate a = latestByInstrument.get(requiredA);
        Candidate b = latestByInstrument.get(requiredB);
        if (a == null || b == null) {
            return Result.err(missingInboundTisError(requestId, poolCid, required, candidates));
        }
        if (a.transfer.contractId().equals(b.transfer.contractId())) {
            return Result.err(preconditionError("TIs must be distinct", Map.of("tiCid", a.transfer.contractId())));
        }

        Result<LiquidityMemo, ApiError> memoValidation = validateMemoPool(a, b, poolCid);
        if (memoValidation.isErr()) {
            return Result.err(memoValidation.getErrorUnsafe());
        }
        LiquidityMemo memo = memoValidation.getValueUnsafe();

        String senderA = a.transfer.sender();
        String senderB = b.transfer.sender();
        if (senderA != null && senderB != null && !senderA.equals(senderB)) {
            return Result.err(preconditionError("Inbound TIs have different senders",
                    Map.of("senderA", senderA, "senderB", senderB)));
        }
        String provider = senderA != null ? senderA : senderB;
        if (provider == null || provider.isBlank()) {
            return Result.err(preconditionError("Unable to determine provider party from TIs",
                    Map.of("tiCidA", a.transfer.contractId(), "tiCidB", b.transfer.contractId())));
        }

        Instant deadlineA = parseDeadline(a.memo != null ? a.memo.deadline : null);
        Instant deadlineB = parseDeadline(b.memo != null ? b.memo.deadline : null);
        Instant deadline = earliestDeadline(deadlineA, deadlineB);
        Instant now = Instant.now();
        if (deadline == null) {
            return Result.err(validationError("memo.deadline is required", "deadline"));
        }
        if (now.isAfter(deadline)) {
            return Result.err(preconditionError("Liquidity deadline has expired", Map.of("deadline", deadline.toString())));
        }
        Instant cutoff = now.minusSeconds(maxAgeSeconds);
        if (a.transfer.executeBefore() != null && a.transfer.executeBefore().isBefore(cutoff)) {
            return Result.err(preconditionError("TI A is too old", Map.of("executeBefore", a.transfer.executeBefore().toString())));
        }
        if (b.transfer.executeBefore() != null && b.transfer.executeBefore().isBefore(cutoff)) {
            return Result.err(preconditionError("TI B is too old", Map.of("executeBefore", b.transfer.executeBefore().toString())));
        }

        if (a.transfer.sender() != null && !provider.equals(a.transfer.sender())) {
            return Result.err(preconditionError("TI A sender does not match provider",
                    Map.of("tiCidA", a.transfer.contractId(), "sender", a.transfer.sender(), "provider", provider)));
        }
        if (b.transfer.sender() != null && !provider.equals(b.transfer.sender())) {
            return Result.err(preconditionError("TI B sender does not match provider",
                    Map.of("tiCidB", b.transfer.contractId(), "sender", b.transfer.sender(), "provider", provider)));
        }
        if (a.transfer.receiver() != null && !operator.equals(a.transfer.receiver())) {
            return Result.err(preconditionError("TI A receiver is not operator",
                    Map.of("tiCidA", a.transfer.contractId(), "receiver", a.transfer.receiver())));
        }
        if (b.transfer.receiver() != null && !operator.equals(b.transfer.receiver())) {
            return Result.err(preconditionError("TI B receiver is not operator",
                    Map.of("tiCidB", b.transfer.contractId(), "receiver", b.transfer.receiver())));
        }

        BigDecimal amountA;
        BigDecimal amountB;
        try {
            amountA = normalizeAmount(a.transfer.amount());
            amountB = normalizeAmount(b.transfer.amount());
        } catch (Exception e) {
            return Result.err(validationError("amount format is invalid", "amount"));
        }
        if (amountA.compareTo(BigDecimal.ZERO) <= 0 || amountB.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.err(preconditionError("Amounts must be positive", Map.of("amountA", amountA, "amountB", amountB)));
        }

        PairSelection selection = new PairSelection(
                a,
                b,
                memo.poolCid,
                memo,
                deadline,
                provider,
                rowMemo(a, b)
        );
        selection.amountA = amountA;
        selection.amountB = amountB;
        return Result.ok(selection);
    }

    private List<Candidate> candidatesForRequest(
            List<TransferInstructionWithMemo> items,
            String requestId,
            String operator
    ) {
        List<Candidate> candidates = new ArrayList<>();
        for (TransferInstructionWithMemo row : items) {
            TransferInstructionDto ti = row.transferInstruction();
            if (ti == null || ti.contractId() == null) continue;
            if (operator != null && ti.receiver() != null && !operator.equals(ti.receiver())) continue;
            LiquidityMemo memo = parseMemo(row.memo());
            if (memo == null || memo.requestId == null) continue;
            if (!requestId.equals(memo.requestId)) continue;
            if (memo.kind == null || !"addLiquidity".equalsIgnoreCase(memo.kind)) continue;
            candidates.add(new Candidate(ti, memo, row.memo()));
        }
        return candidates;
    }

    private Result<PairSelection, ApiError> awaitPairSelection(
            String operator,
            String requestId,
            String poolCid,
            HoldingPoolResponse pool,
            long maxAgeSeconds
    ) {
        long waitMs = consumeWaitMs;
        long pollMs = consumePollMs <= 0 ? 1000L : consumePollMs;
        long deadlineMs = System.currentTimeMillis() + Math.max(waitMs, 0L);
        int attempts = 0;
        ApiError lastMissing = null;
        while (true) {
            attempts += 1;
            Result<List<TransferInstructionWithMemo>, DomainError> pending = tiQueryService.listForReceiverWithMemo(operator);
            if (pending.isErr()) {
                return Result.err(domainError("Failed to query pending TIs", pending.getErrorUnsafe()));
            }
            Result<PairSelection, ApiError> selection = selectPair(
                    pending.getValueUnsafe(),
                    requestId,
                    poolCid,
                    pool,
                    maxAgeSeconds,
                    operator
            );
            if (selection.isOk()) {
                if (attempts > 1) {
                    LOG.info("[LiquidityConsume] Found inbound TIs after {} attempts for requestId={}", attempts, requestId);
                }
                return selection;
            }
            ApiError error = selection.getErrorUnsafe();
            if (error.code == ErrorCode.MISSING_INBOUND_TIS_FOR_POOL_INSTRUMENT) {
                lastMissing = error;
            } else {
                return selection;
            }
            if (waitMs <= 0 || System.currentTimeMillis() >= deadlineMs) {
                return Result.err(lastMissing != null ? lastMissing : error);
            }
            long sleepMs = Math.min(pollMs, Math.max(0L, deadlineMs - System.currentTimeMillis()));
            if (sleepMs <= 0) {
                return Result.err(lastMissing != null ? lastMissing : error);
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Result.err(lastMissing != null ? lastMissing : error);
            }
        }
    }

    private String rowMemo(Candidate a, Candidate b) {
        if (a != null && a.memoRaw != null && !a.memoRaw.isBlank()) return a.memoRaw;
        if (b != null) return b.memoRaw;
        return null;
    }

    private Result<LiquidityMemo, ApiError> validateMemoPool(Candidate a, Candidate b, String poolCid) {
        LiquidityMemo memoA = a != null ? a.memo : null;
        LiquidityMemo memoB = b != null ? b.memo : null;
        if (memoA == null || memoA.poolCid == null || memoA.poolCid.isBlank()) {
            return Result.err(validationError("memo.poolCid is required", "poolCid"));
        }
        if (memoB == null || memoB.poolCid == null || memoB.poolCid.isBlank()) {
            return Result.err(validationError("memo.poolCid is required", "poolCid"));
        }
        if (!poolCid.equals(memoA.poolCid) || !poolCid.equals(memoB.poolCid)) {
            return Result.err(preconditionError(
                    "memo.poolCid does not match request",
                    Map.of("memoPoolCidA", memoA.poolCid, "memoPoolCidB", memoB.poolCid, "requestPoolCid", poolCid)
            ));
        }
        return Result.ok(memoA);
    }

    private Instant earliestDeadline(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private LiquidityMemo parseMemo(String memoRaw) {
        if (memoRaw == null || memoRaw.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(memoRaw, LiquidityMemo.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse liquidity memo JSON: {}", e.getMessage());
            return null;
        }
    }

    private Instant parseDeadline(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeAdmin(String admin) {
        if (admin == null) return null;
        String trimmed = admin.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeInstrumentId(String id) {
        if (id == null) return null;
        String trimmed = id.trim();
        if (trimmed.isBlank()) return null;
        if ("AMULET".equalsIgnoreCase(trimmed) || "CC".equalsIgnoreCase(trimmed)) {
            return "CC";
        }
        return trimmed.toUpperCase();
    }

    private InstrumentKey instrumentKey(HoldingPoolCreateRequest.InstrumentRef ref) {
        if (ref == null) return null;
        String admin = normalizeAdmin(ref.admin);
        String id = normalizeInstrumentId(ref.id);
        if (admin == null || id == null) return null;
        return new InstrumentKey(admin, id);
    }

    private InstrumentKey instrumentKey(Candidate candidate) {
        if (candidate == null) return null;
        LiquidityMemo memo = candidate.memo;
        String admin = firstNonBlank(
                normalizeAdmin(memo != null ? memo.instrumentAdmin : null),
                normalizeAdmin(candidate.transfer.admin())
        );
        String id = firstNonBlank(
                normalizeInstrumentId(memo != null ? memo.instrumentId : null),
                normalizeInstrumentId(candidate.transfer.instrumentId())
        );
        if (admin == null || id == null) return null;
        return new InstrumentKey(admin, id);
    }

    private Map<InstrumentKey, Candidate> latestByInstrument(List<Candidate> candidates, List<InstrumentKey> required) {
        Map<InstrumentKey, Candidate> latest = new LinkedHashMap<>();
        if (candidates == null) return latest;
        for (Candidate c : candidates) {
            InstrumentKey key = instrumentKey(c);
            if (key == null) continue;
            if (required != null && !required.contains(key)) continue;
            Candidate existing = latest.get(key);
            if (existing == null) {
                latest.put(key, c);
                continue;
            }
            Instant existingTime = existing.transfer.executeBefore();
            Instant currentTime = c.transfer.executeBefore();
            if (existingTime == null || (currentTime != null && currentTime.isAfter(existingTime))) {
                latest.put(key, c);
            }
        }
        return latest;
    }

    private List<InstrumentKey> foundInstrumentKeys(List<Candidate> candidates) {
        List<InstrumentKey> keys = new ArrayList<>();
        if (candidates == null) return keys;
        for (Candidate c : candidates) {
            InstrumentKey key = instrumentKey(c);
            if (key != null && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private ApiError missingInboundTisError(
            String requestId,
            String poolCid,
            List<InstrumentKey> required,
            List<Candidate> candidates
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestId", requestId);
        details.put("poolCid", poolCid);
        details.put("requiredInstruments", instrumentKeyDetails(required));
        details.put("foundInstruments", instrumentKeyDetails(foundInstrumentKeys(candidates)));
        details.put("foundTis", foundTiDetails(candidates));
        return new ApiError(
                ErrorCode.MISSING_INBOUND_TIS_FOR_POOL_INSTRUMENT,
                "Missing inbound TIs for pool instruments",
                details,
                false,
                null,
                null
        );
    }

    private List<Map<String, Object>> instrumentKeyDetails(List<InstrumentKey> keys) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (keys == null) return out;
        for (InstrumentKey key : keys) {
            if (key == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("admin", key.admin());
            row.put("id", key.id());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> foundTiDetails(List<Candidate> candidates) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (candidates == null) return out;
        for (Candidate c : candidates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tiCid", c.transfer.contractId());
            row.put("admin", c.transfer.admin());
            row.put("instrumentId", c.transfer.instrumentId());
            row.put("normalizedId", normalizeInstrumentId(c.transfer.instrumentId()));
            row.put("sender", c.transfer.sender());
            row.put("receiver", c.transfer.receiver());
            row.put("executeBefore", c.transfer.executeBefore() != null ? c.transfer.executeBefore().toString() : null);
            row.put("memoInstrumentAdmin", c.memo != null ? c.memo.instrumentAdmin : null);
            row.put("memoInstrumentId", c.memo != null ? c.memo.instrumentId : null);
            row.put("memoLeg", c.memo != null ? c.memo.leg : null);
            row.put("memoAmount", c.memo != null ? c.memo.amount : null);
            row.put("memoRaw", c.memoRaw);
            out.add(row);
        }
        return out;
    }

    private List<LiquidityInspectResponse.InstrumentInfo> toInstrumentInfos(List<InstrumentKey> keys) {
        List<LiquidityInspectResponse.InstrumentInfo> out = new ArrayList<>();
        if (keys == null) return out;
        for (InstrumentKey key : keys) {
            if (key == null) continue;
            out.add(new LiquidityInspectResponse.InstrumentInfo(key.admin(), key.id(), normalizeInstrumentId(key.id())));
        }
        return out;
    }

    private LiquidityInspectResponse.InstrumentInfo toInstrumentInfo(HoldingPoolCreateRequest.InstrumentRef ref) {
        if (ref == null) {
            return null;
        }
        return new LiquidityInspectResponse.InstrumentInfo(ref.admin, ref.id, normalizeInstrumentId(ref.id));
    }

    private List<InstrumentKey> missingInstrumentKeys(List<InstrumentKey> required, List<InstrumentKey> found) {
        List<InstrumentKey> missing = new ArrayList<>();
        if (required == null) return missing;
        for (InstrumentKey key : required) {
            if (key == null) continue;
            if (found == null || !found.contains(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    private List<LiquidityInspectResponse.InboundTiInfo> toInboundTiInfos(List<Candidate> candidates) {
        List<LiquidityInspectResponse.InboundTiInfo> out = new ArrayList<>();
        if (candidates == null) return out;
        for (Candidate c : candidates) {
            LiquidityInspectResponse.InboundTiInfo row = new LiquidityInspectResponse.InboundTiInfo();
            row.tiCid = c.transfer.contractId();
            row.admin = c.transfer.admin();
            row.instrumentId = c.transfer.instrumentId();
            row.normalizedId = normalizeInstrumentId(c.transfer.instrumentId());
            row.memoInstrumentAdmin = c.memo != null ? c.memo.instrumentAdmin : null;
            row.memoInstrumentId = c.memo != null ? c.memo.instrumentId : null;
            row.memoLeg = c.memo != null ? c.memo.leg : null;
            row.memoAmount = c.memo != null ? c.memo.amount : null;
            row.sender = c.transfer.sender();
            row.receiver = c.transfer.receiver();
            row.executeBefore = c.transfer.executeBefore() != null ? c.transfer.executeBefore().toString() : null;
            row.memoRaw = c.memoRaw;
            out.add(row);
        }
        return out;
    }

    private BigDecimal normalizeAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        return new BigDecimal(value).setScale(SwapConstants.SCALE, RoundingMode.DOWN);
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

    private LiquidityOutcome extractLiquidityOutcome(CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp) {
        if (resp == null || !resp.hasTransaction()) return null;
        for (var ev : resp.getTransaction().getEventsList()) {
            if (!ev.hasExercised()) continue;
            if (!"AddLiquidityFromTransferInstructionsV1".equals(ev.getExercised().getChoice())) continue;
            var result = ev.getExercised().getExerciseResult();
            if (!result.hasRecord()) continue;
            var rec = result.getRecord();
            if (rec.getFieldsCount() < 4) continue;
            String newPoolCid = rec.getFields(0).getValue().getContractId();
            BigDecimal lpMinted = numericFromValue(rec.getFields(1).getValue());
            BigDecimal newReserveA = numericFromValue(rec.getFields(2).getValue());
            BigDecimal newReserveB = numericFromValue(rec.getFields(3).getValue());
            if (newPoolCid == null || newPoolCid.isBlank()) continue;
            return new LiquidityOutcome(newPoolCid, lpMinted, newReserveA, newReserveB);
        }
        return null;
    }

    private BigDecimal numericFromValue(ValueOuterClass.Value value) {
        if (value == null || value.getSumCase() != ValueOuterClass.Value.SumCase.NUMERIC) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        try {
            return new BigDecimal(value.getNumeric());
        } catch (Exception e) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
    }

    private CompletableFuture<Result<LiquidityConsumeResponse, ApiError>> completedError(ApiError err) {
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
                Map.of("cause", err.message()),
                false,
                null,
                null
        );
    }

    private ApiError annotateNoSynchronizer(
            ApiError error,
            String step,
            List<String> actAs,
            List<String> readAs,
            String synchronizerId
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (error.details != null) {
            details.putAll(error.details);
        }
        details.put("step", step);
        details.put("actAs", actAs);
        details.put("readAs", readAs);
        if (synchronizerId != null) {
            details.put("synchronizerId", synchronizerId);
        }
        return new ApiError(
                error.code,
                error.message,
                details,
                error.retryable,
                error.grpcStatus,
                error.grpcDescription
        );
    }

    private record Candidate(TransferInstructionDto transfer, LiquidityMemo memo, String memoRaw) {}

    private record InstrumentKey(String admin, String id) {}

    private static final class PairSelection {
        private final Candidate candidateA;
        private final Candidate candidateB;
        private final String poolCid;
        private final LiquidityMemo memo;
        private final Instant deadline;
        private final String providerParty;
        private final String memoRaw;
        private BigDecimal amountA;
        private BigDecimal amountB;

        private PairSelection(Candidate candidateA,
                              Candidate candidateB,
                              String poolCid,
                              LiquidityMemo memo,
                              Instant deadline,
                              String providerParty,
                              String memoRaw) {
            this.candidateA = candidateA;
            this.candidateB = candidateB;
            this.poolCid = poolCid;
            this.memo = memo;
            this.deadline = deadline;
            this.providerParty = providerParty;
            this.memoRaw = memoRaw;
        }
    }

    private record LiquidityOutcome(String newPoolCid, BigDecimal lpMinted, BigDecimal newReserveA, BigDecimal newReserveB) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LiquidityMemo {
        public int v;
        public String kind;
        public String requestId;
        public String poolCid;
        public String leg;
        public String instrumentAdmin;
        public String instrumentId;
        public String amount;
        public String receiverParty;
        public String deadline;
    }
}

