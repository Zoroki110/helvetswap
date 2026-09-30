package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.ledger.LedgerApi.RawActiveContract;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Helper to query TransferInstruction ACS entries for a receiver and filter by instrument/amount.
 */
@Service
public class TransferInstructionAcsQueryService {

    private static final Logger LOG = LoggerFactory.getLogger(TransferInstructionAcsQueryService.class);
    private static final String MEMO_KEY = "splice.lfdecentralizedtrust.org/reason";

    private static final String TI_MODULE = "Splice.Api.Token.TransferInstructionV1";
    private static final String TI_ENTITY = "TransferInstruction";
    private static final String AMULET_TI_MODULE = "Splice.AmuletTransferInstruction";
    private static final String AMULET_TI_ENTITY = "AmuletTransferInstruction";
    private static final String TRANSFER_OFFER_MODULE = "Utility.Registry.App.V0.Model.Transfer";
    private static final String TRANSFER_OFFER_ENTITY = "TransferOffer";

    private final LedgerApi ledgerApi;

    public TransferInstructionAcsQueryService(final LedgerApi ledgerApi) {
        this.ledgerApi = ledgerApi;
    }

    public record TransferInstructionDto(
            String contractId,
            String sender,
            String receiver,
            String admin,
            String instrumentId,
            String amount,
            Instant executeBefore
    ) { }

    public record TransferInstructionWithMemo(
            TransferInstructionDto transferInstruction,
            String memo
    ) { }

    @WithSpan
    public Result<List<TransferInstructionDto>, DomainError> listForReceiver(String receiverParty) {
        try {
            List<RawActiveContract> acs = ledgerApi.getActiveContractsRawForParty(receiverParty).toCompletableFuture().join();
            List<TransferInstructionDto> tis = new ArrayList<>();
            for (RawActiveContract rac : acs) {
                String mod = rac.templateId().getModuleName();
                String ent = rac.templateId().getEntityName();
                Optional<TransferInstructionDto> parsed;
                if (AMULET_TI_MODULE.equals(mod) && AMULET_TI_ENTITY.equals(ent)) {
                    parsed = parseAmuletTi(rac);
                } else if (TRANSFER_OFFER_MODULE.equals(mod) && TRANSFER_OFFER_ENTITY.equals(ent)) {
                    parsed = parseTransferOfferLike(rac);
                } else if (TI_MODULE.equals(mod) && TI_ENTITY.equals(ent)) {
                    parsed = parseStandardTi(rac);
                } else {
                    continue;
                }
                parsed.ifPresent(tis::add);
            }
            // newest-first by contractId appearance (ACS is from newest to oldest already) — keep order
            return Result.ok(tis);
        } catch (Exception e) {
            LOG.error("Failed to list TransferInstructions for {}: {}", receiverParty, e.getMessage(), e);
            return Result.err(new UnexpectedError(e.getMessage()));
        }
    }

