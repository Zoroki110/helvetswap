package com.digitalasset.quickstart.controller;

import helvet_amm.token.token.Token;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.security.devnet.DevNetAuthService;
import com.digitalasset.quickstart.service.CbtcTransferOfferService;
import com.digitalasset.quickstart.service.CbtcTransferOfferService.CbtcTransferOfferDto;
import com.digitalasset.quickstart.service.CbtcTransferOfferService.AcceptOfferResult;
import com.digitalasset.quickstart.service.CbtcTransferOfferService.ProbeAcceptResponse;
import com.digitalasset.quickstart.service.CbtcTransferOfferService.RegistryProbeResponse;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryKind;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.ErrorMapper;
import com.digitalasset.quickstart.dto.HoldingTemplateListResponse;
import com.digitalasset.quickstart.dto.PayoutRequest;
import com.digitalasset.quickstart.dto.PayoutResponse;
import com.digitalasset.quickstart.dto.TransferInstructionListResponse;
import com.digitalasset.quickstart.dto.HoldingUtxoDto;
import com.digitalasset.quickstart.service.HoldingsService;
import com.digitalasset.quickstart.service.PayoutService;
import com.digitalasset.quickstart.config.TemplateSchemaDebugConfig;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * DevNet Testing Controller
 *
 * Provides convenience endpoints for DevNet testing and development.
 * These endpoints should NOT be available in production.
 *
 * Endpoints:
 * - POST /api/devnet/mint - Mint tokens for testing
 */
