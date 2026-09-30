package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.PackageServiceGrpc;
import com.daml.ledger.api.v2.PackageServiceOuterClass;
import io.grpc.*;

import java.util.List;

/**
 * List packageIds from PackageService.
 *
 * Usage:
 *   ./gradlew backend:run --args="list-packages [--host <HOST>] [--port <PORT>] [--token <JWT>] [--verbose]"
 */
public final class ListPackagesTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: list-packages [--host <HOST>] [--port <PORT>] [--token <JWT>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            PackageServiceGrpc.PackageServiceBlockingStub pkg = PackageServiceGrpc.newBlockingStub(channel);
            PackageServiceOuterClass.ListPackagesResponse resp = pkg.listPackages(
                    PackageServiceOuterClass.ListPackagesRequest.newBuilder().build()
            );
            List<String> ids = resp.getPackageIdsList();
            if (ids.isEmpty()) {
                System.out.println("No packages returned.");
            } else {
                System.out.println("Packages:");
                ids.forEach(System.out::println);
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static final class Cli {
        final String host;
        final int port;
        final String token;
        final boolean verbose;

        private Cli(String host, int port, String token, boolean verbose) {
            this.host = host;
            this.port = port;
            this.token = token;
            this.verbose = verbose;
        }

        static Cli parse(String[] args) {
            if (args.length == 0 || !"list-packages".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String token = "";
            boolean verbose = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--verbose" -> verbose = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            return new Cli(host, port, token, verbose);
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

