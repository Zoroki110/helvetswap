package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import io.grpc.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Local CLI tool to accept wallet TransferOffer contracts (Splice.Wallet.TransferOffer:TransferOffer).
 * No HTTP endpoints are added; operators run this manually.
 *
 * Usage:
 *   ./gradlew backend:run --args="accept-wallet-transfer-offers --party <PARTY> [--contractId <cid>] [--acceptAll] [--host <HOST>] [--port <PORT>] [--token <TOKEN>] [--verbose]"
 */
public final class AcceptWalletTransferOffersTool {

    private static final String TARGET_MODULE = "Splice.Wallet.TransferOffer";
    private static final String TARGET_ENTITY = "TransferOffer";
    private static final String CHOICE_NAME = "TransferOffer_Accept";

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: accept-wallet-transfer-offers --party <PARTY> [--contractId <cid>] [--acceptAll] [--host <HOST>] [--port <PORT>] [--token <TOKEN>] [--verbose]");
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
            StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);
            CommandServiceGrpc.CommandServiceFutureStub commands = CommandServiceGrpc.newFutureStub(channel);

            List<Candidate> candidates = fetchCandidates(state, cli.party, cli.verbose);
            if (candidates.isEmpty()) {
                System.err.println("No Splice.Wallet.TransferOffer:TransferOffer contracts visible for party=" + cli.party);
                System.exit(2);
            }

            List<Candidate> toAccept = new ArrayList<>();
            if (cli.contractId != null) {
                candidates.stream()
                        .filter(c -> c.contractId.equals(cli.contractId))
                        .findFirst()
                        .ifPresent(toAccept::add);
                if (toAccept.isEmpty()) {
                    System.err.println("Specified contractId not found or not visible: " + cli.contractId);
                    System.exit(3);
                }
            } else {
                // Default: pick receiver matches party
                for (Candidate c : candidates) {
                    if (cli.acceptAll) {
                        if (cli.party.equals(c.receiver)) {
                            toAccept.add(c);
                        }
                    } else {
                        if (cli.party.equals(c.receiver)) {
                            toAccept.add(c);
                            break;
                        }
                    }
                }
                if (toAccept.isEmpty()) {
                    // fallback: first candidate
                    toAccept.add(candidates.get(0));
                }
            }

            if (!cli.verbose) {
                System.out.println("Found " + candidates.size() + " candidate(s). Will accept " + toAccept.size() + " contract(s).");
            } else {
                System.out.println("Candidates:");
                candidates.forEach(c -> System.out.println("  cid=" + c.contractId + " sender=" + nullSafe(c.sender) + " receiver=" + nullSafe(c.receiver) + " amount=" + nullSafe(c.amount)));
                System.out.println("Selected to accept:");
                toAccept.forEach(c -> System.out.println("  cid=" + c.contractId + " sender=" + nullSafe(c.sender) + " receiver=" + nullSafe(c.receiver) + " amount=" + nullSafe(c.amount)));
            }

            for (Candidate c : toAccept) {
                submitAccept(commands, c, cli.party);
            }

            System.out.println("Done. Verify holdings with:");
            System.out.println("  curl -s \"http://localhost:8080/api/holdings/" + cli.party + "\" | jq");
        } finally {
            channel.shutdownNow();
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "(unknown)" : s;
    }

    private static List<Candidate> fetchCandidates(StateServiceGrpc.StateServiceBlockingStub state,
                                                   String party,
                                                   boolean verbose) {
        List<Candidate> results = new ArrayList<>();

        long ledgerEnd = state.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build()).getOffset();

        TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                .putFiltersByParty(party, TransactionFilterOuterClass.Filters.newBuilder()
                        .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                .build())
                        .build())
                .build();

        StateServiceOuterClass.GetActiveContractsRequest req = StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                .setEventFormat(eventFormat)
                .setActiveAtOffset(ledgerEnd)
                .build();

        var it = state.getActiveContracts(req);
        while (it.hasNext()) {
            StateServiceOuterClass.GetActiveContractsResponse resp = it.next();
            if (!resp.hasActiveContract()) continue;
            EventOuterClass.CreatedEvent created = resp.getActiveContract().getCreatedEvent();
            ValueOuterClass.Identifier tmpl = created.getTemplateId();
            if (!TARGET_MODULE.equals(tmpl.getModuleName()) || !TARGET_ENTITY.equals(tmpl.getEntityName())) {
                continue;
            }
            Candidate c = new Candidate(created.getContractId(), tmpl, parseSender(created), parseReceiver(created), parseAmount(created));
            if (verbose) {
                System.out.println("Candidate: cid=" + c.contractId + " sender=" + nullSafe(c.sender) + " receiver=" + nullSafe(c.receiver) + " amount=" + nullSafe(c.amount));
            }
            results.add(c);
        }
        return results;
    }

    private static String parseSender(EventOuterClass.CreatedEvent created) {
        ValueOuterClass.Record args = created.hasCreateArguments() ? created.getCreateArguments() : null;
        if (args == null) return null;
        String sender = null;
        for (ValueOuterClass.RecordField f : args.getFieldsList()) {
            if (f.hasValue() && f.getValue().hasParty()) {
                if (sender == null) {
                    sender = f.getValue().getParty();
                }
            }
        }
        return sender;
    }

    private static String parseReceiver(EventOuterClass.CreatedEvent created) {
        ValueOuterClass.Record args = created.hasCreateArguments() ? created.getCreateArguments() : null;
        if (args == null) return null;
        String sender = null;
        String receiver = null;
        for (ValueOuterClass.RecordField f : args.getFieldsList()) {
            if (f.hasValue() && f.getValue().hasParty()) {
                if (sender == null) {
                    sender = f.getValue().getParty();
                } else if (receiver == null) {
                    receiver = f.getValue().getParty();
                    break;
                }
            }
        }
        return receiver;
    }

    private static String parseAmount(EventOuterClass.CreatedEvent created) {
        ValueOuterClass.Record args = created.hasCreateArguments() ? created.getCreateArguments() : null;
        if (args == null) return null;
        for (ValueOuterClass.RecordField f : args.getFieldsList()) {
            if (f.hasValue() && f.getValue().hasNumeric()) {
                return f.getValue().getNumeric();
            }
        }
        return null;
    }

    private static void submitAccept(CommandServiceGrpc.CommandServiceFutureStub commands,
                                     Candidate candidate,
                                     String party) throws Exception {
        CommandsOuterClass.ExerciseCommand exercise = CommandsOuterClass.ExerciseCommand.newBuilder()
                .setTemplateId(candidate.templateId)
                .setContractId(candidate.contractId)
                .setChoice(CHOICE_NAME)
                .setChoiceArgument(ValueOuterClass.Value.newBuilder()
                        .setRecord(ValueOuterClass.Record.newBuilder().build())
                        .build())
                .build();

        CommandsOuterClass.Commands cmds = CommandsOuterClass.Commands.newBuilder()
                .setCommandId(UUID.randomUUID().toString())
                .setUserId(party)
                .addActAs(party)
                .addReadAs(party)
                .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise).build())
                .build();

        TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                .putFiltersByParty(party, TransactionFilterOuterClass.Filters.newBuilder()
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
        System.out.println("Accepted cid=" + candidate.contractId + " updateId=" + updateId);
    }

    private record Candidate(String contractId, ValueOuterClass.Identifier templateId, String sender, String receiver, String amount) {}

    private static final class Cli {
        final String party;
        final String contractId;
        final boolean acceptAll;
        final boolean verbose;
        final String host;
        final int port;
        final String token;
        final String grpcAuthority;

        private Cli(String party, String contractId, boolean acceptAll, boolean verbose, String host, int port, String token, String grpcAuthority) {
            this.party = party;
            this.contractId = contractId;
            this.acceptAll = acceptAll;
            this.verbose = verbose;
            this.host = host;
            this.port = port;
            this.token = token;
            this.grpcAuthority = grpcAuthority;
        }

        static Cli parse(String[] args) {
            if (args.length == 0 || !"accept-wallet-transfer-offers".equals(args[0])) {
                return null;
            }
            String party = null;
            String contractId = null;
            boolean acceptAll = false;
            boolean verbose = false;
            String host = "localhost";
            int port = 6865;
            String token = "";
            String authority = null;

            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--party" -> party = nextArg(args, ++i, "--party");
                    case "--contractId" -> contractId = nextArg(args, ++i, "--contractId");
                    case "--acceptAll" -> acceptAll = true;
                    case "--verbose" -> verbose = true;
                    case "--host" -> host = nextArg(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(nextArg(args, ++i, "--port"));
                    case "--token" -> token = nextArg(args, ++i, "--token");
                    case "--grpc-authority" -> authority = nextArg(args, ++i, "--grpc-authority");
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (party == null || party.isBlank()) {
                return null;
            }
            return new Cli(party, contractId, acceptAll, verbose, host, port, token, authority);
        }

        private static String nextArg(String[] args, int idx, String flag) {
            if (idx >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
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