    @WithSpan
    public Result<List<TransferInstructionWithMemo>, DomainError> listForReceiverWithMemo(String receiverParty) {
        try {
            List<RawActiveContract> acs = ledgerApi.getActiveContractsRawForParty(receiverParty).toCompletableFuture().join();
            List<TransferInstructionWithMemo> tis = new ArrayList<>();
            for (RawActiveContract rac : acs) {
                String mod = rac.templateId().getModuleName();
                String ent = rac.templateId().getEntityName();
                Optional<TransferInstructionDto> parsed;
                if (AMULET_TI_MODULE.equals(mod) && AMULET_TI_ENTITY.equals(ent)) {
                    parsed = parseAmuletTi(rac);
                    LOG.info("[TI-ACS] DIAG matched template=AmuletTransferInstruction cid={} pkg={}",
                            rac.contractId(), rac.templateId().getPackageId());
                } else if (TRANSFER_OFFER_MODULE.equals(mod) && TRANSFER_OFFER_ENTITY.equals(ent)) {
                    parsed = parseTransferOfferLike(rac);
                    LOG.info("[TI-ACS] DIAG matched template=TransferOffer cid={} pkg={}",
                            rac.contractId(), rac.templateId().getPackageId());
                } else if (TI_MODULE.equals(mod) && TI_ENTITY.equals(ent)) {
                    parsed = parseStandardTi(rac);
                    LOG.info("[TI-ACS] DIAG matched template=TransferInstruction cid={} pkg={}",
                            rac.contractId(), rac.templateId().getPackageId());
                } else {
                    continue;
                }
                if (parsed.isEmpty()) {
                    continue;
                }
                String memo = extractMemo(rac.createArguments());
                tis.add(new TransferInstructionWithMemo(parsed.get(), memo));
            }
            return Result.ok(tis);
        } catch (Exception e) {
            LOG.error("Failed to list TransferInstructions with memo for {}: {}", receiverParty, e.getMessage(), e);
            return Result.err(new UnexpectedError(e.getMessage()));
        }
    }

    @WithSpan
    public Result<List<TransferInstructionDto>, DomainError> listOutgoingForSender(String senderParty) {
        try {
            List<RawActiveContract> acs = ledgerApi.getActiveContractsRawForParty(senderParty).toCompletableFuture().join();
            List<TransferInstructionDto> tis = new ArrayList<>();
            for (RawActiveContract rac : acs) {
                String mod = rac.templateId().getModuleName();
                String ent = rac.templateId().getEntityName();
                Optional<TransferInstructionDto> parsed;
                if (AMULET_TI_MODULE.equals(mod) && AMULET_TI_ENTITY.equals(ent)) {
                    parsed = parseAmuletTi(rac);
                } else if (TRANSFER_OFFER_MODULE.equals(mod) && TRANSFER_OFFER_ENTITY.equals(ent)) {
                    parsed = parseTransferOfferLike(rac);
                } else if (TI_MODULE.equals(mod) && TI_ENTITY.equals(ent)) {
                    parsed = parseStandardTi(rac);
                } else {
                    continue;
                }
                parsed.filter(dto -> senderParty.equals(dto.sender())).ifPresent(tis::add);
            }
            return Result.ok(tis);
        } catch (Exception e) {
            LOG.error("Failed to list outgoing TransferInstructions for {}: {}", senderParty, e.getMessage(), e);
            return Result.err(new UnexpectedError(e.getMessage()));
        }
    }

