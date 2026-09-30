package com.digitalasset.quickstart.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Convenience wrapper for exercising TransferInstruction_Accept via interface.
 */
public final class AcceptTransferInstructionTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: accept-transfer-instruction --party <PARTY> --contractId <CID> [--host <HOST>] [--port <PORT>] [--token <JWT>] [--interfacePackageId <PKG>] [--verbose]");
            System.exit(1);
        }
        String pkg = cli.interfacePackageId != null ? cli.interfacePackageId : cli.defaultPackageId;
        List<String> forward = new ArrayList<>();
        forward.add("exercise-by-interface");
        forward.add("--host"); forward.add(cli.host);
        forward.add("--port"); forward.add(Integer.toString(cli.port));
        forward.add("--party"); forward.add(cli.party);
        forward.add("--contractId"); forward.add(cli.contractId);
        forward.add("--interfacePackageId"); forward.add(pkg);
        forward.add("--interfaceModule"); forward.add("Splice.Api.Token.TransferInstructionV1");
        forward.add("--interfaceEntity"); forward.add("TransferInstruction");
        forward.add("--choice"); forward.add("TransferInstruction_Accept");
        forward.add("--token"); forward.add(cli.token);
        if (cli.verbose) forward.add("--verbose");
        ExerciseByInterfaceTool.runCli(forward.toArray(new String[0]));
    }

    private record Cli(String host, int port, String party, String contractId, String interfacePackageId,
                       String defaultPackageId, String token, boolean verbose) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"accept-transfer-instruction".equals(args[0])) {
                return null;
            }
            String host = "localhost";
            int port = 5001;
            String party = null;
            String contractId = null;
            String interfacePackageId = null;
            String defaultPackageId = null;
            String token = "";
            boolean verbose = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = next(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--party" -> party = next(args, ++i, "--party");
                    case "--contractId" -> contractId = next(args, ++i, "--contractId");
                    case "--interfacePackageId" -> interfacePackageId = next(args, ++i, "--interfacePackageId");
                    case "--defaultPackageId" -> defaultPackageId = next(args, ++i, "--defaultPackageId");
                    case "--token" -> token = next(args, ++i, "--token");
                    case "--verbose" -> verbose = true;
                    default -> {
                        System.err.println("Unknown arg: " + args[i]);
                        return null;
                    }
                }
            }
            if (party == null || contractId == null) return null;
            return new Cli(host, port, party, contractId, interfacePackageId, defaultPackageId, token, verbose);
        }

        private static String next(String[] args, int idx, String flag) {
            if (idx >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
            return args[idx];
        }
    }
}
