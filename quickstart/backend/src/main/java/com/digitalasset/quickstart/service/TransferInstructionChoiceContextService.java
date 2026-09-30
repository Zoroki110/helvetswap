package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryEndpoint;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryKind;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolve TransferInstruction choice-context disclosures for devnet-only workflows.
 */
@Service
@Profile("prod")
public class TransferInstructionChoiceContextService {

    private static final Logger logger = LoggerFactory.getLogger(TransferInstructionChoiceContextService.class);

    private final LedgerConfig ledgerConfig;

    @Autowired(required = false)
    private RegistryRoutingConfig registryRouting;

    @Autowired(required = false)
    private LocalFallbackChoiceContextProvider localFallbackProvider;

    @Value("${clearportx.choice-context.scan.force-fail:false}")
    private boolean forceScanFail;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public TransferInstructionChoiceContextService(final LedgerConfig ledgerConfig) {
        this.ledgerConfig = ledgerConfig;
    }

    public record ChoiceContextResult(
            List<CommandsOuterClass.DisclosedContract> disclosedContracts,
            ValueOuterClass.Record extraArgs,
            int contextKeyCount,
            String registryUsed,
            List<String> registriesAttempted,
            String synchronizerId
    ) { }

    public Result<ChoiceContextResult, ApiError> resolveDisclosedContracts(String contractId, String adminHint) {
        return resolveDisclosedContracts(contractId, adminHint, null);
    }

    public Result<ChoiceContextResult, ApiError> resolveDisclosedContracts(String contractId, String adminHint, String requestId) {
        return resolveDisclosedContracts(contractId, adminHint, requestId, Set.of());
    }

