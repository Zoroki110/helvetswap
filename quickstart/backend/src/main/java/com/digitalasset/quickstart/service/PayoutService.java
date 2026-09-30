package com.digitalasset.quickstart.service;

import com.daml.daml_lf_dev.DamlLf1;
import com.daml.daml_lf_dev.DamlLf2;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.TransactionOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig;
import com.digitalasset.quickstart.config.TemplateSchemaDebugConfig;
import com.digitalasset.quickstart.constants.SwapConstants;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.HoldingSelectRequest;
import com.digitalasset.quickstart.dto.HoldingSelectResponse;
import com.digitalasset.quickstart.dto.PayoutRequest;
import com.digitalasset.quickstart.dto.PayoutResponse;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.AuthUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Empty;
import io.grpc.Status;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * DEVNET-only payout factory for outbound Amulet and CBTC instructions.
 * Uses TemplateSchemaService to deterministically build create arguments (no guessing order).
 */
@Service
public class PayoutService {

    private static final Logger LOG = LoggerFactory.getLogger(PayoutService.class);

    // Constants for instruments
    private static final String AMULET_ADMIN = "DSO::1220b1431ef217342db44d516bb9befde802be7d8899637d290895fa58880f19accc";
    private static final String AMULET_ID = "Amulet";
    // Canton 3.5 (Splice 0.6.x): package-id references removed from the Ledger API command path.
    // Use the package-NAME (#splice-amulet); resolved to a concrete id only for schema introspection
    // (see resolvePackageId at the getTemplateAst call). Was hex 3ca1343a… = splice-amulet 0.1.14.
    private static final String AMULET_PKG = "#splice-amulet";
    private static final String AMULET_MODULE = "Splice.AmuletTransferInstruction";
    private static final String AMULET_ENTITY = "AmuletTransferInstruction";
    private static final String TRANSFER_INSTRUCTION_PKG = "55ba4deb0ad4662c4168b39859738a0e91388d252286480c7331b3f71a517281";
    private static final String TRANSFER_INSTRUCTION_MODULE = "Splice.Api.Token.TransferInstructionV1";
    private static final String TRANSFER_FACTORY_ENTITY = "TransferFactory";
    private static final String TRANSFER_FACTORY_ARG = "TransferFactory_Transfer";
    private static final String TRANSFER_FACTORY_CHOICE = "TransferFactory_Transfer";
    private static final String TRANSFER_FACTORY_INTERFACE_PKG = "#splice-api-token-transfer-instruction-v1";

    private static final String CBTC_ADMIN = "cbtc-network::12205af3b949a04776fc48cdcc05a060f6bda2e470632935f375d1049a8546a3b262";
    private static final String CBTC_ID = "CBTC";
    // Canton 3.5 package-NAME (#utility-registry-app-v0); resolved to a concrete id only for schema
    // introspection. Was hex 170929b1… = utility-registry-app-v0 0.4.0; #name floats to highest vetted.
    private static final String CBTC_PKG = "#utility-registry-app-v0";
    private static final String CBTC_MODULE = "Utility.Registry.App.V0.Model.Transfer";
    private static final String CBTC_ENTITY = "TransferOffer";

    // Cure (iv.1): outbound payout memo emission on TransferFactory_Transfer.transfer.meta.values
    static final String SWAP_MEMO_META_KEY = "helvetswap.app/swap-memo";
    static final String SWAP_MEMO_ORIGINATOR = "helvetswap-amm";
    static final String SWAP_REQUEST_ID_PREFIX = "swap-payout-";

    private final LedgerApi ledgerApi;
    private final TemplateSchemaService schemaService;
    private final HoldingSelectorService holdingSelectorService;
    private final AuthUtils authUtils;
    private final TemplateSchemaDebugConfig schemaConfig;
    private final String amuletChoiceName;
    private final LedgerConfig ledgerConfig;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PayoutService(LedgerApi ledgerApi,
                         TemplateSchemaService schemaService,
                         HoldingSelectorService holdingSelectorService,
                         AuthUtils authUtils,
                         TemplateSchemaDebugConfig schemaConfig,
                         LedgerConfig ledgerConfig,
                         @org.springframework.beans.factory.annotation.Value("${feature.amulet-payout-choice:}") String amuletChoiceName) {
        this.ledgerApi = ledgerApi;
        this.schemaService = schemaService;
        this.holdingSelectorService = holdingSelectorService;
        this.authUtils = authUtils;
        this.schemaConfig = schemaConfig;
        this.ledgerConfig = ledgerConfig;
        this.amuletChoiceName = amuletChoiceName;
        this.httpClient = HttpClient.newHttpClient();
    }

    public boolean isSchemaEnabled() {
        return schemaConfig.isEnabled();
    }

    @WithSpan
    public Result<PayoutResponse, ApiError> createAmuletPayout(PayoutRequest request, String requestId) {
        return createAmuletViaHoldingChoice(request, requestId);
    }

    @WithSpan
    public Result<PayoutResponse, ApiError> createCbtcPayout(PayoutRequest request, String requestId) {
        Result<PayoutResponse, ApiError> viaFactory = createPayoutViaTransferFactory(request, InstrumentConfig.cbtc(), requestId);
        if (viaFactory.isOk()) {
            return viaFactory;
        }
        if (isRegistryUrlNotConfigured(viaFactory.getErrorUnsafe())) {
            return createPayout(request, InstrumentConfig.cbtc(), requestId);
        }
        return viaFactory;
    }

    @WithSpan
    public Result<TransferFactoryPlan, ApiError> prepareTransferFactory(
            String instrumentAdmin,
            String instrumentId,
            String holdingCid,
            String receiverParty,
            BigDecimal amount,
            Instant executeBefore,
            String memo,
            String requestId
    ) {
        if (!schemaConfig.isEnabled()) {
            return Result.err(new ApiError(
                    ErrorCode.PRECONDITION_FAILED,
                    "Template schema debug is disabled",
                    Map.of("flag", "feature.enable-template-schema-debug"),
                    false,
                    null,
                    null
            ));
        }
        if (holdingCid == null || holdingCid.isBlank()) {
            return Result.err(new ApiError(
                    ErrorCode.VALIDATION,
                    "holdingCid is required",
                    Map.of("field", "holdingCid"),
                    false,
                    null,
                    null
            ));
        }
        if (receiverParty == null || receiverParty.isBlank()) {
            return Result.err(new ApiError(
                    ErrorCode.VALIDATION,
                    "receiverParty is required",
                    Map.of("field", "receiverParty"),
                    false,
                    null,
                    null
            ));
        }
        if (instrumentAdmin == null || instrumentAdmin.isBlank() || instrumentId == null || instrumentId.isBlank()) {
            return Result.err(new ApiError(
                    ErrorCode.VALIDATION,
                    "instrument is required",
                    Map.of("field", "instrumentId"),
                    false,
                    null,
                    null
            ));
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.err(new ApiError(
                    ErrorCode.VALIDATION,
                    "amount must be positive",
                    Map.of("field", "amount"),
                    false,
                    null,
                    null
            ));
        }
        if (executeBefore == null) {
            return Result.err(new ApiError(
                    ErrorCode.VALIDATION,
                    "executeBefore is required",
                    Map.of("field", "executeBefore"),
                    false,
                    null,
                    null
            ));
        }

        String operator = authUtils.getAppProviderPartyId();
        String amountText = amount.setScale(SwapConstants.SCALE, RoundingMode.DOWN).toPlainString();

        TemplateSchemaService.DataTypeAst tfAst = schemaService.getDataTypeAst(
                TRANSFER_INSTRUCTION_PKG,
                TRANSFER_INSTRUCTION_MODULE,
                TRANSFER_FACTORY_ARG
        );
        ValueOuterClass.Record transferFactoryArg = buildRecordFromDataTypeAst(tfAst, new BuildContext(
                operator,
                receiverParty,
                instrumentAdmin,
                instrumentId,
                amountText,
                executeBefore,
                memo,
                holdingCid
        ));
        // Cure (iv.1): inject outbound payout memo under the helvetswap.app/swap-memo
        // meta key. Defensive: any failure swallows + logs; TI submission proceeds without memo.
        try {
            String unprefixedRequestId = (requestId != null && requestId.startsWith(SWAP_REQUEST_ID_PREFIX))
                    ? requestId.substring(SWAP_REQUEST_ID_PREFIX.length())
                    : requestId;
            Optional<String> memoJsonOpt = buildSwapMemoJson(memo, unprefixedRequestId);
            if (memoJsonOpt.isPresent()) {
                transferFactoryArg = injectSwapMemoIntoTransferFactoryArg(transferFactoryArg, memoJsonOpt.get());
            }
        } catch (Exception e) {
            LOG.warn("[PayoutService] Memo emission outer-catch swallowed: {}", e.getMessage());
        }
        Map<String, Object> payloadBody = Map.of("choiceArguments", recordToJson(transferFactoryArg));
        Result<String, ApiError> payloadJson = toJsonSafe(payloadBody);
        if (payloadJson.isErr()) {
            return Result.err(payloadJson.getErrorUnsafe());
        }

        String url = resolveTransferFactoryUrl(instrumentAdmin);
        LOG.info("[PayoutService] prepareTransferFactory url={} instrumentAdmin={} instrumentId={} holdingCid={} receiver={}",
                url, instrumentAdmin, instrumentId, holdingCid, receiverParty);
        if (url == null || url.isBlank()) {
            return Result.err(new ApiError(
                    ErrorCode.PRECONDITION_FAILED,
                    "Transfer factory registry URL not configured",
                    Map.of("instrumentAdmin", instrumentAdmin, "instrumentId", instrumentId),
                    false,
                    null,
                    null
            ));
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson.getValueUnsafe()));
        if (ledgerConfig.getRegistryAuthHeader() != null && !ledgerConfig.getRegistryAuthHeader().isBlank()
                && ledgerConfig.getRegistryAuthToken() != null && !ledgerConfig.getRegistryAuthToken().isBlank()) {
            req.header(ledgerConfig.getRegistryAuthHeader(), ledgerConfig.getRegistryAuthToken());
        }

