package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.PackageServiceGrpc;
import com.daml.ledger.api.v2.PackageServiceOuterClass;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.CallOptions;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.io.FileOutputStream;
import java.nio.file.Path;

/**
 * Download a package (DAR/DALF payload) via Ledger API PackageService.
 *
 * Usage:
 *   ./gradlew backend:run --args="download-package --packageId <pkg> [--host <host>] [--port <port>] [--out <file>] [--token <jwt>] [--verbose]"
 */
public final class DownloadPackageTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: download-package --packageId <pkg> [--host <host>] [--port <port>] [--out <file>] [--token <jwt>] [--verbose]");
            System.exit(1);
        }

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(cli.host, cli.port)
                .usePlaintext();
        builder.intercept(new AuthInterceptor(cli.token));
        ManagedChannel channel = builder.build();

        try {
            PackageServiceGrpc.PackageServiceBlockingStub pkg = PackageServiceGrpc.newBlockingStub(channel);
            if (cli.verbose) {
                System.out.println("Requesting package " + cli.packageId + " from " + cli.host + ":" + cli.port);
            }
            PackageServiceOuterClass.GetPackageResponse resp = pkg.getPackage(
                    PackageServiceOuterClass.GetPackageRequest.newBuilder()
                            .setPackageId(cli.packageId)
                            .build()
            );
            byte[] bytes = resp.getArchivePayload().toByteArray();
            try (FileOutputStream fos = new FileOutputStream(cli.outFile)) {
                fos.write(bytes);
            }
            System.out.println("Package written to " + cli.outFile);
            if (cli.verbose) {
                System.out.println("Size: " + bytes.length + " bytes");
            }
            System.out.println("Inspect with: daml damlc inspect-dalf " + cli.outFile + " | less");
        } finally {
            channel.shutdownNow();
        }
    }

    private static final class Cli {
        final String host;
        final int port;
        final String packageId;
        final String outFile;
        final String token;
        final boolean verbose;

        private Cli(String host, int port, String packageId, String outFile, String token, boolean verbose) {
            this.host = host;
            this.port = port;
            this.packageId = packageId;
            this.outFile = outFile;
            this.token = token;
            this.verbose = verbose;
        }

        static Cli parse(String[] args) {
            if (args.length == 0 || !"download-package".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String packageId = null;
            String outFile = null;
            String token = "";
            boolean verbose = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--packageId" -> packageId = next(args, ++i, "--packageId");
                    case "--out" -> outFile = next(args, ++i, "--out");
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--verbose" -> verbose = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (packageId == null || packageId.isBlank()) {
                return null;
            }
            if (outFile == null || outFile.isBlank()) {
                outFile = Path.of("/tmp", packageId + ".dalf").toString();
            }
            return new Cli(host, port, packageId, outFile, token, verbose);
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

