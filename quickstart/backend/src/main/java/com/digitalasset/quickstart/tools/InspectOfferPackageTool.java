package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.PackageServiceGrpc;
import com.daml.ledger.api.v2.PackageServiceOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import io.grpc.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fetch package for a given contractId visible to a party: writes dalf to /tmp/<pkgId>.dalf.
 *
 * Usage:
 *   ./gradlew backend:run --args="inspect-offer-package --party <PARTY> --contractId <CID> [--host <HOST>] [--port <PORT>] [--token <JWT>]"
 */
public final class InspectOfferPackageTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: inspect-offer-package --party <PARTY> --contractId <CID> [--host <HOST>] [--port <PORT>] [--token <JWT>]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel);
            PackageServiceGrpc.PackageServiceBlockingStub pkg = PackageServiceGrpc.newBlockingStub(channel);

            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(cli.party, TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();

            long ledgerEnd = state.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build()).getOffset();

            StateServiceOuterClass.GetActiveContractsRequest req = StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                    .setEventFormat(eventFormat)
                    .setActiveAtOffset(ledgerEnd)
                    .build();

            boolean found = false;
            String pkgId = null;
            var it = state.getActiveContracts(req);
            while (it.hasNext()) {
                StateServiceOuterClass.GetActiveContractsResponse resp = it.next();
                if (!resp.hasActiveContract()) continue;
                EventOuterClass.CreatedEvent created = resp.getActiveContract().getCreatedEvent();
                if (!created.getContractId().equals(cli.contractId)) continue;
                ValueOuterClass.Identifier tmpl = created.getTemplateId();
                pkgId = tmpl.getPackageId();
                found = true;
                break;
            }
            if (!found || pkgId == null || pkgId.isBlank()) {
                System.err.println("Contract not found or no packageId visible.");
                System.exit(2);
            }

            PackageServiceOuterClass.GetPackageResponse presp = pkg.getPackage(
                    PackageServiceOuterClass.GetPackageRequest.newBuilder()
                            .setPackageId(pkgId)
                            .build()
            );
            byte[] bytes = presp.getArchivePayload().toByteArray();
            Path out = Path.of("/tmp", pkgId + ".dalf");
            Files.write(out, bytes);
            System.out.println("Package " + pkgId + " written to " + out + " (" + bytes.length + " bytes)");
        } finally {
            channel.shutdownNow();
        }
    }

    private static final class Cli {
        final String host;
        final int port;
        final String party;
        final String contractId;
        final String token;

        private Cli(String host, int port, String party, String contractId, String token) {
            this.host = host;
            this.port = port;
            this.party = party;
            this.contractId = contractId;
            this.token = token;
        }

        static Cli parse(String[] args) {
            if (args.length == 0 || !"inspect-offer-package".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String party = null;
            String contractId = null;
            String token = "";
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--party" -> party = next(args, ++i, "--party");
                    case "--contractId" -> contractId = next(args, ++i, "--contractId");
                    case "--token" -> token = next(args, ++i, "--token");
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (party == null || contractId == null) {
                return null;
            }
            return new Cli(host, port, party, contractId, token);
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