    @WithSpan
    public Optional<TransferInstructionDto> findByCid(String party, String cid) {
        try {
            List<RawActiveContract> acs = ledgerApi.getActiveContractsRawForParty(party).toCompletableFuture().join();
            for (RawActiveContract rac : acs) {
                if (!cid.equals(rac.contractId())) continue;
                String mod = rac.templateId().getModuleName();
                String ent = rac.templateId().getEntityName();
                if (AMULET_TI_MODULE.equals(mod) && AMULET_TI_ENTITY.equals(ent)) {
                    return parseAmuletTi(rac);
                } else if (TRANSFER_OFFER_MODULE.equals(mod) && TRANSFER_OFFER_ENTITY.equals(ent)) {
                    return parseTransferOfferLike(rac);
                } else if (TI_MODULE.equals(mod) && TI_ENTITY.equals(ent)) {
                    return parseStandardTi(rac);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            LOG.error("Failed to find TI by cid {} for party {}: {}", cid, party, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public Optional<TransferInstructionDto> selectBest(List<TransferInstructionDto> candidates) {
        return candidates.stream()
                .max(Comparator.comparing(TransferInstructionDto::executeBefore));
    }

    private boolean isTransferInstruction(final com.daml.ledger.api.v2.ValueOuterClass.Identifier id) {
        if (id == null) return false;
        String mod = id.getModuleName();
        String ent = id.getEntityName();
        return (TI_MODULE.equals(mod) && TI_ENTITY.equals(ent))
                || (AMULET_TI_MODULE.equals(mod) && AMULET_TI_ENTITY.equals(ent))
                || (TRANSFER_OFFER_MODULE.equals(mod) && TRANSFER_OFFER_ENTITY.equals(ent));
    }

    private Optional<TransferInstructionDto> parseStandardTi(final RawActiveContract rac) {
        var args = rac.createArguments();
        if (args == null) return Optional.empty();

        String sender = textOrParty(args, "sender", 0);
        String receiver = textOrParty(args, "receiver", 1);
        Instrument inst = parseInstrumentFlexible(args, "instrumentId", new int[]{2, 3});
        String amount = numeric(args, "amount", 2);
        if (amount == null) {
            amount = numeric(args, "amount", 3);
        }
        Instant executeBefore = timestamp(args, "executeBefore", 5);
        if (executeBefore == null) {
            executeBefore = timestamp(args, "executeBefore", 4);
        }

        if (executeBefore == null) {
            executeBefore = Instant.now().plusSeconds(3600);
        }

        if (receiver == null || inst == null || amount == null) {
            LOG.debug("Skipping TI {} missing fields sender={} receiver={} inst={} amount={} executeBefore={}",
                    rac.contractId(), sender, receiver, inst, amount, executeBefore);
            return Optional.empty();
        }

        return Optional.of(new TransferInstructionDto(
                rac.contractId(),
                sender,
                receiver,
                inst.admin,
                inst.id,
                amount,
                executeBefore
        ));
    }

    private Optional<TransferInstructionDto> parseAmuletTi(final RawActiveContract rac) {
        var args = rac.createArguments();
        if (args == null) return Optional.empty();
        try {
            if (args.getFieldsCount() < 2) return Optional.empty();
            var transferVal = args.getFields(1).getValue();
            if (transferVal == null || !transferVal.hasRecord()) return Optional.empty();
            var rec = transferVal.getRecord();
            String sender = partyAt(rec, 0);
            String receiver = partyAt(rec, 1);
            String amount = numericAt(rec, 2);
            Instrument inst = null;
            if (rec.getFieldsCount() > 3 && rec.getFields(3).getValue().hasRecord()) {
                var instRec = rec.getFields(3).getValue().getRecord();
                String admin = partyAt(instRec, 0);
                String id = textAt(instRec, 1);
                if (admin != null && id != null) {
                    inst = new Instrument(admin, id);
                }
            }
            Instant executeBefore = timestampAt(rec, 5);
            if (executeBefore == null) {
                executeBefore = timestampAt(rec, 4);
            }
            if (executeBefore == null) {
                executeBefore = Instant.now().plusSeconds(3600);
            }
            if (receiver == null || inst == null || amount == null) {
                LOG.debug("Amulet TI {} missing fields sender={} receiver={} inst={} amount={} executeBefore={}",
                        rac.contractId(), sender, receiver, inst, amount, executeBefore);
                return Optional.empty();
            }
            return Optional.of(new TransferInstructionDto(
                    rac.contractId(),
                    sender,
                    receiver,
                    inst.admin,
                    inst.id,
                    amount,
                    executeBefore
            ));
        } catch (Exception e) {
            LOG.warn("Failed to parse Amulet TI {}: {}", rac.contractId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parse Utility Registry TransferOffer as TI-like (for CBTC inbound from Loop).
     */
    private Optional<TransferInstructionDto> parseTransferOfferLike(final RawActiveContract rac) {
        var args = rac.createArguments();
        if (args == null) return Optional.empty();
        try {
            var holdingVal = field(args, "holding", 2);
            if (holdingVal == null || !holdingVal.hasRecord()) {
                LOG.debug("TransferOffer {} missing holding record", rac.contractId());
                return Optional.empty();
            }
            var holdRec = holdingVal.getRecord();
            String sender = textOrParty(holdRec, "sender", 0);
            String receiver = textOrParty(holdRec, "receiver", 1);
            String amount = numeric(holdRec, "amount", 2);
            Instrument inst = parseInstrumentFlexible(holdRec, "instrumentId", new int[]{3});
            Instant executeBefore = timestamp(holdRec, "executeBefore", 5);
            if (executeBefore == null) {
                executeBefore = timestamp(holdRec, "executeBefore", 4);
            }
            if (executeBefore == null) {
                executeBefore = Instant.now().plusSeconds(3600);
            }
            if (receiver == null || inst == null || amount == null) {
                LOG.debug("TransferOffer {} missing fields sender={} receiver={} inst={} amount={} executeBefore={}",
                        rac.contractId(), sender, receiver, inst, amount, executeBefore);
                return Optional.empty();
            }
            return Optional.of(new TransferInstructionDto(
                    rac.contractId(),
                    sender,
                    receiver,
                    inst.admin,
                    inst.id,
                    amount,
                    executeBefore
            ));
        } catch (Exception e) {
            LOG.warn("Failed to parse TransferOffer-like TI {}: {}", rac.contractId(), e.getMessage());
            return Optional.empty();
        }
    }

    private record Instrument(String admin, String id) { }

    private Instrument parseInstrumentFlexible(final com.daml.ledger.api.v2.ValueOuterClass.Record rec, final String label, final int[] indexFallbacks) {
        com.daml.ledger.api.v2.ValueOuterClass.Value v = field(rec, label, -1);
        if ((v == null || !v.hasRecord()) && indexFallbacks != null) {
            for (int idx : indexFallbacks) {
                v = field(rec, label, idx);
                if (v != null && v.hasRecord()) break;
            }
        }
        if (v == null || !v.hasRecord()) return null;
        var r = v.getRecord();
        String admin = textOrParty(r, "admin", 0);
        if (admin == null) admin = textOrParty(r, "", 0);
        String id = text(r, "id", 1);
        if (id == null) id = text(r, "", 1);
        if (admin == null || id == null) return null;
        return new Instrument(admin, id);
    }

    private String textOrParty(final com.daml.ledger.api.v2.ValueOuterClass.Record rec, final String label, final int indexFallback) {
        var v = field(rec, label, indexFallback);
        if (v == null) return null;
        return switch (v.getSumCase()) {
            case TEXT -> v.getText();
            case PARTY -> v.getParty();
            default -> null;
        };
    }

    private String text(final com.daml.ledger.api.v2.ValueOuterClass.Record rec, final String label, final int indexFallback) {
        var v = field(rec, label, indexFallback);
        if (v == null || v.getSumCase() != com.daml.ledger.api.v2.ValueOuterClass.Value.SumCase.TEXT) return null;
        return v.getText();
    }

    private String partyAt(final com.daml.ledger.api.v2.ValueOuterClass.Record rec, final int index) {
        if (rec == null || index >= rec.getFieldsCount()) return null;
        var v = rec.getFields(index).getValue();
        if (v.getSumCase() != com.daml.ledger.api.v2.ValueOuterClass.Value.SumCase.PARTY) return null;
        return v.getParty();
    }

    private String numericAt(final com.daml.ledger.api.v2.ValueOuterClass.Record rec, final int index) {
        if (rec == null || index >= rec.getFieldsCount()) return null;
        var v = rec.getFields(index).getValue();
        if (v.getSumCase() != com.daml.ledger.api.v2.ValueOuterClass.Value.SumCase.NUMERIC) return null;
        return v.getNumeric();
    }

    private Instant timestampAt(final com.daml.ledger.api.v2.ValueOuterClass.Record rec, final int index) {
        if (rec == null || index >= rec.getFieldsCount()) return null;
        var v = rec.getFields(index).getValue();
        if (v.getSumCase() != com.daml.ledger.api.v2.ValueOuterClass.Value.SumCase.TIMESTAMP) return null;
        long micros = v.getTimestamp();
        long seconds = micros / 1_000_000L;
        long nanos = (micros % 1_000_000L) * 1000L;
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private String numeric(final com.daml.ledger.api.v2.ValueOuterClass.Record rec, final String label, final int indexFallback) {
        var v = field(rec, label, indexFallback);
        if (v == null || v.getSumCase() != com.daml.ledger.api.v2.ValueOuterClass.Value.SumCase.NUMERIC) return null;
        return v.getNumeric();
    }

    private String textAt(final com.daml.ledger.api.v2.ValueOuterClass.Record rec, final int index) {
        if (rec == null || index >= rec.getFieldsCount()) return null;
        var v = rec.getFields(index).getValue();
        if (v.getSumCase() != com.daml.ledger.api.v2.ValueOuterClass.Value.SumCase.TEXT) return null;
        return v.getText();
    }

    private Instant timestamp(final com.daml.ledger.api.v2.ValueOuterClass.Record rec, final String label, final int indexFallback) {
        var v = field(rec, label, indexFallback);
        if (v == null || v.getSumCase() != com.daml.ledger.api.v2.ValueOuterClass.Value.SumCase.TIMESTAMP) return null;
        long micros = v.getTimestamp();
        long seconds = micros / 1_000_000L;
        long nanos = (micros % 1_000_000L) * 1000L;
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private com.daml.ledger.api.v2.ValueOuterClass.Value field(final com.daml.ledger.api.v2.ValueOuterClass.Record rec, final String label, final int indexFallback) {
        Optional<com.daml.ledger.api.v2.ValueOuterClass.Value> byLabel = rec.getFieldsList().stream()
                .filter(f -> label.equals(f.getLabel()))
                .map(com.daml.ledger.api.v2.ValueOuterClass.RecordField::getValue)
                .findFirst();
        if (byLabel.isPresent()) return byLabel.get();
        if (indexFallback >= 0 && indexFallback < rec.getFieldsCount()) {
            return rec.getFields(indexFallback).getValue();
        }
        return null;
    }

    private String extractMemo(final com.daml.ledger.api.v2.ValueOuterClass.Record record) {
        if (record == null) {
            return null;
        }
        com.daml.ledger.api.v2.ValueOuterClass.Value root = com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder()
                .setRecord(record)
                .build();
        return findMemoInValue(root);
    }

    private String findMemoInValue(final com.daml.ledger.api.v2.ValueOuterClass.Value value) {
        if (value == null) {
            return null;
        }
        switch (value.getSumCase()) {
            case TEXT_MAP -> {
                for (var entry : value.getTextMap().getEntriesList()) {
                    if (MEMO_KEY.equals(entry.getKey())
                            && entry.hasValue()
                            && entry.getValue().getSumCase() == com.daml.ledger.api.v2.ValueOuterClass.Value.SumCase.TEXT) {
                        return entry.getValue().getText();
                    }
                }
            }
            case RECORD -> {
                for (var field : value.getRecord().getFieldsList()) {
                    String found = findMemoInValue(field.getValue());
                    if (found != null) {
                        return found;
                    }
                }
            }
            case VARIANT -> {
                return findMemoInValue(value.getVariant().getValue());
            }
            case OPTIONAL -> {
                if (value.getOptional().hasValue()) {
                    return findMemoInValue(value.getOptional().getValue());
                }
            }
            case LIST -> {
                for (var element : value.getList().getElementsList()) {
                    String found = findMemoInValue(element);
                    if (found != null) {
                        return found;
                    }
                }
            }
            case GEN_MAP -> {
                for (var entry : value.getGenMap().getEntriesList()) {
                    String found = findMemoInValue(entry.getValue());
                    if (found != null) {
                        return found;
                    }
                }
            }
            default -> {
                // ignore
            }
        }
        return null;
    }
}

