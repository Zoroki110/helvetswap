package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.TransactionOuterClass;
import com.daml.ledger.api.v2.UpdateServiceGrpc;
import com.daml.ledger.api.v2.UpdateServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Show the requestedAt and executeBefore timestamps for the TransferOffer.
 *
 * Usage:
 *   ./gradlew backend:run --args="show-transfer-deadline --party <PARTY> [--offerContractId <CID> | --updateId <UPDATE_ID>] [--host <HOST>] [--port <PORT>] [--token <JWT>] [--verbose]"
 */
public final class ShowTransferDeadlineTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: show-transfer-deadline --party <PARTY> --updateId <UPDATE_ID> [--host <HOST>] [--port <PORT>] [--token <JWT>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            EventOuterClass.CreatedEvent offer = null;
            if (cli.offerCid != null) {
                offer = fetchOfferFromAcs(channel, cli);
            }
            if (offer == null) {
                offer = fetchOfferFromUpdate(channel, cli);
            }
            if (offer == null) {
                System.out.println("Offer not found (by offerContractId or updateId).");
                return;
            }

            if (cli.verbose) {
                System.out.println("Offer template=" + fmtId(offer.getTemplateId()) + " cid=" + offer.getContractId());
                System.out.println("  witnesses=" + String.join(",", offer.getWitnessPartiesList()));
                if (offer.hasCreateArguments()) {
                    System.out.println("  createArguments=" + offer.getCreateArguments());
                }
            }

            ValueOuterClass.Record offerArgs = offer.hasCreateArguments() ? offer.getCreateArguments() : null;
            if (offerArgs == null) {
                System.out.println("Offer has no createArguments in payload.");
                return;
            }

            Instant requestedAt = findTimestamp(offerArgs, "requestedat");
            Instant executeBefore = findTimestamp(offerArgs, "executebefore");

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            if (requestedAt != null) {
                System.out.println("requestedAt: UTC=" + fmtZ(requestedAt, ZoneId.of("UTC"), fmt) +
                        " Europe/Paris=" + fmtZ(requestedAt, ZoneId.of("Europe/Paris"), fmt));
            } else {
                System.out.println("requestedAt: not found");
            }
            if (executeBefore != null) {
                System.out.println("executeBefore: UTC=" + fmtZ(executeBefore, ZoneId.of("UTC"), fmt) +
                        " Europe/Paris=" + fmtZ(executeBefore, ZoneId.of("Europe/Paris"), fmt));
                boolean expired = Instant.now().isAfter(executeBefore);
                System.out.println("executeBefore expired? " + expired);
            } else {
                System.out.println("executeBefore: not found");
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static EventOuterClass.CreatedEvent findOffer(TransactionOuterClass.Transaction txn) {
        for (EventOuterClass.Event ev : txn.getEventsList()) {
            if (!ev.hasCreated()) continue;
            EventOuterClass.CreatedEvent c = ev.getCreated();
            String mod = c.getTemplateId().getModuleName().toLowerCase();
            String ent = c.getTemplateId().getEntityName().toLowerCase();
            if (mod.contains("transfer") && ent.contains("offer")) {
                return c;
            }
        }
        return null;
    }

    private static TransactionOuterClass.Transaction fetchUpdate(UpdateServiceGrpc.UpdateServiceBlockingStub update, Cli cli) {
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
                .setUpdateId(cli.updateId)
                .setUpdateFormat(updateFormat)
                .build();

        UpdateServiceOuterClass.GetUpdateResponse resp = update.getUpdateById(req);
        if (!resp.hasTransaction()) {
            System.out.println("No transaction payload for updateId=" + cli.updateId + " (case=" + resp.getUpdateCase() + ")");
            return null;
        }
        return resp.getTransaction();
    }

    private static EventOuterClass.CreatedEvent fetchOfferFromUpdate(ManagedChannel channel, Cli cli) {
        UpdateServiceGrpc.UpdateServiceBlockingStub update = UpdateServiceGrpc.newBlockingStub(channel);
        TransactionOuterClass.Transaction txn = fetchUpdate(update, cli);
        if (txn == null) return null;
        return findOffer(txn);
    }

    private static EventOuterClass.CreatedEvent fetchOfferFromAcs(ManagedChannel channel, Cli cli) {
        StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);
        long ledgerEnd = state.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build()).getOffset();
        TransactionFilterOuterClass.Filters wildcardFilters = TransactionFilterOuterClass.Filters.newBuilder()
                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                        .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                        .build())
                .build();
        TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                .putFiltersByParty(cli.party, wildcardFilters)
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
            if (cli.offerCid != null && cli.offerCid.equals(c.getContractId())) {
                return c;
            }
        }
        return null;
    }

    private static Instant findTimestamp(ValueOuterClass.Record rec, String targetLower) {
        for (ValueOuterClass.RecordField f : rec.getFieldsList()) {
            String label = f.getLabel().toLowerCase();
            ValueOuterClass.Value v = f.getValue();
            if (label.equals(targetLower)) {
                Instant ts = toInstant(v);
                if (ts != null) return ts;
            }
            if (v.hasRecord()) {
                Instant nested = findTimestamp(v.getRecord(), targetLower);
                if (nested != null) return nested;
            } else if (v.hasList()) {
                for (ValueOuterClass.Value lv : v.getList().getElementsList()) {
                    if (lv.hasRecord()) {
                        Instant nested = findTimestamp(lv.getRecord(), targetLower);
                        if (nested != null) return nested;
                    } else {
                        Instant ts = toInstant(lv);
                        if (ts != null && label.equals(targetLower)) {
                            return ts;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static Instant toInstant(ValueOuterClass.Value v) {
        return switch (v.getSumCase()) {
            case TIMESTAMP -> {
                long micros = v.getTimestamp();
                long secs = micros / 1_000_000L;
                long nanos = (micros % 1_000_000L) * 1_000L;
                yield Instant.ofEpochSecond(secs, nanos);
            }
            default -> null;
        };
    }

    private static String fmtZ(Instant i, ZoneId zone, DateTimeFormatter fmt) {
        ZonedDateTime z = ZonedDateTime.ofInstant(i, zone);
        return fmt.format(z);
    }

    private static String fmtId(ValueOuterClass.Identifier id) {
        return id.getPackageId() + ":" + id.getModuleName() + ":" + id.getEntityName();
    }

    private record Cli(String host, int port, String party, String offerCid, String updateId, String token, boolean verbose) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"show-transfer-deadline".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String party = null;
            String offerCid = null;
            String updateId = null;
            String token = "";
            boolean verbose = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--party" -> party = next(args, ++i, "--party");
                    case "--offerContractId" -> offerCid = next(args, ++i, "--offerContractId");
                    case "--updateId" -> updateId = next(args, ++i, "--updateId");
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--verbose" -> verbose = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (party == null || (offerCid == null && updateId == null)) {
                return null;
            }
            return new Cli(host, port, party, offerCid, updateId, token, verbose);
        }

        private static String next(String[] args, int idx, String flag) {
            if (idx >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
            return args[idx];
        }
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

