package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.TransactionOuterClass;
import com.daml.ledger.api.v2.UpdateServiceGrpc;
import com.daml.ledger.api.v2.UpdateServiceOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.CallOptions;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Local CLI tool: fetch transaction by updateId, find a TransferOffer-like created event,
 * and exercise Accept as the given party.
 *
 * Usage:
 *   ./gradlew backend:run --args="accept-transfer-offer --party <PARTY> --updateId <UPDATE_ID> [--host <HOST>] [--port <PORT>] [--token <TOKEN>] [--verbose]"
 *
 * No HTTP endpoints are added; this is for operators running locally.
 */
public final class AcceptTransferOfferTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: accept-transfer-offer --party <PARTY> --updateId <UPDATE_ID> [--host <HOST>] [--port <PORT>] [--token <TOKEN>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        if (cli.grpcAuthority != null && !cli.grpcAuthority.isBlank()) {
            builder = builder.overrideAuthority(cli.grpcAuthority);
        }
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            UpdateServiceGrpc.UpdateServiceFutureStub updates = UpdateServiceGrpc.newFutureStub(channel);
            CommandServiceGrpc.CommandServiceFutureStub commands = CommandServiceGrpc.newFutureStub(channel);

            TransactionOuterClass.Transaction txn = fetchUpdate(updates, cli.updateId, cli.party);
            if (txn == null) {
                System.err.println("No transaction/update visible for updateId=" + cli.updateId + " as party=" + cli.party);
                System.exit(2);
            }

            List<CreatedInfo> candidates = extractCandidates(txn, cli.verbose);
            if (candidates.isEmpty()) {
                System.err.println("No TransferOffer-like created events found in updateId=" + cli.updateId);
                System.exit(3);
            }
            CreatedInfo selected = pickBest(candidates);

            System.out.println("Selected contractId=" + selected.contractId);
            System.out.println("TemplateId=" + selected.templateId.getModuleName() + ":" + selected.templateId.getEntityName());
            if (selected.instrumentId != null) {
                System.out.println("InstrumentId=" + selected.instrumentId);
            }

            // Candidate choice names to try if the default is not present
            List<String> choiceNames = List.of(
                    "Accept",
                    "AcceptOffer",
                    "AcceptTransfer",
                    "TransferOffer_Accept",
                    "AcceptTransferOffer",
                    "AcceptAsReceiver",
                    "AcceptByReceiver",
                    "AcceptIncoming",
                    "AcceptProposal",
                    "AcceptInstruction",
                    "AcceptTransferInstruction"
            );

            // Targets: try template first, then a guessed interface id (same package, Splice.Wallet:TransferOffer) as a fallback
            List<ExerciseTarget> targets = new ArrayList<>();
            targets.add(new ExerciseTarget(selected.templateId, "template"));
            targets.add(new ExerciseTarget(ValueOuterClass.Identifier.newBuilder()
                    .setPackageId(selected.templateId.getPackageId())
                    .setModuleName("Splice.Wallet")
                    .setEntityName("TransferOffer")
                    .build(), "interface"));

            boolean submitted = false;
            Exception lastError = null;

            for (ExerciseTarget target : targets) {
                for (String choiceName : choiceNames) {
                    try {
                        CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                                .setTemplateId(target.identifier)
                                .setContractId(selected.contractId)
                                .setChoice(choiceName)
                                .setChoiceArgument(ValueOuterClass.Value.newBuilder()
                                        .setRecord(ValueOuterClass.Record.newBuilder().build())
                                        .build())
                                .build();

                        CommandsOuterClass.Commands cmds = CommandsOuterClass.Commands.newBuilder()
                                .setCommandId(UUID.randomUUID().toString())
                                .setUserId(cli.party)
                                .addActAs(cli.party)
                                .addReadAs(cli.party)
                                .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build())
                                .build();

                        com.daml.ledger.api.v2.TransactionFilterOuterClass.EventFormat eventFormat = com.daml.ledger.api.v2.TransactionFilterOuterClass.EventFormat.newBuilder()
                                .putFiltersByParty(cli.party, com.daml.ledger.api.v2.TransactionFilterOuterClass.Filters.newBuilder()
                                        .addCumulative(com.daml.ledger.api.v2.TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                                .setWildcardFilter(com.daml.ledger.api.v2.TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                                .build())
                                        .build())
                                .build();

                        com.daml.ledger.api.v2.TransactionFilterOuterClass.TransactionFormat txFormat = com.daml.ledger.api.v2.TransactionFilterOuterClass.TransactionFormat.newBuilder()
                                .setEventFormat(eventFormat)
                                .setTransactionShape(com.daml.ledger.api.v2.TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                                .build();

                        CommandServiceOuterClass.SubmitAndWaitForTransactionRequest req =
                                CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                                        .setCommands(cmds)
                                        .setTransactionFormat(txFormat)
                                        .build();

                        var resp = commands.submitAndWaitForTransaction(req).get();
                        String newUpdateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : "(no updateId)";
                        System.out.println("Accept submitted using " + target.label
                                + " id and choice " + choiceName + ". UpdateId=" + newUpdateId);
                        submitted = true;
                        break;
                    } catch (Exception e) {
                        lastError = e;
                        if (cli.verbose) {
                            System.err.println("Attempt with " + target.label
                                    + " and choice '" + choiceName + "' failed: " + e.getMessage());
                        }
                        // try next combination
                    }
                }
                if (submitted) {
                    break;
                }
            }

            if (!submitted) {
                throw lastError != null ? lastError : new RuntimeException("Failed to submit Accept command");
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static TransactionOuterClass.Transaction fetchUpdate(UpdateServiceGrpc.UpdateServiceFutureStub updates,
                                                                 String updateId,
                                                                 String party) throws Exception {
        com.daml.ledger.api.v2.TransactionFilterOuterClass.EventFormat eventFormat = com.daml.ledger.api.v2.TransactionFilterOuterClass.EventFormat.newBuilder()
                .putFiltersByParty(party, com.daml.ledger.api.v2.TransactionFilterOuterClass.Filters.newBuilder()
                        .addCumulative(com.daml.ledger.api.v2.TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                .setWildcardFilter(com.daml.ledger.api.v2.TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                .build())
                        .build())
                .build();

        com.daml.ledger.api.v2.TransactionFilterOuterClass.TransactionFormat txFormat = com.daml.ledger.api.v2.TransactionFilterOuterClass.TransactionFormat.newBuilder()
                .setEventFormat(eventFormat)
                .setTransactionShape(com.daml.ledger.api.v2.TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                .build();

        com.daml.ledger.api.v2.TransactionFilterOuterClass.UpdateFormat updateFormat = com.daml.ledger.api.v2.TransactionFilterOuterClass.UpdateFormat.newBuilder()
                .setIncludeTransactions(txFormat)
                .build();

        UpdateServiceOuterClass.GetUpdateByIdRequest req = UpdateServiceOuterClass.GetUpdateByIdRequest.newBuilder()
                .setUpdateId(updateId)
                .setUpdateFormat(updateFormat)
                .build();
        var resp = updates.getUpdateById(req).get();
        if (resp.getUpdateCase() == UpdateServiceOuterClass.GetUpdateResponse.UpdateCase.TRANSACTION) {
            return resp.getTransaction();
        }
        return null;
    }

    private static List<CreatedInfo> extractCandidates(TransactionOuterClass.Transaction txn, boolean verbose) {
        List<CreatedInfo> out = new ArrayList<>();
        for (EventOuterClass.Event ev : txn.getEventsList()) {
            if (!ev.hasCreated()) continue;
            EventOuterClass.CreatedEvent created = ev.getCreated();
            ValueOuterClass.Identifier tid = created.getTemplateId();
            String mod = tid.getModuleName().toLowerCase(Locale.ROOT);
            String ent = tid.getEntityName().toLowerCase(Locale.ROOT);
            if (mod.contains("transfer") || ent.contains("transfer")) {
                String instrumentId = extractInstrumentId(created.getCreateArguments());
                if (verbose) {
                    System.out.println("Candidate: cid=" + created.getContractId()
                            + " tmpl=" + tid.getModuleName() + ":" + tid.getEntityName()
                            + " instrument=" + instrumentId);
                }
                out.add(new CreatedInfo(created.getContractId(), tid, instrumentId));
            }
        }
        return out;
    }

    private static final class ExerciseTarget {
        final ValueOuterClass.Identifier identifier;
        final String label;
        ExerciseTarget(ValueOuterClass.Identifier identifier, String label) {
            this.identifier = identifier;
            this.label = label;
        }
    }

    private static CreatedInfo pickBest(List<CreatedInfo> candidates) {
        return candidates.stream()
                .sorted((a, b) -> {
                    boolean ah = a.instrumentId != null && !a.instrumentId.isBlank();
                    boolean bh = b.instrumentId != null && !b.instrumentId.isBlank();
                    if (ah == bh) return 0;
                    return ah ? -1 : 1;
                })
                .findFirst()
                .orElse(candidates.get(0));
    }

    private static String extractInstrumentId(ValueOuterClass.Record rec) {
        if (rec == null) return null;
        ValueOuterClass.Value v = getField(rec, "instrumentId", 4);
        if (v != null && v.hasRecord()) {
            ValueOuterClass.Record r = v.getRecord();
            ValueOuterClass.Value id = getField(r, "id", 1);
            if (id != null && id.hasText()) {
                return id.getText();
            }
        }
        for (var f : rec.getFieldsList()) {
            if (f.getValue().hasText()) {
                String lbl = f.getLabel().toLowerCase(Locale.ROOT);
                if (lbl.contains("instrument") || lbl.equals("id")) {
                    return f.getValue().getText();
                }
            }
        }
        return null;
    }

    private static ValueOuterClass.Value getField(ValueOuterClass.Record rec, String label, int index) {
        if (rec == null) return null;
        for (ValueOuterClass.RecordField f : rec.getFieldsList()) {
            if (label.equals(f.getLabel())) {
                return f.getValue();
            }
        }
        if (rec.getFieldsCount() > index) {
            return rec.getFields(index).getValue();
        }
        return null;
    }

    private record CreatedInfo(String contractId, ValueOuterClass.Identifier templateId, String instrumentId) {}

    private record Cli(String party, String updateId, String host, int port, String token, boolean verbose, String grpcAuthority, String applicationId) {
        static Cli parse(String[] args) {
            String party = null;
            String updateId = null;
            String host = System.getenv().getOrDefault("LEDGER_HOST", "localhost");
            int port = Integer.parseInt(System.getenv().getOrDefault("LEDGER_PORT", "5001"));
            String token = System.getenv().getOrDefault("LEDGER_API_TOKEN", "");
            String grpcAuthority = System.getenv().getOrDefault("LEDGER_GRPC_AUTHORITY", "");
            String appId = System.getenv().getOrDefault("LEDGER_APP_ID", "quickstart-backend");
            boolean verbose = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--party" -> party = (i + 1 < args.length) ? args[++i] : null;
                    case "--updateId" -> updateId = (i + 1 < args.length) ? args[++i] : null;
                    case "--host" -> host = (i + 1 < args.length) ? args[++i] : host;
                    case "--port" -> {
                        if (i + 1 < args.length) {
                            port = Integer.parseInt(args[++i]);
                        }
                    }
                    case "--token" -> token = (i + 1 < args.length) ? args[++i] : token;
                    case "--grpcAuthority" -> grpcAuthority = (i + 1 < args.length) ? args[++i] : grpcAuthority;
                    case "--verbose" -> verbose = true;
                    default -> { }
                }
            }
            if (party == null || updateId == null) {
                return null;
            }
            return new Cli(party, updateId, host, port, token, verbose, grpcAuthority, appId);
        }
    }

    private static class AuthInterceptor implements ClientInterceptor {
        private final Metadata.Key<String> AUTH_HEADER = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        private final String token;
        AuthInterceptor(String token) {
            this.token = token == null ? "" : token;
        }
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    if (!token.isEmpty()) {
                        headers.put(AUTH_HEADER, "Bearer " + token);
                    }
                    super.start(responseListener, headers);
                }
            };
        }
    }
}
