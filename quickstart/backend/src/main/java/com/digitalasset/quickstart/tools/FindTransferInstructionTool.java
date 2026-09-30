package com.digitalasset.quickstart.tools;

import com.daml.ledger.api.v2.ValueOuterClass;

/**
 * CLI to locate the TransferInstruction contract matching a TransferOffer for a party.
 *
 * Usage:
 *   ./gradlew backend:run --args="find-transfer-instruction --party <PARTY> [--offerContractId <CID> | --updateId <UPDATE_ID>] [--host <HOST>] [--port <PORT>] [--token <JWT>] [--verbose]"
 */
public final class FindTransferInstructionTool {

    public static void runCli(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli == null) {
            System.err.println("Usage: find-transfer-instruction --party <PARTY> [--offerContractId <CID> | --updateId <UPDATE_ID>] [--host <HOST>] [--port <PORT>] [--token <JWT>] [--verbose]");
            System.exit(1);
        }

        TransferInstructionFinder finder = new TransferInstructionFinder(cli.host, cli.port, cli.token);
        try {
            TransferInstructionFinder.Result res = finder.find(cli.party, cli.offerCid, cli.updateId, cli.verbose);
            TransferInstructionFinder.printOfferSummary(res.offer());
            if (res.bestCandidate() != null) {
                System.out.println("Best instruction candidate:");
                TransferInstructionFinder.printCandidate(res.bestCandidate());
            } else {
                System.out.println("No matching instruction candidates found.");
            }
            if (cli.verbose) {
                System.out.println("All candidates (sorted by score):");
                res.candidates().stream()
                        .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                        .forEach(TransferInstructionFinder::printCandidate);
                if (!res.relatedTransfers().isEmpty()) {
                    System.out.println("Other transfer-like contracts visible:");
                    res.relatedTransfers().forEach(s -> System.out.println("  cid=" + s.contractId() + " template=" + TransferInstructionFinder.fmtId(s.templateId())));
                }
            }
        } finally {
            finder.close();
        }
    }

    private record Cli(String host, int port, String party, String offerCid, String updateId, String token, boolean verbose) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"find-transfer-instruction".equals(args[0])) {
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
}