    public Result<ChoiceContextResult, ApiError> resolveDisclosedContracts(String contractId, String adminHint, String requestId, Set<String> staleCids) {
        if (contractId == null || contractId.isBlank()) {
            return Result.err(ApiError.of(ErrorCode.VALIDATION, "contractId is required"));
        }

        logger.info("[TIChoiceContext] DIAG resolveDisclosedContracts contractId={} adminHint={} requestId={} staleCids={} forceScanFail={}",
                contractId, adminHint, requestIdOrDefault(requestId), staleCids != null ? staleCids.size() : 0, forceScanFail);

        List<String> attempted = new ArrayList<>();
        ChoiceContextDto ctx = null;
        String successfulBase = null;
        String synchronizerId = null;
        ChoiceContextDto lastStaleCtx = null;
        String lastStaleSynchronizerId = null;

        // Track per-provider results for enriched error reporting
        Map<String, String> providerResults = new LinkedHashMap<>();

        // ── Scan providers (skip entirely if force-fail is enabled) ──
        if (forceScanFail) {
            logger.warn("[ChoiceContext] SCAN_FORCED_FAIL enabled, skipping all Scan registries for requestId={}",
                    requestIdOrDefault(requestId));
            providerResults.put("SCAN", "FORCED_FAIL");
        } else {
            List<RegistryEndpoint> registriesToTry = getRegistriesToTry(adminHint);
            if (registriesToTry.isEmpty()) {
                providerResults.put("SCAN", "NO_REGISTRY_CONFIGURED");
            }

            for (RegistryEndpoint ep : registriesToTry) {
                attempted.add(ep.baseUri());
                try {
                    RegistryFetchResult fetch = fetchChoiceContext(ep, contractId, requestId);
                    if (fetch != null) {
                        ctx = fetch.ctx;
                        synchronizerId = fetch.synchronizerId;
                        providerResults.put(abbreviate(ep.baseUri()),
                                fetch.status >= 200 && fetch.status < 300 ? "OK_" + fetch.status : "FAIL_" + fetch.status);
                    } else {
                        providerResults.put(abbreviate(ep.baseUri()), "NULL_RESPONSE");
                    }
                    if (ctx != null && ctx.disclosedContracts != null && !ctx.disclosedContracts.isEmpty()) {
                        if (staleCids != null && !staleCids.isEmpty()) {
                            boolean allStale = ctx.disclosedContracts.stream()
                                    .allMatch(dc -> dc.contractId != null && staleCids.contains(dc.contractId));
                            if (allStale) {
                                logger.warn("[TIChoiceContext] requestId={} base={} returned all stale CIDs ({} contracts), trying next registry",
                                        requestIdOrDefault(requestId), abbreviate(ep.baseUri()), ctx.disclosedContracts.size());
                                lastStaleCtx = ctx;
                                lastStaleSynchronizerId = synchronizerId;
                                ctx = null;
                                synchronizerId = null;
                                providerResults.put(abbreviate(ep.baseUri()), "STALE");
                                continue;
                            }
                        }
                        successfulBase = ep.baseUri();
                        break;
                    }
                } catch (Exception e) {
                    logger.warn("[TIChoiceContext] requestId={} base={} kind={} contractId={} error={}",
                            requestIdOrDefault(requestId),
                            abbreviate(ep.baseUri()),
                            ep.kind(),
                            abbreviate(contractId),
                            e.getMessage());
                    providerResults.put(abbreviate(ep.baseUri()), "ERROR: " + e.getMessage());
                }
            }

            if ((ctx == null || ctx.disclosedContracts == null || ctx.disclosedContracts.isEmpty())
                    && lastStaleCtx != null) {
                logger.warn("[TIChoiceContext] requestId={} all {} registries returned stale data, using last stale result",
                        requestIdOrDefault(requestId), attempted.size());
                ctx = lastStaleCtx;
                synchronizerId = lastStaleSynchronizerId;
                successfulBase = "stale-fallback";
            }
        }

        // ── Local fallback provider (when Scan fails) ──
        if (ctx == null || ctx.disclosedContracts == null || ctx.disclosedContracts.isEmpty()) {
            if (localFallbackProvider != null && localFallbackProvider.isEnabled()) {
                logger.info("[ChoiceContext] SCAN_FAIL registries={} reason=all_failed_or_empty, falling back to LOCAL for requestId={}",
                        attempted.size(), requestIdOrDefault(requestId));
                try {
                    ChoiceContextResult localResult = localFallbackProvider.resolveLocally(contractId, adminHint, requestId);
                    if (localResult != null && localResult.disclosedContracts() != null && !localResult.disclosedContracts().isEmpty()) {
                        providerResults.put("LOCAL_FALLBACK", "OK");
                        return Result.ok(localResult);
                    }
                    providerResults.put("LOCAL_FALLBACK", "NO_DISCLOSURES");
                } catch (Exception e) {
                    logger.warn("[ChoiceContext] LOCAL_FALLBACK failed for requestId={}: {}",
                            requestIdOrDefault(requestId), e.getMessage());
                    providerResults.put("LOCAL_FALLBACK", "ERROR: " + e.getMessage());
                }
            } else {
                String reason = localFallbackProvider == null ? "NOT_WIRED" : localFallbackProvider.getDiscoveryStatus();
                providerResults.put("LOCAL_FALLBACK", "DISABLED_" + reason);
                logger.info("[ChoiceContext] LOCAL_FALLBACK_DISABLED all providers failed for requestId={} reason={}",
                        requestIdOrDefault(requestId), reason);
            }
        }

        if (ctx == null || ctx.disclosedContracts == null || ctx.disclosedContracts.isEmpty()) {
            Map<String, Object> errorDetails = new LinkedHashMap<>();
            errorDetails.put("attempted", attempted);
            errorDetails.put("providerResults", providerResults);
            errorDetails.put("tiCid", contractId);
            errorDetails.put("requestId", requestIdOrDefault(requestId));
            errorDetails.put("errorCode", "CHOICE_CONTEXT_UNAVAILABLE");
            return Result.err(ApiError.of(
                    ErrorCode.PRECONDITION_FAILED,
                    "choice-context missing disclosures",
                    errorDetails
            ));
        }

        if (synchronizerId == null) {
            synchronizerId = ctx.disclosedContracts.stream()
                    .map(dc -> dc.synchronizerId)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElse(null);
        }

        List<CommandsOuterClass.DisclosedContract> disclosed = ctx.disclosedContracts.stream()
                .map(TransferInstructionChoiceContextService::toDisclosedContract)
                .filter(Objects::nonNull)
                .toList();

        if (disclosed.isEmpty()) {
            return Result.err(ApiError.of(
                    ErrorCode.PRECONDITION_FAILED,
                    "choice-context disclosures are invalid",
                    java.util.Map.of("attempted", attempted)
            ));
        }

        ValueOuterClass.Record extraArgs = buildExtraArgs(ctx);
        int contextKeys = ctx != null && ctx.choiceContextData != null && ctx.choiceContextData.values != null
                ? ctx.choiceContextData.values.size()
                : 0;
        return Result.ok(new ChoiceContextResult(disclosed, extraArgs, contextKeys, successfulBase, attempted, synchronizerId));
    }

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
            logger.warn("[TIChoiceContext] requestId={} base={} kind={} contractId={} error={}",
                    requestIdOrDefault(requestId),
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
            logger.info("[TIChoiceContext] requestId={} contractId={} adminHint={} base={} kind={} url={} status={} size={} disclosed={} contextKeys={} extraArgs={} syncId={} keys={}",
                    requestIdOrDefault(requestId),
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
            // Per-cid disclosure log — closes the count-only gap surfaced in Phase 3F.8B.
            if (ctx != null && ctx.disclosedContracts != null) {
                for (DisclosedContractDto dc : ctx.disclosedContracts) {
                    logger.info("[TIChoiceContext] requestId={} disclosed cid={} template={} synchronizerId={}",
                            requestIdOrDefault(requestId),
                            abbreviate(dc.contractId),
                            dc.templateId,
                            abbreviate(dc.synchronizerId));
                }
            }
            return new RegistryFetchResult(ctx, url, resp.statusCode(), body.length(), disclosed, contextKeys, synchronizerId);
        } catch (Exception e) {
            logger.warn("[TIChoiceContext] requestId={} url={} contractId={} error={}",
                    requestIdOrDefault(requestId),
                    abbreviate(url),
                    abbreviate(contractId),
                    e.getMessage());
            return new RegistryFetchResult(null, url, 0, 0, 0, 0, null);
        }
    }

