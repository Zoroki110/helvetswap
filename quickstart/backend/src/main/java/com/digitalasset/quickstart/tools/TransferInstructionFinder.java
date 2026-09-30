package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.TransactionOuterClass;
import com.daml.ledger.api.v2.UpdateServiceGrpc;
import com.daml.ledger.api.v2.UpdateServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared helper for locating TransferInstruction contracts matching a TransferOffer.
 */
public final class TransferInstructionFinder {

    public record OfferInfo(
            String offerCid,
            ValueOuterClass.Identifier templateId,
            String sender,
            String receiver,
            String amount,
            String instrumentAdmin,
            String instrumentId,
            long requestedAtMicros,
            long executeBeforeMicros,
            List<String> inputHoldingCids
    ) { }

    public record Candidate(
            String contractId,
            ValueOuterClass.Identifier templateId,
            String sender,
            String receiver,
            String amount,
            String instrumentAdmin,
            String instrumentId,
            int score
    ) { }

    public record Result(
            OfferInfo offer,
            Candidate bestCandidate,
            List<Candidate> candidates,
            List<Seen> relatedTransfers
    ) { }

    public record Seen(String contractId, ValueOuterClass.Identifier templateId) { }

    private final ManagedChannel channel;
    private final String token;

    public TransferInstructionFinder(String host, int port, String token) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port).usePlaintext();
        builder.intercept(new AuthInterceptor(token));
        this.channel = builder.build();
        this.token = token;
    }

    public void close() {
        channel.shutdownNow();
    }

    public Result find(String party, String offerCid, String updateId, boolean verbose) {
        OfferInfo offer = offerCid != null ? fetchOfferFromAcs(party, offerCid, verbose) : null;
        if (offer == null && updateId != null) {
            offer = fetchOfferFromUpdate(updateId, verbose);
        }
        if (offer == null) {
            throw new IllegalStateException("Offer not found (by offerContractId or updateId).");
        }

        var acs = scanAcsForInstructions(party, offer, verbose);
        return new Result(offer, acs.bestCandidate, acs.candidates, acs.relatedTransfers);
    }

    private OfferInfo fetchOfferFromAcs(String party, String offerCid, boolean verbose) {
        StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);
        long ledgerEnd = state.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build()).getOffset();
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
                .setActiveAtOffset(ledgerEnd)
                .build();
        var it = state.getActiveContracts(acsReq);
        while (it.hasNext()) {
            var resp = it.next();
            if (!resp.hasActiveContract()) continue;
            EventOuterClass.CreatedEvent c = resp.getActiveContract().getCreatedEvent();
            if (!offerCid.equals(c.getContractId())) continue;
            if (verbose) {
                System.out.println("Offer fetched from ACS: " + c.getContractId());
            }
            return parseOffer(c);
        }
        return null;
    }

    private OfferInfo fetchOfferFromUpdate(String updateId, boolean verbose) {
        UpdateServiceGrpc.UpdateServiceBlockingStub update = UpdateServiceGrpc.newBlockingStub(channel);

        TransactionFilterOuterClass.Filters wildcardFilters = TransactionFilterOuterClass.Filters.newBuilder()
                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                        .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                        .build())
                .build();
        TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                .setFiltersForAnyParty(wildcardFilters)
                .setVerbose(true)
                .build();
        TransactionFilterOuterClass.TransactionFormat txFormat = TransactionFilterOuterClass.TransactionFormat.newBuilder()
                .setEventFormat(eventFormat)
                .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                .build();
        TransactionFilterOuterClass.UpdateFormat updateFormat = TransactionFilterOuterClass.UpdateFormat.newBuilder()
                .setIncludeTransactions(txFormat)
                .build();
        UpdateServiceOuterClass.GetUpdateByIdRequest req = UpdateServiceOuterClass.GetUpdateByIdRequest.newBuilder()
                .setUpdateId(updateId)
                .setUpdateFormat(updateFormat)
                .build();
        UpdateServiceOuterClass.GetUpdateResponse resp = update.getUpdateById(req);
        if (!resp.hasTransaction()) {
            return null;
        }
        TransactionOuterClass.Transaction txn = resp.getTransaction();
        for (EventOuterClass.Event ev : txn.getEventsList()) {
            if (!ev.hasCreated()) continue;
            EventOuterClass.CreatedEvent c = ev.getCreated();
            if (!isOffer(c.getTemplateId())) continue;
            if (verbose) {
                System.out.println("Offer fetched from updateId=" + updateId + " cid=" + c.getContractId());
            }
            return parseOffer(c);
        }
        return null;
    }

    private OfferInfo parseOffer(EventOuterClass.CreatedEvent c) {
        ValueOuterClass.Record args = c.hasCreateArguments() ? c.getCreateArguments() : ValueOuterClass.Record.getDefaultInstance();
        // Offer fields: transfer record containing sender/receiver/amount/instrumentId/requestedAt/executeBefore/inputHoldingCids
        ValueOuterClass.Record transfer = getRecordField(args, "transfer");
        String sender = getPartyField(transfer, "sender");
        String receiver = getPartyField(transfer, "receiver");
        String amount = getNumericField(transfer, "amount");
        String instrumentAdmin = null;
        String instrumentId = null;
        ValueOuterClass.Record instrumentRec = getRecordField(transfer, "instrumentId");
        if (instrumentRec != null) {
            instrumentAdmin = getPartyField(instrumentRec, "admin");
            instrumentId = getTextField(instrumentRec, "id");
        }
        long requestedAt = getTimestampMicros(transfer, "requestedAt");
        long executeBefore = getTimestampMicros(transfer, "executeBefore");
        List<String> inputHoldingCids = getContractIdList(transfer, "inputHoldingCids");

        return new OfferInfo(c.getContractId(), c.getTemplateId(), sender, receiver, amount, instrumentAdmin, instrumentId, requestedAt, executeBefore, inputHoldingCids);
    }

    private record ScanResult(Candidate bestCandidate, List<Candidate> candidates, List<Seen> relatedTransfers) {}

    private ScanResult scanAcsForInstructions(String party, OfferInfo offer, boolean verbose) {
        StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);
        long ledgerEnd = state.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build()).getOffset();
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
                .setActiveAtOffset(ledgerEnd)
                .build();

        List<Candidate> candidates = new ArrayList<>();
        List<Seen> relatedTransfers = new ArrayList<>();

        var it = state.getActiveContracts(acsReq);
        while (it.hasNext()) {
            var resp = it.next();
            if (!resp.hasActiveContract()) continue;
            EventOuterClass.CreatedEvent c = resp.getActiveContract().getCreatedEvent();
            ValueOuterClass.Identifier tid = c.getTemplateId();
            boolean looksTransfer = tid.getModuleName().toLowerCase().contains("transfer") || tid.getEntityName().toLowerCase().contains("transfer");
            if (looksTransfer) {
                relatedTransfers.add(new Seen(c.getContractId(), tid));
            }
            if (!isInstructionCandidate(tid)) continue;

            ValueOuterClass.Record args = c.hasCreateArguments() ? c.getCreateArguments() : ValueOuterClass.Record.getDefaultInstance();
            String sender = getPartyField(args, "sender");
            String receiver = getPartyField(args, "receiver");
            String amount = getNumericField(args, "amount");
            String instrumentAdmin = null;
            String instrumentId = null;
            ValueOuterClass.Record instr = getRecordField(args, "instrumentId");
            if (instr != null) {
                instrumentAdmin = getPartyField(instr, "admin");
                instrumentId = getTextField(instr, "id");
            }

            int score = scoreCandidate(offer, sender, receiver, amount, instrumentAdmin, instrumentId);
            candidates.add(new Candidate(c.getContractId(), tid, sender, receiver, amount, instrumentAdmin, instrumentId, score));
        }

        Candidate best = candidates.stream()
                .max(Comparator.comparingInt(Candidate::score))
                .orElse(null);
        return new ScanResult(best, candidates, relatedTransfers);
    }

    private int scoreCandidate(OfferInfo offer, String sender, String receiver, String amount, String admin, String instrId) {
        int s = 0;
        if (eq(offer.sender, sender)) s++;
        if (eq(offer.receiver, receiver)) s++;
        if (eqNumeric(offer.amount, amount)) s++;
        if (eq(offer.instrumentAdmin, admin)) s++;
        if (eq(offer.instrumentId, instrId)) s++;
        return s;
    }

    private static boolean eq(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private static boolean eqNumeric(String a, String b) {
        try {
            if (a == null || b == null) return false;
            BigDecimal da = new BigDecimal(a);
            BigDecimal db = new BigDecimal(b);
            return da.compareTo(db) == 0;
        } catch (Exception ignore) {
            return false;
        }
    }

    private boolean isOffer(ValueOuterClass.Identifier tid) {
        String mod = tid.getModuleName().toLowerCase();
        String ent = tid.getEntityName().toLowerCase();
        return mod.contains("transfer") && ent.contains("offer");
    }

    private boolean isInstructionCandidate(ValueOuterClass.Identifier tid) {
        String mod = tid.getModuleName().toLowerCase();
        String ent = tid.getEntityName().toLowerCase();
        return mod.contains("transferinstruction") || ent.contains("instruction") || tid.getPackageId().startsWith("55ba4d");
    }

    private ValueOuterClass.Record getRecordField(ValueOuterClass.Record rec, String labelLower) {
        if (rec == null) return null;
        for (var f : rec.getFieldsList()) {
            if (f.getLabel().equalsIgnoreCase(labelLower) && f.getValue().hasRecord()) {
                return f.getValue().getRecord();
            }
        }
        return null;
    }

    private String getPartyField(ValueOuterClass.Record rec, String labelLower) {
        if (rec == null) return null;
        for (var f : rec.getFieldsList()) {
            if (f.getLabel().equalsIgnoreCase(labelLower) && f.getValue().hasParty()) {
                return f.getValue().getParty();
            }
        }
        return null;
    }

    private String getNumericField(ValueOuterClass.Record rec, String labelLower) {
        if (rec == null) return null;
        for (var f : rec.getFieldsList()) {
            if (f.getLabel().equalsIgnoreCase(labelLower) && f.getValue().hasNumeric()) {
                return f.getValue().getNumeric();
            }
        }
        return null;
    }

    private String getTextField(ValueOuterClass.Record rec, String labelLower) {
        if (rec == null) return null;
        for (var f : rec.getFieldsList()) {
            if (f.getLabel().equalsIgnoreCase(labelLower) && f.getValue().hasText()) {
                return f.getValue().getText();
            }
        }
        return null;
    }

    private long getTimestampMicros(ValueOuterClass.Record rec, String labelLower) {
        if (rec == null) return 0L;
        for (var f : rec.getFieldsList()) {
            if (f.getLabel().equalsIgnoreCase(labelLower) && f.getValue().hasTimestamp()) {
                return f.getValue().getTimestamp();
            }
        }
        return 0L;
    }

    private List<String> getContractIdList(ValueOuterClass.Record rec, String labelLower) {
        List<String> out = new ArrayList<>();
        if (rec == null) return out;
        for (var f : rec.getFieldsList()) {
            if (f.getLabel().equalsIgnoreCase(labelLower) && f.getValue().hasList()) {
                for (var v : f.getValue().getList().getElementsList()) {
                    if (v.hasContractId()) {
                        out.add(v.getContractId());
                    }
                }
            }
        }
        return out;
    }

    public static void printOfferSummary(OfferInfo offer) {
        System.out.println("Offer cid=" + offer.offerCid + " template=" + fmtId(offer.templateId));
        System.out.println("  sender=" + offer.sender + " receiver=" + offer.receiver + " amount=" + offer.amount);
        System.out.println("  instrument admin=" + offer.instrumentAdmin + " id=" + offer.instrumentId);
        System.out.println("  requestedAt(micros)=" + offer.requestedAtMicros + " executeBefore(micros)=" + offer.executeBeforeMicros);
        if (!offer.inputHoldingCids.isEmpty()) {
            System.out.println("  inputHoldingCids=" + String.join(",", offer.inputHoldingCids));
        }
    }

    public static void printCandidate(Candidate c) {
        System.out.println("Candidate cid=" + c.contractId + " template=" + fmtId(c.templateId) + " score=" + c.score);
        System.out.println("  sender=" + c.sender + " receiver=" + c.receiver + " amount=" + c.amount);
        System.out.println("  instrument admin=" + c.instrumentAdmin + " id=" + c.instrumentId);
    }

    public static String fmtId(ValueOuterClass.Identifier id) {
        return id.getPackageId() + ":" + id.getModuleName() + ":" + id.getEntityName();
    }

    public ManagedChannel channel() {
        return channel;
    }

    public String token() {
        return token;
    }

    private static final class AuthInterceptor implements ClientInterceptor {
        private final Metadata.Key<String> AUTHORIZATION_HEADER = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
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