        return await(
                httpClient.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()),
                ex -> internalError("Transfer factory registry call failed", ex, Map.of("url", url))
        ).flatMap(resp -> {
            LOG.info("[PayoutService] registry response status={} bodyLength={}", resp.statusCode(),
                    resp.body() != null ? resp.body().length() : 0);
            if (resp.statusCode() / 100 != 2) {
                LOG.warn("[PayoutService] registry HTTP error status={} body={}", resp.statusCode(),
                        resp.body() != null ? resp.body().substring(0, Math.min(resp.body().length(), 500)) : "null");
                return Result.err(mapRegistryHttpError(resp.statusCode(), resp.body(), url, null));
            }
            Result<TransferFactoryContext, ApiError> parsed = parseTransferFactoryContext(resp.body(), url, null);
            if (parsed.isErr()) {
                return Result.err(parsed.getErrorUnsafe());
            }
            TransferFactoryContext ctx = parsed.getValueUnsafe();
            String factoryCid = ctx.factoryCid;
            List<DisclosedContractDto> disclosedContracts = ctx.choiceContext != null ? ctx.choiceContext.disclosedContracts : null;
            LOG.info("[PayoutService] registry factoryCid={} disclosedCount={}",
                    factoryCid, disclosedContracts != null ? disclosedContracts.size() : 0);
            if (disclosedContracts != null) {
                disclosedContracts.forEach(dc ->
                        LOG.info("[PayoutService] disclosed contractId={} templateId={} synchronizerId={}",
                                dc.contractId, dc.templateId, dc.synchronizerId));
            }
            if (factoryCid == null || factoryCid.isBlank()) {
                return Result.err(new ApiError(
                        ErrorCode.PRECONDITION_FAILED,
                        "Registry response did not include factoryCid",
                        Map.of("url", url),
                        false,
                        null,
                        null
                ));
            }
            if (disclosedContracts == null || disclosedContracts.isEmpty()) {
                return Result.err(new ApiError(
                        ErrorCode.PRECONDITION_FAILED,
                        "Registry response did not include disclosedContracts",
                        Map.of("url", url),
                        false,
                        null,
                        null
                ));
            }

            List<CommandsOuterClass.DisclosedContract> disclosed = disclosedContracts.stream()
                    .map(this::toDisclosedContract)
                    .filter(dc -> dc != null)
                    .toList();
            if (disclosed.isEmpty()) {
                return Result.err(new ApiError(
                        ErrorCode.PRECONDITION_FAILED,
                        "Registry disclosures invalid",
                        Map.of("url", url),
                        false,
                        null,
                        null
                ));
            }

            ValueOuterClass.Record extraArgs = buildExtraArgs(ctx.choiceContext);
            String synchronizerId = disclosedContracts.stream()
                    .map(dc -> dc.synchronizerId)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElse(null);
            return Result.ok(new TransferFactoryPlan(factoryCid, extraArgs, disclosed, synchronizerId));
        });
    }

    private Result<PayoutResponse, ApiError> createAmuletViaHoldingChoice(PayoutRequest request, String requestId) {
        if (!schemaConfig.isEnabled()) {
            return Result.err(new ApiError(
                    ErrorCode.PRECONDITION_FAILED,
                    "Template schema debug is disabled",
                    Map.of("flag", "feature.enable-template-schema-debug"),
                    false,
                    null,
                    null
            ));
        }

        Result<ValidatedRequest, ApiError> validated = validateRequest(request);
        if (validated.isErr()) {
            return Result.err(validated.getErrorUnsafe());
        }
        ValidatedRequest vr = validated.getValueUnsafe();

        String operator = authUtils.getAppProviderPartyId();
        HoldingSelectRequest selectReq = new HoldingSelectRequest(
                operator,
                AMULET_ADMIN,
                AMULET_ID,
                vr.amount(),
                0,
                0
        );

        return await(
                holdingSelectorService.selectHoldingOnce(selectReq),
                ex -> internalError("Holding selection failed", ex, Map.of("instrumentId", AMULET_ID))
        ).flatMap(selection -> {
            if (!selection.found()) {
                return Result.err(new ApiError(
                        ErrorCode.NOT_FOUND,
                        "No suitable holding found",
                        Map.of(
                                "instrumentAdmin", AMULET_ADMIN,
                                "instrumentId", AMULET_ID,
                                "amount", vr.amountText()
                        ),
                        false,
                        null,
                        null
                ));
            }

            String holdingCid = selection.holdingCid();
            return await(
                    ledgerApi.getActiveContractsRawForParty(operator),
                    ex -> internalError("Failed to load active contracts", ex, Map.of("party", operator))
            ).flatMap(acs -> {
                LedgerApi.RawActiveContract rac = acs.stream()
                        .filter(c -> holdingCid.equals(c.contractId()))
                        .findFirst()
                        .orElse(null);
                if (rac == null) {
                    return Result.err(new ApiError(
                            ErrorCode.NOT_FOUND,
                            "Holding contract not visible in ACS",
                            Map.of("holdingCid", holdingCid),
                            false,
                            null,
                            null
                    ));
                }

                ValueOuterClass.Identifier templateId = rac.templateId();
                TemplateSchemaService.TemplateChoicesAst choicesAst =
                        schemaService.getTemplateChoicesAst(templateId.getPackageId(), templateId.getModuleName(), templateId.getEntityName());
                Result<TemplateSchemaService.ChoiceAst, ApiError> choice = selectAmuletChoice(choicesAst);
                if (choice.isErr()) {
                    return createViaTransferFactory(vr, holdingCid, operator, InstrumentConfig.amulet(), requestId, choice.getErrorUnsafe());
                }
                TemplateSchemaService.ChoiceAst chosen = choice.getValueUnsafe();
                Result<ValueOuterClass.Record, ApiError> choiceArgsResult = buildChoiceArgs(choicesAst, chosen, new BuildContext(
                        operator,
                        vr.receiverParty(),
                        AMULET_ADMIN,
                        AMULET_ID,
                        vr.amountText(),
                        vr.executeBefore(),
                        vr.memo(),
                        holdingCid
                ));
                if (choiceArgsResult.isErr()) {
                    return Result.err(choiceArgsResult.getErrorUnsafe());
                }
                ValueOuterClass.Record choiceArgs = choiceArgsResult.getValueUnsafe();

                return await(
                        ledgerApi.exerciseRawWithLabel(
                                "Payout",
                                templateId,
                                holdingCid,
                                chosen.name(),
                                choiceArgs,
                                List.of(operator),
                                List.of(operator),
                                List.of(),
                                null
                        ),
                        ex -> mapLedgerError("Ledger rejected exercise", ex, Map.of(
                                "template", templateId.getModuleName() + ":" + templateId.getEntityName(),
                                "choice", chosen.name()
                        ))
                ).map(resp -> {
                    String cid = firstCreated(resp.getTransaction()).orElse(null);
                    LOG.info("[PayoutService] [requestId={}] Amulet payout via holding choice={} cid={} updateId={}",
                            requestId, chosen.name(), cid, resp.getTransaction().getUpdateId());
                    return new PayoutResponse(
                            cid,
                            resp.getTransaction().getUpdateId(),
                            vr.executeBefore().toString(),
                            operator,
                            vr.receiverParty(),
                            AMULET_ADMIN,
                            AMULET_ID,
                            vr.amountText(),
                            vr.memo(),
                            null,
                            null
                    );
                });
            });
        });
    }

    private Result<PayoutResponse, ApiError> createPayout(PayoutRequest request, InstrumentConfig cfg, String requestId) {
        if (!schemaConfig.isEnabled()) {
            return Result.err(new ApiError(
                    ErrorCode.PRECONDITION_FAILED,
                    "Template schema debug is disabled",
                    Map.of("flag", "feature.enable-template-schema-debug"),
                    false,
                    null,
                    null
            ));
        }

        Result<ValidatedRequest, ApiError> validated = validateRequest(request);
        if (validated.isErr()) {
            return Result.err(validated.getErrorUnsafe());
        }
        ValidatedRequest vr = validated.getValueUnsafe();

        String operator = authUtils.getAppProviderPartyId();
        HoldingSelectRequest selectReq = new HoldingSelectRequest(
                operator,
                cfg.admin,
                cfg.id,
                vr.amount(),
                0,
                0
        );

        return await(
                holdingSelectorService.selectHoldingOnce(selectReq),
                ex -> internalError("Holding selection failed", ex, Map.of("instrumentId", cfg.id))
        ).flatMap(selection -> {
            if (!selection.found()) {
                return Result.err(new ApiError(
                        ErrorCode.NOT_FOUND,
                        "No suitable holding found",
                        Map.of(
                                "instrumentAdmin", cfg.admin,
                                "instrumentId", cfg.id,
                                "amount", vr.amountText()
                        ),
                        false,
                        null,
                        null
                ));
            }

            String holdingCid = selection.holdingCid();
            // Dual-use split: cfg.packageId is a #package-name (correct for the command templateId at
            // setPackageId below), but getTemplateAst → GetPackage needs a concrete package-id → resolve it.
            String schemaPackageId = ledgerApi.resolvePackageId(cfg.packageId);
            TemplateSchemaService.TemplateAst ast = schemaService.getTemplateAst(schemaPackageId, cfg.moduleName, cfg.entityName);
            ValueOuterClass.Record args = buildCreateArgs(ast, new BuildContext(
                    operator,
                    vr.receiverParty(),
                    cfg.admin,
                    cfg.id,
                    vr.amountText(),
                    vr.executeBefore(),
                    vr.memo(),
                    holdingCid
            ));

            ValueOuterClass.Identifier tid = ValueOuterClass.Identifier.newBuilder()
                    .setPackageId(cfg.packageId)
                    .setModuleName(cfg.moduleName)
                    .setEntityName(cfg.entityName)
                    .build();

            return await(
                    ledgerApi.createRaw(tid, args, List.of(operator), List.of(operator)),
                    ex -> mapLedgerError("Ledger rejected create", ex, Map.of("template", cfg.moduleName + ":" + cfg.entityName))
            ).map(resp -> {
                String cid = firstCreated(resp.getTransaction()).orElse(null);
                LOG.info("[PayoutService] [requestId={}] Created outbound {} payout cid={} updateId={}",
                        requestId, cfg.id, cid, resp.getTransaction().getUpdateId());
                return new PayoutResponse(
                        cid,
                        resp.getTransaction().getUpdateId(),
                        vr.executeBefore().toString(),
                        operator,
                        vr.receiverParty(),
                        cfg.admin,
                        cfg.id,
                        vr.amountText(),
                        vr.memo(),
                        null,
                        null
                );
            });
        });
    }

    private Result<PayoutResponse, ApiError> createViaTransferFactory(
            ValidatedRequest vr,
            String holdingCid,
            String operator,
            InstrumentConfig cfg,
            String requestId,
            ApiError holdingChoiceError
    ) {
        String url = resolveTransferFactoryUrl(cfg.admin);
        if (url == null || url.isBlank()) {
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("config", "ledger.registry.transfer-factory-url");
            details.put("instrumentAdmin", cfg.admin);
            details.put("instrumentId", cfg.id);
            details.put("reason", "REGISTRY_URL_NOT_CONFIGURED");
            details.put("holdingChoiceError", holdingChoiceError != null ? holdingChoiceError.message : null);
            return Result.err(new ApiError(
                    ErrorCode.PRECONDITION_FAILED,
                    "Transfer factory registry URL not configured",
                    details,
                    false,
                    null,
                    null
            ));
        }

        TemplateSchemaService.DataTypeAst tfAst = schemaService.getDataTypeAst(
                TRANSFER_INSTRUCTION_PKG,
                TRANSFER_INSTRUCTION_MODULE,
                TRANSFER_FACTORY_ARG
        );
        ValueOuterClass.Record transferFactoryArg = buildRecordFromDataTypeAst(tfAst, new BuildContext(
                operator,
                vr.receiverParty(),
                cfg.admin,
                cfg.id,
                vr.amountText(),
                vr.executeBefore(),
                vr.memo(),
                holdingCid
        ));
        Map<String, Object> payloadBody = Map.of("choiceArguments", recordToJson(transferFactoryArg));
        Result<String, ApiError> payloadJson = toJsonSafe(payloadBody);
        if (payloadJson.isErr()) {
            return Result.err(payloadJson.getErrorUnsafe());
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson.getValueUnsafe()));
        if (ledgerConfig.getRegistryAuthHeader() != null && !ledgerConfig.getRegistryAuthHeader().isBlank()
                && ledgerConfig.getRegistryAuthToken() != null && !ledgerConfig.getRegistryAuthToken().isBlank()) {
            req.header(ledgerConfig.getRegistryAuthHeader(), ledgerConfig.getRegistryAuthToken());
        }

        return await(
                httpClient.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()),
                ex -> internalError("Transfer factory registry call failed", ex, Map.of("url", url))
        ).flatMap(resp -> {
            if (resp.statusCode() / 100 != 2) {
                return Result.err(mapRegistryHttpError(resp.statusCode(), resp.body(), url, holdingChoiceError));
            }
            Result<TransferFactoryContext, ApiError> parsed = parseTransferFactoryContext(resp.body(), url, holdingChoiceError);
            if (parsed.isErr()) {
                return Result.err(parsed.getErrorUnsafe());
            }
            TransferFactoryContext ctx = parsed.getValueUnsafe();

            String factoryCid = ctx.factoryCid;
            List<DisclosedContractDto> disclosedContracts = ctx.choiceContext != null ? ctx.choiceContext.disclosedContracts : null;
            if (factoryCid == null && disclosedContracts != null) {
                factoryCid = disclosedContracts.stream()
                        .filter(dc -> dc.templateId != null && dc.templateId.contains("Splice.AmuletRules:AmuletRules"))
                        .map(dc -> dc.contractId)
                        .findFirst()
                        .orElse(null);
            }
            if (factoryCid == null) {
                Map<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("url", url);
                details.put("bodySnippet", snippet(resp.body()));
                details.put("holdingChoiceError", holdingChoiceError != null ? holdingChoiceError.message : null);
                return Result.err(new ApiError(
                        ErrorCode.PRECONDITION_FAILED,
                        "Registry response did not include factoryCid",
                        details,
                        false,
                        null,
                        null
                ));
            }

            if (disclosedContracts == null || disclosedContracts.isEmpty()) {
                return Result.err(new ApiError(
                        ErrorCode.PRECONDITION_FAILED,
                        "Registry response did not include disclosedContracts",
                        Map.of("url", url),
                        false,
                        null,
                        null
                ));
            }

            final String finalFactoryCid = factoryCid;
            ValueOuterClass.Record extraArgs = buildExtraArgs(ctx.choiceContext);
            ValueOuterClass.Record transferFactoryArgWithContext = replaceRecordField(
                    transferFactoryArg,
                    "extraArgs",
                    ValueOuterClass.Value.newBuilder().setRecord(extraArgs).build()
            );

            List<CommandsOuterClass.DisclosedContract> disclosed = disclosedContracts.stream()
                    .map(this::toDisclosedContract)
                    .filter(dc -> dc != null)
                    .toList();
            if (disclosed.isEmpty()) {
                return Result.err(new ApiError(
                        ErrorCode.PRECONDITION_FAILED,
                        "Registry disclosures invalid",
                        Map.of("url", url),
                        false,
                        null,
                        null
                ));
            }

            ValueOuterClass.Identifier interfaceId = ValueOuterClass.Identifier.newBuilder()
                    .setPackageId(TRANSFER_FACTORY_INTERFACE_PKG)
                    .setModuleName(TRANSFER_INSTRUCTION_MODULE)
                    .setEntityName(TRANSFER_FACTORY_ENTITY)
                    .build();

            return await(
                    ledgerApi.exerciseRawWithLabel(
                            "Payout",
                            interfaceId,
                            finalFactoryCid,
                            TRANSFER_FACTORY_CHOICE,
                            transferFactoryArgWithContext,
                            List.of(operator),
                            List.of(operator),
                            disclosed,
                            null
                    ),
                    ex -> mapLedgerError("Ledger rejected transfer factory exercise", ex, Map.of("choice", TRANSFER_FACTORY_CHOICE))
            ).map(resp2 -> {
                String cid = firstCreated(resp2.getTransaction()).orElse(null);
                LOG.info("[PayoutService] [requestId={}] Payout via transfer factory instrument={} cid={} updateId={}",
                        requestId, cfg.id, cid, resp2.getTransaction().getUpdateId());
                return new PayoutResponse(
                        cid,
                        resp2.getTransaction().getUpdateId(),
                        vr.executeBefore().toString(),
                        operator,
                        vr.receiverParty(),
                        cfg.admin,
                        cfg.id,
                        vr.amountText(),
                        vr.memo(),
                        finalFactoryCid,
                        disclosed.size()
                );
            });
        });
    }

    private Result<PayoutResponse, ApiError> createPayoutViaTransferFactory(
            PayoutRequest request,
            InstrumentConfig cfg,
            String requestId
    ) {
        if (!schemaConfig.isEnabled()) {
            return Result.err(new ApiError(
                    ErrorCode.PRECONDITION_FAILED,
                    "Template schema debug is disabled",
                    Map.of("flag", "feature.enable-template-schema-debug"),
                    false,
                    null,
                    null
            ));
        }

        Result<ValidatedRequest, ApiError> validated = validateRequest(request);
        if (validated.isErr()) {
            return Result.err(validated.getErrorUnsafe());
        }
        ValidatedRequest vr = validated.getValueUnsafe();

        String operator = authUtils.getAppProviderPartyId();
        HoldingSelectRequest selectReq = new HoldingSelectRequest(
                operator,
                cfg.admin,
                cfg.id,
                vr.amount(),
                0,
                0
        );
        return await(
                holdingSelectorService.selectHoldingOnce(selectReq),
                ex -> internalError("Holding selection failed", ex, Map.of("instrumentId", cfg.id))
        ).flatMap(selection -> {
            if (!selection.found()) {
                return Result.err(new ApiError(
                        ErrorCode.NOT_FOUND,
                        "No suitable holding found",
                        Map.of(
                                "instrumentAdmin", cfg.admin,
                                "instrumentId", cfg.id,
                                "amount", vr.amountText()
                        ),
                        false,
                        null,
                        null
                ));
            }
            return createViaTransferFactory(vr, selection.holdingCid(), operator, cfg, requestId, null);
        });
    }

    private Result<ValidatedRequest, ApiError> validateRequest(PayoutRequest request) {
        if (request == null) {
            return Result.err(validationError("request body is required", Map.of("field", "request")));
        }
        if (request.receiverParty == null || request.receiverParty.isBlank()) {
            return Result.err(validationError("receiverParty is required", Map.of("field", "receiverParty")));
        }
        if (request.amount == null || request.amount.isBlank()) {
            return Result.err(validationError("amount is required", Map.of("field", "amount")));
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(request.amount);
        } catch (NumberFormatException e) {
            return Result.err(validationError("amount must be a valid decimal", Map.of("field", "amount")));
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.err(validationError("amount must be positive", Map.of("field", "amount")));
        }

        long executeBeforeSeconds = request.executeBeforeSeconds != null ? request.executeBeforeSeconds : 7200L;
        if (executeBeforeSeconds <= 0) {
            return Result.err(validationError("executeBeforeSeconds must be positive", Map.of("field", "executeBeforeSeconds")));
        }

        Instant executeBefore = Instant.now().plusSeconds(executeBeforeSeconds);
        return Result.ok(new ValidatedRequest(
                request.receiverParty,
                amount,
                amount.toPlainString(),
                executeBefore,
                request.memo
        ));
    }

    private <T> Result<T, ApiError> await(CompletableFuture<T> future, java.util.function.Function<Throwable, ApiError> errorMapper) {
        return future.handle((value, ex) -> {
            if (ex != null) {
                return Result.<T, ApiError>err(errorMapper.apply(ex));
            }
            return Result.<T, ApiError>ok(value);
        }).join();
    }

    private ApiError validationError(String message, Map<String, Object> details) {
        return new ApiError(ErrorCode.VALIDATION, message, details, false, null, null);
    }

    private ApiError internalError(String message, Throwable ex, Map<String, Object> details) {
        Throwable root = unwrap(ex);
        Map<String, Object> merged = details == null ? Map.of() : details;
        return new ApiError(
                ErrorCode.INTERNAL,
                message + ": " + (root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName()),
                merged,
                false,
                null,
                null
        );
    }

    private ApiError mapLedgerError(String message, Throwable ex, Map<String, Object> details) {
        Throwable root = unwrap(ex);
        Status status = Status.fromThrowable(root);
        if (status != null && status.getCode() != Status.Code.UNKNOWN) {
            boolean timeout = status.getCode() == Status.Code.DEADLINE_EXCEEDED || status.getCode() == Status.Code.UNAVAILABLE;
            ErrorCode code = timeout ? ErrorCode.TIMEOUT : ErrorCode.LEDGER_REJECTED;
            return new ApiError(
                    code,
                    message,
                    details,
                    timeout,
                    status.getCode().name(),
                    status.getDescription()
            );
        }
        return internalError(message, root, details);
    }

    private boolean isRegistryUrlNotConfigured(ApiError error) {
        if (error == null || error.details == null) {
            return false;
        }
        Object reason = error.details.get("reason");
        return "REGISTRY_URL_NOT_CONFIGURED".equals(reason);
    }

    private Throwable unwrap(Throwable ex) {
        Throwable t = ex;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private Optional<String> firstCreated(TransactionOuterClass.Transaction txn) {
        for (var ev : txn.getEventsList()) {
            if (ev.hasCreated()) return Optional.of(ev.getCreated().getContractId());
        }
        return Optional.empty();
    }

    /**
     * Build a Value.Record respecting field order using the decoded template AST.
     */
    private ValueOuterClass.Record buildCreateArgs(TemplateSchemaService.TemplateAst ast, BuildContext ctx) {
        if (ast.isLf2()) {
            return buildCreateArgsLf2(ast.lf2Package(), ast.lf2Fields(), ctx);
        }
        return buildCreateArgsLf1(ast.lf1Package(), ast.lf1Fields(), ctx);
    }

    private ValueOuterClass.Record buildCreateArgsLf1(
            DamlLf1.Package pkg,
            List<DamlLf1.FieldWithType> fields,
            BuildContext ctx
    ) {
        return buildRecordFromFieldsLf1(pkg, fields, ctx);
    }

    private ValueOuterClass.Record buildCreateArgsLf2(
            DamlLf2.Package pkg,
            List<DamlLf2.FieldWithType> fields,
            BuildContext ctx
    ) {
        return buildRecordFromFieldsLf2(pkg, fields, ctx);
    }

    private ValueOuterClass.Record buildRecordFromDataTypeAst(TemplateSchemaService.DataTypeAst ast, BuildContext ctx) {
        if (ast.isLf2()) {
            return buildRecordFromFieldsLf2(ast.lf2Package(), ast.lf2Fields(), ctx);
        }
        return buildRecordFromFieldsLf1(ast.lf1Package(), ast.lf1Fields(), ctx);
    }

    private ValueOuterClass.Record buildRecordFromFieldsLf1(
            DamlLf1.Package pkg,
            List<DamlLf1.FieldWithType> fields,
            BuildContext ctx
    ) {
        List<ValueOuterClass.RecordField> out = new ArrayList<>();
        for (DamlLf1.FieldWithType f : fields) {
            String name = resolveFieldNameLf1(pkg, f);
            ValueOuterClass.Value v = buildValueLf1(pkg, f.getType(), name, ctx);
            out.add(ValueOuterClass.RecordField.newBuilder().setLabel(name).setValue(v).build());
        }
        return ValueOuterClass.Record.newBuilder().addAllFields(out).build();
    }

    private ValueOuterClass.Record buildRecordFromFieldsLf2(
            DamlLf2.Package pkg,
            List<DamlLf2.FieldWithType> fields,
            BuildContext ctx
    ) {
        List<ValueOuterClass.RecordField> out = new ArrayList<>();
        for (DamlLf2.FieldWithType f : fields) {
            String name = resolveFieldNameLf2(pkg, f);
            ValueOuterClass.Value v = buildValueLf2(pkg, f.getType(), name, ctx);
            out.add(ValueOuterClass.RecordField.newBuilder().setLabel(name).setValue(v).build());
        }
        return ValueOuterClass.Record.newBuilder().addAllFields(out).build();
    }

    private ValueOuterClass.Value buildValueLf1(DamlLf1.Package pkg, DamlLf1.Type ty, String fieldName, BuildContext ctx) {
        ty = resolveInternedLf1(pkg, ty);
        return switch (ty.getSumCase()) {
            case PRIM -> buildPrimLf1(pkg, ty.getPrim(), fieldName, ctx);
            case VAR -> throw new IllegalArgumentException("Type variables not supported for field " + fieldName);
            case CON -> buildTypeConLf1(pkg, ty.getCon(), fieldName, ctx);
            case STRUCT -> ValueOuterClass.Value.newBuilder().setRecord(ValueOuterClass.Record.newBuilder().build()).build();
            case NAT -> ValueOuterClass.Value.newBuilder().setNumeric("0").build();
            case FORALL, SYN -> throw new IllegalArgumentException("Unsupported type for field " + fieldName);
            case INTERNED -> throw new IllegalStateException("Interned type should have been resolved");
            case SUM_NOT_SET -> throw new IllegalStateException("Unknown type for field " + fieldName);
        };
    }

    private ValueOuterClass.Value buildPrimLf1(DamlLf1.Package pkg, DamlLf1.Type.Prim prim, String fieldName, BuildContext ctx) {
        return switch (prim.getPrim()) {
            case PARTY -> ValueOuterClass.Value.newBuilder().setParty(resolveParty(fieldName, ctx)).build();
            case TEXT -> ValueOuterClass.Value.newBuilder().setText(resolveText(fieldName, ctx)).build();
            case INT64 -> ValueOuterClass.Value.newBuilder().setInt64(0L).build();
            case NUMERIC, DECIMAL, BIGNUMERIC -> ValueOuterClass.Value.newBuilder()
                    .setNumeric(resolveAmount(fieldName, ctx)).build();
            case TIMESTAMP -> ValueOuterClass.Value.newBuilder()
                    .setTimestamp(resolveTimestampMicros(fieldName, ctx)).build();
            case BOOL -> ValueOuterClass.Value.newBuilder().setBool(false).build();
            case CONTRACT_ID -> ValueOuterClass.Value.newBuilder().setContractId(ctx.holdingCid()).build();
            case OPTIONAL -> {
                if (prim.getArgsCount() == 0) {
                    yield ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().build()).build();
                }
                String memo = ctx.memo();
                if (memo == null || memo.isBlank()) {
                    yield ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().build()).build();
                }
                ValueOuterClass.Value inner = buildValueLf1(pkg, prim.getArgs(0), fieldName + ".value", ctx);
                yield ValueOuterClass.Value.newBuilder()
                        .setOptional(ValueOuterClass.Optional.newBuilder().setValue(inner).build())
                        .build();
            }
            case LIST -> {
                ValueOuterClass.List.Builder lb = ValueOuterClass.List.newBuilder();
                if (isHoldingCidList(fieldName) && ctx.holdingCid() != null) {
                    lb.addElements(ValueOuterClass.Value.newBuilder().setContractId(ctx.holdingCid()).build());
                }
                yield ValueOuterClass.Value.newBuilder().setList(lb).build();
            }
            case TEXTMAP -> ValueOuterClass.Value.newBuilder()
                    .setTextMap(ValueOuterClass.TextMap.newBuilder())
                    .build();
            case GENMAP -> ValueOuterClass.Value.newBuilder()
                    .setGenMap(ValueOuterClass.GenMap.newBuilder())
                    .build();
            default -> throw new IllegalArgumentException("Unsupported primitive " + prim.getPrim() + " for field " + fieldName);
        };
    }

    private ValueOuterClass.Value buildTypeConLf1(DamlLf1.Package pkg, DamlLf1.Type.Con con, String fieldName, BuildContext ctx) {
        TemplateSchemaService.ResolvedDataTypeLf1 resolved = schemaService.resolveDataTypeLf1(pkg, con.getTycon());
        DamlLf1.Package targetPkg = resolved.pkg();
        DamlLf1.DefDataType dt = resolved.def();
        if (dt.hasRecord()) {
            List<ValueOuterClass.RecordField> fields = new ArrayList<>();
            int idx = 0;
            for (var f : dt.getRecord().getFieldsList()) {
                String name = resolveFieldNameLf1(targetPkg, f);
                ValueOuterClass.Value v = buildValueLf1(targetPkg, resolveInternedLf1(targetPkg, f.getType()), name, ctx);
                fields.add(ValueOuterClass.RecordField.newBuilder().setLabel(name).setValue(v).build());
                idx++;
            }
            return ValueOuterClass.Value.newBuilder()
                    .setRecord(ValueOuterClass.Record.newBuilder().addAllFields(fields).build())
                    .build();
        } else if (dt.hasEnum()) {
            var enumCons = dt.getEnum();
            String ctor = "<enum>";
            if (enumCons.getConstructorsStrCount() > 0) {
                ctor = enumCons.getConstructorsStr(0);
            } else if (enumCons.getConstructorsInternedStrCount() > 0) {
                ctor = targetPkg.getInternedStrings(enumCons.getConstructorsInternedStr(0));
            } else if (!enumCons.getConstructorsStrList().isEmpty()) {
                ctor = enumCons.getConstructorsStrList().get(0);
            }
            return ValueOuterClass.Value.newBuilder()
                    .setEnum(ValueOuterClass.Enum.newBuilder()
                            .setConstructor(ctor)
                            .build())
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported data type for field " + fieldName);
        }
    }

    private DamlLf1.Type resolveInternedLf1(DamlLf1.Package pkg, DamlLf1.Type ty) {
        if (ty.getSumCase() == DamlLf1.Type.SumCase.INTERNED) {
            return pkg.getInternedTypes(ty.getInterned());
        }
        return ty;
    }

    private ValueOuterClass.Value buildValueLf2(DamlLf2.Package pkg, DamlLf2.Type ty, String fieldName, BuildContext ctx) {
        ty = resolveInternedLf2(pkg, ty);
        return switch (ty.getSumCase()) {
            case BUILTIN -> buildBuiltinLf2(pkg, ty.getBuiltin(), fieldName, ctx);
            case VAR -> throw new IllegalArgumentException("Type variables not supported for field " + fieldName);
            case CON -> buildTypeConLf2(pkg, ty.getCon(), fieldName, ctx);
            case STRUCT -> ValueOuterClass.Value.newBuilder().setRecord(ValueOuterClass.Record.newBuilder().build()).build();
            case NAT -> ValueOuterClass.Value.newBuilder().setNumeric("0").build();
            case FORALL, SYN -> throw new IllegalArgumentException("Unsupported type for field " + fieldName);
            case INTERNED -> throw new IllegalStateException("Interned type should have been resolved");
            case SUM_NOT_SET -> throw new IllegalStateException("Unknown type for field " + fieldName);
        };
    }

    private ValueOuterClass.Value buildBuiltinLf2(DamlLf2.Package pkg, DamlLf2.Type.Builtin builtin, String fieldName, BuildContext ctx) {
        return switch (builtin.getBuiltin()) {
            case PARTY -> ValueOuterClass.Value.newBuilder().setParty(resolveParty(fieldName, ctx)).build();
            case TEXT -> ValueOuterClass.Value.newBuilder().setText(resolveText(fieldName, ctx)).build();
            case INT64 -> ValueOuterClass.Value.newBuilder().setInt64(0L).build();
            case NUMERIC, BIGNUMERIC -> ValueOuterClass.Value.newBuilder()
                    .setNumeric(resolveAmount(fieldName, ctx)).build();
            case TIMESTAMP -> ValueOuterClass.Value.newBuilder()
                    .setTimestamp(resolveTimestampMicros(fieldName, ctx)).build();
            case BOOL -> ValueOuterClass.Value.newBuilder().setBool(false).build();
            case CONTRACT_ID -> ValueOuterClass.Value.newBuilder().setContractId(ctx.holdingCid()).build();
            case OPTIONAL -> {
                if (builtin.getArgsCount() == 0) {
                    yield ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().build()).build();
                }
                String memo = ctx.memo();
                if (memo == null || memo.isBlank()) {
                    yield ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().build()).build();
                }
                ValueOuterClass.Value inner = buildValueLf2(pkg, builtin.getArgs(0), fieldName + ".value", ctx);
                yield ValueOuterClass.Value.newBuilder()
                        .setOptional(ValueOuterClass.Optional.newBuilder().setValue(inner).build())
                        .build();
            }
            case LIST -> {
                ValueOuterClass.List.Builder lb = ValueOuterClass.List.newBuilder();
                if (isHoldingCidList(fieldName) && ctx.holdingCid() != null) {
                    lb.addElements(ValueOuterClass.Value.newBuilder().setContractId(ctx.holdingCid()).build());
                }
                yield ValueOuterClass.Value.newBuilder().setList(lb).build();
            }
            case TEXTMAP -> ValueOuterClass.Value.newBuilder().setTextMap(ValueOuterClass.TextMap.newBuilder()).build();
            case GENMAP -> ValueOuterClass.Value.newBuilder().setGenMap(ValueOuterClass.GenMap.newBuilder()).build();
            case UNIT -> ValueOuterClass.Value.newBuilder().setUnit(Empty.getDefaultInstance()).build();
            case UNRECOGNIZED -> {
                if (builtin.getBuiltinValue() == 19 && builtin.getArgsCount() == 1) {
                    yield ValueOuterClass.Value.newBuilder().setTextMap(ValueOuterClass.TextMap.newBuilder()).build();
                }
                throw new IllegalArgumentException("Unsupported builtin " + builtin.getBuiltinValue() + " for field " + fieldName);
            }
            default -> throw new IllegalArgumentException("Unsupported builtin " + builtin.getBuiltin() + " for field " + fieldName);
        };
    }

    private ValueOuterClass.Value buildTypeConLf2(DamlLf2.Package pkg, DamlLf2.Type.Con con, String fieldName, BuildContext ctx) {
        String modName = moduleNameLf2(pkg, con.getTycon());
        String dataName = typeNameLf2(pkg, con.getTycon());
        String promotedName = promotedNameLf2(modName, dataName);
        if (promotedName != null) {
            return buildPromotedLf2(promotedName, fieldName, ctx);
        }

        TemplateSchemaService.ResolvedDataTypeLf2 resolved = schemaService.resolveDataTypeLf2(pkg, con.getTycon());
        DamlLf2.Package targetPkg = resolved.pkg();
        DamlLf2.DefDataType dt = resolved.def();
        if (dt.hasRecord()) {
            List<ValueOuterClass.RecordField> fields = new ArrayList<>();
            for (var f : dt.getRecord().getFieldsList()) {
                String name = resolveFieldNameLf2(targetPkg, f);
                ValueOuterClass.Value v = buildValueLf2(targetPkg, resolveInternedLf2(targetPkg, f.getType()), name, ctx);
                fields.add(ValueOuterClass.RecordField.newBuilder().setLabel(name).setValue(v).build());
            }
            return ValueOuterClass.Value.newBuilder()
                    .setRecord(ValueOuterClass.Record.newBuilder().addAllFields(fields).build())
                    .build();
        } else if (dt.hasEnum()) {
            var enumCons = dt.getEnum();
            String ctor = "<enum>";
            if (enumCons.getConstructorsInternedStrCount() > 0) {
                ctor = targetPkg.getInternedStrings(enumCons.getConstructorsInternedStr(0));
            }
            return ValueOuterClass.Value.newBuilder()
                    .setEnum(ValueOuterClass.Enum.newBuilder().setConstructor(ctor).build())
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported data type for field " + fieldName);
        }
    }

    private DamlLf2.Type resolveInternedLf2(DamlLf2.Package pkg, DamlLf2.Type ty) {
        if (ty.getSumCase() == DamlLf2.Type.SumCase.INTERNED) {
            return pkg.getInternedTypes(ty.getInterned());
        }
        return ty;
    }

    private ValueOuterClass.Value buildPromotedLf2(String promotedName, String fieldName, BuildContext ctx) {
        return switch (promotedName) {
            case "PromotedText" -> ValueOuterClass.Value.newBuilder().setText(resolveText(fieldName, ctx)).build();
            case "PromotedParty" -> ValueOuterClass.Value.newBuilder().setParty(resolveParty(fieldName, ctx)).build();
            case "PromotedNumeric", "PromotedDecimal", "PromotedBigNumeric" ->
                    ValueOuterClass.Value.newBuilder().setNumeric(resolveAmount(fieldName, ctx)).build();
            case "PromotedInt64" -> ValueOuterClass.Value.newBuilder().setInt64(0L).build();
            case "PromotedTimestamp" -> ValueOuterClass.Value.newBuilder()
                    .setTimestamp(ctx.executeBefore().toEpochMilli() * 1000L).build();
            case "PromotedBool" -> ValueOuterClass.Value.newBuilder().setBool(false).build();
            case "PromotedContractId" -> ValueOuterClass.Value.newBuilder().setContractId(ctx.holdingCid()).build();
            case "PromotedUnit" -> ValueOuterClass.Value.newBuilder().setUnit(Empty.getDefaultInstance()).build();
            default -> throw new IllegalArgumentException("Unsupported promoted type: " + promotedName);
        };
    }

    private String moduleNameLf2(DamlLf2.Package pkg, DamlLf2.TypeConName name) {
        if (!name.hasModule()) {
            return "<mod>";
        }
        return dottedNameFromInternedLf2(pkg, name.getModule().getModuleNameInternedDname());
    }

    private String typeNameLf2(DamlLf2.Package pkg, DamlLf2.TypeConName name) {
        return dottedNameFromInternedLf2(pkg, name.getNameInternedDname());
    }

    private String promotedNameLf2(String modName, String dataName) {
        if (modName != null && modName.startsWith("DA.Internal.Promoted")) {
            return modName.substring("DA.Internal.".length());
        }
        if ("DA.Internal".equals(modName) && dataName.startsWith("Promoted")) {
            return dataName;
        }
        String full = modName + "." + dataName;
        if (full.startsWith("DA.Internal.Promoted")) {
            return full.substring("DA.Internal.".length());
        }
        return null;
    }

    private String dottedNameFromInternedLf2(DamlLf2.Package pkg, int internedIndex) {
        if (internedIndex < 0 || internedIndex >= pkg.getInternedDottedNamesCount()) {
            return "<bad-index>";
        }
        var interned = pkg.getInternedDottedNames(internedIndex);
        List<String> segments = new ArrayList<>();
        for (int idx : interned.getSegmentsInternedStrList()) {
            segments.add(pkg.getInternedStrings(idx));
        }
        return String.join(".", segments);
    }

    private String resolveParty(String fieldName, BuildContext ctx) {
        String lower = fieldName.toLowerCase();
        if (lower.contains("receiver")) return ctx.receiver();
        if (lower.contains("sender") || lower.contains("provider") || lower.contains("owner")) return ctx.sender();
        if (lower.contains("admin")) return ctx.instrumentAdmin();
        return ctx.sender();
    }

    private long resolveTimestampMicros(String fieldName, BuildContext ctx) {
        String lower = fieldName.toLowerCase();
        Instant ts = lower.contains("execute") ? ctx.executeBefore() : Instant.now();
        return ts.toEpochMilli() * 1000L;
    }

    private String resolveText(String fieldName, BuildContext ctx) {
        String lower = fieldName.toLowerCase();
        if (lower.equals("id") || lower.contains("instrument")) return ctx.instrumentId();
        if (lower.contains("memo") && ctx.memo() != null) return ctx.memo();
        if (lower.contains("receiver")) return ctx.receiver();
        if (lower.contains("admin")) return ctx.instrumentAdmin();
        return "";
    }

    private String resolveAmount(String fieldName, BuildContext ctx) {
        if (fieldName.toLowerCase().contains("amount")) {
            return ctx.amount();
        }
        return "0";
    }

    private String resolveFieldNameLf1(DamlLf1.Package pkg, DamlLf1.FieldWithType f) {
        return switch (f.getFieldCase()) {
            case FIELD_STR -> f.getFieldStr();
            case FIELD_INTERNED_STR -> pkg.getInternedStrings(f.getFieldInternedStr());
            case FIELD_NOT_SET -> "";
        };
    }

    private String resolveFieldNameLf2(DamlLf2.Package pkg, DamlLf2.FieldWithType f) {
        return pkg.getInternedStrings(f.getFieldInternedStr());
    }

    private Result<TemplateSchemaService.ChoiceAst, ApiError> selectAmuletChoice(TemplateSchemaService.TemplateChoicesAst choicesAst) {
        List<String> names = choicesAst.choices().stream().map(TemplateSchemaService.ChoiceAst::name).toList();
        if (amuletChoiceName != null && !amuletChoiceName.isBlank()) {
            return choicesAst.choices().stream()
                    .filter(c -> amuletChoiceName.equals(c.name()))
                    .findFirst()
                    .map(Result::<TemplateSchemaService.ChoiceAst, ApiError>ok)
                    .orElseGet(() -> Result.err(new ApiError(
                            ErrorCode.PRECONDITION_FAILED,
                            "Configured amulet payout choice not found on template",
                            Map.of("choice", amuletChoiceName, "availableChoices", names),
                            false,
                            null,
                            null
                    )));
        }

        List<TemplateSchemaService.ChoiceAst> candidates = choicesAst.choices().stream()
                .filter(c -> {
                    String lower = c.name().toLowerCase();
                    return lower.contains("transfer") || lower.contains("payout");
                })
                .toList();
        if (candidates.size() == 1) {
            return Result.ok(candidates.get(0));
        }
        Map<String, Object> details = Map.of(
                "availableChoices", names,
                "matchedChoices", candidates.stream().map(TemplateSchemaService.ChoiceAst::name).toList()
        );
        return Result.err(new ApiError(
                ErrorCode.PRECONDITION_FAILED,
                candidates.isEmpty()
                        ? "No transfer/payout choice found on holding template"
                        : "Multiple transfer-like choices found; set feature.amulet-payout-choice",
                details,
                false,
                null,
                null
        ));
    }

    private Result<ValueOuterClass.Record, ApiError> buildChoiceArgs(
            TemplateSchemaService.TemplateChoicesAst choicesAst,
            TemplateSchemaService.ChoiceAst choice,
            BuildContext ctx
    ) {
        ValueOuterClass.Value argValue;
        if (choicesAst.isLf2()) {
            DamlLf2.Type argType = choice.lf2ArgType();
            if (argType == null) {
                return Result.ok(ValueOuterClass.Record.newBuilder().build());
            }
            argValue = buildValueLf2(choicesAst.lf2Package(), argType, choice.name(), ctx);
        } else {
            DamlLf1.Type argType = choice.lf1ArgType();
            if (argType == null) {
                return Result.ok(ValueOuterClass.Record.newBuilder().build());
            }
            argValue = buildValueLf1(choicesAst.lf1Package(), argType, choice.name(), ctx);
        }
        if (!argValue.hasRecord()) {
            return Result.err(new ApiError(
                    ErrorCode.PRECONDITION_FAILED,
                    "Choice argument is not a record; exerciseRaw supports only record arguments",
                    Map.of("choice", choice.name(), "argType", argValue.getSumCase().name()),
                    false,
                    null,
                    null
            ));
        }
        return Result.ok(argValue.getRecord());
    }

    private Result<String, ApiError> toJsonSafe(Object obj) {
        try {
            return Result.ok(mapper.writeValueAsString(obj));
        } catch (Exception e) {
            return Result.err(internalError("Failed to serialize JSON", e, null));
        }
    }

    private Map<String, Object> recordToJson(ValueOuterClass.Record record) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (record == null) {
            return out;
        }
        int idx = 0;
        for (var f : record.getFieldsList()) {
            String label = f.getLabel() != null && !f.getLabel().isBlank() ? f.getLabel() : "field" + idx;
            out.put(label, valueToJson(f.getValue()));
            idx++;
        }
        return out;
    }

    private Object valueToJson(ValueOuterClass.Value v) {
        return switch (v.getSumCase()) {
            case PARTY -> v.getParty();
            case TEXT -> v.getText();
            case NUMERIC -> v.getNumeric();
            case INT64 -> v.getInt64();
            case BOOL -> v.getBool();
            case TIMESTAMP -> Instant.ofEpochMilli(v.getTimestamp() / 1000L).toString();
            case CONTRACT_ID -> v.getContractId();
            case RECORD -> recordToJson(v.getRecord());
            case LIST -> v.getList().getElementsList().stream().map(this::valueToJson).toList();
            case TEXT_MAP -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                for (var e : v.getTextMap().getEntriesList()) {
                    m.put(e.getKey(), valueToJson(e.getValue()));
                }
                yield m;
            }
            case OPTIONAL -> v.getOptional().hasValue() ? valueToJson(v.getOptional().getValue()) : null;
            case VARIANT -> Map.of("tag", v.getVariant().getConstructor(), "value", valueToJson(v.getVariant().getValue()));
            default -> v.getSumCase().name();
        };
    }

    private ApiError mapRegistryHttpError(int status, String body, String url, ApiError holdingChoiceError) {
        ErrorCode code = switch (status) {
            case 400 -> ErrorCode.VALIDATION;
            case 401, 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            default -> ErrorCode.INTERNAL;
        };
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("url", url);
        details.put("status", status);
        details.put("bodySnippet", snippet(body));
        details.put("holdingChoiceError", holdingChoiceError != null ? holdingChoiceError.message : null);
        return new ApiError(code, "Transfer factory registry request failed", details, false, null, null);
    }

    private Result<TransferFactoryContext, ApiError> parseTransferFactoryContext(String body, String url, ApiError holdingChoiceError) {
        if (body == null || body.isBlank()) {
            return Result.err(new ApiError(
                    ErrorCode.PRECONDITION_FAILED,
                    "Empty registry response",
                    Map.of("url", url, "holdingChoiceError", holdingChoiceError != null ? holdingChoiceError.message : null),
                    false,
                    null,
                    null
            ));
        }
        try {
            JsonNode root = mapper.readTree(body);
            ChoiceContextDto ctx = parseChoiceContext(root);
            String factoryCid = null;
            if (root.has("factoryId")) {
                factoryCid = root.get("factoryId").asText(null);
            } else if (root.has("factoryCid")) {
                factoryCid = root.get("factoryCid").asText(null);
            } else if (root.has("transferFactoryCid")) {
                factoryCid = root.get("transferFactoryCid").asText(null);
            }
            return Result.ok(new TransferFactoryContext(factoryCid, ctx));
        } catch (Exception e) {
            return Result.err(new ApiError(
                    ErrorCode.PRECONDITION_FAILED,
                    "Failed to parse registry response",
                    Map.of("url", url, "error", e.getMessage()),
                    false,
                    null,
                    null
            ));
        }
    }

    private ChoiceContextDto parseChoiceContext(JsonNode root) {
        if (root == null) return null;
        ChoiceContextDto dto = new ChoiceContextDto();
        JsonNode disclosed = root.path("disclosedContracts");
        if (!disclosed.isArray()) {
            disclosed = root.path("choiceContext").path("disclosedContracts");
        }
        if (disclosed.isArray()) {
            dto.disclosedContracts = new ArrayList<>();
            for (JsonNode n : disclosed) {
                DisclosedContractDto dc = new DisclosedContractDto();
                dc.templateId = n.path("templateId").asText(null);
                dc.contractId = n.path("contractId").asText(null);
                dc.createdEventBlob = n.path("createdEventBlob").asText(null);
                if (dc.templateId != null && dc.contractId != null && dc.createdEventBlob != null) {
                    dto.disclosedContracts.add(dc);
                }
            }
        }
        JsonNode ctxValues = root.path("choiceContextData").path("values");
        if (!ctxValues.isObject()) {
            ctxValues = root.path("choiceContext").path("values");
        }
        if (!ctxValues.isObject()) {
            ctxValues = root.path("choiceContext").path("choiceContextData").path("values");
        }
        if (ctxValues.isObject()) {
            dto.choiceContextData = new ChoiceContextDataDto();
            dto.choiceContextData.values = new java.util.LinkedHashMap<>();
            ctxValues.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode v = entry.getValue();
                ContextValueDto cv = new ContextValueDto();
                cv.tag = v.path("tag").asText(null);
                JsonNode rawVal = v.get("value");
                if (rawVal != null && !rawVal.isMissingNode() && !rawVal.isNull()) {
                    if (rawVal.isBoolean()) {
                        cv.value = rawVal.booleanValue();
                    } else if (rawVal.isTextual()) {
                        cv.value = rawVal.textValue();
                    } else if (rawVal.isArray()) {
                        List<String> vals = new ArrayList<>();
                        rawVal.forEach(node -> vals.add(node.asText()));
                        cv.value = vals;
                    } else {
                        cv.value = rawVal.toString();
                    }
                }
                dto.choiceContextData.values.put(key, cv);
            });
        }
        return dto;
    }

    private ValueOuterClass.Record buildExtraArgs(ChoiceContextDto ctx) {
        ValueOuterClass.TextMap.Builder valuesMapBuilder = ValueOuterClass.TextMap.newBuilder();
        if (ctx.choiceContextData != null && ctx.choiceContextData.values != null) {
            for (var entry : ctx.choiceContextData.values.entrySet()) {
                String key = entry.getKey();
                ContextValueDto cv = entry.getValue();
                ValueOuterClass.Value anyValue = toApplicationValue(cv);
                if (anyValue != null) {
                    valuesMapBuilder.addEntries(ValueOuterClass.TextMap.Entry.newBuilder()
                            .setKey(key)
                            .setValue(anyValue)
                            .build());
                }
            }
        }

        ValueOuterClass.Record choiceContext = ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("values")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setTextMap(valuesMapBuilder.build())
                                .build())
                        .build())
                .build();

        ValueOuterClass.Record emptyMeta = ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("values")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setTextMap(ValueOuterClass.TextMap.newBuilder().build())
                                .build())
                        .build())
                .build();

        return ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("context")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setRecord(choiceContext)
                                .build())
                        .build())
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("meta")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setRecord(emptyMeta)
                                .build())
                        .build())
                .build();
    }

    private ValueOuterClass.Value toApplicationValue(ContextValueDto cv) {
        if (cv == null || cv.tag == null) return null;
        ValueOuterClass.Value innerVal = null;
        switch (cv.tag) {
            case "AV_Bool" -> {
                boolean b = false;
                if (cv.value instanceof Boolean boolVal) {
                    b = boolVal;
                } else if (cv.value instanceof String s) {
                    b = Boolean.parseBoolean(s);
                }
                innerVal = ValueOuterClass.Value.newBuilder().setBool(b).build();
            }
            case "AV_ContractId" -> innerVal = ValueOuterClass.Value.newBuilder()
                    .setContractId(cv.value != null ? cv.value.toString() : "")
                    .build();
            case "AV_List" -> {
                ValueOuterClass.List.Builder lb = ValueOuterClass.List.newBuilder();
                if (cv.value instanceof List<?> listVal) {
                    for (Object o : listVal) {
                        lb.addElements(ValueOuterClass.Value.newBuilder()
                                .setText(o != null ? o.toString() : "")
                                .build());
                    }
                }
                innerVal = ValueOuterClass.Value.newBuilder().setList(lb.build()).build();
            }
            default -> {
                return null;
            }
        }

        return ValueOuterClass.Value.newBuilder()
                .setVariant(ValueOuterClass.Variant.newBuilder()
                        .setConstructor(cv.tag)
                        .setValue(innerVal != null ? innerVal : ValueOuterClass.Value.getDefaultInstance())
                        .build())
                .build();
    }

    private CommandsOuterClass.DisclosedContract toDisclosedContract(DisclosedContractDto dc) {
        try {
            ValueOuterClass.Identifier tid = parseTemplateIdentifier(dc.templateId);
            byte[] blob = Base64.getDecoder().decode(dc.createdEventBlob);
            CommandsOuterClass.DisclosedContract.Builder builder = CommandsOuterClass.DisclosedContract.newBuilder()
                    .setTemplateId(tid)
                    .setContractId(dc.contractId)
                    .setCreatedEventBlob(com.google.protobuf.ByteString.copyFrom(blob));
            if (dc.synchronizerId != null && !dc.synchronizerId.isBlank()) {
                builder.setSynchronizerId(dc.synchronizerId);
            }
            return builder.build();
        } catch (Exception e) {
            return null;
        }
    }

    private ValueOuterClass.Identifier parseTemplateIdentifier(String templateIdStr) {
        String[] parts = templateIdStr.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid templateId format: " + templateIdStr);
        }
        return ValueOuterClass.Identifier.newBuilder()
                .setPackageId(parts[0])
                .setModuleName(parts[1])
                .setEntityName(parts[2])
                .build();
    }

    /**
     * Cure (iv.1) helper: build the outbound payout memo JSON for a CBTC TI, given the
     * inbound swap memo JSON (raw String) and the unprefixed wallet requestId.
     *
     * Returns Optional.empty() if construction fails (null/blank/unparseable inbound,
     * missing required inbound fields). Caller treats this as soft-fail — TI submission
     * proceeds without memo emission per DD-5 defensive posture.
     *
     * Package-private for unit testability (PayoutMemoEmissionTest).
     */
    Optional<String> buildSwapMemoJson(String inboundMemoRaw, String unprefixedRequestId) {
        try {
            if (inboundMemoRaw == null || inboundMemoRaw.isBlank()) {
                return Optional.empty();
            }
            JsonNode inbound = mapper.readTree(inboundMemoRaw);
            if (!inbound.has("poolCid") || !inbound.has("direction")) {
                return Optional.empty();
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("v", 1);
            out.put("requestId", unprefixedRequestId);
            out.put("poolCid", inbound.get("poolCid").asText());
            out.put("direction", inbound.get("direction").asText());
            out.put("originator", SWAP_MEMO_ORIGINATOR);
            return Optional.of(mapper.writeValueAsString(out));
        } catch (Exception e) {
            LOG.warn("[PayoutService] Failed to build swap memo JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Cure (iv.1) helper: inject the outbound memo JSON into the TransferFactory_Transfer
     * choice argument record at path transfer.meta.values[SWAP_MEMO_META_KEY].
     *
     * Defensive: each navigation step checks for missing/wrong-shaped fields and returns
     * the original record unchanged on any failure. Combined with the outer try/catch in
     * prepareTransferFactory, this honors DD-5 (defensive log-and-continue).
     *
     * Package-private for unit testability (PayoutMemoEmissionTest).
     */
    ValueOuterClass.Record injectSwapMemoIntoTransferFactoryArg(
            ValueOuterClass.Record transferFactoryArg, String memoJson) {
        try {
            // Step 1: extract transfer record
            ValueOuterClass.RecordField transferField = transferFactoryArg.getFieldsList().stream()
                    .filter(f -> "transfer".equals(f.getLabel())).findFirst().orElse(null);
            if (transferField == null || !transferField.getValue().hasRecord()) {
                return transferFactoryArg;
            }
            ValueOuterClass.Record transfer = transferField.getValue().getRecord();

            // Step 2: extract meta record
            ValueOuterClass.RecordField metaField = transfer.getFieldsList().stream()
                    .filter(f -> "meta".equals(f.getLabel())).findFirst().orElse(null);
            if (metaField == null || !metaField.getValue().hasRecord()) {
                return transferFactoryArg;
            }
            ValueOuterClass.Record meta = metaField.getValue().getRecord();

            // Step 3: extract values TextMap, add new entry
            ValueOuterClass.RecordField valuesField = meta.getFieldsList().stream()
                    .filter(f -> "values".equals(f.getLabel())).findFirst().orElse(null);
            if (valuesField == null || !valuesField.getValue().hasTextMap()) {
                return transferFactoryArg;
            }
            ValueOuterClass.TextMap existing = valuesField.getValue().getTextMap();
            ValueOuterClass.TextMap newValues = ValueOuterClass.TextMap.newBuilder()
                    .addAllEntries(existing.getEntriesList())
                    .addEntries(ValueOuterClass.TextMap.Entry.newBuilder()
                            .setKey(SWAP_MEMO_META_KEY)
                            .setValue(ValueOuterClass.Value.newBuilder().setText(memoJson).build())
                            .build())
                    .build();

            // Step 4: rebuild meta with new values
            ValueOuterClass.Record newMeta = replaceRecordField(meta, "values",
                    ValueOuterClass.Value.newBuilder().setTextMap(newValues).build());

            // Step 5: rebuild transfer with new meta
            ValueOuterClass.Record newTransfer = replaceRecordField(transfer, "meta",
                    ValueOuterClass.Value.newBuilder().setRecord(newMeta).build());

            // Step 6: rebuild transferFactoryArg with new transfer
            return replaceRecordField(transferFactoryArg, "transfer",
                    ValueOuterClass.Value.newBuilder().setRecord(newTransfer).build());
        } catch (Exception e) {
            LOG.warn("[PayoutService] Failed to inject swap memo into TransferFactory_Transfer arg: {}", e.getMessage());
            return transferFactoryArg;
        }
    }

    private ValueOuterClass.Record replaceRecordField(
            ValueOuterClass.Record record,
            String label,
            ValueOuterClass.Value newValue
    ) {
        List<ValueOuterClass.RecordField> fields = new ArrayList<>();
        for (var f : record.getFieldsList()) {
            if (label.equals(f.getLabel())) {
                fields.add(ValueOuterClass.RecordField.newBuilder()
                        .setLabel(label)
                        .setValue(newValue)
                        .build());
            } else {
                fields.add(f);
            }
        }
        return ValueOuterClass.Record.newBuilder().addAllFields(fields).build();
    }

    private String snippet(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) + "..." : s;
    }

    private boolean isHoldingCidList(String fieldName) {
        String lower = fieldName.toLowerCase();
        return lower.contains("holding") && lower.contains("cid");
    }

    private String resolveTransferFactoryUrl(String instrumentAdmin) {
        RegistryRoutingConfig routing = ledgerConfig.getRegistryRouting();
        if (routing != null && instrumentAdmin != null) {
            RegistryRoutingConfig.RegistryEndpoint adminEp = routing.getRegistryForAdmin(instrumentAdmin);
            if (adminEp != null && adminEp.baseUri() != null && !adminEp.baseUri().isBlank()) {
                return switch (adminEp.kind()) {
                    case UTILITIES_TOKEN_STANDARD -> buildUtilitiesTransferFactoryUrl(adminEp.baseUri(), instrumentAdmin);
                    case SCAN -> joinUrl(adminEp.baseUri(), "/registry/transfer-instruction/v1/transfer-factory");
                    default -> null;
                };
            }
        }

        String configured = ledgerConfig.getRegistryTransferFactoryUrl();
        if (configured != null && !configured.isBlank()) {
            String trimmed = configured.trim();
            if (trimmed.contains("/registry/transfer-instruction/v1/transfer-factory")
                    || trimmed.contains("/transfer-instruction/v1/transfer-factory")) {
                return trimmed;
            }
            return joinUrl(trimmed, "/registry/transfer-instruction/v1/transfer-factory");
        }

        if (routing != null && routing.getDefaultBaseUri() != null && !routing.getDefaultBaseUri().isBlank()) {
            return joinUrl(routing.getDefaultBaseUri(), "/registry/transfer-instruction/v1/transfer-factory");
        }
        return null;
    }

    private String buildUtilitiesTransferFactoryUrl(String base, String admin) {
        String safeBase = trimTrailingSlash(base);
        String encAdmin = urlEncode(admin);
        return safeBase + "/api/token-standard/v0/registrars/" + encAdmin + "/registry/transfer-instruction/v1/transfer-factory";
    }

    private String joinUrl(String base, String path) {
        String safe = trimTrailingSlash(base);
        if (!path.startsWith("/")) {
            return safe + "/" + path;
        }
        return safe + path;
    }

    private String trimTrailingSlash(String base) {
        if (base == null) return "";
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String urlEncode(String raw) {
        try {
            return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return raw;
        }
    }

    private record BuildContext(
            String sender,
            String receiver,
            String instrumentAdmin,
            String instrumentId,
            String amount,
            Instant executeBefore,
            String memo,
            String holdingCid
    ) { }

    private static final class ChoiceContextDto {
        public ChoiceContextDataDto choiceContextData;
        public List<DisclosedContractDto> disclosedContracts;
    }
    private static final class ChoiceContextDataDto {
        public Map<String, ContextValueDto> values;
    }
    private static final class ContextValueDto {
        public String tag;
        public Object value;
    }
    private static final class DisclosedContractDto {
        public String templateId;
        public String contractId;
        public String createdEventBlob;
        public String synchronizerId;
    }

    public record TransferFactoryPlan(
            String factoryCid,
            ValueOuterClass.Record extraArgs,
            List<CommandsOuterClass.DisclosedContract> disclosedContracts,
            String synchronizerId
    ) { }

    private record TransferFactoryContext(
            String factoryCid,
            ChoiceContextDto choiceContext
    ) { }


    private record ValidatedRequest(
            String receiverParty,
            BigDecimal amount,
            String amountText,
            Instant executeBefore,
            String memo
    ) { }

    private record InstrumentConfig(String admin, String id, String packageId, String moduleName, String entityName) {
        static InstrumentConfig amulet() {
            return new InstrumentConfig(AMULET_ADMIN, AMULET_ID, AMULET_PKG, AMULET_MODULE, AMULET_ENTITY);
        }

        static InstrumentConfig cbtc() {
            return new InstrumentConfig(CBTC_ADMIN, CBTC_ID, CBTC_PKG, CBTC_MODULE, CBTC_ENTITY);
        }
    }
}