@RestController
@RequestMapping("/api/devnet")
@Profile("devnet")
@CrossOrigin(origins = "*")
public class DevNetController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetController.class);
    private static final String AMULET_ADMIN = "DSO::1220b1431ef217342db44d516bb9befde802be7d8899637d290895fa58880f19accc";
    private static final String AMULET_ID = "Amulet";
    private static final String CBTC_ADMIN = "cbtc-network::12205af3b949a04776fc48cdcc05a060f6bda2e470632935f375d1049a8546a3b262";
    private static final String CBTC_ID = "CBTC";

    @Autowired
    private LedgerApi ledgerApi;

    @Autowired
    private DevNetAuthService authService;

    @Autowired
    private CbtcTransferOfferService cbtcTransferOfferService;

    @Autowired
    private TransferInstructionAcsQueryService tiQueryService;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private HoldingsService holdingsService;

    @Autowired
    private TemplateSchemaDebugConfig schemaDebugConfig;

    /**
     * POST /api/devnet/mint
     *
     * Mint test tokens for a user. The issuer is always app-provider.
     *
     * Request body:
     * {
     *   "owner": "alice",           // Username (will be resolved to full party ID)
     *   "symbol": "ETH",            // Token symbol
     *   "amount": "10.0"            // Amount to mint
     * }
     *
     * Headers:
     * - X-User: app-provider (required - only app-provider can mint)
     *
     * Response:
     * {
     *   "success": true,
     *   "contractId": "00abc123...",
     *   "owner": "alice",
     *   "ownerPartyId": "Alice-9cefe94d::1220...",
     *   "symbol": "ETH",
     *   "amount": "10.0",
     *   "issuer": "app-provider-4f1df03a::1220..."
     * }
     */
    @PostMapping("/mint")
    @PreAuthorize("@partyGuard.isPoolParty(#jwt)")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> mintToken(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody MintTokenRequest request,
            @RequestHeader(value = "X-User", required = false) String username
    ) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Validate request
            if (request.owner == null || request.owner.isBlank()) {
                response.put("success", false);
                response.put("error", "Owner username is required");
                return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(response));
            }

            if (request.symbol == null || request.symbol.isBlank()) {
                response.put("success", false);
                response.put("error", "Token symbol is required");
                return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(response));
            }

            if (request.amount == null || request.amount.compareTo(BigDecimal.ZERO) <= 0) {
                response.put("success", false);
                response.put("error", "Amount must be positive");
                return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(response));
            }

            // Resolve issuer (app-provider from X-User header)
            String issuerUsername = username != null ? username : "app-provider";
            var issuerPartyOpt = authService.resolvePartyId(issuerUsername);
            if (issuerPartyOpt.isEmpty()) {
                response.put("success", false);
                response.put("error", "Issuer not found: " + issuerUsername);
                response.put("hint", "Add 'X-User: app-provider' header");
                return CompletableFuture.completedFuture(ResponseEntity.status(401).body(response));
            }
            String issuerPartyId = issuerPartyOpt.get();

            // Resolve owner party ID
            var ownerPartyOpt = authService.resolvePartyId(request.owner);
            if (ownerPartyOpt.isEmpty()) {
                response.put("success", false);
                response.put("error", "Owner username not found: " + request.owner);
                response.put("availableUsers", authService.getAllMappings().keySet());
                return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(response));
            }
            String ownerPartyId = ownerPartyOpt.get();

            logger.info("Minting {} {} for {} (issuer: {})", request.amount, request.symbol, request.owner, issuerUsername);

            // Create token on ledger
            Token token = new Token(
                new Party(issuerPartyId),
                new Party(ownerPartyId),
                request.symbol,
                request.amount
            );

            String commandId = "mint-" + UUID.randomUUID().toString();

            // Create token and get contract ID
            return ledgerApi.createAndGetCid(
                token,
                java.util.List.of(issuerPartyId),  // actAs
                java.util.List.of(),                 // readAs
                commandId,
                token.templateId()
            ).thenApply(tokenCid -> {
                    logger.info("✅ Minted {} {} for {}: {}", request.amount, request.symbol, request.owner, tokenCid.getContractId);
                    response.put("success", true);
                    response.put("contractId", tokenCid.getContractId);
                    response.put("owner", request.owner);
                    response.put("ownerPartyId", ownerPartyId);
                    response.put("symbol", request.symbol);
                    response.put("amount", request.amount.toPlainString());
                    response.put("issuer", issuerUsername);
                    response.put("issuerPartyId", issuerPartyId);
                    return ResponseEntity.ok(response);
                })
                .exceptionally(ex -> {
                    logger.error("Failed to mint token: {}", ex.getMessage(), ex);
                    response.put("success", false);
                    response.put("error", "Failed to mint token: " + ex.getMessage());
                    return ResponseEntity.status(500).body(response);
                });

        } catch (Exception e) {
            logger.error("Error minting token: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return CompletableFuture.completedFuture(ResponseEntity.status(500).body(response));
        }
    }

    /**
     * Request body for mint endpoint
     */
    public static class MintTokenRequest {
        public String owner;      // Username (alice, bob, etc.)
        public String symbol;     // Token symbol (ETH, USDC, etc.)
        public BigDecimal amount; // Amount to mint

        @Override
        public String toString() {
            return String.format("MintTokenRequest{owner='%s', symbol='%s', amount=%s}", owner, symbol, amount);
        }
    }

    /**
     * GET /api/devnet/cbtc/offers
     *
     * List incoming CBTC TransferOffers for a receiver party.
     * Used by the frontend to display offers that can be accepted via Loop SDK.
     *
     * Query params:
     * - receiverParty: Full party ID of the receiver (e.g., ClearportX-DEX-1::1220...)
     *
     * Response:
     * [
     *   {
     *     "contractId": "00abc123...",
     *     "sender": "cbtc-network::1220...",
     *     "receiver": "ClearportX-DEX-1::1220...",
     *     "amount": "0.1",
     *     "reason": "CBTC transfer",
     *     "executeBefore": null,
     *     "instrumentId": "CBTC",
     *     "instrumentAdmin": "cbtc-network::1220...",
     *     "rawTemplateId": "Utility.Registry.App.V0.Model.Transfer:TransferOffer"
     *   }
     * ]
     */
    @GetMapping("/cbtc/offers")
    public CompletableFuture<ResponseEntity<List<CbtcTransferOfferDto>>> getCbtcOffers(
            @RequestParam(value = "receiverParty") String receiverParty,
            @RequestHeader(value = "X-Party", required = false) String xPartyHeader,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        String requestId = "cbtc-offers-" + UUID.randomUUID().toString().substring(0, 8);

        logger.info("[DevNetController] [requestId={}] GET /cbtc/offers STARTED", requestId);
        logger.info("[DevNetController] [requestId={}]   receiverParty (query param): {}", requestId, receiverParty);
        logger.info("[DevNetController] [requestId={}]   X-Party header: {}", requestId, xPartyHeader != null ? truncateParty(xPartyHeader) : "NOT_SET");
        logger.info("[DevNetController] [requestId={}]   Authorization header: {}", requestId, authHeader != null ? "Bearer ***" : "NOT_SET");
        logger.info("[DevNetController] [requestId={}]   Party used for ACS query: {}", requestId, truncateParty(receiverParty));
        logger.info("[DevNetController] [requestId={}]   Template filter: Utility.Registry.App.V0.Model.Transfer:TransferOffer", requestId);
        logger.info("[DevNetController] [requestId={}]   Instrument filter: instrumentId=CBTC, instrumentAdmin=cbtc-network::1220...", requestId);

        if (receiverParty == null || receiverParty.isBlank()) {
            logger.warn("[DevNetController] [requestId={}] receiverParty is required", requestId);
            return CompletableFuture.completedFuture(
                    ApiSurfaceHeaders.withSurface(ResponseEntity.badRequest().build(), ApiSurfaceHeaders.DEVNET)
            );
        }

        return cbtcTransferOfferService.getIncomingOffers(receiverParty, requestId)
                .thenApply(offers -> {
                    logger.info("[DevNetController] [requestId={}] Returning {} CBTC offers for receiver", requestId, offers.size());
                    return ApiSurfaceHeaders.withSurface(ResponseEntity.ok(offers), ApiSurfaceHeaders.DEVNET);
                })
                .exceptionally(ex -> {
                    logger.error("[DevNetController] [requestId={}] Error fetching CBTC offers: {}", requestId, ex.getMessage(), ex);
                    return ApiSurfaceHeaders.withSurface(ResponseEntity.status(500).build(), ApiSurfaceHeaders.DEVNET);
                });
    }

    /**
     * Diagnostic endpoint to probe whether a TransferOffer_Accept would succeed for a given party.
     * DevNet only.
     */
    @PostMapping("/cbtc/offers/{offerCid}/probe-accept")
    public CompletableFuture<ResponseEntity<ProbeAcceptResponse>> probeAccept(
            @PathVariable("offerCid") String offerCid,
            @RequestBody(required = false) ProbeAcceptRequest body
    ) {
        String requestId = "cbtc-probe-" + UUID.randomUUID().toString().substring(0, 8);
        String actAs = body != null && body.actAsParty != null && !body.actAsParty.isBlank()
                ? body.actAsParty
                : "ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37";
        boolean dryRun = body == null || body.dryRun == null ? true : body.dryRun;

        logger.info("[DevNetController] [requestId={}] POST /cbtc/offers/{}/probe-accept START", requestId, offerCid);
        logger.info("[DevNetController] [requestId={}]   actAsParty: {}", requestId, actAs);
        logger.info("[DevNetController] [requestId={}]   dryRun: {}", requestId, dryRun);

        return cbtcTransferOfferService.probeAccept(offerCid, actAs, dryRun, requestId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("[DevNetController] [requestId={}] probe-accept failed: {}", requestId, ex.getMessage(), ex);
                    return ResponseEntity.status(500).build();
                });
    }

    public static class ProbeAcceptRequest {
        public String actAsParty;
        public Boolean dryRun;
    }

    /**
     * POST /api/devnet/cbtc/offers/{offerCid}/accept
     * Accept CBTC TransferInstruction via registry choice-context using backend operator party.
     */
    @PostMapping("/cbtc/offers/{offerCid}/accept")
    @PreAuthorize("@partyGuard.isPoolParty(#jwt)")
    public CompletionStage<ResponseEntity<AcceptOfferResult>> acceptCbtcOffer(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("offerCid") String offerCid,
            @RequestBody(required = false) CbtcAcceptRequest body
    ) {
        String requestId = "cbtc-accept-" + UUID.randomUUID().toString().substring(0, 8);
        String actAs = body != null && body.actAsParty != null && !body.actAsParty.isBlank()
                ? body.actAsParty
                : "ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37";

        logger.info("[DevNetController] [requestId={}] POST /cbtc/offers/{}/accept START", requestId, offerCid);
        logger.info("[DevNetController] [requestId={}]   actAsParty: {}", requestId, actAs);

        return cbtcTransferOfferService.acceptOffer(offerCid, actAs, requestId)
                .thenApply(result -> ApiSurfaceHeaders.withSurface(ResponseEntity.ok(result), ApiSurfaceHeaders.DEVNET))
                .exceptionally(ex -> {
                    logger.error("[DevNetController] [requestId={}] accept failed: {}", requestId, ex.getMessage(), ex);
                    return ApiSurfaceHeaders.withSurface(ResponseEntity.status(500).build(), ApiSurfaceHeaders.DEVNET);
                });
    }

    public static class CbtcAcceptRequest {
        public String actAsParty;
    }

    /**
     * POST /api/devnet/cbtc/registry/probe
     * DevNet-only: probe registry choice-context endpoint for CBTC (or other) by admin/tiCid/offerCid.
     */
    @PostMapping("/cbtc/registry/probe")
    public CompletionStage<ResponseEntity<RegistryProbeResponse>> probeCbtcRegistry(
            @RequestBody RegistryProbeRequest body
    ) {
        String requestId = "cbtc-registry-probe-" + UUID.randomUUID().toString().substring(0, 8);
        String admin = body.adminParty;
        String tiCid = body.tiCid;
        String offerCid = body.offerCid;
        String baseOverride = body.baseUriOverride;
        RegistryKind kind = body.kind != null ? RegistryKind.from(body.kind) : null;

        logger.info("[DevNetController] [requestId={}] POST /cbtc/registry/probe admin={} tiCid={} offerCid={} baseOverride={} kind={}",
                requestId, admin, tiCid, offerCid, baseOverride, kind);

        return cbtcTransferOfferService.probeRegistry(admin, tiCid, offerCid, baseOverride, kind, requestId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("[DevNetController] [requestId={}] registry probe failed: {}", requestId, ex.getMessage(), ex);
                    return ResponseEntity.status(500).build();
                });
    }

    public static class RegistryProbeRequest {
        public String adminParty;
        public String tiCid;
        public String offerCid;
        public String baseUriOverride;
        public String kind;
    }

    /**
     * GET /api/devnet/transfer-instructions/raw-acs
     * Debug: dump all ACS entries for receiver (cid, package, module, entity).
     */
    @GetMapping("/transfer-instructions/raw-acs")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> dumpTiAcs(
            @RequestParam("receiverParty") String receiverParty
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> body = new HashMap<>();
            try {
                var acs = ledgerApi.getActiveContractsRawForParty(receiverParty).toCompletableFuture().join();
                var items = acs.stream()
                        .map(c -> Map.of(
                                "cid", c.contractId(),
                                "packageId", c.templateId().getPackageId(),
                                "moduleName", c.templateId().getModuleName(),
                                "entityName", c.templateId().getEntityName()
                        ))
                        .toList();
                body.put("ok", true);
                body.put("count", items.size());
                body.put("items", items);
                return ResponseEntity.ok(body);
            } catch (Exception e) {
                body.put("ok", false);
                body.put("error", e.getMessage());
                return ResponseEntity.internalServerError().body(body);
            }
        });
    }

    /**
     * GET /api/devnet/transfer-instructions/debug
     * Debug: list parsed TI-like entries for receiver (all instruments).
     */
    @GetMapping("/transfer-instructions/debug")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> debugParsedTis(
            @RequestParam("receiverParty") String receiverParty
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> body = new HashMap<>();
            var res = tiQueryService.listForReceiver(receiverParty);
            if (!res.isOk()) {
                body.put("ok", false);
                body.put("error", res.getErrorUnsafe().message());
                return ResponseEntity.internalServerError().body(body);
            }
            List<TransferInstructionDto> items = res.getValueUnsafe();
            body.put("ok", true);
            body.put("count", items.size());
            body.put("items", items);
            return ResponseEntity.ok(body);
        });
    }

    /**
     * GET /api/devnet/transfer-instructions/inspect
     * Inspect a single contract visible to receiverParty, dumping field labels/types.
     */
    @GetMapping("/transfer-instructions/inspect")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> inspectTi(
            @RequestParam("receiverParty") String receiverParty,
            @RequestParam("cid") String contractId
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> body = new HashMap<>();
            try {
                var acs = ledgerApi.getActiveContractsRawForParty(receiverParty).toCompletableFuture().join();
                var match = acs.stream().filter(c -> contractId.equals(c.contractId())).findFirst();
                if (match.isEmpty()) {
                    body.put("ok", false);
                    body.put("error", "not found in ACS");
                    return ResponseEntity.ok(body);
                }
                var rac = match.get();
                Map<String, Object> info = new HashMap<>();
                info.put("cid", rac.contractId());
                info.put("packageId", rac.templateId().getPackageId());
                info.put("moduleName", rac.templateId().getModuleName());
                info.put("entityName", rac.templateId().getEntityName());
                List<Map<String, Object>> fields = new ArrayList<>();
                var args = rac.createArguments();
                if (args != null) {
                    for (int i = 0; i < args.getFieldsCount(); i++) {
                        var f = args.getFields(i);
                        Map<String, Object> fm = new HashMap<>();
                        fm.put("index", i);
                        fm.put("label", f.getLabel());
                        fm.put("type", f.getValue().getSumCase().name());
                        fm.put("value", summarizeValue(f.getValue()));
                        fields.add(fm);
                    }
                }
                info.put("fields", fields);
                body.put("ok", true);
                body.put("contract", info);
                return ResponseEntity.ok(body);
            } catch (Exception e) {
                body.put("ok", false);
                body.put("error", e.getMessage());
                return ResponseEntity.internalServerError().body(body);
            }
        });
    }

    private Object summarizeValue(com.daml.ledger.api.v2.ValueOuterClass.Value v) {
        return switch (v.getSumCase()) {
            case PARTY -> v.getParty();
            case TEXT -> v.getText();
            case NUMERIC -> v.getNumeric();
            case TIMESTAMP -> v.getTimestamp();
            case CONTRACT_ID -> v.getContractId();
            case RECORD -> {
                List<Map<String, Object>> nested = new ArrayList<>();
                var r = v.getRecord();
                for (int i = 0; i < r.getFieldsCount(); i++) {
                    var nf = r.getFields(i);
                    Map<String, Object> m = new HashMap<>();
                    m.put("index", i);
                    m.put("label", nf.getLabel());
                    m.put("type", nf.getValue().getSumCase().name());
                    m.put("value", summarizeValue(nf.getValue()));
                    nested.add(m);
                }
                yield nested;
            }
            case LIST -> v.getList().getElementsList().stream().map(this::summarizeValue).toList();
            default -> v.getSumCase().name();
        };
    }

    /**
     * GET /api/devnet/transfer-instructions/pending
     * Debug: list pending TransferInstructions for a receiver/instrument.
     */
    @GetMapping("/transfer-instructions/pending")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> listPendingTis(
            @RequestParam("receiverParty") String receiverParty,
            @RequestParam("instrumentAdmin") String instrumentAdmin,
            @RequestParam("instrumentId") String instrumentId) {
        Map<String, Object> body = new HashMap<>();
        return CompletableFuture.supplyAsync(() -> {
            var res = tiQueryService.listForReceiver(receiverParty);
            if (!res.isOk()) {
                body.put("ok", false);
                body.put("error", res.getErrorUnsafe().message());
                return ResponseEntity.internalServerError().body(body);
            }
            List<TransferInstructionDto> filtered = res.getValueUnsafe().stream()
                    .filter(ti -> instrumentAdmin.equals(ti.admin()) && instrumentId.equals(ti.instrumentId()))
                    .filter(ti -> receiverParty.equals(ti.receiver()))
                    .sorted(Comparator.comparing(TransferInstructionDto::executeBefore).reversed())
                    .toList();
            body.put("ok", true);
            body.put("count", filtered.size());
            body.put("items", filtered);
            return ResponseEntity.ok(body);
        });
    }

    /**
     * GET /api/devnet/holdings/templates
     * List template IDs for holdings visible to ownerParty (optionally filter by instrument).
     */
    @GetMapping("/holdings/templates")
    public CompletableFuture<ResponseEntity<ApiResponse<HoldingTemplateListResponse>>> listHoldingTemplates(
            @RequestParam("ownerParty") String ownerParty,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "instrumentAdmin", required = false) String instrumentAdmin,
            HttpServletRequest httpRequest
    ) {
        String requestId = newRequestId("holding-templates", httpRequest);
        if (!schemaDebugConfig.isEnabled()) {
            return CompletableFuture.completedFuture(
                    respond(requestId, "holdings/templates", Result.err(new ApiError(
                            ErrorCode.PRECONDITION_FAILED,
                            "Holdings template debug is disabled",
                            Map.of("flag", "feature.enable-template-schema-debug"),
                            false,
                            null,
                            null
                    )))
            );
        }
        if (ownerParty == null || ownerParty.isBlank()) {
            return CompletableFuture.completedFuture(
                    respond(requestId, "holdings/templates", Result.err(validationError("ownerParty is required", "ownerParty")))
            );
        }

        CompletableFuture<Result<List<HoldingUtxoDto>, DomainError>> holdingsFuture =
                holdingsService.getHoldingUtxos(ownerParty);
        CompletableFuture<List<LedgerApi.RawActiveContract>> acsFuture =
                ledgerApi.getActiveContractsRawForParty(ownerParty);

        return holdingsFuture.thenCombine(acsFuture, (holdingsResult, acs) -> {
            if (holdingsResult.isErr()) {
                return respond(requestId, "holdings/templates", Result.err(domainError("Holdings query failed", holdingsResult.getErrorUnsafe())));
            }
            Map<String, LedgerApi.RawActiveContract> byCid = acs.stream()
                    .collect(Collectors.toMap(LedgerApi.RawActiveContract::contractId, c -> c, (a, b) -> a));

            List<HoldingTemplateListResponse.HoldingTemplateInfo> items = holdingsResult.getValueUnsafe().stream()
                    .filter(h -> instrumentId == null || instrumentId.equals(h.instrumentId))
                    .filter(h -> instrumentAdmin == null || instrumentAdmin.equals(h.instrumentAdmin))
                    .map(h -> {
                        LedgerApi.RawActiveContract raw = byCid.get(h.contractId);
                        String pkg = raw != null ? raw.templateId().getPackageId() : null;
                        String mod = raw != null ? raw.templateId().getModuleName() : null;
                        String ent = raw != null ? raw.templateId().getEntityName() : null;
                        return new HoldingTemplateListResponse.HoldingTemplateInfo(
                                h.contractId,
                                h.instrumentAdmin,
                                h.instrumentId,
                                pkg,
                                mod,
                                ent
                        );
                    })
                    .collect(Collectors.toList());
            return respond(requestId, "holdings/templates",
                    Result.ok(new HoldingTemplateListResponse(items.size(), items)));
        });
    }

    /**
     * GET /api/devnet/transfer-instructions/outgoing
     * List outgoing (sender-side) TIs/offers for a party and instrument.
     */
    @GetMapping("/transfer-instructions/outgoing")
    public ResponseEntity<ApiResponse<TransferInstructionListResponse>> listOutgoing(
            @RequestParam("senderParty") String senderParty,
            @RequestParam("instrumentAdmin") String instrumentAdmin,
            @RequestParam("instrumentId") String instrumentId,
            HttpServletRequest httpRequest
    ) {
        String requestId = newRequestId("ti-outgoing", httpRequest);
        logger.info("[DevNetController] [requestId={}] GET /transfer-instructions/outgoing senderParty={} admin={} instrumentId={}",
                requestId, truncateParty(senderParty), truncateParty(instrumentAdmin), instrumentId);

        Result<TransferInstructionListResponse, ApiError> result = validateOutgoingParams(senderParty, instrumentAdmin, instrumentId)
                .flatMap(valid -> {
                    var res = tiQueryService.listOutgoingForSender(senderParty);
                    if (res.isErr()) {
                        return Result.err(domainError("Failed to query outgoing TIs", res.getErrorUnsafe()));
                    }
                    List<TransferInstructionDto> filtered = res.getValueUnsafe().stream()
                            .filter(ti -> instrumentAdmin.equals(ti.admin()) && instrumentId.equals(ti.instrumentId()))
                            .filter(ti -> senderParty.equals(ti.sender()))
                            .toList();
                    return Result.ok(new TransferInstructionListResponse(filtered.size(), filtered));
                });

        return respond(requestId, "transfer-instructions/outgoing", result);
    }

    /**
     * GET /api/devnet/transfer-instructions/by-cid
     * Inspect a TI/Offer visible to a party by contractId.
     */
    @GetMapping("/transfer-instructions/by-cid")
    public ResponseEntity<ApiResponse<TransferInstructionDto>> getTiByCid(
            @RequestParam("party") String party,
            @RequestParam("cid") String cid,
            HttpServletRequest httpRequest
    ) {
        String requestId = newRequestId("ti-by-cid", httpRequest);
        logger.info("[DevNetController] [requestId={}] GET /transfer-instructions/by-cid party={} cid={}",
                requestId, truncateParty(party), truncateCid(cid));

        Result<TransferInstructionDto, ApiError> result = validateByCidParams(party, cid)
                .flatMap(valid -> tiQueryService.findByCid(party, cid)
                        .map(dto -> Result.<TransferInstructionDto, ApiError>ok(dto))
                        .orElseGet(() -> Result.err(new ApiError(
                                ErrorCode.NOT_FOUND,
                                "TransferInstruction not found in ACS for party",
                                Map.of("party", party, "cid", cid),
                                false,
                                null,
                                null
                        ))));

        return respond(requestId, "transfer-instructions/by-cid", result);
    }

    /**
     * GET /api/devnet/bootstrap-tis
     * Finds the pending inbound Amulet/CBTC TransferInstructions for the operator to use in bootstrap.
     */
    @GetMapping("/bootstrap-tis")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getBootstrapTis(
            @RequestParam("receiverParty") String receiverParty,
            @RequestParam("amuletAmount") String amuletAmount,
            @RequestParam("cbtcAmount") String cbtcAmount,
            @RequestParam(value = "maxAgeSeconds", defaultValue = "7200") long maxAgeSeconds
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> body = new HashMap<>();
            try {
                BigDecimal targetA = normalizeAmount(amuletAmount);
                BigDecimal targetB = normalizeAmount(cbtcAmount);

                var res = tiQueryService.listForReceiver(receiverParty);
                if (!res.isOk()) {
                    body.put("ok", false);
                    body.put("error", res.getErrorUnsafe().message());
                    return ResponseEntity.internalServerError().body(body);
                }
                List<TransferInstructionDto> all = res.getValueUnsafe();
                List<TransferInstructionDto> amuletCandidates = all.stream()
                        .filter(ti -> AMULET_ADMIN.equals(ti.admin()) && AMULET_ID.equals(ti.instrumentId()))
                        .filter(ti -> receiverParty.equals(ti.receiver()))
                        .filter(ti -> targetA.compareTo(normalizeAmount(ti.amount())) == 0)
                        .sorted(Comparator.comparing(TransferInstructionDto::executeBefore).reversed())
                        .toList();
                List<TransferInstructionDto> cbtcCandidates = all.stream()
                        .filter(ti -> CBTC_ADMIN.equals(ti.admin()) && CBTC_ID.equals(ti.instrumentId()))
                        .filter(ti -> receiverParty.equals(ti.receiver()))
                        .filter(ti -> targetB.compareTo(normalizeAmount(ti.amount())) == 0)
                        .sorted(Comparator.comparing(TransferInstructionDto::executeBefore).reversed())
                        .toList();

                Optional<TransferInstructionDto> chosenAmulet = tiQueryService.selectBest(amuletCandidates);
                Optional<TransferInstructionDto> chosenCbtc = tiQueryService.selectBest(cbtcCandidates);

                body.put("ok", chosenAmulet.isPresent() && chosenCbtc.isPresent());
                body.put("receiverParty", receiverParty);
                body.put("amulet", chosenAmulet.map(this::toMap).orElse(Map.of()));
                body.put("cbtc", chosenCbtc.map(this::toMap).orElse(Map.of()));
                Map<String, Object> cand = new HashMap<>();
                cand.put("amulet", amuletCandidates);
                cand.put("cbtc", cbtcCandidates);
                body.put("candidates", cand);

                chosenAmulet.ifPresent(ti -> logger.info("[bootstrap-tis] chosen amulet ti={} amount={} sender={} execBefore={}",
                        ti.contractId(), ti.amount(), ti.sender(), ti.executeBefore()));
                chosenCbtc.ifPresent(ti -> logger.info("[bootstrap-tis] chosen cbtc ti={} amount={} sender={} execBefore={}",
                        ti.contractId(), ti.amount(), ti.sender(), ti.executeBefore()));

                return ResponseEntity.ok(body);
            } catch (Exception e) {
                body.put("ok", false);
                body.put("error", e.getMessage());
                return ResponseEntity.internalServerError().body(body);
            }
        });
    }

    /**
     * POST /api/devnet/payout/amulet
     * Create outbound Amulet TransferInstruction (operator -> receiver).
     */
    @PostMapping("/payout/amulet")
    @PreAuthorize("@partyGuard.isPoolParty(#jwt)")
    public ResponseEntity<ApiResponse<PayoutResponse>> payoutAmulet(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PayoutRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = newRequestId("payout-amulet", httpRequest);
        logger.info("[DevNetController] [requestId={}] POST /payout/amulet receiverParty={} amount={}",
                requestId, request != null ? truncateParty(request.receiverParty) : "null", request != null ? request.amount : "null");
        Result<PayoutResponse, ApiError> result = payoutService.createAmuletPayout(request, requestId);
        return respond(requestId, "payout/amulet", result);
    }

    /**
     * POST /api/devnet/payout/cbtc
     * Create outbound CBTC TransferOffer (operator -> receiver).
     */
    @PostMapping("/payout/cbtc")
    @PreAuthorize("@partyGuard.isPoolParty(#jwt)")
    public ResponseEntity<ApiResponse<PayoutResponse>> payoutCbtc(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PayoutRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = newRequestId("payout-cbtc", httpRequest);
        logger.info("[DevNetController] [requestId={}] POST /payout/cbtc receiverParty={} amount={}",
                requestId, request != null ? truncateParty(request.receiverParty) : "null", request != null ? request.amount : "null");
        Result<PayoutResponse, ApiError> result = payoutService.createCbtcPayout(request, requestId);
        return respond(requestId, "payout/cbtc", result);
    }

    private String newRequestId(String prefix, HttpServletRequest request) {
        String requestId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        if (request != null) {
            request.setAttribute("requestId", requestId);
        }
        return requestId;
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(String requestId, String operation, Result<T, ApiError> result) {
        ResponseEntity<ApiResponse<T>> response;
        if (result.isOk()) {
            response = ResponseEntity.ok(ApiResponse.success(requestId, result.getValueUnsafe()));
        } else {
            ApiError error = result.getErrorUnsafe();
            logFailure(requestId, operation, error);
            response = ResponseEntity.status(ErrorMapper.toHttpStatus(error.code))
                    .body(ApiResponse.failure(requestId, error));
        }
        return ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.DEVNET);
    }

    private void logFailure(String requestId, String operation, ApiError error) {
        logger.error("[DevNetController] [requestId={}] {} failed code={} message={} details={}",
                requestId, operation, error.code, error.message, error.details);
    }

    private Result<Void, ApiError> validateOutgoingParams(String senderParty, String instrumentAdmin, String instrumentId) {
        if (senderParty == null || senderParty.isBlank()) {
            return Result.err(validationError("senderParty is required", "senderParty"));
        }
        if (instrumentAdmin == null || instrumentAdmin.isBlank()) {
            return Result.err(validationError("instrumentAdmin is required", "instrumentAdmin"));
        }
        if (instrumentId == null || instrumentId.isBlank()) {
            return Result.err(validationError("instrumentId is required", "instrumentId"));
        }
        return Result.ok(null);
    }

    private Result<Void, ApiError> validateByCidParams(String party, String cid) {
        if (party == null || party.isBlank()) {
            return Result.err(validationError("party is required", "party"));
        }
        if (cid == null || cid.isBlank()) {
            return Result.err(validationError("cid is required", "cid"));
        }
        return Result.ok(null);
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

    private ApiError domainError(String message, DomainError err) {
        return new ApiError(
                ErrorCode.INTERNAL,
                message,
                Map.of("domainCode", err.code(), "domainMessage", err.message()),
                false,
                null,
                null
        );
    }

    private Map<String, Object> toMap(TransferInstructionDto ti) {
        Map<String, Object> m = new HashMap<>();
        m.put("cid", ti.contractId());
        m.put("sender", ti.sender());
        m.put("receiver", ti.receiver());
        m.put("amount", ti.amount());
        m.put("executeBefore", ti.executeBefore().toString());
        m.put("instrumentAdmin", ti.admin());
        m.put("instrumentId", ti.instrumentId());
        return m;
    }

    private BigDecimal normalizeAmount(String v) {
        return new BigDecimal(v).setScale(10, RoundingMode.UNNECESSARY);
    }

    private String truncateCid(String cid) {
        if (cid == null || cid.length() <= 16) return cid;
        return cid.substring(0, 12) + "...";
    }

    private String truncateParty(String party) {
        if (party == null || party.length() <= 30) return party;
        int sep = party.indexOf("::");
        if (sep > 0 && sep < 20) {
            return party.substring(0, sep + 10) + "...";
        }
        return party.substring(0, 20) + "...";
    }
}
