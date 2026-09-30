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
import com.digitalasset.quickstart.dto.HoldingSelectRequest;
import com.digitalasset.quickstart.dto.HoldingSelectResponse;
import com.digitalasset.quickstart.dto.LiquidityRemoveConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityRemoveConsumeResponse;
import com.digitalasset.quickstart.dto.LiquidityRemoveInspectResponse;
import com.digitalasset.quickstart.dto.LpTokenDTO;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("prod")
public class LiquidityRemoveService {

    private static final Logger LOG = LoggerFactory.getLogger(LiquidityRemoveService.class);
    private static final long DEFAULT_DEADLINE_SECONDS = 7200L;

    private final HoldingPoolService holdingPoolService;
    private final LedgerReader ledgerReader;
    private final HoldingSelectorService holdingSelectorService;
    private final PayoutService payoutService;
    private final LedgerApi ledgerApi;
    private final AuthUtils authUtils;
    private final IdempotencyService idempotencyService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    private FeaturedAppMarkerService featuredAppMarkerService;

    @Value("${holdingpool.package-id:}")
    private String holdingPoolPackageId;

    public LiquidityRemoveService(
            HoldingPoolService holdingPoolService,
            LedgerReader ledgerReader,
            HoldingSelectorService holdingSelectorService,
            PayoutService payoutService,
            LedgerApi ledgerApi,
            AuthUtils authUtils,
            IdempotencyService idempotencyService
    ) {
        this.holdingPoolService = holdingPoolService;
        this.ledgerReader = ledgerReader;
        this.holdingSelectorService = holdingSelectorService;
        this.payoutService = payoutService;
        this.ledgerApi = ledgerApi;
        this.authUtils = authUtils;
        this.idempotencyService = idempotencyService;
    }

