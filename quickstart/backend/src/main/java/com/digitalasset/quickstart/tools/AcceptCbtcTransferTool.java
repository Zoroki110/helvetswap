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
 * Accept the CBTC transfer by locating the TransferInstruction contract and exercising TransferInstruction_Accept.
 *
 * Usage:
 *   ./gradlew backend:run --args="accept-cbtc-transfer --party <PARTY> --updateId <UPDATE_ID> [--host <HOST>] [--port <PORT>] [--token <JWT>] [--verbose]"
 */
public final class AcceptCbtcTransferTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: accept-cbtc-transfer --party <PARTY> --updateId <UPDATE_ID> [--host <HOST>] [--port <PORT>] [--token <JWT>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            TransferInstructionFinder finder = new TransferInstructionFinder(cli.host, cli.port, cli.token);
            TransferInstructionFinder.Result res = finder.find(cli.party, cli.offerCid, cli.updateId, cli.verbose);
            if (cli.verbose) {
                TransferInstructionFinder.printOfferSummary(res.offer());
            }
            if (res.bestCandidate() == null) {
                System.out.println("No TransferInstruction contract disclosed to party=" + cli.party + ". Cannot accept.");
                if (!res.relatedTransfers().isEmpty()) {
                    System.out.println("Other transfer-like contracts visible (up to 20):");
                    res.relatedTransfers().stream().limit(20).forEach(r ->
                            System.out.println("  cid=" + r.contractId() + " template=" + TransferInstructionFinder.fmtId(r.templateId())));
                }
                return;
            }

            var cand = res.bestCandidate();
            System.out.println("Attempting TransferInstruction_Accept on cid=" + cand.contractId() + " template=" + TransferInstructionFinder.fmtId(cand.templateId()));
            ValueOuterClass.Record choiceArgs = ValueOuterClass.Record.newBuilder().build(); // Unit

            CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(cand.templateId())
                    .setContractId(cand.contractId())
                    .setChoice("TransferInstruction_Accept")
                    .setChoiceArgument(ValueOuterClass.Value.newBuilder().setRecord(choiceArgs).build())
                    .build();

            CommandsOuterClass.Commands cmds = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(UUID.randomUUID().toString())
                    .setUserId(cli.party)
                    .addActAs(cli.party)
                    .addReadAs(cli.party)
                    .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build())
                    .build();

            TransactionFilterOuterClass.EventFormat submitEventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(cli.party, TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();
            TransactionFilterOuterClass.TransactionFormat txFormat = TransactionFilterOuterClass.TransactionFormat.newBuilder()
                    .setEventFormat(submitEventFormat)
                    .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                    .build();

            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest req =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(cmds)
                            .setTransactionFormat(txFormat)
                            .build();

            CommandServiceGrpc.CommandServiceFutureStub commands = CommandServiceGrpc.newFutureStub(channel);
            var resp = commands.submitAndWaitForTransaction(req).get();
            String updateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : "(no updateId)";
            System.out.println("SUCCESS: accepted cid=" + cand.contractId() + " updateId=" + updateId);
            System.out.println("Hint: curl -s \"http://localhost:8080/api/holdings/" + cli.party + "\" | jq");
        } finally {
            channel.shutdownNow();
        }
    }

    private static String fmtId(ValueOuterClass.Identifier id) {
        return id.getPackageId() + ":" + id.getModuleName() + ":" + id.getEntityName();
    }

    private record Cli(String host, int port, String party, String offerCid, String updateId, String token, boolean verbose) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"accept-cbtc-transfer".equals(args[0])) {
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

