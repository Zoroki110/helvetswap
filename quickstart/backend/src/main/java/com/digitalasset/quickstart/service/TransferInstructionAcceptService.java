package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryEndpoint;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryKind;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.security.TokenProvider;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Profile-agnostic service for accepting TransferInstructions via registry/scan choice-context.
 *
 * Extracted from DevNetTransferInstructionController so HoldingPoolService.bootstrap can run
 * under the prod profile. The DevNet controller still exposes the REST endpoint by delegating
 * to this service.
 */
@Service
public class TransferInstructionAcceptService {

    private static final Logger logger = LoggerFactory.getLogger(TransferInstructionAcceptService.class);

    private final LedgerConfig ledgerConfig;
    private final TokenProvider tokenProvider;
    private final AuthUtils authUtils;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    private RegistryRoutingConfig registryRouting;

    @Autowired(required = false)
    private TransferInstructionAcsQueryService tiQueryService;

    public TransferInstructionAcceptService(LedgerConfig ledgerConfig, TokenProvider tokenProvider, AuthUtils authUtils) {
        this.ledgerConfig = ledgerConfig;
        this.tokenProvider = tokenProvider;
        this.authUtils = authUtils;
    }

    public record AcceptRequest(String contractId, String asParty) { }

    public record AcceptWithContextResponse(
            boolean accepted,
            String updateId,
            String holdingCid,
            String error,
            String registryUsed,
            List<String> registriesAttempted
    ) {
        // Backward-compatible constructor
        public AcceptWithContextResponse(boolean accepted, String updateId, String holdingCid, String error) {
            this(accepted, updateId, holdingCid, error, null, null);
        }
    }

