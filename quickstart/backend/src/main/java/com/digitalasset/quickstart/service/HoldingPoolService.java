package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.TransactionOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.HoldingPoolCreateRequest;
import com.digitalasset.quickstart.dto.HoldingPoolResponse;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HoldingPoolService {

    private static final Logger LOG = LoggerFactory.getLogger(HoldingPoolService.class);
    private static final String MODULE_NAME = "AMM.HoldingPool";
    private static final String ENTITY_NAME = "HoldingPool";

    @Value("${holdingpool.package-id:}")
    private String packageId;

    private final LedgerApi ledgerApi;
    private final AuthUtils authUtils;
    private final TransferInstructionAcsQueryService tiQueryService;
    private final CbtcTransferOfferService cbtcService;
    private final TransferInstructionAcceptService tiAcceptService;

    public HoldingPoolService(final LedgerApi ledgerApi,
                              final AuthUtils authUtils,
                              final TransferInstructionAcsQueryService tiQueryService,
                              final CbtcTransferOfferService cbtcService,
                              final TransferInstructionAcceptService tiAcceptService) {
        this.ledgerApi = ledgerApi;
        this.authUtils = authUtils;
        this.tiQueryService = tiQueryService;
        this.cbtcService = cbtcService;
        this.tiAcceptService = tiAcceptService;
    }

    @WithSpan
    public Result<java.util.Map<String, Object>, DomainError> bootstrap(String poolCid, com.digitalasset.quickstart.dto.HoldingPoolBootstrapRequest req) {
        if (poolCid == null || poolCid.isBlank()) {
            return Result.err(new ValidationError("poolId is required", ValidationError.Type.REQUEST));
        }
        if (req == null || req.tiCidA == null || req.tiCidA.isBlank() || req.tiCidB == null || req.tiCidB.isBlank()
                || req.amountA == null || req.amountB == null) {
            return Result.err(new ValidationError("tiCidA, tiCidB, amountA, amountB are required", ValidationError.Type.REQUEST));
        }

        String requestId = "bootstrap-" + UUID.randomUUID().toString().substring(0, 8);
        String operator = authUtils.getAppProviderPartyId();
        String lpProvider = req.lpProvider != null && !req.lpProvider.isBlank() ? req.lpProvider : operator;
        if (req.amountA == null || req.amountB == null) {
            return Result.err(new ValidationError("amountA and amountB are required", ValidationError.Type.REQUEST));
        }

        // Resolve TIs if not provided (reuse existing discovery)
        String tiA = req.tiCidA;
        String tiB = req.tiCidB;
        if (isBlank(tiA) || isBlank(tiB)) {
            var discovery = tiQueryService.listForReceiver(operator);
            if (!discovery.isOk()) {
                return Result.err(discovery.getErrorUnsafe());
            }
            List<TransferInstructionDto> all = discovery.getValueUnsafe();
            tiA = tiA != null ? tiA : pickByAmount(all, req.amountA, true);
            tiB = tiB != null ? tiB : pickByAmount(all, req.amountB, false);
        }
        if (isBlank(tiA) || isBlank(tiB)) {
            return Result.err(new ValidationError("Could not resolve TI candidates", ValidationError.Type.REQUEST));
        }

        TransferInstructionDto tiAInfo = tiQueryService.findByCid(operator, tiA).orElse(null);
        TransferInstructionDto tiBInfo = tiQueryService.findByCid(operator, tiB).orElse(null);
        if (tiAInfo == null || tiBInfo == null) {
            return Result.err(new UnexpectedError("Bootstrap TIs not found in ACS"));
        }
        LOG.info("[Bootstrap] requestId={} poolCid={} tiA={} senderA={} receiverA={} adminA={} instrumentA={} amountA={} execBeforeA={}",
                requestId,
                poolCid,
                tiA,
                tiAInfo.sender(),
                tiAInfo.receiver(),
                tiAInfo.admin(),
                tiAInfo.instrumentId(),
                tiAInfo.amount(),
                tiAInfo.executeBefore());
        LOG.info("[Bootstrap] requestId={} poolCid={} tiB={} senderB={} receiverB={} adminB={} instrumentB={} amountB={} execBeforeB={}",
                requestId,
                poolCid,
                tiB,
                tiBInfo.sender(),
                tiBInfo.receiver(),
                tiBInfo.admin(),
                tiBInfo.instrumentId(),
                tiBInfo.amount(),
                tiBInfo.executeBefore());

        TransferInstructionAcceptService.AcceptWithContextResponse amuletBody;
        CbtcTransferOfferService.AcceptOfferResult cbtcResult = null;
        // Accept Amulet TI via TransferInstructionAcceptService (accept-with-context)
        LOG.info("[Bootstrap] requestId={} accepting Amulet TI {}", requestId, tiA);
        amuletBody = tiAcceptService.acceptWithContext(
                new TransferInstructionAcceptService.AcceptRequest(tiA, operator)
        );
        if (amuletBody == null || !amuletBody.accepted()) {
            return Result.err(new UnexpectedError("Amulet TI accept failed: " + (amuletBody != null ? amuletBody.error() : "null response")));
        }

        // Accept CBTC TI via CbtcTransferOfferService (using acceptOffer on OFFER CID)
        String cbtcRequestId = "bootstrap-cbtc-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        LOG.info("[Bootstrap] requestId={} accepting CBTC TI {}", cbtcRequestId, tiB);
        cbtcResult = cbtcService.acceptOffer(tiB, operator, cbtcRequestId).toCompletableFuture().join();
        if (!cbtcResult.ok()) {
            return Result.err(new UnexpectedError("CBTC TI accept failed: " + cbtcResult.classification()));
        }

        // Post-check ACS: ensure TIs are gone
        var postCheck = tiQueryService.listForReceiver(operator);
        if (!postCheck.isOk()) {
            return Result.err(postCheck.getErrorUnsafe());
        }
        final String tiAFinal = tiA;
        final String tiBFinal = tiB;
        boolean amuletStill = postCheck.getValueUnsafe().stream().anyMatch(t -> tiAFinal.equals(t.contractId()));
        boolean cbtcStill = postCheck.getValueUnsafe().stream().anyMatch(t -> tiBFinal.equals(t.contractId()));
        if (amuletStill || cbtcStill) {
            return Result.err(new UnexpectedError("BOOTSTRAP_ACCEPT_NOT_CONSUMED"));
        }

        ValueOuterClass.Identifier tid = templateId();
        ValueOuterClass.Record choiceArg = ValueOuterClass.Record.newBuilder()
                .addFields(recordField("tiCidA", ValueOuterClass.Value.newBuilder().setContractId(tiA).build()))
                .addFields(recordField("tiCidB", ValueOuterClass.Value.newBuilder().setContractId(tiB).build()))
                .addFields(recordField("amountA", decimalValue(req.amountA)))
                .addFields(recordField("amountB", decimalValue(req.amountB)))
                .addFields(recordField("lpProvider", ValueOuterClass.Value.newBuilder().setParty(lpProvider).build()))
                .build();

        try {
            CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp = ledgerApi.exerciseRaw(
                    tid,
                    poolCid,
                    "Bootstrap",
                    choiceArg,
                    List.of(operator),
                    List.of(),
                    List.of()
            ).toCompletableFuture().join();
            String updateId = resp.getTransaction().getUpdateId();
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("ok", true);
            payload.put("accept", java.util.Map.of(
                    "amulet", java.util.Map.of("ok", true, "ledgerUpdateId", amuletBody.updateId()),
                    "cbtc", java.util.Map.of("ok", true, "ledgerUpdateId", cbtcResult.ledgerUpdateId())
            ));
            payload.put("postCheck", java.util.Map.of(
                    "amuletStillInAcs", false,
                    "cbtcStillInAcs", false
            ));
            payload.put("bootstrap", java.util.Map.of("ok", true, "ledgerUpdateId", updateId));
            return Result.ok(payload);
        } catch (Exception e) {
            LOG.error("Bootstrap failed: {}", e.getMessage(), e);
            return Result.err(new UnexpectedError(e.getMessage()));
        }
    }

    @WithSpan
    public Result<java.util.Map<String, Object>, DomainError> archive(final String contractId) {
        if (isBlank(contractId)) {
            return Result.err(new ValidationError("contractId is required", ValidationError.Type.REQUEST));
        }
        String operator = authUtils.getAppProviderPartyId();
        ValueOuterClass.Identifier tid = templateId();
        ValueOuterClass.Record empty = ValueOuterClass.Record.newBuilder().build();
        try {
            CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp = ledgerApi.exerciseRaw(
                    tid,
                    contractId,
                    "Archive",
                    empty,
                    List.of(operator),
                    List.of(),
                    List.of()
            ).toCompletableFuture().join();
            String updateId = resp.getTransaction().getUpdateId();
            return Result.ok(java.util.Map.of("ok", true, "ledgerUpdateId", updateId));
        } catch (Exception e) {
            LOG.error("Archive failed: {}", e.getMessage(), e);
            return Result.err(new UnexpectedError(e.getMessage()));
        }
    }

    @WithSpan
    public CompletableFuture<Result<HoldingPoolResponse, DomainError>> create(final HoldingPoolCreateRequest req) {
        Optional<DomainError> validation = validate(req);
        if (validation.isPresent()) {
            return completedError(validation.get());
        }
        String operator = authUtils.getAppProviderPartyId();
        ValueOuterClass.Identifier tid = templateId();
        ValueOuterClass.Record args = buildCreateArgs(req, operator);

        return ledgerApi.createRaw(tid, args, List.of(operator), List.of(operator))
                .<Result<HoldingPoolResponse, DomainError>>handle((resp, throwable) -> {
                    if (throwable != null) {
                        LOG.error("HoldingPool create failed: {}", throwable.getMessage(), throwable);
                        return Result.err(new UnexpectedError(throwable.getMessage()));
                    }
                    String cid = extractCreatedCid(resp, tid);
                    HoldingPoolResponse dto = new HoldingPoolResponse(
                            cid,
                            "Uninitialized",
                            req.instrumentA,
                            req.instrumentB,
                            req.feeBps,
                            Instant.now().toString(),
                            "0", "0", "0", "0", "0",
                            false
                    );
                    dto.poolId = req.poolId;
                    return Result.ok(dto);
                });
    }

    @WithSpan
    public CompletableFuture<Result<List<HoldingPoolResponse>, DomainError>> list() {
        String operator = authUtils.getAppProviderPartyId();
        ValueOuterClass.Identifier tid = templateId();
        return ledgerApi.getActiveContractsRawForParty(operator)
                .<Result<List<HoldingPoolResponse>, DomainError>>handle((acs, throwable) -> {
                    if (throwable != null) {
                        LOG.error("Failed to list HoldingPool ACS: {}", throwable.getMessage(), throwable);
                        return Result.err(new UnexpectedError(throwable.getMessage()));
                    }
                    List<HoldingPoolResponse> pools = new ArrayList<>();
                    acs.stream()
                            .filter(rac -> isHoldingPool(rac.templateId()))
                            .forEach(rac -> {
                                LOG.debug("HoldingPool ACS raw createArguments for {}: {}", rac.contractId(), rac.createArguments());
                                parseHoldingPool(rac.createArguments(), rac.contractId())
                                    .ifPresent(pools::add);
                            });
                    return Result.ok(pools);
                });
    }

    @WithSpan
    public CompletableFuture<Result<HoldingPoolResponse, DomainError>> getByContractId(final String contractId) {
        if (contractId == null || contractId.isBlank()) {
            return completedError(new ValidationError("contractId is required", ValidationError.Type.REQUEST));
        }
        String operator = authUtils.getAppProviderPartyId();
        return ledgerApi.getActiveContractsRawForParty(operator)
                .<Result<HoldingPoolResponse, DomainError>>handle((acs, throwable) -> {
                    if (throwable != null) {
                        LOG.error("Failed to list HoldingPool ACS: {}", throwable.getMessage(), throwable);
                        return Result.err(new UnexpectedError(throwable.getMessage()));
                    }
                    return acs.stream()
                            .filter(rac -> isHoldingPool(rac.templateId()))
                            .filter(rac -> contractId.equals(rac.contractId()))
                            .findFirst()
                            .flatMap(rac -> {
                                LOG.debug("HoldingPool ACS raw createArguments for {}: {}", rac.contractId(), rac.createArguments());
                                return parseHoldingPool(rac.createArguments(), rac.contractId());
                            })
                            .map(Result::<HoldingPoolResponse, DomainError>ok)
                            .orElseGet(() -> Result.err(new UnexpectedError("HoldingPool not found: " + contractId)));
                });
    }

    @WithSpan
    public CompletableFuture<Result<HoldingPoolResponse, DomainError>> resolveActiveByPoolId(final String poolId) {
        if (poolId == null || poolId.isBlank()) {
            return completedError(new ValidationError("poolId is required", ValidationError.Type.REQUEST));
        }
        return list().thenApply(result -> {
            if (result.isErr()) {
                return Result.err(result.getErrorUnsafe());
            }
            List<HoldingPoolResponse> matches = result.getValueUnsafe().stream()
                    .filter(pool -> pool != null && pool.poolId != null && pool.poolId.equals(poolId))
                    .filter(pool -> pool.status != null && "active".equalsIgnoreCase(pool.status))
                    .toList();
            if (matches.isEmpty()) {
                return Result.err(new ValidationError("No active pool found for poolId: " + poolId, ValidationError.Type.REQUEST));
            }
            if (matches.size() > 1) {
                return Result.err(new ValidationError("Multiple active pools found for poolId: " + poolId, ValidationError.Type.REQUEST));
            }
            return Result.ok(matches.get(0));
        });
    }

    @WithSpan
    public Result<Void, DomainError> bootstrapStub() {
        return Result.err(new UnexpectedError("Bootstrap not implemented. Required inputs: holdingCidA, holdingCidB, amounts, minLpOut"));
    }

    private Optional<DomainError> validate(final HoldingPoolCreateRequest req) {
        if (req == null) {
            return Optional.of(new ValidationError("request is required", ValidationError.Type.REQUEST));
        }
        if (isBlank(packageId)) {
            return Optional.of(new ValidationError("holdingpool.package-id is not configured", ValidationError.Type.REQUEST));
        }
        if (isBlank(req.poolId)) {
            return Optional.of(new ValidationError("poolId is required", ValidationError.Type.REQUEST));
        }
        if (req.instrumentA == null || isBlank(req.instrumentA.admin) || isBlank(req.instrumentA.id)) {
            return Optional.of(new ValidationError("instrumentA.admin and instrumentA.id are required", ValidationError.Type.REQUEST));
        }
        if (req.instrumentB == null || isBlank(req.instrumentB.admin) || isBlank(req.instrumentB.id)) {
            return Optional.of(new ValidationError("instrumentB.admin and instrumentB.id are required", ValidationError.Type.REQUEST));
        }
        if (req.feeBps == null || req.feeBps < 0) {
            return Optional.of(new ValidationError("feeBps must be non-negative", ValidationError.Type.REQUEST));
        }
        return Optional.empty();
    }

    private ValueOuterClass.Identifier templateId() {
        return ValueOuterClass.Identifier.newBuilder()
                .setPackageId(packageId)
                .setModuleName(MODULE_NAME)
                .setEntityName(ENTITY_NAME)
                .build();
    }

    private ValueOuterClass.Record buildCreateArgs(final HoldingPoolCreateRequest req, final String operator) {
        return ValueOuterClass.Record.newBuilder()
                .addFields(recordField("operator", ValueOuterClass.Value.newBuilder().setParty(operator).build()))
                .addFields(recordField("instrumentA", instrumentRecord(req.instrumentA)))
                .addFields(recordField("instrumentB", instrumentRecord(req.instrumentB)))
                .addFields(recordField("status", statusVariant("Uninitialized")))
                .addFields(recordField("reserveAmountA", decimalValue(BigDecimal.ZERO)))
                .addFields(recordField("reserveAmountB", decimalValue(BigDecimal.ZERO)))
                .addFields(recordField("lockedAmountA", decimalValue(BigDecimal.ZERO)))
                .addFields(recordField("lockedAmountB", decimalValue(BigDecimal.ZERO)))
                .addFields(recordField("feeRate", decimalValue(bpsToRate(req.feeBps))))
                .addFields(recordField("lpSupply", decimalValue(BigDecimal.ZERO)))
                .addFields(recordField("poolId", optionalTextValue(req.poolId)))
                .build();
    }

    private ValueOuterClass.Value instrumentRecord(final HoldingPoolCreateRequest.InstrumentRef ref) {
        ValueOuterClass.Record rec = ValueOuterClass.Record.newBuilder()
                .addFields(recordField("admin", ValueOuterClass.Value.newBuilder().setParty(ref.admin).build()))
                .addFields(recordField("id", ValueOuterClass.Value.newBuilder().setText(ref.id).build()))
                .build();
        return ValueOuterClass.Value.newBuilder().setRecord(rec).build();
    }

    private ValueOuterClass.Value statusVariant(final String constructor) {
        ValueOuterClass.Identifier statusId = ValueOuterClass.Identifier.newBuilder()
                .setPackageId(packageId)
                .setModuleName(MODULE_NAME)
                .setEntityName("PoolStatus")
                .build();
        return ValueOuterClass.Value.newBuilder()
                .setEnum(ValueOuterClass.Enum.newBuilder()
                        .setEnumId(statusId)
                        .setConstructor(constructor)
                        .build())
                .build();
    }

    private ValueOuterClass.RecordField recordField(final String label, final ValueOuterClass.Value value) {
        return ValueOuterClass.RecordField.newBuilder().setLabel(label).setValue(value).build();
    }

    private boolean isHoldingPool(final ValueOuterClass.Identifier id) {
        return id != null
                && ENTITY_NAME.equals(id.getEntityName())
                && MODULE_NAME.equals(id.getModuleName());
    }

    private Optional<HoldingPoolResponse> parseHoldingPool(final ValueOuterClass.Record args, final String contractId) {
        if (args == null) {
            return Optional.empty();
        }
        String poolId = textOrOptionalTextField(args, "poolId");
        HoldingPoolCreateRequest.InstrumentRef instrumentA = parseInstrument(args, "instrumentA");
        HoldingPoolCreateRequest.InstrumentRef instrumentB = parseInstrument(args, "instrumentB");
        String status = statusField(args, "status");
        BigDecimal feeRate = decimalField(args, "feeRate");
        BigDecimal reserveA = decimalField(args, "reserveAmountA");
        BigDecimal reserveB = decimalField(args, "reserveAmountB");
        BigDecimal lockedA = decimalField(args, "lockedAmountA");
        BigDecimal lockedB = decimalField(args, "lockedAmountB");
        BigDecimal lpSupply = decimalField(args, "lpSupply");
        if (instrumentA == null || instrumentB == null) {
            return Optional.empty();
        }
        Integer feeBps = feeRate != null ? feeRate.multiply(BigDecimal.valueOf(10000)).intValue() : null;
        HoldingPoolResponse response = new HoldingPoolResponse(
                contractId,
                status,
                instrumentA,
                instrumentB,
                feeBps,
                null,
                toStringOrZero(reserveA),
                toStringOrZero(reserveB),
                toStringOrZero(lockedA),
                toStringOrZero(lockedB),
                toStringOrZero(lpSupply),
                false
        );
        response.poolId = poolId;
        return Optional.of(response);
    }

    private HoldingPoolCreateRequest.InstrumentRef parseInstrument(final ValueOuterClass.Record args, final String label) {
        ValueOuterClass.Value v = field(args, label);
        if (v == null || !v.hasRecord()) return null;
        ValueOuterClass.Record rec = v.getRecord();
        String admin = textOrParty(rec, "admin");
        String id = textField(rec, "id");
        if (admin == null || id == null) return null;
        return new HoldingPoolCreateRequest.InstrumentRef(admin, id);
    }

    private String statusField(final ValueOuterClass.Record args, final String label) {
        ValueOuterClass.Value v = field(args, label);
        if (v == null) return null;
        switch (v.getSumCase()) {
            case VARIANT:
                return v.getVariant().getConstructor();
            case ENUM:
                return v.getEnum().getConstructor();
            default:
                return null;
        }
    }

    private BigDecimal decimalField(final ValueOuterClass.Record args, final String label) {
        ValueOuterClass.Value v = field(args, label);
        if (v == null || v.getSumCase() != ValueOuterClass.Value.SumCase.NUMERIC) return null;
        return new BigDecimal(v.getNumeric());
    }

    private String textField(final ValueOuterClass.Record args, final String label) {
        ValueOuterClass.Value v = field(args, label);
        if (v == null || v.getSumCase() != ValueOuterClass.Value.SumCase.TEXT) return null;
        return v.getText();
    }

    private String textOrOptionalTextField(final ValueOuterClass.Record args, final String label) {
        ValueOuterClass.Value v = field(args, label);
        if (v == null) return null;
        if (v.getSumCase() == ValueOuterClass.Value.SumCase.TEXT) {
            return v.getText();
        }
        if (v.getSumCase() == ValueOuterClass.Value.SumCase.OPTIONAL && v.getOptional().hasValue()) {
            ValueOuterClass.Value inner = v.getOptional().getValue();
            if (inner.getSumCase() == ValueOuterClass.Value.SumCase.TEXT) {
                return inner.getText();
            }
        }
        return null;
    }

    private ValueOuterClass.Value optionalTextValue(final String value) {
        ValueOuterClass.Optional.Builder opt = ValueOuterClass.Optional.newBuilder();
        if (value != null && !value.isBlank()) {
            opt.setValue(ValueOuterClass.Value.newBuilder().setText(value).build());
        }
        return ValueOuterClass.Value.newBuilder().setOptional(opt.build()).build();
    }

    private String textOrParty(final ValueOuterClass.Record args, final String label) {
        ValueOuterClass.Value v = field(args, label);
        if (v == null) return null;
        if (v.getSumCase() == ValueOuterClass.Value.SumCase.TEXT) return v.getText();
        if (v.getSumCase() == ValueOuterClass.Value.SumCase.PARTY) return v.getParty();
        return null;
    }

    private ValueOuterClass.Value field(final ValueOuterClass.Record args, final String label) {
        Optional<ValueOuterClass.Value> byLabel = args.getFieldsList().stream()
                .filter(f -> label.equals(f.getLabel()))
                .map(ValueOuterClass.RecordField::getValue)
                .findFirst();
        if (byLabel.isPresent()) {
            return byLabel.get();
        }
        int idx = indexForLabel(label);
        if (idx >= 0 && idx < args.getFieldsCount()) {
            return args.getFields(idx).getValue();
        }
        return null;
    }

    private ValueOuterClass.Value decimalValue(BigDecimal bd) {
        if (bd == null) bd = BigDecimal.ZERO;
        return ValueOuterClass.Value.newBuilder()
                .setNumeric(bd.setScale(10, RoundingMode.UNNECESSARY).toPlainString())
                .build();
    }

    private BigDecimal bpsToRate(Integer bps) {
        if (bps == null) return BigDecimal.ZERO.setScale(10, RoundingMode.UNNECESSARY);
        return new BigDecimal(bps).movePointLeft(4).setScale(10, RoundingMode.UNNECESSARY);
    }

    private boolean isBlank(final String s) {
        return s == null || s.isBlank();
    }

    private <T, E> CompletableFuture<Result<T, E>> completedError(E error) {
        return CompletableFuture.completedFuture(Result.err(error));
    }

    private String toStringOrZero(BigDecimal bd) {
        return bd != null ? bd.toPlainString() : "0";
    }

    private String pickByAmount(List<TransferInstructionDto> list, BigDecimal amount, boolean amulet) {
        if (amount == null) return null;
        BigDecimal target = amount.setScale(10, RoundingMode.UNNECESSARY);
        return list.stream()
                .filter(t -> target.compareTo(new BigDecimal(t.amount())) == 0)
                .filter(t -> amulet ? "Amulet".equals(t.instrumentId()) : "CBTC".equals(t.instrumentId()))
                .map(TransferInstructionDto::contractId)
                .findFirst()
                .orElse(null);
    }

    private int indexForLabel(final String label) {
        return switch (label) {
            case "operator" -> 0;
            case "instrumentA" -> 1;
            case "instrumentB" -> 2;
            case "status" -> 3;
            case "reserveAmountA" -> 4;
            case "reserveAmountB" -> 5;
            case "lockedAmountA" -> 6;
            case "lockedAmountB" -> 7;
            case "feeRate" -> 8;
            case "lpSupply" -> 9;
            case "poolId" -> 10;
            case "admin" -> 0;
            case "id" -> 1;
            default -> -1;
        };
    }

    private String extractCreatedCid(final CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp,
                                     final ValueOuterClass.Identifier target) {
        try {
            if (resp == null || !resp.hasTransaction()) return null;
            for (var event : resp.getTransaction().getEventsList()) {
                if (event.hasCreated()) {
                    var created = event.getCreated();
                    ValueOuterClass.Identifier tid = created.getTemplateId();
                    if (isHoldingPool(tid)) {
                        return created.getContractId();
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to extract contractId from transaction: {}", e.getMessage());
        }
        return null;
    }
}
