package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import io.grpc.*;
import java.util.Base64;

/**
 * Scan ACS for a party and list contracts whose module/entity contains a pattern.
 *
 * Usage:
 *   ./gradlew backend:run --args="scan-acs --party <PARTY> --pattern Transfer --host localhost --port 5001 [--token <JWT>] [--anyParty] [--contractId <CID>] [--includeBlob] [--verbose]"
 */
public final class ScanAcsTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: scan-acs --party <PARTY> --pattern <PATTERN> --host <HOST> --port <PORT> [--token <JWT>] [--anyParty] [--contractId <CID>] [--includeBlob] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);

            TransactionFilterOuterClass.WildcardFilter.Builder wildcardBuilder = TransactionFilterOuterClass.WildcardFilter.newBuilder();
            if (cli.includeBlob) {
                wildcardBuilder.setIncludeCreatedEventBlob(true);
            }
            TransactionFilterOuterClass.Filters wildcardFilters = TransactionFilterOuterClass.Filters.newBuilder()
                    .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                            .setWildcardFilter(wildcardBuilder.build())
                            .build())
                    .build();

            TransactionFilterOuterClass.EventFormat.Builder eventFormatBuilder = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(cli.party, wildcardFilters)
                    .setVerbose(true);
            if (cli.anyParty) {
                eventFormatBuilder.setFiltersForAnyParty(wildcardFilters);
            }
            TransactionFilterOuterClass.EventFormat eventFormat = eventFormatBuilder.build();

            long ledgerEnd = state.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build()).getOffset();

            StateServiceOuterClass.GetActiveContractsRequest req = StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                    .setEventFormat(eventFormat)
                    .setActiveAtOffset(ledgerEnd)
                    .build();

            var it = state.getActiveContracts(req);
            boolean foundAny = false;
            while (it.hasNext()) {
                StateServiceOuterClass.GetActiveContractsResponse resp = it.next();
                if (!resp.hasActiveContract()) continue;
                EventOuterClass.CreatedEvent created = resp.getActiveContract().getCreatedEvent();
                var tid = created.getTemplateId();
                String mod = tid.getModuleName();
                String ent = tid.getEntityName();
                if (!mod.toLowerCase().contains(cli.pattern) && !ent.toLowerCase().contains(cli.pattern)) {
                    continue;
                }
                if (cli.contractId != null && !cli.contractId.isBlank() && !cli.contractId.equals(created.getContractId())) {
                    continue;
                }
                foundAny = true;
                System.out.println("template=" + fmtId(tid) + " cid=" + created.getContractId());
                System.out.println("  witnesses=" + String.join(",", created.getWitnessPartiesList()));
                if (cli.verbose) {
                    System.out.println("  arguments=" + created.getCreateArguments());
                }
                if (cli.includeBlob) {
                    if (!created.getCreatedEventBlob().isEmpty()) {
                        String b64 = Base64.getEncoder().encodeToString(created.getCreatedEventBlob().toByteArray());
                        System.out.println("  created_event_blob(base64)=" + b64);
                    } else {
                        System.out.println("  created_event_blob=<absent>");
                    }
                }
            }
            if (!foundAny) {
                System.out.println("No active contracts matching pattern='" + cli.pattern + "' for party=" + cli.party + " (anyParty=" + cli.anyParty + ")");
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static String fmtId(com.daml.ledger.api.v2.ValueOuterClass.Identifier id) {
        return id.getPackageId() + ":" + id.getModuleName() + ":" + id.getEntityName();
    }

    private record Cli(String host, int port, String party, String pattern, String contractId, boolean includeBlob, String token, boolean anyParty, boolean verbose) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"scan-acs".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String party = null;
            String pattern = "transfer";
            String contractId = null;
            boolean includeBlob = false;
            String token = "";
            boolean anyParty = false;
            boolean verbose = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--party" -> party = next(args, ++i, "--party");
                    case "--pattern" -> pattern = next(args, ++i, "--pattern").toLowerCase();
                    case "--contractId" -> contractId = next(args, ++i, "--contractId");
                    case "--includeBlob" -> includeBlob = true;
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--anyParty" -> anyParty = true;
                    case "--verbose" -> verbose = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (party == null || party.isBlank()) {
                return null;
            }
            return new Cli(host, port, party, pattern, contractId, includeBlob, token, anyParty, verbose);
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

