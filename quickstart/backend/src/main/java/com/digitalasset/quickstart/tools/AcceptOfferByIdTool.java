package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import io.grpc.*;

import java.util.List;
import java.util.UUID;

/**
 * Exercise a choice on a specific contractId using raw identifiers.
 *
 * Usage:
 *   ./gradlew backend:run --args="accept-offer --party <PARTY> --contractId <CID> --templateModule <Module> --templateEntity <Entity> --choice <Choice> [--host <HOST>] [--port <PORT>] [--token <JWT>] [--argJson <json>] [--verbose]"
 *
 * Note: argJson is accepted for future payloads; currently only Unit is sent.
 */
public final class AcceptOfferByIdTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: accept-offer --party <PARTY> --contractId <CID> --templateModule <Module> --templateEntity <Entity> --choice <Choice> [--host <HOST>] [--port <PORT>] [--token <JWT>] [--argJson <json>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            CommandServiceGrpc.CommandServiceFutureStub commands = CommandServiceGrpc.newFutureStub(channel);

            var attemptOrder = java.util.List.of(
                    cli.choice,
                    "AcceptTransferOffer",
                    "TransferOffer_Accept",
                    "Accept",
                    "AcceptOffer",
                    "AcceptAsReceiver",
                    "AcceptByReceiver",
                    "AcceptIncoming",
                    "AcceptProposal",
                    "AcceptInstruction",
                    "AcceptTransferInstruction",
                    "Claim",
                    "Receive",
                    "Execute",
                    "Settle",
                    "ReceiveTransferOffer",
                    "AcceptTransfer",
                    "AcceptOfferV0",
                    "AcceptTransferOfferV0",
                    "AcceptOffer_Receive",
                    "AcceptTransferRequest",
                    "AcceptOfferRequest",
                    "AcceptOfferAndClaim",
                    "AcceptOfferAndReceive",
                    "Take",
                    "Collect",
                    "Confirm"
            );

            boolean submitted = false;
            Exception lastErr = null;
            for (String choiceName : attemptOrder) {
                try {
                    ValueOuterClass.Identifier templateId = ValueOuterClass.Identifier.newBuilder()
                            .setPackageId(cli.packageIdOrEmpty())
                            .setModuleName(cli.templateModule)
                            .setEntityName(cli.templateEntity)
                            .build();

                    ValueOuterClass.Record choiceArgs = ValueOuterClass.Record.newBuilder().build(); // Unit

                    CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                            .setTemplateId(templateId)
                            .setContractId(cli.contractId)
                            .setChoice(choiceName)
                            .setChoiceArgument(ValueOuterClass.Value.newBuilder().setRecord(choiceArgs).build())
                            .build();

                    CommandsOuterClass.Commands cmds = CommandsOuterClass.Commands.newBuilder()
                            .setCommandId(UUID.randomUUID().toString())
                            .setUserId(cli.party)
                            .addActAs(cli.party)
                            .addReadAs(cli.party)
                            .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build())
                            .build();

                    TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                            .putFiltersByParty(cli.party, TransactionFilterOuterClass.Filters.newBuilder()
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
                                    .setCommands(cmds)
                                    .setTransactionFormat(txFormat)
                                    .build();

                    var resp = commands.submitAndWaitForTransaction(req).get();
                    String updateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : "(no updateId)";
                    System.out.println("Success using choice=" + choiceName + ". updateId=" + updateId);
                    submitted = true;
                    break;
                } catch (Exception e) {
                    lastErr = e;
                    if (cli.verbose) {
                        System.err.println("Attempt with choice '" + choiceName + "' failed: " + e.getMessage());
                    }
                }
            }
            if (!submitted) {
                throw lastErr != null ? lastErr : new RuntimeException("All choice attempts failed");
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static final class Cli {
        final String host;
        final int port;
        final String party;
        final String contractId;
        final String packageId;
        final String templateModule;
        final String templateEntity;
        final String choice;
        final String argJson;
        final String token;
        final boolean verbose;

        private Cli(String host, int port, String party, String contractId, String packageId, String templateModule, String templateEntity, String choice, String argJson, String token, boolean verbose) {
            this.host = host;
            this.port = port;
            this.party = party;
            this.contractId = contractId;
            this.packageId = packageId;
            this.templateModule = templateModule;
            this.templateEntity = templateEntity;
            this.choice = choice;
            this.argJson = argJson;
            this.token = token;
            this.verbose = verbose;
        }

        String packageIdOrEmpty() {
            return packageId == null ? "" : packageId;
        }

        static Cli parse(String[] args) {
            if (args.length == 0 || !"accept-offer".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String party = null;
            String contractId = null;
            String packageId = null;
            String templateModule = null;
            String templateEntity = null;
            String choice = null;
            String argJson = null;
            String token = "";
            boolean verbose = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--party" -> party = next(args, ++i, "--party");
                    case "--contractId" -> contractId = next(args, ++i, "--contractId");
                    case "--packageId" -> packageId = next(args, ++i, "--packageId");
                    case "--templateModule" -> templateModule = next(args, ++i, "--templateModule");
                    case "--templateEntity" -> templateEntity = next(args, ++i, "--templateEntity");
                    case "--choice" -> choice = next(args, ++i, "--choice");
                    case "--argJson" -> argJson = next(args, ++i, "--argJson");
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--verbose" -> verbose = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (party == null || contractId == null || templateModule == null || templateEntity == null || choice == null) {
                return null;
            }
            return new Cli(host, port, party, contractId, packageId, templateModule, templateEntity, choice, argJson, token, verbose);
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