    @WithSpan
    public CompletableFuture<Result<LiquidityRemoveConsumeResponse, ApiError>> consume(final LiquidityRemoveConsumeRequest request) {
        if (request == null || request.requestId == null || request.requestId.isBlank()) {
            return completedError(validationError("requestId is required", "requestId"));
        }
        if (request.lpCid == null || request.lpCid.isBlank()) {
            return completedError(validationError("lpCid is required", "lpCid"));
        }
        if (request.receiverParty == null || request.receiverParty.isBlank()) {
            return completedError(validationError("receiverParty is required", "receiverParty"));
        }
        Object cached = idempotencyService.checkIdempotency(request.requestId);
        if (cached instanceof LiquidityRemoveConsumeResponse cachedResponse) {
            return CompletableFuture.completedFuture(Result.ok(cachedResponse));
        }
        if (holdingPoolPackageId == null || holdingPoolPackageId.isBlank()) {
            return completedError(validationError("holdingpool.package-id is not configured", "holdingpool.package-id"));
        }

        Result<BigDecimal, ApiError> burnParsed = parseDecimalRequired(request.lpBurnAmount, "lpBurnAmount");
        if (burnParsed.isErr()) {
            return completedError(burnParsed.getErrorUnsafe());
        }
        BigDecimal lpBurnAmount = burnParsed.getValueUnsafe();
        if (lpBurnAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return completedError(preconditionError("lpBurnAmount must be positive",
                    Map.of("lpBurnAmount", request.lpBurnAmount)));
        }

        BigDecimal minOutA = parseDecimalOrZero(request.minOutA);
        BigDecimal minOutB = parseDecimalOrZero(request.minOutB);
        Instant deadline = parseDeadline(request.deadlineIso);

        String operator = authUtils.getAppProviderPartyId();
        String receiverParty = request.receiverParty;
        String providedPoolCid = request.poolCid;

        return ledgerReader.lpTokensForParty(receiverParty)
                .thenCompose(tokens -> {
                    Optional<LpTokenDTO> lpTokenOpt = tokens.stream()
                            .filter(t -> request.lpCid.equals(t.contractId))
                            .findFirst();
                    if (lpTokenOpt.isEmpty()) {
                        return completedError(preconditionError("LP position not found for owner",
                                Map.of("lpCid", request.lpCid, "owner", receiverParty)));
                    }
                    LpTokenDTO lpToken = lpTokenOpt.get();
                    return resolvePoolForRemove(lpToken, providedPoolCid)
                            .thenCompose(poolResult -> {
                                if (poolResult.isErr()) {
                                    return completedError(poolResult.getErrorUnsafe());
                                }
                                HoldingPoolResponse pool = poolResult.getValueUnsafe();
                                String resolvedPoolCid = pool.contractId;
                                String poolIdText = (pool.poolId != null && !pool.poolId.isBlank())
                                        ? pool.poolId
                                        : lpToken.poolId;
                                if (poolIdText == null || poolIdText.isBlank()) {
                                    return completedError(preconditionError("PoolId is missing",
                                            Map.of("poolCid", resolvedPoolCid)));
                                }
                                if (poolIdText != null && lpToken.poolId != null && !poolIdText.equals(lpToken.poolId)) {
                                    if (looksLikeCid(lpToken.poolId)) {
                                        return completedError(legacyPoolNotFound(lpToken, providedPoolCid, poolIdText, null));
                                    }
                                    return completedError(preconditionError("LP position pool mismatch",
                                            Map.of("lpCid", request.lpCid, "poolId", lpToken.poolId, "resolvedPoolId", poolIdText)));
                                }
                                if (pool.status == null || !"active".equalsIgnoreCase(pool.status)) {
                                    return completedError(preconditionError("Pool is not active", Map.of("status", pool.status)));
                                }
                                if (pool.instrumentA == null || pool.instrumentB == null) {
                                    return completedError(preconditionError("Pool instruments are missing",
                                            Map.of("poolCid", resolvedPoolCid)));
                                }

                                BigDecimal reserveA = parseDecimalOrZero(pool.reserveAmountA);
                                BigDecimal reserveB = parseDecimalOrZero(pool.reserveAmountB);
                                BigDecimal lpSupply = parseDecimalOrZero(pool.lpSupply);
                                if (lpSupply.compareTo(BigDecimal.ZERO) <= 0) {
                                    return completedError(preconditionError("LP supply must be positive", Map.of("lpSupply", pool.lpSupply)));
                                }

                                BigDecimal lpBalance = parseDecimalOrZero(lpToken.amount);
                                if (lpBalance.compareTo(lpBurnAmount) < 0) {
                                    return completedError(preconditionError("LP burn exceeds balance",
                                            Map.of("lpBalance", lpToken.amount, "lpBurnAmount", lpBurnAmount.toPlainString())));
                                }

                                BigDecimal share = lpBurnAmount.divide(lpSupply, 18, RoundingMode.DOWN);
                                BigDecimal outA = reserveA.multiply(share).setScale(SwapConstants.SCALE, RoundingMode.DOWN);
                                BigDecimal outB = reserveB.multiply(share).setScale(SwapConstants.SCALE, RoundingMode.DOWN);
                                if (outA.compareTo(BigDecimal.ZERO) <= 0 || outB.compareTo(BigDecimal.ZERO) <= 0) {
                                    return completedError(preconditionError("Output amounts must be positive",
                                            Map.of("outA", outA.toPlainString(), "outB", outB.toPlainString())));
                                }
                                if (outA.compareTo(minOutA) < 0) {
                                    return completedError(preconditionError("Output A below minimum",
                                            Map.of("outA", outA.toPlainString(), "minOutA", minOutA.toPlainString())));
                                }
                                if (outB.compareTo(minOutB) < 0) {
                                    return completedError(preconditionError("Output B below minimum",
                                            Map.of("outB", outB.toPlainString(), "minOutB", minOutB.toPlainString())));
                                }

                                HoldingPoolCreateRequest.InstrumentRef instA = pool.instrumentA;
                                HoldingPoolCreateRequest.InstrumentRef instB = pool.instrumentB;

                                HoldingSelectRequest selectA = new HoldingSelectRequest(
                                        operator,
                                        instA.admin,
                                        instA.id,
                                        outA,
                                        0,
                                        0
                                );
                                HoldingSelectRequest selectB = new HoldingSelectRequest(
                                        operator,
                                        instB.admin,
                                        instB.id,
                                        outB,
                                        0,
                                        0
                                );

                                CompletableFuture<HoldingSelectResponse> selectAFut = holdingSelectorService.selectHoldingOnce(selectA);
                                CompletableFuture<HoldingSelectResponse> selectBFut = holdingSelectorService.selectHoldingOnce(selectB);

                                return selectAFut.thenCombine(selectBFut, (selA, selB) -> {
                                    if (selA == null || !selA.found()) {
                                        return Result.<HoldingSelectResponsePair, ApiError>err(preconditionError(
                                                "No output holding found for payout A",
                                                Map.of("admin", instA.admin, "id", instA.id, "minAmount", outA.toPlainString())));
                                    }
                                    if (selB == null || !selB.found()) {
                                        return Result.<HoldingSelectResponsePair, ApiError>err(preconditionError(
                                                "No output holding found for payout B",
                                                Map.of("admin", instB.admin, "id", instB.id, "minAmount", outB.toPlainString())));
                                    }
                                    return Result.<HoldingSelectResponsePair, ApiError>ok(new HoldingSelectResponsePair(selA, selB));
                                }).thenCompose((Result<HoldingSelectResponsePair, ApiError> pairResult) -> {
                                    if (pairResult.isErr()) {
                                        return completedError(pairResult.getErrorUnsafe());
                                    }
                                    HoldingSelectResponsePair pair = pairResult.getValueUnsafe();
                                    String holdingCidA = pair.a.holdingCid();
                                    String holdingCidB = pair.b.holdingCid();

                                String memoRaw = buildMemo(request.requestId, resolvedPoolCid, request.lpCid, receiverParty, deadline,
                                            lpBurnAmount, outA, outB);
                                    Result<PayoutService.TransferFactoryPlan, ApiError> payoutPlanAResult =
                                            payoutService.prepareTransferFactory(
                                                    instA.admin,
                                                    instA.id,
                                                    holdingCidA,
                                                    receiverParty,
                                                    outA,
                                                    deadline,
                                                    memoRaw,
                                                    "remove-payout-a-" + request.requestId
                                            );
                                    if (payoutPlanAResult.isErr()) {
                                        return completedError(annotateNoSynchronizer(payoutPlanAResult.getErrorUnsafe(),
                                                "PreparePayoutA",
                                                List.of(operator),
                                                List.of(operator),
                                                null));
                                    }
                                    Result<PayoutService.TransferFactoryPlan, ApiError> payoutPlanBResult =
                                            payoutService.prepareTransferFactory(
                                                    instB.admin,
                                                    instB.id,
                                                    holdingCidB,
                                                    receiverParty,
                                                    outB,
                                                    deadline,
                                                    memoRaw,
                                                    "remove-payout-b-" + request.requestId
                                            );
                                    if (payoutPlanBResult.isErr()) {
                                        return completedError(annotateNoSynchronizer(payoutPlanBResult.getErrorUnsafe(),
                                                "PreparePayoutB",
                                                List.of(operator),
                                                List.of(operator),
                                                null));
                                    }
                                    PayoutService.TransferFactoryPlan planA = payoutPlanAResult.getValueUnsafe();
                                    PayoutService.TransferFactoryPlan planB = payoutPlanBResult.getValueUnsafe();

                                    List<com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract> disclosed =
                                            mergeDisclosed(planA.disclosedContracts(), planB.disclosedContracts());
                                    String synchronizerId = firstNonBlank(planA.synchronizerId(), planB.synchronizerId());

                                LOG.info("[LiquidityRemove] requestId={} operator={} poolCid={} poolIdText={} lpPoolId={} lpCid={} lpBurnAmount={} outA={} outB={} receiver={} payoutFactoryA={} payoutFactoryB={} actAs=[{}]",
                                            request.requestId,
                                            operator,
                                        resolvedPoolCid,
                                        poolIdText,
                                        lpToken.poolId,
                                            request.lpCid,
                                            lpBurnAmount.toPlainString(),
                                            outA.toPlainString(),
                                            outB.toPlainString(),
                                            receiverParty,
                                            planA.factoryCid(),
                                            planB.factoryCid(),
                                            operator);

                                    ValueOuterClass.Record choiceArgs = ValueOuterClass.Record.newBuilder()
                                            .addFields(recordField("requestId", textValue(request.requestId)))
                                            .addFields(recordField("lpCid", contractIdValue(request.lpCid)))
                                            .addFields(recordField("lpBurnAmount", numericValue(lpBurnAmount)))
                                            .addFields(recordField("receiverParty", partyValue(receiverParty)))
                                            .addFields(recordField("minOutA", numericValue(minOutA)))
                                            .addFields(recordField("minOutB", numericValue(minOutB)))
                                            .addFields(recordField("deadline", timestampValue(deadline)))
                                        .addFields(recordField("poolIdText", textValue(poolIdText)))
                                            .addFields(recordField("outputHoldingCidA", contractIdValue(holdingCidA)))
                                            .addFields(recordField("outputHoldingCidB", contractIdValue(holdingCidB)))
                                            .addFields(recordField("payoutFactoryCidA", contractIdValue(planA.factoryCid())))
                                            .addFields(recordField("payoutFactoryCidB", contractIdValue(planB.factoryCid())))
                                            .addFields(recordField("payoutExtraArgsA", recordValue(planA.extraArgs())))
                                            .addFields(recordField("payoutExtraArgsB", recordValue(planB.extraArgs())))
                                            .addFields(recordField("payoutMemo", textValue(memoRaw)))
                                            .build();

                                    return ledgerApi.exerciseRawWithLabel(
                                                    "RemoveLiquidityFromLpV1",
                                                    holdingPoolTemplateId(),
                                                resolvedPoolCid,
                                                    "RemoveLiquidityFromLpV1",
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
                                                                    "RemoveLiquidityFromLpV1",
                                                                    List.of(operator),
                                                                    List.of(),
                                                                    synchronizerId));
                                                }
                                                return Result.<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse, ApiError>ok(resp);
                                            })
                                            .thenCompose(result -> {
                                                if (result.isErr()) {
                                                    return completedError(result.getErrorUnsafe());
                                                }
                                                CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp = result.getValueUnsafe();
                                                String updateId = extractUpdateId(resp);
                                                RemoveOutcome outcome = extractRemoveOutcome(resp);
                                                if (updateId == null || updateId.isBlank()) {
                                                    return completedError(ApiError.of(ErrorCode.INTERNAL, "RemoveLiquidity returned no updateId"));
                                                }
                                                if (outcome == null || outcome.newPoolCid == null) {
                                                    return completedError(ApiError.of(ErrorCode.INTERNAL, "RemoveLiquidity returned no result"));
                                                }

                                                LiquidityRemoveConsumeResponse response = new LiquidityRemoveConsumeResponse();
                                                response.requestId = request.requestId;
                                                response.poolCid = resolvedPoolCid;
                                                response.lpCid = request.lpCid;
                                                response.receiverParty = receiverParty;
                                                response.lpBurnAmount = lpBurnAmount.toPlainString();
                                                response.outAmountA = outcome.outA.toPlainString();
                                                response.outAmountB = outcome.outB.toPlainString();
                                                response.payoutStatusA = outcome.payoutA.completed ? "COMPLETED" : "CREATED";
                                                response.payoutStatusB = outcome.payoutB.completed ? "COMPLETED" : "CREATED";
                                                response.payoutCidA = outcome.payoutA.payoutCid;
                                                response.payoutCidB = outcome.payoutB.payoutCid;
                                                response.payoutFactoryIdA = planA.factoryCid();
                                                response.payoutFactoryIdB = planB.factoryCid();
                                                response.newReserveA = outcome.newReserveA.toPlainString();
                                                response.newReserveB = outcome.newReserveB.toPlainString();
                                                response.ledgerUpdateId = updateId;
                                                response.executeStatus = "SUCCEEDED";

                                                idempotencyService.registerSuccess(request.requestId, request.requestId, updateId, response);
                                                if (featuredAppMarkerService != null) {
                                                    featuredAppMarkerService.createMarker(request.requestId, "LIQUIDITY_REMOVE",
                                                            request.lpCid + ":burn=" + lpBurnAmount);
                                                }
                                                return CompletableFuture.completedFuture(Result.ok(response));
                                            });
                                });
                            });
                });
    }

    @WithSpan
    public CompletableFuture<Result<LiquidityRemoveInspectResponse, ApiError>> inspect(
            String requestId,
            String poolCid,
            String lpCid,
            String receiverParty,
            String lpBurnAmount
    ) {
        if (requestId == null || requestId.isBlank()) {
            return completedError(validationError("requestId is required", "requestId"));
        }
        if (lpCid == null || lpCid.isBlank()) {
            return completedError(validationError("lpCid is required", "lpCid"));
        }
        if (receiverParty == null || receiverParty.isBlank()) {
            return completedError(validationError("receiverParty is required", "receiverParty"));
        }

        boolean alreadyConsumed = idempotencyService.checkIdempotency(requestId) instanceof LiquidityRemoveConsumeResponse;

        String providedPoolCid = poolCid;

        return ledgerReader.lpTokensForParty(receiverParty)
                .thenCompose(tokens -> {
                    Optional<LpTokenDTO> lpTokenOpt = tokens.stream()
                            .filter(t -> lpCid.equals(t.contractId))
                            .findFirst();
                    if (lpTokenOpt.isEmpty()) {
                        return completedError(preconditionError("LP position not found for owner",
                                Map.of("lpCid", lpCid, "owner", receiverParty)));
                    }
                    LpTokenDTO lpToken = lpTokenOpt.get();
                    return resolvePoolForRemove(lpToken, providedPoolCid)
                            .thenCompose(poolResult -> {
                                if (poolResult.isErr()) {
                                    return completedError(poolResult.getErrorUnsafe());
                                }
                                HoldingPoolResponse pool = poolResult.getValueUnsafe();
                                String resolvedPoolCid = pool.contractId;
                                String poolIdText = (pool.poolId != null && !pool.poolId.isBlank())
                                        ? pool.poolId
                                        : lpToken.poolId;
                                if (poolIdText == null || poolIdText.isBlank()) {
                                    return completedError(preconditionError("PoolId is missing",
                                            Map.of("poolCid", resolvedPoolCid)));
                                }
                                if (poolIdText != null && lpToken.poolId != null && !poolIdText.equals(lpToken.poolId)) {
                                    if (looksLikeCid(lpToken.poolId)) {
                                        return completedError(legacyPoolNotFound(lpToken, providedPoolCid, poolIdText, null));
                                    }
                                    return completedError(preconditionError("LP position pool mismatch",
                                            Map.of("lpCid", lpCid, "poolId", lpToken.poolId, "resolvedPoolId", poolIdText)));
                                }

                                BigDecimal reserveA = parseDecimalOrZero(pool.reserveAmountA);
                                BigDecimal reserveB = parseDecimalOrZero(pool.reserveAmountB);
                                BigDecimal lpSupply = parseDecimalOrZero(pool.lpSupply);
                                BigDecimal lpBalance = parseDecimalOrZero(lpToken.amount);
                                BigDecimal burnAmount = lpBurnAmount == null || lpBurnAmount.isBlank()
                                        ? lpBalance
                                        : parseDecimalOrZero(lpBurnAmount);
                                BigDecimal share = lpSupply.compareTo(BigDecimal.ZERO) > 0
                                        ? burnAmount.divide(lpSupply, 18, RoundingMode.DOWN)
                                        : BigDecimal.ZERO;
                                BigDecimal outA = reserveA.multiply(share).setScale(SwapConstants.SCALE, RoundingMode.DOWN);
                                BigDecimal outB = reserveB.multiply(share).setScale(SwapConstants.SCALE, RoundingMode.DOWN);
                                BigDecimal shareBps = share.multiply(new BigDecimal("10000")).setScale(0, RoundingMode.DOWN);

                                LiquidityRemoveInspectResponse resp = new LiquidityRemoveInspectResponse();
                                resp.requestId = requestId;
                                resp.poolCid = resolvedPoolCid;
                                resp.poolId = poolIdText;
                                resp.poolStatus = pool.status;
                                resp.instrumentA = pool.instrumentA;
                                resp.instrumentB = pool.instrumentB;
                                resp.reserveA = pool.reserveAmountA;
                                resp.reserveB = pool.reserveAmountB;
                                resp.lpSupply = pool.lpSupply;
                                resp.lpCid = lpCid;
                                resp.lpOwner = receiverParty;
                                resp.lpBalance = lpToken.amount;
                                resp.lpBurnAmount = burnAmount.toPlainString();
                                resp.shareBps = shareBps.toPlainString();
                                resp.outAmountA = outA.toPlainString();
                                resp.outAmountB = outB.toPlainString();
                                resp.alreadyConsumed = alreadyConsumed;
                                return CompletableFuture.completedFuture(Result.ok(resp));
                            });
                });
    }

    private String buildMemo(
            String requestId,
            String poolCid,
            String lpCid,
            String receiverParty,
            Instant deadline,
            BigDecimal lpBurnAmount,
            BigDecimal outA,
            BigDecimal outB
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("v", 1);
            payload.put("kind", "removeLiquidity");
            payload.put("requestId", requestId);
            payload.put("poolCid", poolCid);
            payload.put("lpCid", lpCid);
            payload.put("receiverParty", receiverParty);
            payload.put("deadline", deadline.toString());
            payload.put("lpBurnAmount", lpBurnAmount.toPlainString());
            payload.put("outAmountA", outA.toPlainString());
            payload.put("outAmountB", outB.toPlainString());
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return requestId;
        }
    }

    private BigDecimal parseDecimalOrZero(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
        try {
            return new BigDecimal(value).setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        } catch (Exception e) {
            return BigDecimal.ZERO.setScale(SwapConstants.SCALE, RoundingMode.DOWN);
        }
    }

    private Result<BigDecimal, ApiError> parseDecimalRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            return Result.err(validationError(field + " is required", field));
        }
        try {
            return Result.ok(new BigDecimal(value).setScale(SwapConstants.SCALE, RoundingMode.DOWN));
        } catch (Exception e) {
            return Result.err(validationError("Invalid decimal value", field));
        }
    }

    private Instant parseDeadline(String iso) {
        if (iso == null || iso.isBlank()) {
            return Instant.now().plusSeconds(DEFAULT_DEADLINE_SECONDS);
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return Instant.now().plusSeconds(DEFAULT_DEADLINE_SECONDS);
        }
    }

    private CompletableFuture<Result<HoldingPoolResponse, ApiError>> resolvePoolForRemove(
            LpTokenDTO lpToken,
            String providedPoolCid
    ) {
        String lpPoolId = lpToken != null ? lpToken.poolId : null;
        boolean legacyHint = looksLikeCid(lpPoolId);
        if (!legacyHint && lpPoolId != null && !lpPoolId.isBlank()) {
            return holdingPoolService.resolveActiveByPoolId(lpPoolId)
                    .thenCompose(result -> {
                        if (result.isOk()) {
                            return CompletableFuture.completedFuture(Result.ok(result.getValueUnsafe()));
                        }
                        return resolveLegacyFallback(lpToken, providedPoolCid, result.getErrorUnsafe());
                    });
        }
        return resolveLegacyFallback(lpToken, providedPoolCid, null);
    }

    private CompletableFuture<Result<HoldingPoolResponse, ApiError>> resolveLegacyFallback(
            LpTokenDTO lpToken,
            String providedPoolCid,
            DomainError resolveError
    ) {
        if (providedPoolCid != null && !providedPoolCid.isBlank()) {
            return holdingPoolService.getByContractId(providedPoolCid)
                    .thenApply(poolResult -> {
                        if (poolResult.isOk()) {
                            return Result.ok(poolResult.getValueUnsafe());
                        }
                        return Result.err(legacyPoolNotFound(lpToken, providedPoolCid, null, resolveError));
                    });
        }
        return CompletableFuture.completedFuture(Result.err(legacyPoolNotFound(lpToken, providedPoolCid, null, resolveError)));
    }

    private boolean looksLikeCid(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("00") && trimmed.length() > 20;
    }

    private ApiError legacyPoolNotFound(
            LpTokenDTO lpToken,
            String providedPoolCid,
            String resolvedPoolId,
            DomainError resolveError
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (lpToken != null) {
            details.put("lpCid", lpToken.contractId);
            details.put("lpPoolIdText", lpToken.poolId);
            details.put("lpOwner", lpToken.owner);
        }
        if (providedPoolCid != null && !providedPoolCid.isBlank()) {
            details.put("providedPoolCid", providedPoolCid);
        }
        if (resolvedPoolId != null && !resolvedPoolId.isBlank()) {
            details.put("resolvedPoolId", resolvedPoolId);
        }
        if (resolveError != null) {
            details.put("resolveError", resolveError.message());
            details.put("resolveErrorCode", resolveError.code());
        }
        return new ApiError(
                ErrorCode.LEGACY_LP_POOL_NOT_FOUND,
                "This LP references an old pool contractId. Please migrate (burn/mint new LP) or contact operator.",
                details,
                false,
                null,
                null
        );
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

    private RemoveOutcome extractRemoveOutcome(CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp) {
        if (resp == null || !resp.hasTransaction()) return null;
        for (var ev : resp.getTransaction().getEventsList()) {
            if (!ev.hasExercised()) continue;
            if (!"RemoveLiquidityFromLpV1".equals(ev.getExercised().getChoice())) continue;
            var result = ev.getExercised().getExerciseResult();
            if (!result.hasRecord()) continue;
            var rec = result.getRecord();
            if (rec.getFieldsCount() < 7) continue;
            String newPoolCid = rec.getFields(0).getValue().getContractId();
            BigDecimal outA = numericFromValue(rec.getFields(1).getValue());
            BigDecimal outB = numericFromValue(rec.getFields(2).getValue());
            BigDecimal newReserveA = numericFromValue(rec.getFields(3).getValue());
            BigDecimal newReserveB = numericFromValue(rec.getFields(4).getValue());
            PayoutOutcomeInfo payoutA = parsePayoutOutcome(rec.getFields(5).getValue());
            PayoutOutcomeInfo payoutB = parsePayoutOutcome(rec.getFields(6).getValue());
            if (newPoolCid == null || newPoolCid.isBlank() || payoutA == null || payoutB == null) {
                continue;
            }
            return new RemoveOutcome(newPoolCid, outA, outB, newReserveA, newReserveB, payoutA, payoutB);
        }
        return null;
    }

    private PayoutOutcomeInfo parsePayoutOutcome(ValueOuterClass.Value value) {
        if (value == null) {
            return null;
        }
        if (value.hasVariant()) {
            var variant = value.getVariant();
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
        if (value.hasContractId()) {
            return new PayoutOutcomeInfo(value.getContractId(), false);
        }
        if (value.hasOptional() && value.getOptional().hasValue()) {
            ValueOuterClass.Value inner = value.getOptional().getValue();
            if (inner.hasContractId()) {
                return new PayoutOutcomeInfo(inner.getContractId(), false);
            }
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

    private <T> CompletableFuture<Result<T, ApiError>> completedError(ApiError err) {
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

    private record HoldingSelectResponsePair(HoldingSelectResponse a, HoldingSelectResponse b) {}

    private record RemoveOutcome(
            String newPoolCid,
            BigDecimal outA,
            BigDecimal outB,
            BigDecimal newReserveA,
            BigDecimal newReserveB,
            PayoutOutcomeInfo payoutA,
            PayoutOutcomeInfo payoutB
    ) {}

    private record PayoutOutcomeInfo(String payoutCid, boolean completed) {}
}