    private List<RegistryEndpoint> getRegistriesToTry(String adminHint) {
        if (registryRouting != null) {
            return registryRouting.getRegistriesToTry(adminHint);
        }
        String base = ledgerConfig.getRegistryBaseUri();
        if (base != null && !base.isBlank()) {
            return List.of(new RegistryEndpoint(base, RegistryKind.SCAN, adminHint));
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
            String safeAdmin = encodePathSegment(ep.admin());
            return url + "/api/token-standard/v0/registrars/" + safeAdmin + "/registry/transfer-instruction/v1/" + safeCid + "/choice-contexts/accept";
        } else if (ep.kind() == RegistryKind.LOOP_TOKEN_STANDARD_V1) {
            return url + "/api/v1/token-standard/transfer-instructions/" + safeCid + "/choice-contexts/accept";
        }
        if (withRegistryPrefix) {
            return url + "/registry/transfer-instruction/v1/" + safeCid + "/choice-contexts/accept";
        }
        return url + "/transfer-instruction/v1/" + safeCid + "/choice-contexts/accept";
    }

    private static String encodePathSegment(String raw) {
        try {
            return URLEncoder.encode(raw, StandardCharsets.UTF_8.toString());
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
        } catch (Exception e) {
            logger.warn("[TIChoiceContext] parse failed error={}", e.getMessage());
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

    private static String abbreviate(String s) {
        if (s == null) return "null";
        if (s.length() <= 30) return s;
        return s.substring(0, 15) + "..." + s.substring(s.length() - 10);
    }

    private ValueOuterClass.Record buildExtraArgs(ChoiceContextDto ctx) {
        ValueOuterClass.TextMap.Builder valuesMapBuilder = ValueOuterClass.TextMap.newBuilder();
        if (ctx != null && ctx.choiceContextData != null && ctx.choiceContextData.values != null) {
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
        ValueOuterClass.Value innerVal;
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
            case "AV_Text" -> innerVal = ValueOuterClass.Value.newBuilder()
                    .setText(cv.value != null ? cv.value.toString() : "")
                    .build();
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

    private String requestIdOrDefault(String requestId) {
        return requestId == null || requestId.isBlank() ? "n/a" : requestId;
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

    private static final class ChoiceContextDto {
        public ChoiceContextDataDto choiceContextData;
        public List<DisclosedContractDto> disclosedContracts;
    }
    private static final class ChoiceContextDataDto {
        public java.util.Map<String, ContextValueDto> values;
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
}