    /**
     * Accept TI using registry/scan choice-context (handles non-disclosed contracts).
     * Supports multi-registry routing: tries default, then fallbacks until one works.
     */
    public AcceptWithContextResponse acceptWithContext(AcceptRequest request) {
        String requestId = "ti-accept-ctx-" + UUID.randomUUID().toString().substring(0, 8);
        String contractId = request.contractId();
        String operatorParty = authUtils.getAppProviderPartyId();
        String actAs = request.asParty() != null && !request.asParty().isBlank() ? request.asParty() : operatorParty;
        if (contractId == null || contractId.isBlank()) {
            return new AcceptWithContextResponse(false, null, null, "contractId is required");
        }
        logCid("accept-with-context", contractId, actAs);

        TransferInstructionDto ti = null;
        if (tiQueryService != null) {
            ti = tiQueryService.findByCid(actAs, contractId).orElse(null);
        }
        String adminHint = ti != null ? ti.admin() : null;
        if (ti != null) {
            logger.info("[TI Accept] requestId={} tiCid={} sender={} receiver={} admin={} instrumentId={} actAs={}",
                    requestId,
                    abbreviate(contractId),
                    abbreviate(ti.sender()),
                    abbreviate(ti.receiver()),
                    abbreviate(ti.admin()),
                    ti.instrumentId(),
                    abbreviate(actAs));
        } else {
            logger.warn("[TI Accept] requestId={} tiCid={} actAs={} note=ti-not-found-in-acs",
                    requestId,
                    abbreviate(contractId),
                    abbreviate(actAs));
        }

        // Get registries to try (multi-registry routing)
        List<RegistryEndpoint> registriesToTry = getRegistriesToTry(adminHint);
        if (registriesToTry.isEmpty()) {
            return new AcceptWithContextResponse(false, null, null, "no registryBaseUri configured", null, List.of());
        }

        List<String> attempted = new ArrayList<>();
        ChoiceContextDto ctx = null;
        String successfulBase = null;
        String synchronizerId = null;

        // Try each registry until we find one with valid disclosures
        for (RegistryEndpoint ep : registriesToTry) {
            attempted.add(ep.baseUri());
            logger.info("[TI Accept] requestId={} trying base={} kind={} contractId={}",
                    requestId, abbreviate(ep.baseUri()), ep.kind(), abbreviate(contractId));

            try {
                RegistryFetchResult fetch = fetchChoiceContext(ep, contractId, requestId);
                if (fetch != null) {
                    ctx = fetch.ctx;
                    synchronizerId = fetch.synchronizerId;
                }
                if (ctx != null && ctx.disclosedContracts != null && !ctx.disclosedContracts.isEmpty()) {
                    successfulBase = ep.baseUri();
                    logger.info("[TI Accept] requestId={} found disclosures base={} count={}",
                            requestId, abbreviate(ep.baseUri()), ctx.disclosedContracts.size());
                    break;
                } else {
                    logger.info("[TI Accept] requestId={} no disclosures base={}", requestId, abbreviate(ep.baseUri()));
                }
            } catch (Exception e) {
                logger.warn("[TI Accept] requestId={} registry base={} failed: {}", requestId, abbreviate(ep.baseUri()), e.getMessage());
            }
        }

        if (ctx == null || ctx.disclosedContracts == null || ctx.disclosedContracts.isEmpty()) {
            String msg = String.format("choice-context missing disclosures after trying %d registries", attempted.size());
            return new AcceptWithContextResponse(false, null, null, msg, null, attempted);
        }

        try {
            logger.info("[TI Accept] requestId={} actAs={} contractId={} disclosures={} registry={} syncId={}",
                    requestId, actAs, abbreviate(contractId), ctx.disclosedContracts.size(),
                    abbreviate(successfulBase), abbreviate(synchronizerId));

            var disclosed = ctx.disclosedContracts.stream()
                    .map(TransferInstructionAcceptService::toDisclosedContract)
                    .filter(Objects::nonNull)
                    .toList();
            if (disclosed.isEmpty()) {
                return new AcceptWithContextResponse(false, null, null, "no valid disclosed contracts in context");
            }

            // For interface choices, use the INTERFACE identifier with package-name reference format
            // TransferInstruction_Accept is a choice on Splice.Api.Token.TransferInstructionV1:TransferInstruction interface
            // Use package-name format: #splice-api-token-transfer-instruction-v1 (Canton 3.4+ / Splice 0.5+)
            ValueOuterClass.Identifier interfaceId = ValueOuterClass.Identifier.newBuilder()
                    .setPackageId("#splice-api-token-transfer-instruction-v1")
                    .setModuleName("Splice.Api.Token.TransferInstructionV1")
                    .setEntityName("TransferInstruction")
                    .build();
            logger.info("Using interface ID for TI accept: #splice-api-token-transfer-instruction-v1:Splice.Api.Token.TransferInstructionV1:TransferInstruction");

            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress(ledgerConfig.getHost(), ledgerConfig.getPort())
                    .usePlaintext()
                    .intercept(new AuthInterceptor(tokenProvider.getToken()))
                    .build();
            try {
                CommandServiceGrpc.CommandServiceBlockingStub cmd = CommandServiceGrpc.newBlockingStub(channel);

                // Build ChoiceContext.values map from choiceContextData.values
                ValueOuterClass.TextMap.Builder valuesMapBuilder = ValueOuterClass.TextMap.newBuilder();
                if (ctx.choiceContextData != null && ctx.choiceContextData.values != null) {
                    for (var entry : ctx.choiceContextData.values.entrySet()) {
                        String key = entry.getKey();
                        ContextValueDto cv = entry.getValue();

                        ValueOuterClass.Value anyValue;
                        if ("AV_Bool".equals(cv.tag)) {
                            anyValue = ValueOuterClass.Value.newBuilder()
                                    .setVariant(ValueOuterClass.Variant.newBuilder()
                                            .setConstructor("AV_Bool")
                                            .setValue(ValueOuterClass.Value.newBuilder()
                                                    .setBool(Boolean.TRUE.equals(cv.value))
                                                    .build())
                                            .build())
                                    .build();
                        } else if ("AV_ContractId".equals(cv.tag)) {
                            anyValue = ValueOuterClass.Value.newBuilder()
                                    .setVariant(ValueOuterClass.Variant.newBuilder()
                                            .setConstructor("AV_ContractId")
                                            .setValue(ValueOuterClass.Value.newBuilder()
                                                    .setContractId(cv.value.toString())
                                                    .build())
                                            .build())
                                    .build();
                        } else {
                            logger.warn("Unknown choiceContextData value type: {} for key {}", cv.tag, key);
                            continue;
                        }

                        valuesMapBuilder.addEntries(ValueOuterClass.TextMap.Entry.newBuilder()
                                .setKey(key)
                                .setValue(anyValue)
                                .build());
                    }
                }
                logger.info("Built ChoiceContext.values with {} entries", valuesMapBuilder.getEntriesCount());

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

                ValueOuterClass.Record extraArgs = ValueOuterClass.Record.newBuilder()
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

                ValueOuterClass.Record choiceArg = ValueOuterClass.Record.newBuilder()
                        .addFields(ValueOuterClass.RecordField.newBuilder()
                                .setLabel("extraArgs")
                                .setValue(ValueOuterClass.Value.newBuilder()
                                        .setRecord(extraArgs)
                                        .build())
                                .build())
                        .build();

                CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                        .setTemplateId(interfaceId)
                        .setContractId(contractId)
                        .setChoice("TransferInstruction_Accept")
                        .setChoiceArgument(ValueOuterClass.Value.newBuilder()
                                .setRecord(choiceArg)
                                .build())
                        .build();

                CommandsOuterClass.Commands commands = CommandsOuterClass.Commands.newBuilder()
                        .setCommandId("accept-ti-ctx-" + UUID.randomUUID())
                        .setUserId(actAs)
                        .addActAs(actAs)
                        .addReadAs(actAs)
                        .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build())
                        .addAllDisclosedContracts(disclosed)
                        .build();
                logger.info("[TI Accept] requestId={} submit actAs=[{}] readAs=[{}] disclosed={}",
                        requestId, actAs, actAs, disclosed.size());

                TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                        .putFiltersByParty(actAs, TransactionFilterOuterClass.Filters.newBuilder()
                                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                        .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                        .build())
                                .build())
                        .build();
                TransactionFilterOuterClass.TransactionFormat txFormat = TransactionFilterOuterClass.TransactionFormat.newBuilder()
                        .setEventFormat(eventFormat)
                        .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                        .build();

                CommandServiceOuterClass.SubmitAndWaitForTransactionRequest req =
                        CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                                .setCommands(commands)
                                .setTransactionFormat(txFormat)
                                .build();

                var resp = cmd.submitAndWaitForTransaction(req);
                String updateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : null;
                String holdingCid = extractHoldingCid(resp);
                logger.info("TI accept-with-context submit: actAs={} contractId={} updateId={} holdingCid={} registry={}",
                        actAs, abbreviate(contractId), updateId, holdingCid, abbreviate(successfulBase));
                return new AcceptWithContextResponse(true, updateId, holdingCid, null, successfulBase, attempted);
            } finally {
                channel.shutdownNow();
            }
        } catch (Exception e) {
            logger.warn("TI accept-with-context failed: actAs={} contractId={} error={}", actAs, abbreviate(contractId), e.getMessage());
            return new AcceptWithContextResponse(false, null, null, e.getMessage(), successfulBase, attempted);
        }
    }

    // ----- helpers -----

    private RegistryFetchResult fetchChoiceContext(RegistryEndpoint ep, String contractId, String requestId) {
        try {
            String url1 = buildUrl(ep, true, contractId);
            RegistryFetchResult first = url1 != null ? postChoiceContext(url1, ep, contractId, requestId) : null;
            if (first != null && first.ctx != null && first.ctx.disclosedContracts != null && !first.ctx.disclosedContracts.isEmpty()) {
                return first;
            }
            String url2 = buildUrl(ep, false, contractId);
            RegistryFetchResult second = url2 != null ? postChoiceContext(url2, ep, contractId, requestId) : null;
            return second != null ? second : first;
        } catch (Exception e) {
            logger.warn("[TI Accept] requestId={} base={} kind={} contractId={} error={}",
                    requestId,
                    abbreviate(ep.baseUri()),
                    ep.kind(),
                    abbreviate(contractId),
                    e.getMessage());
            return null;
        }
    }

    private RegistryFetchResult postChoiceContext(String url, RegistryEndpoint ep, String contractId, String requestId) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"meta\":{}}"));
            if (ledgerConfig.getRegistryAuthHeader() != null && !ledgerConfig.getRegistryAuthHeader().isBlank()
                    && ledgerConfig.getRegistryAuthToken() != null && !ledgerConfig.getRegistryAuthToken().isBlank()) {
                b.header(ledgerConfig.getRegistryAuthHeader(), ledgerConfig.getRegistryAuthToken());
            }
            HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String body = resp.body() != null ? resp.body() : "";
            ChoiceContextDto ctx = resp.statusCode() >= 200 && resp.statusCode() < 300
                    ? parseChoiceContext(body)
                    : null;
            int disclosed = ctx != null && ctx.disclosedContracts != null ? ctx.disclosedContracts.size() : 0;
            int contextKeys = ctx != null && ctx.choiceContextData != null && ctx.choiceContextData.values != null
                    ? ctx.choiceContextData.values.size()
                    : 0;
            String synchronizerId = ctx != null && ctx.disclosedContracts != null
                    ? ctx.disclosedContracts.stream()
                        .map(dc -> dc.synchronizerId)
                        .filter(s -> s != null && !s.isBlank())
                        .findFirst().orElse(null)
                    : null;
            Set<String> keys = extractTopLevelKeys(body);
            logger.info("[TI Accept] requestId={} contractId={} adminHint={} base={} kind={} url={} status={} size={} disclosed={} contextKeys={} extraArgs={} syncId={} keys={}",
                    requestId,
                    abbreviate(contractId),
                    abbreviate(ep.admin()),
                    abbreviate(ep.baseUri()),
                    ep.kind(),
                    abbreviate(url),
                    resp.statusCode(),
                    body.length(),
                    disclosed,
                    contextKeys,
                    contextKeys > 0,
                    abbreviate(synchronizerId),
                    keys);
            return new RegistryFetchResult(ctx, url, resp.statusCode(), body.length(), disclosed, contextKeys, synchronizerId);
        } catch (Exception e) {
            logger.warn("[TI Accept] requestId={} url={} contractId={} error={}",
                    requestId,
                    abbreviate(url),
                    abbreviate(contractId),
                    e.getMessage());
            return new RegistryFetchResult(null, url, 0, 0, 0, 0, null);
        }
    }

    private List<RegistryEndpoint> getRegistriesToTry(String adminPartyHint) {
        if (registryRouting != null) {
            return registryRouting.getRegistriesToTry(adminPartyHint);
        }
        String base = ledgerConfig.getRegistryBaseUri();
        if (base != null && !base.isBlank()) {
            return List.of(new RegistryEndpoint(base, RegistryKind.SCAN, null));
        }
        return List.of();
    }

    private static String buildUrl(RegistryEndpoint ep, boolean withRegistryPrefix, String contractId) {
        if (ep == null || ep.baseUri() == null || ep.baseUri().isBlank()) return null;
        String url = ep.baseUri();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        String safeCid = encodePathSegment(contractId);
        if (ep.kind() == RegistryKind.UTILITIES_TOKEN_STANDARD) {
            if (ep.admin() == null || ep.admin().isBlank()) return null;
            return url + "/api/token-standard/v0/registrars/" + encodePathSegment(ep.admin()) + "/registry/transfer-instruction/v1/" + safeCid + "/choice-contexts/accept";
        } else if (ep.kind() == RegistryKind.LOOP_TOKEN_STANDARD_V1) {
            return url + "/api/v1/token-standard/transfer-instructions/" + safeCid + "/choice-contexts/accept";
        }
        if (withRegistryPrefix) {
            return url + "/registry/transfer-instruction/v1/" + safeCid + "/choice-contexts/accept";
        } else {
            return url + "/transfer-instruction/v1/" + safeCid + "/choice-contexts/accept";
        }
    }

    private static String encodePathSegment(String raw) {
        try {
            return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return raw;
        }
    }

    private ChoiceContextDto parseChoiceContext(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode top = root.has("choiceContext") ? root.get("choiceContext") : root;
            ChoiceContextDto dto = new ChoiceContextDto();
            JsonNode disclosed = root.has("disclosedContracts") ? root.get("disclosedContracts") : top.get("disclosedContracts");
            if (disclosed != null && disclosed.isArray()) {
                dto.disclosedContracts = new ArrayList<>();
                for (JsonNode n : disclosed) {
                    DisclosedContractDto dc = new DisclosedContractDto();
                    dc.templateId = n.path("templateId").asText(null);
                    dc.contractId = n.path("contractId").asText(null);
                    dc.createdEventBlob = n.path("createdEventBlob").asText(null);
                    dc.synchronizerId = n.path("synchronizerId").asText(null);
                    if (dc.templateId != null && dc.contractId != null && dc.createdEventBlob != null) {
                        dto.disclosedContracts.add(dc);
                    }
                }
            }

            JsonNode ctxValues = top.path("choiceContextData").path("values");
            if (!ctxValues.isObject()) {
                ctxValues = root.path("choiceContextData").path("values");
            }
            if (ctxValues.isObject()) {
                dto.choiceContextData = new ChoiceContextDataDto();
                dto.choiceContextData.values = new LinkedHashMap<>();
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
        } catch (Exception e) {
            logger.warn("[TI Accept] choice-context parse failed error={}", e.getMessage());
            return null;
        }
    }

    private Set<String> extractTopLevelKeys(String body) {
        if (body == null || body.isBlank()) return Set.of();
        try {
            JsonNode root = mapper.readTree(body);
            Set<String> keys = new LinkedHashSet<>();
            root.fieldNames().forEachRemaining(keys::add);
            return keys;
        } catch (Exception e) {
            return Set.of();
        }
    }

    private static CommandsOuterClass.DisclosedContract toDisclosedContract(DisclosedContractDto dc) {
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

    private static ValueOuterClass.Identifier parseTemplateIdentifier(String templateIdStr) {
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

    private static void logCid(String phase, String cid, String operator) {
        if (cid == null) return;
        String trimmed = cid.trim();
        int len = trimmed.length();
        String prefix = len >= 8 ? trimmed.substring(0, 8) : trimmed;
        String suffix = len >= 8 ? trimmed.substring(len - 8) : trimmed;
        logger.info("TI {} received cid len={} prefix={} suffix={} operator={}", phase, len, prefix, suffix, operator);
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        if (s.length() <= 30) return s;
        return s.substring(0, 15) + "..." + s.substring(s.length() - 10);
    }

    private static String extractHoldingCid(CommandServiceOuterClass.SubmitAndWaitForTransactionResponse resp) {
        if (resp == null || !resp.hasTransaction()) return null;
        for (var event : resp.getTransaction().getEventsList()) {
            if (!event.hasCreated()) continue;
            var created = event.getCreated();
            if ("Holding".equalsIgnoreCase(created.getTemplateId().getEntityName())) {
                return created.getContractId();
            }
        }
        return null;
    }

    // ----- DTOs -----

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

    private record RegistryFetchResult(
            ChoiceContextDto ctx,
            String url,
            int status,
            int bodySize,
            int disclosedCount,
            int contextKeys,
            String synchronizerId
    ) { }

    private static final class AuthInterceptor implements ClientInterceptor {
        private static final Metadata.Key<String> AUTHORIZATION_HEADER = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        private final String token;

        AuthInterceptor(String token) {
            this.token = token;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            ClientCall<ReqT, RespT> clientCall = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(clientCall) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    if (token != null && !token.isBlank()) {
                        headers.put(AUTHORIZATION_HEADER, "Bearer " + token);
                    }
                    super.start(responseListener, headers);
                }
            };
        }
    }
}
