package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.ValueOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass.Optional;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryEndpoint;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.digitalasset.quickstart.ledger.LedgerApi;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Service for querying incoming CBTC TransferOffer contracts.
 *
 * CBTC transfers use Utility.Registry.App.V0.Model.Transfer:TransferOffer template.
 * These offers are visible to the receiver party but the underlying TransferInstruction
 * is NOT disclosed - hence backend cannot accept them (only Loop SDK can).
 *
 * This service queries the ledger ACS directly (not scan API) to find TransferOffers
 * where the receiver matches the specified party.
 */
@Service
public class CbtcTransferOfferService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CbtcTransferOfferService.class);

    // CBTC TransferOffer template identifier
    private static final String TRANSFER_OFFER_MODULE = "Utility.Registry.App.V0.Model.Transfer";
    private static final String TRANSFER_OFFER_ENTITY = "TransferOffer";

    // Known CBTC instrument admin on DevNet
    private static final String CBTC_INSTRUMENT_ADMIN = "cbtc-network::12205af3b949a04776fc48cdcc05a060f6bda2e470632935f375d1049a8546a3b262";
    private static final String CBTC_INSTRUMENT_ID = "CBTC";

    private final LedgerApi ledgerApi;
    private final LedgerConfig ledgerConfig;
    private final RegistryRoutingConfig registryRouting;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public CbtcTransferOfferService(final LedgerApi ledgerApi,
                                    final LedgerConfig ledgerConfig,
                                    final RegistryRoutingConfig registryRouting) {
        this.ledgerApi = ledgerApi;
        this.ledgerConfig = ledgerConfig;
        this.registryRouting = registryRouting;
    }

    public record ProbeAcceptResponse(
            String requestId,
            String offerCid,
            Attempt attempt,
            Result result
    ) {
        public record Attempt(
                String templateId,
                String choice,
                String actAsParty,
                Map<String, Object> arguments
        ) { }

        public record Result(
                boolean ok,
                String classification,
                String rawError,
                String hint
        ) { }
    }

    /**
     * DTO for CBTC TransferOffer
     */
    public record CbtcTransferOfferDto(
            String contractId,
            String sender,
            String receiver,
            BigDecimal amount,
            String reason,
            String executeBefore,
            String instrumentId,
            String instrumentAdmin,
            String rawTemplateId,
            String transferInstructionId,
            String packageId
    ) {}

    public record AcceptOfferResult(
            String requestId,
            String offerCid,
            String transferInstructionId,
            String actAsParty,
            boolean ok,
            String classification,
            String rawError,
            String hint,
            String ledgerUpdateId
    ) {}

    public record RegistryProbeAttempt(
            String cidTried,
            String url,
            int status,
            Map<String, String> headers,
            String bodySnippet,
            Integer disclosedCount,
            Integer contextKeysCount
    ) {}

    public record RegistryProbeResponse(
            String requestId,
            boolean ok,
            String classification,
            String hint,
            String rawError,
            List<RegistryProbeAttempt> attempts
    ) {}

    private record RegistryFetchResult(
            ChoiceContextDto ctx,
            String url,
            Integer status,
            String bodySnippet
    ) {}

    /**
     * Get all incoming CBTC TransferOffers for a receiver party.
     *
     * @param receiverParty The party receiving the CBTC offers
     * @param requestId Correlation ID for logging
     * @return List of TransferOffer DTOs
     */
    @WithSpan
    public CompletableFuture<List<CbtcTransferOfferDto>> getIncomingOffers(final String receiverParty, final String requestId) {
        LOGGER.info("[CbtcTransferOfferService] [requestId={}] Querying incoming offers", requestId);
        LOGGER.info("[CbtcTransferOfferService] [requestId={}]   partyUsedForLedgerQuery: {}", requestId, receiverParty);

        return ledgerApi.getActiveContractsRawForParty(receiverParty)
                .thenApply(contracts -> {
                    List<CbtcTransferOfferDto> offers = new ArrayList<>();

                    int totalAcsCount = contracts.size();
                    LOGGER.info("[CbtcTransferOfferService] [requestId={}] Raw ACS returned {} contracts BEFORE any filter", requestId, totalAcsCount);

                    // Log first 3 sample contracts for debugging
                    int sampleCount = Math.min(3, contracts.size());
                    for (int i = 0; i < sampleCount; i++) {
                        LedgerApi.RawActiveContract sample = contracts.get(i);
                        ValueOuterClass.Identifier tid = sample.templateId();
                        String fullTemplateId = tid.getModuleName() + ":" + tid.getEntityName();

                        // Try to parse sender/receiver if possible
                        String sampleReceiver = "N/A";
                        String sampleSender = "N/A";
                        try {
                            sampleReceiver = getPartyField(sample.createArguments(), "receiver", 1);
                            sampleSender = getPartyField(sample.createArguments(), "sender", 0);
                        } catch (Exception ignored) {}

                        LOGGER.info("[CbtcTransferOfferService] [requestId={}] Sample ACS[{}]: contractId={}, templateId={}, sender={}, receiver={}",
                                requestId, i, truncateCid(sample.contractId()), fullTemplateId,
                                truncateParty(sampleSender), truncateParty(sampleReceiver));
                    }

                    int afterTemplateFilterCount = 0;
                    int afterReceiverFilterCount = 0;
                    int afterInstrumentFilterCount = 0;

                    for (LedgerApi.RawActiveContract contract : contracts) {
                        ValueOuterClass.Identifier templateId = contract.templateId();
                        String fullTemplateId = templateId.getModuleName() + ":" + templateId.getEntityName();

                        // Check if this is a TransferOffer
                        if (!TRANSFER_OFFER_MODULE.equals(templateId.getModuleName()) ||
                            !TRANSFER_OFFER_ENTITY.equals(templateId.getEntityName())) {
                            continue;
                        }
                        afterTemplateFilterCount++;

                        LOGGER.debug("[CbtcTransferOfferService] [requestId={}] Found TransferOffer: cid={}", requestId, contract.contractId());

                        // Parse the TransferOffer create arguments
                        CbtcTransferOfferDto dto = parseTransferOffer(contract);
                        if (dto == null) {
                            continue;
                        }
                        afterReceiverFilterCount++;

                        // Filter for CBTC only (optional - can remove to show all transfer offers)
                        if (!CBTC_INSTRUMENT_ID.equals(dto.instrumentId())) {
                            LOGGER.debug("[CbtcTransferOfferService] [requestId={}] Skipping offer - not CBTC: instrumentId={}",
                                    requestId, dto.instrumentId());
                            continue;
                        }
                        afterInstrumentFilterCount++;

                        // NOTE: We don't filter by receiver field because:
                        // 1. The ACS query already ensures this party has visibility (stakeholder/observer rights)
                        // 2. The receiver field in TransferOffer may represent the instrument admin, not the destination
                        // 3. The Loop SDK will handle acceptance based on the party's stakeholder rights
                        LOGGER.debug("[CbtcTransferOfferService] [requestId={}] TransferOffer receiver field: {}, but party has visibility via ACS",
                                requestId, truncateParty(dto.receiver()));

                        LOGGER.info("[CbtcTransferOfferService] [requestId={}] Found CBTC offer: cid={}, amount={}, sender={}, instrumentId={}, instrumentAdmin={}",
                                requestId, truncateCid(dto.contractId()), dto.amount(), truncateParty(dto.sender()),
                                dto.instrumentId(), truncateParty(dto.instrumentAdmin()));

                        offers.add(dto);
                    }

                    LOGGER.info("[CbtcTransferOfferService] [requestId={}] Filter results:", requestId);
                    LOGGER.info("[CbtcTransferOfferService] [requestId={}]   Total ACS contracts: {}", requestId, totalAcsCount);
                    LOGGER.info("[CbtcTransferOfferService] [requestId={}]   After templateId filter (Utility.Registry.App.V0.Model.Transfer:TransferOffer): {}", requestId, afterTemplateFilterCount);
                    LOGGER.info("[CbtcTransferOfferService] [requestId={}]   After parse (successfully parsed TransferOffer): {}", requestId, afterReceiverFilterCount);
                    LOGGER.info("[CbtcTransferOfferService] [requestId={}]   After instrument filter (instrumentId=CBTC): {}", requestId, afterInstrumentFilterCount);
                    LOGGER.info("[CbtcTransferOfferService] [requestId={}] FINAL: Returning {} CBTC offers", requestId, offers.size());

                    return offers;
                })
                .exceptionally(ex -> {
                    LOGGER.error("[CbtcTransferOfferService] [requestId={}] Error querying offers: {}", requestId, ex.getMessage(), ex);
                    return List.of();
                });
    }

    /**
     * Accept a CBTC TransferInstruction referenced by the TransferOffer using registry choice-context.
     */
    @WithSpan
    public CompletionStage<AcceptOfferResult> acceptOffer(
            final String offerCid,
            final String actAsParty,
            final String requestId
    ) {
        LOGGER.info("[CbtcTransferOfferService] [requestId={}] acceptOffer START offerCid={} actAs={}", requestId, offerCid, actAsParty);

        return ledgerApi.getActiveContractsRawForParty(actAsParty)
                .thenCompose(acs -> {
                    LedgerApi.RawActiveContract matched = acs.stream()
                            .filter(c -> offerCid.equals(c.contractId()))
                            .findFirst()
                            .orElse(null);

                    if (matched == null) {
                        String msg = "TransferOffer not visible to actAs party";
                        return CompletableFuture.completedFuture(new AcceptOfferResult(
                                requestId, offerCid, null, actAsParty, false, "DISCLOSURE", msg,
                                "Offer not found in ACS", null
                        ));
                    }

                    CbtcTransferOfferDto dto = parseTransferOffer(matched);
                    if (dto == null || dto.transferInstructionId == null || dto.transferInstructionId.isBlank()) {
                        String msg = "transferInstructionId missing from TransferOffer";
                        return CompletableFuture.completedFuture(new AcceptOfferResult(
                                requestId, offerCid, null, actAsParty, false, "CHOICE_CONTEXT", msg,
                                "Offer does not embed TransferInstruction id", null
                        ));
                    }

                    List<RegistryEndpoint> registries = getRegistriesToTry(dto.instrumentAdmin());
                    if (registries.isEmpty()) {
                        String msg = "registryBaseUri not configured";
                        return CompletableFuture.completedFuture(new AcceptOfferResult(
                                requestId, offerCid, dto.transferInstructionId, actAsParty, false, "CHOICE_CONTEXT", msg,
                                "Configure LEDGER_REGISTRY_BASE_URI", null
                        ));
                    }

                    RegistryFetchResult ctxResult = fetchChoiceContextForOffer(registries, offerCid, requestId);
                    ChoiceContextDto ctx = ctxResult != null ? ctxResult.ctx : null;

                    if (ctx == null || ctx.disclosedContracts == null || ctx.disclosedContracts.isEmpty()) {
                        String snippet = ctxResult != null ? ctxResult.bodySnippet : null;
                        Integer status = ctxResult != null ? ctxResult.status : null;
                        String url = ctxResult != null ? abbreviate(ctxResult.url) : null;
                        LOGGER.warn("[CbtcTransferOfferService] [requestId={}] choice-context missing disclosures url={} status={} bodySnippet={}",
                                requestId, url, status, snippet);
                        String msg = "choice-context missing disclosures";
                        return CompletableFuture.completedFuture(new AcceptOfferResult(
                                requestId, offerCid, dto.transferInstructionId, actAsParty, false, "REGISTRY_EMPTY", msg,
                                "Registry did not return disclosures", null
                        ));
                    }

                    List<CommandsOuterClass.DisclosedContract> disclosed = ctx.disclosedContracts.stream()
                            .map(this::toDisclosedContract)
                            .filter(dc -> dc != null)
                            .toList();
                    if (disclosed.isEmpty()) {
                        String msg = "no valid disclosed contracts";
                        return CompletableFuture.completedFuture(new AcceptOfferResult(
                                requestId, offerCid, dto.transferInstructionId, actAsParty, false, "REGISTRY_EMPTY", msg,
                                "Registry disclosures invalid", null
                        ));
                    }

                // Use interface exercise on the TransferOffer CID (registry endpoint uses OFFER CID).
                final String tiToAccept = dto.contractId();
                ValueOuterClass.Record choiceArg = buildChoiceArgument(ctx);
                ValueOuterClass.Identifier templateId = ValueOuterClass.Identifier.newBuilder()
                        .setPackageId("#splice-api-token-transfer-instruction-v1")
                        .setModuleName("Splice.Api.Token.TransferInstructionV1")
                        .setEntityName("TransferInstruction")
                        .build();
                String choiceName = "TransferInstruction_Accept";

                return ledgerApi.exerciseRaw(
                                templateId,
                                tiToAccept,
                                choiceName,
                                choiceArg,
                                List.of(actAsParty),
                                List.of(),
                                disclosed
                        )
                            .thenApply(resp -> {
                                String updateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : null;
                                LOGGER.info("[CbtcTransferOfferService] [requestId={}] acceptOffer SUCCESS updateId={} tiCid={}",
                                        requestId, updateId, truncateCid(tiToAccept));
                                return new AcceptOfferResult(
                                        requestId, offerCid, tiToAccept, actAsParty, true,
                                        "OK", null, null, updateId
                                );
                            })
                            .exceptionally(ex -> classifyAcceptError(requestId, offerCid, tiToAccept, actAsParty, ex));
                });
    }

    /**
    * Probe exercising TransferOffer_Accept to classify failure causes (auth/disclosure/schema).
    */
    @WithSpan
    public CompletableFuture<ProbeAcceptResponse> probeAccept(
            final String offerCid,
            final String actAsParty,
            final boolean dryRun,
            final String requestId
    ) {
        LOGGER.info("[CbtcTransferOfferService] [requestId={}] probeAccept START offerCid={}", requestId, offerCid);

        return ledgerApi.getActiveContractsRawForParty(actAsParty)
                .thenCompose(acs -> {
                    ValueOuterClass.Identifier tmpId = null;
                    for (var c : acs) {
                        if (offerCid.equals(c.contractId())) {
                            tmpId = c.templateId();
                            break;
                        }
                    }
                    final ValueOuterClass.Identifier templateId = tmpId != null
                            ? tmpId
                            : ValueOuterClass.Identifier.newBuilder()
                                .setPackageId("")
                                .setModuleName(TRANSFER_OFFER_MODULE)
                                .setEntityName(TRANSFER_OFFER_ENTITY)
                                .build();

                    LOGGER.info("[CbtcTransferOfferService] [requestId={}] probeAccept templateId={}:{} actAs={}",
                            requestId, templateId.getModuleName(), templateId.getEntityName(), actAsParty);

                    ValueOuterClass.Record emptyArgs = ValueOuterClass.Record.newBuilder().build();

                    // No dry-run mechanism available; we submit real exercise.
                    final ValueOuterClass.Identifier finalTid = templateId;
                    return ledgerApi.exerciseRaw(
                                    templateId,
                                    offerCid,
                                    "TransferOffer_Accept",
                                    emptyArgs,
                                    List.of(actAsParty),
                                    List.of(),
                                    List.of()
                            )
                            .thenApply(txnResp -> classifySuccess(requestId, offerCid, finalTid, actAsParty))
                            .exceptionally(ex -> classifyError(requestId, offerCid, finalTid, actAsParty, ex));
                });
    }

    private ProbeAcceptResponse classifySuccess(String requestId, String offerCid, ValueOuterClass.Identifier tid, String actAs) {
        LOGGER.info("[CbtcTransferOfferService] [requestId={}] probeAccept SUCCESS", requestId);
        return new ProbeAcceptResponse(
                requestId,
                offerCid,
                new ProbeAcceptResponse.Attempt(
                        tid.getModuleName() + ":" + tid.getEntityName(),
                        "TransferOffer_Accept",
                        actAs,
                        Map.of()
                ),
                new ProbeAcceptResponse.Result(true, "OK", null, "Accept succeeded (mutating).")
        );
    }

    private ProbeAcceptResponse classifyError(
            String requestId,
            String offerCid,
            ValueOuterClass.Identifier tid,
            String actAs,
            Throwable ex
    ) {
        String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        String classification = "UNKNOWN";
        String hint = "Check rawError for details.";
        String lower = msg.toLowerCase();
        if (lower.contains("authorizer") || lower.contains("authorization") || lower.contains("not an authorizer")) {
            classification = "AUTHORIZATION";
            hint = "Party is not an authorizer; try receiver/issuer via Loop.";
        } else if (lower.contains("disclos") || lower.contains("visible") || lower.contains("unknown contract")) {
            classification = "DISCLOSURE";
            hint = "Contract not visible; requires disclosure/Loop wallet.";
        } else if (lower.contains("unknown choice")) {
            classification = "CHOICE_NOT_FOUND";
            hint = "Choice name/module mismatch; verify template/choice.";
        } else if (lower.contains("invalid argument") || lower.contains("decod") || lower.contains("json")) {
            classification = "PAYLOAD_SCHEMA";
            hint = "Payload mismatch; check choice arguments.";
        }

        LOGGER.error("[CbtcTransferOfferService] [requestId={}] probeAccept ERROR classification={} msg={}",
                requestId, classification, msg);

        return new ProbeAcceptResponse(
                requestId,
                offerCid,
                new ProbeAcceptResponse.Attempt(
                        tid.getModuleName() + ":" + tid.getEntityName(),
                        "TransferOffer_Accept",
                        actAs,
                        Map.of()
                ),
                new ProbeAcceptResponse.Result(false, classification, msg, hint)
        );
    }

    /**
     * Parse a RawActiveContract into a CbtcTransferOfferDto.
     *
     * TransferOffer structure (from cbtc-lib):
     * - sender: Party
     * - receiver: Party
     * - amount: Numeric 10
     * - instrumentId: InstrumentId { admin: Party, id: Text }
     * - reason: Text
     * - executeBefore: Optional Time
     */
    private CbtcTransferOfferDto parseTransferOffer(final LedgerApi.RawActiveContract contract) {
        ValueOuterClass.Record args = contract.createArguments();
        if (args == null) {
            return null;
        }

        try {
            // TransferOffer structure:
            // Field[0]: PARTY (sender - party making the offer)
            // Field[1]: PARTY (receiver - party receiving the offer)
            // Field[2]: RECORD (holding/transfer details with 8 fields)
            //   NestedField[0]: PARTY (instrument issuer/admin)
            //   NestedField[1]: PARTY (instrument owner/sender)
            //   NestedField[2]: NUMERIC (amount)
            //   NestedField[3]: RECORD (instrumentId with admin+id)
            //   NestedField[4]: TIMESTAMP (created or executeBefore)

            String sender = getPartyField(args, "sender", 0);
            String receiver = getPartyField(args, "receiver", 1);

            // Get the holding/transfer details record at Field[2]
            ValueOuterClass.Value holdingValue = getFieldValue(args, "holding", 2);
            BigDecimal amount = null;
            String instrumentAdmin = null;
            String instrumentId = null;
            String reason = "CBTC transfer";  // Default reason
            String executeBefore = null;
            String transferInstructionId = null;

            if (holdingValue != null && holdingValue.hasRecord()) {
                ValueOuterClass.Record holdingRecord = holdingValue.getRecord();
                LOGGER.debug("Parsing holding record with {} fields", holdingRecord.getFieldsCount());

                // Parse amount from NestedField[2]
                amount = getNumericField(holdingRecord, "amount", 2);

                // Parse instrumentId from NestedField[3]
                ValueOuterClass.Value instrumentValue = getFieldValue(holdingRecord, "instrumentId", 3);
                if (instrumentValue != null && instrumentValue.hasRecord()) {
                    ValueOuterClass.Record instrumentRecord = instrumentValue.getRecord();
                    instrumentAdmin = getPartyField(instrumentRecord, "admin", 0);
                    instrumentId = getTextField(instrumentRecord, "id", 1);
                    LOGGER.debug("Parsed instrumentId: admin={}, id={}",
                        instrumentAdmin != null ? instrumentAdmin.substring(0, Math.min(20, instrumentAdmin.length())) : "null",
                        instrumentId);
                }

                // Parse timestamp from NestedField[4]
                ValueOuterClass.Value timestampValue = getFieldValue(holdingRecord, "executeBefore", 4);
                if (timestampValue != null && timestampValue.getSumCase() == ValueOuterClass.Value.SumCase.TIMESTAMP) {
                    executeBefore = String.valueOf(timestampValue.getTimestamp());
                }

                // Attempt to locate transferInstructionId anywhere in the holding record
                transferInstructionId = findFirstContractId(holdingRecord);
            }

            String rawTemplateId = contract.templateId().getModuleName() + ":" + contract.templateId().getEntityName();
            String pkgId = contract.templateId().getPackageId();

            return new CbtcTransferOfferDto(
                    contract.contractId(),
                    sender,
                    receiver,
                    amount != null ? amount : BigDecimal.ZERO,
                    reason,
                    executeBefore,
                    instrumentId,
                    instrumentAdmin,
                    rawTemplateId,
                    transferInstructionId,
                    pkgId
            );
        } catch (Exception e) {
            LOGGER.warn("[CbtcTransferOfferService] Failed to parse TransferOffer {}: {}",
                    contract.contractId(), e.getMessage());
            return null;
        }
    }

    private ValueOuterClass.Value getFieldValue(ValueOuterClass.Record record, String label, int fallbackIndex) {
        if (record == null) return null;

        // Try by label first
        for (ValueOuterClass.RecordField field : record.getFieldsList()) {
            if (label.equals(field.getLabel())) {
                return field.getValue();
            }
        }

        // Fall back to index
        if (record.getFieldsCount() > fallbackIndex) {
            return record.getFields(fallbackIndex).getValue();
        }

        return null;
    }

    private String getPartyField(ValueOuterClass.Record record, String label, int fallbackIndex) {
        ValueOuterClass.Value value = getFieldValue(record, label, fallbackIndex);
        if (value != null && value.getSumCase() == ValueOuterClass.Value.SumCase.PARTY) {
            return value.getParty();
        }
        return null;
    }

    private String getTextField(ValueOuterClass.Record record, String label, int fallbackIndex) {
        ValueOuterClass.Value value = getFieldValue(record, label, fallbackIndex);
        if (value != null && value.getSumCase() == ValueOuterClass.Value.SumCase.TEXT) {
            return value.getText();
        }
        return null;
    }

    private String findFirstContractId(ValueOuterClass.Record record) {
        if (record == null) return null;
        for (ValueOuterClass.RecordField field : record.getFieldsList()) {
            String cid = findFirstContractId(field.getValue());
            if (cid != null) return cid;
        }
        return null;
    }

    private String findFirstContractId(ValueOuterClass.Value value) {
        if (value == null) return null;
        switch (value.getSumCase()) {
            case CONTRACT_ID:
                return value.getContractId();
            case RECORD:
                return findFirstContractId(value.getRecord());
            case VARIANT:
                return findFirstContractId(value.getVariant().getValue());
            case LIST:
                for (ValueOuterClass.Value v : value.getList().getElementsList()) {
                    String cid = findFirstContractId(v);
                    if (cid != null) return cid;
                }
                return null;
            case OPTIONAL:
                Optional opt = value.getOptional();
                if (opt.hasValue()) return findFirstContractId(opt.getValue());
                return null;
            case TEXT_MAP:
                for (var entry : value.getTextMap().getEntriesList()) {
                    String cid = findFirstContractId(entry.getValue());
                    if (cid != null) return cid;
                }
                return null;
            default:
                return null;
        }
    }

    private List<RegistryEndpoint> getRegistriesToTry(String adminPartyHint) {
        if (registryRouting != null) {
            return registryRouting.getRegistriesToTry(adminPartyHint);
        }
        String base = ledgerConfig.getRegistryBaseUri();
        if (base != null && !base.isBlank()) {
            return List.of(new RegistryEndpoint(base, RegistryKind.SCAN, adminPartyHint));
        }
        return List.of();
    }

    private RegistryFetchResult fetchChoiceContextForOffer(List<RegistryEndpoint> registries, String offerCid, String requestId) {
        RegistryFetchResult lastResult = null;
        for (RegistryEndpoint ep : registries) {
            List<String> urls = buildOfferUrls(ep, offerCid);
            for (String url : urls) {
                if (url == null) continue;
                try {
                    LOGGER.info("[CbtcTransferOfferService] [requestId={}] registry fetch base={} kind={} offerCid={} encoded={}", requestId, abbreviate(ep.baseUri()), ep.kind(), truncateCid(offerCid), url.contains("%3A") ? "yes" : "no");
                    RegistryFetchResult ctx = doRegistryPost(url, requestId);
                    if (ctx != null) {
                        lastResult = ctx;
                    }
                    if (ctx != null && ctx.ctx != null && ctx.ctx.disclosedContracts != null && !ctx.ctx.disclosedContracts.isEmpty()) {
                        LOGGER.info("[CbtcTransferOfferService] choice-context fetched from base={} kind={} disclosures={}",
                                abbreviate(ep.baseUri()), ep.kind(), ctx.ctx.disclosedContracts.size());
                        return ctx;
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    LOGGER.warn("[CbtcTransferOfferService] [requestId={}] choice-context fetch failed base={} kind={} cid={} error={}",
                            requestId, abbreviate(ep.baseUri()), ep.kind(), truncateCid(offerCid), msg);
                }
            }
        }
        return lastResult;
    }

    private RegistryFetchResult doRegistryPost(String url, String requestId) {
        try {
            HttpResponse<String> resp = executeRegistryPost(url);
            String body = resp.body() != null ? resp.body() : "";
            ChoiceContextDto parsed = null;
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                parsed = parseChoiceContext(body);
            }
            int disclosedCount = parsed != null && parsed.disclosedContracts != null ? parsed.disclosedContracts.size() : 0;
            int contextKeys = parsed != null && parsed.choiceContextData != null && parsed.choiceContextData.values != null
                    ? parsed.choiceContextData.values.size()
                    : 0;
            if (disclosedCount == 0) {
                LOGGER.warn("[CbtcTransferOfferService] [requestId={}] registry POST status={} url={} disclosed=0 contextKeys={} bodySnippet={}",
                        requestId, resp.statusCode(), abbreviate(url), contextKeys, snippet(body));
            } else {
                LOGGER.info("[CbtcTransferOfferService] [requestId={}] registry POST status={} url={} disclosed={} contextKeys={}",
                        requestId, resp.statusCode(), abbreviate(url), disclosedCount, contextKeys);
            }
            return new RegistryFetchResult(parsed, url, resp.statusCode(), snippet(body));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "(no message)";
            LOGGER.warn("[CbtcTransferOfferService] [requestId={}] registry POST failed url={} error={}", requestId, url, msg);
            return new RegistryFetchResult(null, url, 0, snippet(msg));
        }
    }

    private HttpResponse<String> executeRegistryPost(String url) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(java.net.URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"meta\":{}}"));
        if (ledgerConfig.getRegistryAuthHeader() != null && !ledgerConfig.getRegistryAuthHeader().isBlank()
                && ledgerConfig.getRegistryAuthToken() != null && !ledgerConfig.getRegistryAuthToken().isBlank()) {
            b.header(ledgerConfig.getRegistryAuthHeader(), ledgerConfig.getRegistryAuthToken());
        }
        return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private ValueOuterClass.Record buildChoiceArgument(ChoiceContextDto ctx) {
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

        return ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("extraArgs")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setRecord(extraArgs)
                                .build())
                        .build())
                .build();
    }

    private CommandsOuterClass.DisclosedContract toDisclosedContract(DisclosedContractDto dc) {
        try {
            ValueOuterClass.Identifier tid = parseTemplateIdentifier(dc.templateId);
            byte[] blob = Base64.getDecoder().decode(dc.createdEventBlob);
            return CommandsOuterClass.DisclosedContract.newBuilder()
                    .setTemplateId(tid)
                    .setContractId(dc.contractId)
                    .setCreatedEventBlob(com.google.protobuf.ByteString.copyFrom(blob))
                    .build();
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

    private AcceptOfferResult classifyAcceptError(
            String requestId,
            String offerCid,
            String transferInstructionId,
            String actAs,
            Throwable ex
    ) {
        String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        String classification = "UNKNOWN";
        String hint = "Check rawError for details.";
        String lower = msg.toLowerCase();
        if (lower.contains("404")) {
            classification = "REGISTRY_404";
            hint = "Registry endpoint not found for admin/path.";
        } else if (lower.contains("authorizer") || lower.contains("authorization") || lower.contains("not an authorizer")) {
            classification = "LEDGER_AUTH";
            hint = "Party is not an authorizer.";
        } else if (lower.contains("disclos") || lower.contains("visible") || lower.contains("unknown contract")) {
            classification = "LEDGER_DISCLOSURE";
            hint = "Contract not visible; requires disclosure/registry.";
        } else if (lower.contains("choice-context") || lower.contains("context")) {
            classification = "CHOICE_CONTEXT";
            hint = "Choice-context invalid or missing.";
        } else if (lower.contains("invalid argument") || lower.contains("decod") || lower.contains("json")) {
            classification = "LEDGER_SUBMIT";
            hint = "Ledger rejected the payload.";
        }

        LOGGER.error("[CbtcTransferOfferService] [requestId={}] acceptOffer ERROR classification={} msg={}", requestId, classification, msg);

        return new AcceptOfferResult(
                requestId,
                offerCid,
                transferInstructionId,
                actAs,
                false,
                classification,
                msg,
                hint,
                null
        );
    }

    /**
     * Probe registry choice-context for CBTC/other instruments. DevNet only.
     */
    @WithSpan
    public CompletionStage<RegistryProbeResponse> probeRegistry(
            String adminParty,
            String tiCid,
            String offerCid,
            String baseOverride,
            RegistryKind kindOverride,
            String requestId
    ) {
        LOGGER.info("[CbtcTransferOfferService] [requestId={}] registry probe START admin={} tiCid={} offerCid={} baseOverride={}",
                requestId, abbreviate(adminParty), truncateCid(tiCid), truncateCid(offerCid), baseOverride);

        List<RegistryProbeAttempt> attempts = new ArrayList<>();
        try {
            List<RegistryEndpoint> registries;
            if (baseOverride != null && !baseOverride.isBlank()) {
                RegistryKind k = kindOverride != null ? kindOverride : RegistryKind.SCAN;
                registries = List.of(new RegistryEndpoint(baseOverride, k, adminParty));
            } else {
                registries = getRegistriesToTry(adminParty);
            }

            if (registries == null || registries.isEmpty()) {
                return CompletableFuture.completedFuture(new RegistryProbeResponse(
                        requestId, false, "REGISTRY_EMPTY", "No registry endpoints configured", null, attempts
                ));
            }

            List<String> cids = new ArrayList<>();
            if (tiCid != null && !tiCid.isBlank()) cids.add(tiCid);
            if (offerCid != null && !offerCid.isBlank()) cids.add(offerCid);

            for (RegistryEndpoint ep : registries) {
                if (ep == null || ep.baseUri() == null || ep.baseUri().isBlank()) continue;
                for (String cid : cids) {
                    RegistryProbeAttempt attempt = doRegistryProbeAttempt(ep, cid, requestId);
                    attempts.add(attempt);
                    if (attempt.disclosedCount != null && attempt.disclosedCount > 0) {
                        return CompletableFuture.completedFuture(new RegistryProbeResponse(
                                requestId, true, "OK", null, null, attempts
                        ));
                    }
                }
            }

            // If none returned disclosures
            return CompletableFuture.completedFuture(new RegistryProbeResponse(
                    requestId, false, "REGISTRY_EMPTY", "No disclosures returned by registry", null, attempts
            ));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return CompletableFuture.completedFuture(new RegistryProbeResponse(
                    requestId, false, "UNKNOWN", msg, msg, attempts
            ));
        }
    }

    private RegistryProbeAttempt doRegistryProbeAttempt(RegistryEndpoint ep, String cid, String requestId) {
        String url = buildUrl(ep, true, cid);
        String fallbackUrl = buildUrl(ep, false, cid);
        List<String> urls = new ArrayList<>();
        if (url != null) urls.add(url);
        if (fallbackUrl != null && (fallbackUrl.equals(url) ? false : true)) urls.add(fallbackUrl);

        for (String u : urls) {
            try {
                HttpResponse<String> resp = executeRegistryPost(u);
                String body = resp.body() != null ? resp.body() : "";
                int status = resp.statusCode();
                Map<String, String> hdrs = new java.util.LinkedHashMap<>();
                resp.headers().firstValue("content-type").ifPresent(v -> hdrs.put("content-type", v));
                resp.headers().firstValue("x-request-id").ifPresent(v -> hdrs.put("request-id", v));
                RegistryProbeAttempt parsed = parseProbeBody(cid, u, status, hdrs, body);
                LOGGER.info("[CbtcTransferOfferService] [requestId={}] registry probe url={} status={} disclosed={} contextKeys={}",
                        requestId, abbreviate(u), status, parsed.disclosedCount, parsed.contextKeysCount);
                return parsed;
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                int status = e.getStatusCode().value();
                String body = e.getResponseBodyAsString();
                Map<String, String> hdrs = safeHeaders(e.getResponseHeaders());
                LOGGER.warn("[CbtcTransferOfferService] [requestId={}] registry probe failed url={} status={} body={}", requestId, abbreviate(u), status, abbreviate(body));
                return new RegistryProbeAttempt(cid, u, status, hdrs, snippet(body), 0, 0);
            } catch (Exception e) {
                LOGGER.warn("[CbtcTransferOfferService] [requestId={}] registry probe exception url={} error={}", requestId, abbreviate(u), e.getMessage());
                return new RegistryProbeAttempt(cid, u, 0, Map.of(), snippet(e.getMessage()), 0, 0);
            }
        }
        return new RegistryProbeAttempt(cid, null, 0, Map.of(), "no url", 0, 0);
    }

    private Map<String, String> safeHeaders(org.springframework.http.HttpHeaders headers) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        if (headers != null) {
            String ct = headers.getFirst("Content-Type");
            String rid = headers.getFirst("x-request-id");
            if (ct != null) m.put("content-type", ct);
            if (rid != null) m.put("request-id", rid);
        }
        return m;
    }

    private RegistryProbeAttempt parseProbeBody(String cid, String url, int status, Map<String, String> headers, String body) {
        try {
            if (body == null) {
                return new RegistryProbeAttempt(cid, url, status, headers, null, 0, 0);
            }
            JsonNode root = mapper.readTree(body);
            int disclosedCount = 0;
            int contextKeys = 0;
            if (root.has("disclosedContracts") && root.get("disclosedContracts").isArray()) {
                disclosedCount = root.get("disclosedContracts").size();
            }
            if (root.has("choiceContextData") && root.get("choiceContextData").has("values")) {
                contextKeys = root.get("choiceContextData").get("values").size();
            }
            return new RegistryProbeAttempt(cid, url, status, headers, snippet(body), disclosedCount, contextKeys);
        } catch (Exception e) {
            return new RegistryProbeAttempt(cid, url, status, headers, snippet(body), 0, 0);
        }
    }

    private static String snippet(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) + "..." : s;
    }

    private ChoiceContextDto parseChoiceContext(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(body);
            ChoiceContextDto dto = new ChoiceContextDto();
            if (root.has("disclosedContracts") && root.get("disclosedContracts").isArray()) {
                dto.disclosedContracts = new ArrayList<>();
                for (JsonNode n : root.get("disclosedContracts")) {
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
            LOGGER.warn("Failed to parse choice-context body: {}", e.getMessage());
            return null;
        }
    }

    private ValueOuterClass.Value toApplicationValue(ContextValueDto cv) {
        if (cv == null || cv.tag == null) return null;
        ValueOuterClass.Value innerVal = null;
        switch (cv.tag) {
            case "AV_Bool":
                boolean b = false;
                if (cv.value instanceof Boolean boolVal) {
                    b = boolVal;
                } else if (cv.value instanceof String s) {
                    b = Boolean.parseBoolean(s);
                }
                innerVal = ValueOuterClass.Value.newBuilder().setBool(b).build();
                break;
            case "AV_ContractId":
                innerVal = ValueOuterClass.Value.newBuilder()
                        .setContractId(cv.value != null ? cv.value.toString() : "")
                        .build();
                break;
            case "AV_List":
                ValueOuterClass.List.Builder lb = ValueOuterClass.List.newBuilder();
                if (cv.value instanceof List<?> listVal) {
                    for (Object o : listVal) {
                        lb.addElements(ValueOuterClass.Value.newBuilder()
                                .setText(o != null ? o.toString() : "")
                                .build());
                    }
                }
                innerVal = ValueOuterClass.Value.newBuilder().setList(lb.build()).build();
                break;
            default:
                return null;
        }

        return ValueOuterClass.Value.newBuilder()
                .setVariant(ValueOuterClass.Variant.newBuilder()
                        .setConstructor(cv.tag)
                        .setValue(innerVal != null ? innerVal : ValueOuterClass.Value.getDefaultInstance())
                        .build())
                .build();
    }

    private static List<String> buildOfferUrls(RegistryEndpoint ep, String contractId) {
        if (ep == null || ep.baseUri() == null || ep.baseUri().isBlank()) return List.of();
        String base = ep.baseUri();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String encCid = encodePathSegment(contractId);
        String encAdmin = encodePathSegment(ep.admin());

        List<String> urls = new ArrayList<>();
        if (ep.kind() == RegistryKind.UTILITIES_TOKEN_STANDARD) {
            if (ep.admin() == null || ep.admin().isBlank()) return List.of();
            urls.add(base + "/api/token-standard/v0/registrars/" + encAdmin + "/registry/transfer-instruction/v1/" + encCid + "/choice-contexts/accept");
        } else if (ep.kind() == RegistryKind.LOOP_TOKEN_STANDARD_V1) {
            urls.add(base + "/api/v1/token-standard/transfer-instructions/" + encCid + "/choice-contexts/accept");
        } else {
            urls.add(base + "/registry/transfer-instruction/v1/" + encCid + "/choice-contexts/accept");
        }
        // dedupe
        return urls.stream().distinct().toList();
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
        } else {
            return url + "/transfer-instruction/v1/" + safeCid + "/choice-contexts/accept";
        }
    }

    private HttpHeaders buildRegistryHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (ledgerConfig.getRegistryAuthHeader() != null && !ledgerConfig.getRegistryAuthHeader().isBlank()
                && ledgerConfig.getRegistryAuthToken() != null && !ledgerConfig.getRegistryAuthToken().isBlank()) {
            headers.add(ledgerConfig.getRegistryAuthHeader(), ledgerConfig.getRegistryAuthToken());
        }
        headers.add("Content-Type", "application/json");
        return headers;
    }

    private static String encodePathSegment(String raw) {
        try {
            return URLEncoder.encode(raw, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return raw;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        if (s.length() <= 30) return s;
        return s.substring(0, 15) + "..." + s.substring(s.length() - 10);
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
        public Object value;
    }
    private static final class DisclosedContractDto {
        public String templateId;
        public String contractId;
        public String createdEventBlob;
    }

    private BigDecimal getNumericField(ValueOuterClass.Record record, String label, int fallbackIndex) {
        ValueOuterClass.Value value = getFieldValue(record, label, fallbackIndex);
        if (value != null && value.getSumCase() == ValueOuterClass.Value.SumCase.NUMERIC) {
            return new BigDecimal(value.getNumeric());
        }
        return null;
    }

    private String getOptionalTimeField(ValueOuterClass.Record record, String label, int fallbackIndex) {
        ValueOuterClass.Value value = getFieldValue(record, label, fallbackIndex);
        if (value == null) return null;

        // Optional is represented as a variant (Some/None)
        if (value.getSumCase() == ValueOuterClass.Value.SumCase.OPTIONAL) {
            ValueOuterClass.Optional opt = value.getOptional();
            if (opt.hasValue() && opt.getValue().getSumCase() == ValueOuterClass.Value.SumCase.TIMESTAMP) {
                return String.valueOf(opt.getValue().getTimestamp());
            }
        }
        return null;
    }

    private String truncateCid(String cid) {
        if (cid == null || cid.length() <= 20) return cid;
        return cid.substring(0, 16) + "...";
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
