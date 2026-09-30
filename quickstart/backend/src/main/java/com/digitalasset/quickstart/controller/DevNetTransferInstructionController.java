package com.digitalasset.quickstart.controller;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryEndpoint;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryKind;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.security.TokenProvider;
import com.digitalasset.quickstart.service.TransferInstructionAcceptService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DevNet-only helper to verify TransferInstruction visibility + acceptance.
 *
 * POST /api/devnet/transfer-instruction/visibility { contractId }
 *   -> { visible: true/false }
 *
 * POST /api/devnet/transfer-instruction/accept { contractId }
 *   -> 409 if not visible
 *   -> { visible:true, accepted:true, updateId, holdingCid }
 *   -> on failure { visible:true, accepted:false, error }
 */
@RestController
@RequestMapping("/api/devnet/transfer-instruction")
@Profile("devnet")
public class DevNetTransferInstructionController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetTransferInstructionController.class);

    private final LedgerConfig ledgerConfig;
    private final TokenProvider tokenProvider;
    private final AuthUtils authUtils;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    private RegistryRoutingConfig registryRouting;

    @Autowired(required = false)
    private TransferInstructionAcsQueryService tiQueryService;

    @Autowired
    private TransferInstructionAcceptService tiAcceptService;

    public DevNetTransferInstructionController(LedgerConfig ledgerConfig, TokenProvider tokenProvider, AuthUtils authUtils) {
        this.ledgerConfig = ledgerConfig;
        this.tokenProvider = tokenProvider;
        this.authUtils = authUtils;
    }

    public record AcceptRequest(String contractId, String asParty) { }
    public record VisibilityResponse(
            String operatorParty,
            String contractId,
            boolean visible
    ) { }

    public record AcceptResponse(
            String operatorParty,
            String contractId,
            boolean visible,
            boolean accepted,
            String updateId,
            String holdingCid,
            String error
    ) { }

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

    public record RegistryPingResponse(
            String registryBaseUri,
            boolean reachable,
            String error
    ) { }

    // New records for multi-registry support
    public record RegistryProbeRequest(String tiCid, List<String> bases) { }

    public record RegistryProbeResult(
            String baseUri,
            int httpStatus,
            boolean hasDisclosures,
            int disclosureCount,
            List<String> contextKeys,
            String error
    ) { }

    public record RegistryProbeResponse(
            String tiCid,
            List<RegistryProbeResult> results,
            String successfulBase
    ) { }

    public record RegistryConfigResponse(
            String defaultBaseUri,
            List<String> fallbackBaseUris,
            Map<String, String> adminMappings
    ) { }

    @PostMapping("/visibility")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public ResponseEntity<VisibilityResponse> visibility(@AuthenticationPrincipal Jwt jwt, @RequestBody AcceptRequest request) {
        String contractId = request.contractId();
        String operatorParty = authUtils.getAppProviderPartyId();
        String actAs = request.asParty() != null && !request.asParty().isBlank() ? request.asParty() : operatorParty;

        if (contractId == null || contractId.isBlank()) {
            return ResponseEntity.badRequest().body(new VisibilityResponse(operatorParty, contractId, false));
        }

        logCid("visibility", contractId, actAs);

        ManagedChannel channel = null;
        try {
            ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                    .forAddress(ledgerConfig.getHost(), ledgerConfig.getPort())
                    .usePlaintext();
            if (ledgerConfig.getGrpcAuthority() != null && !ledgerConfig.getGrpcAuthority().isBlank()) {
                builder = builder.overrideAuthority(ledgerConfig.getGrpcAuthority());
            }
            builder.intercept(new AuthInterceptor(tokenProvider.getToken()));
            channel = builder.build();

            boolean visible = isVisible(channel, actAs, contractId);
            logger.info("TI visibility check: requestedAs={} actAs={} contractId={} visible={}", request.asParty(), actAs, contractId, visible);
            return ResponseEntity.ok(new VisibilityResponse(actAs, contractId, visible));
        } finally {
            if (channel != null) channel.shutdownNow();
        }
    }

    @PostMapping("/accept")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public ResponseEntity<AcceptResponse> accept(@AuthenticationPrincipal Jwt jwt, @RequestBody AcceptRequest request) {
        String contractId = request.contractId();
        String operatorParty = authUtils.getAppProviderPartyId();
        String actAs = request.asParty() != null && !request.asParty().isBlank() ? request.asParty() : operatorParty;

        if (contractId == null || contractId.isBlank()) {
            return ResponseEntity.badRequest().body(new AcceptResponse(actAs, contractId, false, false, null, null, "contractId is required"));
        }

        logCid("accept", contractId, actAs);

        ManagedChannel channel = null;
        try {
            ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                    .forAddress(ledgerConfig.getHost(), ledgerConfig.getPort())
                    .usePlaintext();
            if (ledgerConfig.getGrpcAuthority() != null && !ledgerConfig.getGrpcAuthority().isBlank()) {
                builder = builder.overrideAuthority(ledgerConfig.getGrpcAuthority());
            }
            builder.intercept(new AuthInterceptor(tokenProvider.getToken()));
            channel = builder.build();

            StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);
            ValueOuterClass.Identifier templateId = findTemplateId(state, actAs, contractId);
            boolean visible = templateId != null;
            logger.info("TI visibility check: requestedAs={} actAs={} contractId={} visible={}", request.asParty(), actAs, contractId, visible);
            if (!visible) {
                return ResponseEntity.status(409).body(new AcceptResponse(actAs, contractId, false, false, null, null, "TransferInstruction not visible to operator"));
            }

            CommandServiceGrpc.CommandServiceBlockingStub cmd = CommandServiceGrpc.newBlockingStub(channel);
            CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(templateId)
                    .setContractId(contractId)
                    .setChoice("TransferInstruction_Accept")
                    .setChoiceArgument(ValueOuterClass.Value.newBuilder()
                            .setRecord(ValueOuterClass.Record.newBuilder().build())
                            .build())
                    .build();

            CommandsOuterClass.Commands commands = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId("accept-ti-" + UUID.randomUUID())
                    .setUserId(actAs)
                    .addActAs(actAs)
                    .addReadAs(actAs)
                    .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build())
                    .build();

            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(operatorParty, TransactionFilterOuterClass.Filters.newBuilder()
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

            logger.info("TI accept submit: requestedAs={} actAs={} contractId={} updateId={} holdingCid={}", request.asParty(), actAs, contractId, updateId, holdingCid);

            return ResponseEntity.ok(new AcceptResponse(actAs, contractId, true, true, updateId, holdingCid, null));
        } catch (Exception e) {
            logger.warn("TI accept failed: requestedAs={} actAs={} contractId={} error={}", request.asParty(), actAs, request.contractId(), e.getMessage());
            return ResponseEntity.ok(new AcceptResponse(actAs, request.contractId(), true, false, null, null, e.getMessage()));
        } finally {
            if (channel != null) channel.shutdownNow();
        }
    }

    /**
     * Accept TI using registry/scan choice-context (handles non-disclosed contracts).
     * Supports multi-registry routing: tries default, then fallbacks until one works.
     */
    @PostMapping("/accept-with-context")
    public ResponseEntity<AcceptWithContextResponse> acceptWithContext(@RequestBody AcceptRequest request) {
        var result = tiAcceptService.acceptWithContext(
                new TransferInstructionAcceptService.AcceptRequest(request.contractId(), request.asParty())
        );
        return ResponseEntity.ok(new AcceptWithContextResponse(
                result.accepted(),
                result.updateId(),
                result.holdingCid(),
                result.error(),
                result.registryUsed(),
                result.registriesAttempted()
        ));
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

    @GetMapping("/registry/ping")
    public ResponseEntity<RegistryPingResponse> pingRegistry() {
        String base = ledgerConfig.getRegistryBaseUri();
        if (base == null || base.isBlank()) {
            return ResponseEntity.ok(new RegistryPingResponse(base, false, "registryBaseUri not configured"));
        }
        RestTemplate rest = new RestTemplate();
        try {
            var resp = rest.getForEntity(base, String.class);
            boolean ok = resp.getStatusCode().is2xxSuccessful();
            return ResponseEntity.ok(new RegistryPingResponse(base, ok, ok ? null : "status=" + resp.getStatusCode()));
        } catch (Exception e) {
            return ResponseEntity.ok(new RegistryPingResponse(base, false, e.getMessage()));
        }
    }

    /**
     * Get current registry routing configuration.
     */
    @GetMapping("/registry/config")
    public ResponseEntity<RegistryConfigResponse> getRegistryConfig() {
        if (registryRouting == null) {
            // Fallback: use single registry from ledgerConfig
            String base = ledgerConfig.getRegistryBaseUri();
            return ResponseEntity.ok(new RegistryConfigResponse(base, List.of(), Map.of()));
        }
        return ResponseEntity.ok(new RegistryConfigResponse(
                registryRouting.getDefaultBaseUri(),
                registryRouting.getAllRegistries().stream()
                        .map(RegistryEndpoint::baseUri)
                        .filter(r -> registryRouting.getDefaultBaseUri() == null || !r.equals(registryRouting.getDefaultBaseUri()))
                        .toList(),
                registryRouting.getAdminMappings()
        ));
    }

    /**
     * Probe multiple registries to find which one can provide choice-context for a TI.
     * Useful for discovering the correct registry for CBTC or other tokens.
     *
     * POST /api/devnet/registry/probe
     * { "tiCid": "<contract-id>", "bases": ["https://...", "https://..."] }
     *
     * If bases is empty/null, uses all configured registries.
     */
    @PostMapping("/registry/probe")
    public ResponseEntity<RegistryProbeResponse> probeRegistries(@RequestBody RegistryProbeRequest request) {
        String tiCid = request.tiCid();
        if (tiCid == null || tiCid.isBlank()) {
            return ResponseEntity.badRequest().body(new RegistryProbeResponse(tiCid, List.of(), null));
        }

        // Determine which registries to probe
        List<RegistryEndpoint> bases;
        if (request.bases() == null || request.bases().isEmpty()) {
            bases = getRegistriesToTry(null);
        } else {
            bases = request.bases().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> new RegistryEndpoint(s, RegistryKind.SCAN, null))
                    .toList();
        }

        logger.info("Registry probe: tiCid={} probing {} registries", abbreviate(tiCid), bases.size());

        List<RegistryProbeResult> results = new ArrayList<>();
        String successfulBase = null;
        RestTemplate rest = new RestTemplate();

        for (RegistryEndpoint ep : bases) {
            RegistryProbeResult result = probeRegistry(rest, ep, tiCid);
            results.add(result);

            if (result.hasDisclosures() && successfulBase == null) {
                successfulBase = ep.baseUri();
            }

            logger.info("Registry probe: base={} status={} hasDisclosures={} disclosureCount={} contextKeys={}",
                    abbreviate(ep.baseUri()), result.httpStatus(), result.hasDisclosures(),
                    result.disclosureCount(), result.contextKeys());
        }

        return ResponseEntity.ok(new RegistryProbeResponse(tiCid, results, successfulBase));
    }

    /**
     * Probe a single registry for a TI's choice-context.
     */
    private RegistryProbeResult probeRegistry(RestTemplate rest, RegistryEndpoint ep, String tiCid) {
        try {
            String url1 = buildUrl(ep, true, tiCid);
            String url2 = buildUrl(ep, false, tiCid);

            ChoiceContextDto ctx = null;
            int httpStatus = 200;

            try {
                ctx = rest.postForObject(url1, Map.of(), ChoiceContextDto.class);
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                httpStatus = e.getStatusCode().value();
                // Try without /registry/ prefix
                try {
                    ctx = rest.postForObject(url2, Map.of(), ChoiceContextDto.class);
                    httpStatus = 200;
                } catch (org.springframework.web.client.HttpClientErrorException e2) {
                    httpStatus = e2.getStatusCode().value();
                }
            }

            if (ctx == null) {
                return new RegistryProbeResult(ep.baseUri(), httpStatus, false, 0, List.of(), "null response");
            }

            boolean hasDisclosures = ctx.disclosedContracts != null && !ctx.disclosedContracts.isEmpty();
            int disclosureCount = ctx.disclosedContracts != null ? ctx.disclosedContracts.size() : 0;
            List<String> contextKeys = ctx.choiceContextData != null && ctx.choiceContextData.values != null
                    ? new ArrayList<>(ctx.choiceContextData.values.keySet())
                    : List.of();

            return new RegistryProbeResult(ep.baseUri(), httpStatus, hasDisclosures, disclosureCount, contextKeys, null);
        } catch (Exception e) {
            return new RegistryProbeResult(ep.baseUri(), 0, false, 0, List.of(), e.getMessage());
        }
    }

    /**
     * Get list of registries to try, in order of preference.
     * Uses RegistryRoutingConfig if available, otherwise falls back to LedgerConfig.
     */
    private List<RegistryEndpoint> getRegistriesToTry(String adminPartyHint) {
        if (registryRouting != null) {
            return registryRouting.getRegistriesToTry(adminPartyHint);
        }
        // Fallback: single registry from ledgerConfig
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
            // Utilities path ignores withRegistryPrefix toggle
            return url + "/api/token-standard/v0/registrars/" + encodePathSegment(ep.admin()) + "/registry/transfer-instruction/v1/" + safeCid + "/choice-contexts/accept";
        } else if (ep.kind() == RegistryKind.LOOP_TOKEN_STANDARD_V1) {
            // Loop token-standard v1 path
            return url + "/api/v1/token-standard/transfer-instructions/" + safeCid + "/choice-contexts/accept";
        }
        // SCAN path (legacy)
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

    // Minimal DTOs for registry choice-context
    private static final class ChoiceContextDto {
        public ChoiceContextDataDto choiceContextData;
        public List<DisclosedContractDto> disclosedContracts;
    }
    private static final class ChoiceContextDataDto {
        public Map<String, ContextValueDto> values;
    }
    private static final class ContextValueDto {
        public String tag;
        public Object value;  // Can be Boolean, String (contractId), etc.
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

    private static ValueOuterClass.Identifier findTemplateId(StateServiceGrpc.StateServiceBlockingStub state, String party, String contractId) {
        TransactionFilterOuterClass.Filters wildcardFilters = TransactionFilterOuterClass.Filters.newBuilder()
                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                        .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                        .build())
                .build();
        TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                .putFiltersByParty(party, wildcardFilters)
                .setVerbose(true)
                .build();
        StateServiceOuterClass.GetActiveContractsRequest acsReq = StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                .setEventFormat(eventFormat)
                .build();
        var it = state.getActiveContracts(acsReq);
        while (it.hasNext()) {
            var resp = it.next();
            if (!resp.hasActiveContract()) continue;
            var created = resp.getActiveContract().getCreatedEvent();
            if (contractId.equals(created.getContractId())) {
                return created.getTemplateId();
            }
        }
        return null;
    }

    private static boolean isVisible(ManagedChannel channel, String party, String contractId) {
        return findTemplateId(StateServiceGrpc.newBlockingStub(channel), party, contractId) != null;
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

