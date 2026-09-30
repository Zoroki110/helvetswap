package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Dump the create_arguments for a TransferOffer from ACS and display deadline timestamps.
 *
 * Usage examples:
 *   ./gradlew backend:run --args="dump-offer-create-args --host localhost --port 5001 --party <PARTY> --offerContractId <CID> --verbose"
 */
public final class DumpOfferCreateArgsTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: dump-offer-create-args --party <PARTY> --offerContractId <CID> [--host <HOST>] [--port <PORT>] [--token <JWT>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            EventOuterClass.CreatedEvent offer = fetchOffer(channel, cli);
            if (offer == null) {
                System.out.println("Offer not found in ACS for party=" + cli.party + " cid=" + cli.offerCid);
                return;
            }

            System.out.println("Offer cid=" + offer.getContractId() + " template=" + fmtId(offer.getTemplateId()));
            System.out.println("  witnesses=" + String.join(",", offer.getWitnessPartiesList()));

            if (offer.hasCreateArguments()) {
                System.out.println("== create_arguments ==");
                dumpValue(ValueOuterClass.Value.newBuilder().setRecord(offer.getCreateArguments()).build(), "root", "");
            } else {
                System.out.println("Offer has no create_arguments.");
            }

            ValueOuterClass.Record rec = offer.hasCreateArguments() ? offer.getCreateArguments() : null;
            if (rec != null) {
                Long requestedAtMicros = findTimestampMicros(rec, "requestedat");
                Long executeBeforeMicros = findTimestampMicros(rec, "executebefore");
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
                if (requestedAtMicros != null) {
                    Instant i = toInstant(requestedAtMicros);
                    System.out.println("requestedAt: UTC=" + fmtZ(i, ZoneId.of("UTC"), fmt) +
                            " Europe/Paris=" + fmtZ(i, ZoneId.of("Europe/Paris"), fmt));
                } else {
                    System.out.println("requestedAt: not found");
                }
                if (executeBeforeMicros != null) {
                    Instant i = toInstant(executeBeforeMicros);
                    System.out.println("executeBefore: UTC=" + fmtZ(i, ZoneId.of("UTC"), fmt) +
                            " Europe/Paris=" + fmtZ(i, ZoneId.of("Europe/Paris"), fmt));
                    System.out.println("executeBefore expired? " + Instant.now().isAfter(i));
                } else {
                    System.out.println("executeBefore: not found");
                }
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static EventOuterClass.CreatedEvent fetchOffer(ManagedChannel channel, Cli cli) {
        StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);

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
                .build();

        var it = state.getActiveContracts(acsReq);
        while (it.hasNext()) {
            var resp = it.next();
            if (!resp.hasActiveContract()) continue;
            EventOuterClass.CreatedEvent c = resp.getActiveContract().getCreatedEvent();
            if (cli.offerCid.equals(c.getContractId())) {
                return c;
            }
        }
        return null;
    }

    private static void dumpValue(ValueOuterClass.Value v, String path, String indent) {
        String shortVal = describe(v);
        System.out.println(indent + path + ": " + shortVal);
        switch (v.getSumCase()) {
            case RECORD -> {
                int idx = 0;
                for (ValueOuterClass.RecordField f : v.getRecord().getFieldsList()) {
                    String lbl = f.getLabel().isBlank() ? ("field" + idx) : f.getLabel();
                    dumpValue(f.getValue(), path + "." + lbl, indent + "  ");
                    idx++;
                }
            }
            case LIST -> {
                int idx = 0;
                for (ValueOuterClass.Value lv : v.getList().getElementsList()) {
                    dumpValue(lv, path + "[" + idx + "]", indent + "  ");
                    idx++;
                }
            }
            case OPTIONAL -> {
                if (v.getOptional().hasValue()) {
                    dumpValue(v.getOptional().getValue(), path + ".some", indent + "  ");
                } else {
                    System.out.println(indent + "  " + path + ".none");
                }
            }
            case VARIANT -> dumpValue(v.getVariant().getValue(), path + "." + v.getVariant().getConstructor(), indent + "  ");
            case TEXT_MAP -> {
                int idx = 0;
                for (var e : v.getTextMap().getEntriesList()) {
                    dumpValue(e.getValue(), path + ".textMap[" + e.getKey() + "]", indent + "  ");
                    idx++;
                }
            }
            case GEN_MAP -> {
                int idx = 0;
                for (var e : v.getGenMap().getEntriesList()) {
                    String keyStr = describe(e.getKey());
                    dumpValue(e.getValue(), path + ".genMap[" + keyStr + "]", indent + "  ");
                    idx++;
                }
            }
            default -> {
            }
        }
    }

    private static String describe(ValueOuterClass.Value v) {
        return switch (v.getSumCase()) {
            case PARTY -> "party:" + v.getParty();
            case CONTRACT_ID -> "contractId:" + v.getContractId();
            case TEXT -> "text:" + v.getText();
            case NUMERIC -> "numeric:" + v.getNumeric();
            case INT64 -> "int64:" + v.getInt64();
            case TIMESTAMP -> {
                long micros = v.getTimestamp();
                long secs = micros / 1_000_000L;
                long nanos = (micros % 1_000_000L) * 1_000L;
                Instant i = Instant.ofEpochSecond(secs, nanos);
                yield "timestamp:" + i;
            }
            case DATE -> "date:" + v.getDate();
            case BOOL -> "bool:" + v.getBool();
            case UNIT -> "unit";
            case ENUM -> "enum:" + v.getEnum().getConstructor();
            case VARIANT -> "variant:" + v.getVariant().getConstructor();
            case RECORD -> "record(" + v.getRecord().getFieldsCount() + " fields)";
            case LIST -> "list(" + v.getList().getElementsCount() + ")";
            case TEXT_MAP -> "textMap(" + v.getTextMap().getEntriesCount() + ")";
            case GEN_MAP -> "genMap(" + v.getGenMap().getEntriesCount() + ")";
            case OPTIONAL -> v.getOptional().hasValue() ? "optional(some)" : "optional(none)";
            case SUM_NOT_SET -> "<empty>";
        };
    }

    private static Long findTimestampMicros(ValueOuterClass.Record rec, String targetLower) {
        for (ValueOuterClass.RecordField f : rec.getFieldsList()) {
            String label = f.getLabel().toLowerCase(Locale.ROOT);
            ValueOuterClass.Value v = f.getValue();
            if (label.equals(targetLower) && v.getSumCase() == ValueOuterClass.Value.SumCase.TIMESTAMP) {
                return v.getTimestamp();
            }
            if (v.hasRecord()) {
                Long nested = findTimestampMicros(v.getRecord(), targetLower);
                if (nested != null) return nested;
            } else if (v.hasList()) {
                for (ValueOuterClass.Value lv : v.getList().getElementsList()) {
                    if (lv.hasRecord()) {
                        Long nested = findTimestampMicros(lv.getRecord(), targetLower);
                        if (nested != null) return nested;
                    } else if (lv.getSumCase() == ValueOuterClass.Value.SumCase.TIMESTAMP && label.equals(targetLower)) {
                        return lv.getTimestamp();
                    }
                }
            }
        }
        return null;
    }

    private static Instant toInstant(long micros) {
        long secs = micros / 1_000_000L;
        long nanos = (micros % 1_000_000L) * 1_000L;
        return Instant.ofEpochSecond(secs, nanos);
    }

    private static String fmtZ(Instant i, ZoneId zone, DateTimeFormatter fmt) {
        ZonedDateTime z = ZonedDateTime.ofInstant(i, zone);
        return fmt.format(z);
    }

    private static String fmtId(ValueOuterClass.Identifier id) {
        return id.getPackageId() + ":" + id.getModuleName() + ":" + id.getEntityName();
    }

    private record Cli(String host, int port, String party, String offerCid, String token, boolean verbose) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"dump-offer-create-args".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String party = null;
            String offerCid = null;
            String token = "";
            boolean verbose = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--party" -> party = next(args, ++i, "--party");
                    case "--offerContractId" -> offerCid = next(args, ++i, "--offerContractId");
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--verbose" -> verbose = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (party == null || offerCid == null) {
                return null;
            }
            return new Cli(host, port, party, offerCid, token, verbose);
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

