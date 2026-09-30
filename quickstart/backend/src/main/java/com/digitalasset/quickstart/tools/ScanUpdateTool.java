package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.TransactionOuterClass;
import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.daml.ledger.api.v2.UpdateServiceGrpc;
import com.daml.ledger.api.v2.UpdateServiceOuterClass;
import io.grpc.*;

/**
 * Scan a specific updateId and print all events with witnesses.
 *
 * Usage:
 *   ./gradlew backend:run --args="scan-update --host localhost --port 5001 --updateId <UPDATE_ID> [--token <JWT>] [--verbose]"
 */
public final class ScanUpdateTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: scan-update --host <HOST> --port <PORT> --updateId <UPDATE_ID> [--token <JWT>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
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
                    .setUpdateId(cli.updateId)
                    .setUpdateFormat(updateFormat)
                    .build();

            UpdateServiceOuterClass.GetUpdateResponse resp = update.getUpdateById(req);
            if (!resp.hasTransaction()) {
                System.out.println("No transaction payload found for updateId=" + cli.updateId + " updateCase=" + resp.getUpdateCase());
                return;
            }

            TransactionOuterClass.Transaction txn = resp.getTransaction();
            System.out.println("UpdateId: " + txn.getUpdateId() + " offset=" + txn.getOffset() + " workflowId=" + txn.getWorkflowId());
            txn.getEventsList().forEach(ev -> printEvent(ev, cli.verbose));
        } finally {
            channel.shutdownNow();
        }
    }

    private static void printEvent(EventOuterClass.Event ev, boolean verbose) {
        if (ev.hasCreated()) {
            var c = ev.getCreated();
            var tid = c.getTemplateId();
            boolean highlight = shouldHighlight(tid);
            System.out.println((highlight ? "[HIGHLIGHT] " : "") + "CREATED template=" + fmtId(tid) + " cid=" + c.getContractId());
            System.out.println("  witnesses=" + String.join(",", c.getWitnessPartiesList()));
            if (verbose || highlight) {
                System.out.println("  createArguments=" + c.getCreateArguments());
            }
        } else if (ev.hasArchived()) {
            var a = ev.getArchived();
            var tid = a.getTemplateId();
            boolean highlight = shouldHighlight(tid);
            System.out.println((highlight ? "[HIGHLIGHT] " : "") + "ARCHIVED template=" + fmtId(tid) + " cid=" + a.getContractId());
            System.out.println("  witnesses=" + String.join(",", a.getWitnessPartiesList()));
        } else if (ev.hasExercised()) {
            var ex = ev.getExercised();
            var tid = ex.getTemplateId();
            boolean highlight = shouldHighlight(tid) || shouldHighlightChoice(ex.getChoice());
            System.out.println((highlight ? "[HIGHLIGHT] " : "") + "EXERCISED template=" + fmtId(tid) + " cid=" + ex.getContractId() + " choice=" + ex.getChoice());
            System.out.println("  actingParties=" + String.join(",", ex.getActingPartiesList()) + " witnesses=" + String.join(",", ex.getWitnessPartiesList()));
            if (verbose || highlight) {
                System.out.println("  choiceArgument=" + ex.getChoiceArgument());
            }
        } else {
            System.out.println("UNKNOWN EVENT TYPE");
        }
    }

    private static boolean shouldHighlight(ValueOuterClass.Identifier tid) {
        String mod = tid.getModuleName();
        String ent = tid.getEntityName();
        return mod.contains("Transfer") || mod.contains("Offer") || ent.contains("Transfer") || ent.contains("Offer");
    }

    private static boolean shouldHighlightChoice(String choice) {
        String l = choice.toLowerCase();
        return l.contains("transfer") || l.contains("offer");
    }

    private static String fmtId(ValueOuterClass.Identifier id) {
        return id.getPackageId() + ":" + id.getModuleName() + ":" + id.getEntityName();
    }

    private record Cli(String host, int port, String updateId, String token, boolean verbose) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"scan-update".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String updateId = null;
            String token = "";
            boolean verbose = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--updateId" -> updateId = next(args, ++i, "--updateId");
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--verbose" -> verbose = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (updateId == null || updateId.isBlank()) {
                return null;
            }
            return new Cli(host, port, updateId, token, verbose);
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

