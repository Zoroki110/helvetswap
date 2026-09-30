package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Dump the created_event_blob of a TransferOffer from ACS, parse it, and heuristically
 * search for TransferInstruction references.
 *
 * Usage examples:
 *   ./gradlew backend:run --args="decode-offer-blob --host localhost --port 5001 --party <PARTY> --offerContractId <CID> --verbose"
 *   ./gradlew backend:run --args="decode-offer-blob --host localhost --port 5001 --party <PARTY> --offerContractId <CID> --out /tmp/offer.bin"
 */
public final class DecodeOfferBlobTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: decode-offer-blob --party <PARTY> --offerContractId <CID> [--host <HOST>] [--port <PORT>] [--out <PATH>] [--token <JWT>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            EventOuterClass.CreatedEvent offer = fetchOfferWithBlob(channel, cli);
            if (offer == null) {
                System.out.println("Offer not found in ACS for party=" + cli.party + " cid=" + cli.offerCid);
                return;
            }
            ByteString blob = offer.getCreatedEventBlob();
            if (blob == null || blob.isEmpty()) {
                System.out.println("No created_event_blob returned; check event_format includeCreatedEventBlob.");
                return;
            }
            Path outPath = Path.of(cli.out);
            Files.write(outPath, blob.toByteArray());
            System.out.println("Wrote created_event_blob to " + outPath + " (" + blob.size() + " bytes)");

            ValueOuterClass.Value parsed = tryParse(blob);
            if (parsed == null) {
                System.out.println("Failed to parse blob as Record or Value protobuf.");
                return;
            }

            List<Candidate> candidates = new ArrayList<>();
            System.out.println("== Parsed blob (tree dump) ==");
            dumpValue(parsed, "root", "", candidates);

            System.out.println();
            System.out.println("POTENTIAL TRANSFER INSTRUCTION REFERENCES:");
            if (candidates.isEmpty()) {
                System.out.println("  (none found)");
            } else {
                candidates.forEach(c -> System.out.println("  " + c.path() + " -> " + c.value()));
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static EventOuterClass.CreatedEvent fetchOfferWithBlob(ManagedChannel channel, Cli cli) {
        StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);

        TransactionFilterOuterClass.Filters wildcardFilters = TransactionFilterOuterClass.Filters.newBuilder()
                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                        .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder()
                                .setIncludeCreatedEventBlob(true)
                                .build())
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
                if (cli.verbose) {
                    System.out.println("Found offer cid=" + c.getContractId() + " template=" + fmtId(c.getTemplateId()));
                    System.out.println("  witnesses=" + String.join(",", c.getWitnessPartiesList()));
                    if (c.hasCreateArguments()) {
                        System.out.println("  createArguments=" + c.getCreateArguments());
                    }
                }
                return c;
            }
        }
        return null;
    }

    private static ValueOuterClass.Value tryParse(ByteString blob) {
        // Attempt Record.parseFrom first.
        try {
            ValueOuterClass.Record rec = ValueOuterClass.Record.parseFrom(blob);
            if (rec.getFieldsCount() > 0) {
                return ValueOuterClass.Value.newBuilder().setRecord(rec).build();
            }
        } catch (InvalidProtocolBufferException ignored) {
        }

        try {
            ValueOuterClass.Value v = ValueOuterClass.Value.parseFrom(blob);
            if (v != null) {
                if (v.hasRecord()) {
                    return v;
                }
                // Wrap bare types in a record-like print.
                ValueOuterClass.Record rec = ValueOuterClass.Record.newBuilder()
                        .addFields(ValueOuterClass.RecordField.newBuilder()
                                .setLabel("value")
                                .setValue(v)
                                .build())
                        .build();
                return ValueOuterClass.Value.newBuilder().setRecord(rec).build();
            }
        } catch (InvalidProtocolBufferException ignored) {
        }
        return null;
    }

    private static void dumpValue(ValueOuterClass.Value v, String path, String indent, List<Candidate> candidates) {
        String shortVal = describe(v);
        System.out.println(indent + path + ": " + shortVal);

        maybeAddCandidate(path, v, candidates);

        switch (v.getSumCase()) {
            case RECORD -> {
                int idx = 0;
                for (ValueOuterClass.RecordField f : v.getRecord().getFieldsList()) {
                    String lbl = f.getLabel().isBlank() ? ("field" + idx) : f.getLabel();
                    dumpValue(f.getValue(), path + "." + lbl, indent + "  ", candidates);
                    idx++;
                }
            }
            case LIST -> {
                int idx = 0;
                for (ValueOuterClass.Value lv : v.getList().getElementsList()) {
                    dumpValue(lv, path + "[" + idx + "]", indent + "  ", candidates);
                    idx++;
                }
            }
            case OPTIONAL -> {
                if (v.getOptional().hasValue()) {
                    dumpValue(v.getOptional().getValue(), path + ".some", indent + "  ", candidates);
                } else {
                    System.out.println(indent + "  " + path + ".none");
                }
            }
            case VARIANT -> dumpValue(v.getVariant().getValue(), path + "." + v.getVariant().getConstructor(), indent + "  ", candidates);
            case TEXT_MAP -> {
                int idx = 0;
                for (var e : v.getTextMap().getEntriesList()) {
                    dumpValue(e.getValue(), path + ".textMap[" + e.getKey() + "]", indent + "  ", candidates);
                    idx++;
                }
            }
            case GEN_MAP -> {
                int idx = 0;
                for (var e : v.getGenMap().getEntriesList()) {
                    String keyStr = describe(e.getKey());
                    dumpValue(e.getValue(), path + ".genMap[" + keyStr + "]", indent + "  ", candidates);
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
                yield "timestamp:" + i.toString();
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

    private static void maybeAddCandidate(String path, ValueOuterClass.Value v, List<Candidate> candidates) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        boolean pathSuggests = lowerPath.contains("instruction");

        switch (v.getSumCase()) {
            case CONTRACT_ID -> candidates.add(new Candidate(path, v.getContractId()));
            case TEXT -> {
                String t = v.getText();
                if (pathSuggests || looksLikeId(t)) {
                    candidates.add(new Candidate(path, t));
                }
            }
            case RECORD, OPTIONAL, VARIANT, LIST, GEN_MAP, TEXT_MAP -> {
                if (pathSuggests) {
                    candidates.add(new Candidate(path, describe(v)));
                }
            }
            default -> {
                if (pathSuggests) {
                    candidates.add(new Candidate(path, describe(v)));
                }
            }
        }
    }

    private static boolean looksLikeId(String t) {
        if (t == null || t.length() < 20) return false;
        if (CONTRACT_ID_LIKE.matcher(t).matches()) return true;
        return t.startsWith("00") && t.length() >= 30;
    }

    private static String fmtId(ValueOuterClass.Identifier id) {
        return id.getPackageId() + ":" + id.getModuleName() + ":" + id.getEntityName();
    }

    private record Candidate(String path, String value) {}

    private record Cli(String host, int port, String party, String offerCid, String out, String token, boolean verbose) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"decode-offer-blob".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String party = null;
            String offerCid = null;
            String out = "/tmp/transferoffer-blob.bin";
            String token = "";
            boolean verbose = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--party" -> party = next(args, ++i, "--party");
                    case "--offerContractId" -> offerCid = next(args, ++i, "--offerContractId");
                    case "--out" -> out = next(args, ++i, "--out");
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
            return new Cli(host, port, party, offerCid, out, token, verbose);
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

    private static final Pattern CONTRACT_ID_LIKE = Pattern.compile("[0-9a-fA-F]{30,}");
}

