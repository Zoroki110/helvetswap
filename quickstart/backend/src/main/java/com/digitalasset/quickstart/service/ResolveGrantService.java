package com.digitalasset.quickstart.service;

import helvet_amm.amm.pool.Pool;
import helvet_amm.token.token.Token;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class ResolveGrantService {

    private static final Logger logger = LoggerFactory.getLogger(ResolveGrantService.class);

    private final LedgerApi ledgerApi;
    private final PoolDirectoryService directory;

    public ResolveGrantService(LedgerApi ledgerApi, PoolDirectoryService directory) {
        this.ledgerApi = ledgerApi;
        this.directory = directory;
    }

    public record Result(String poolId,
                         String poolCid,
                         boolean visibilityGranted,
                         String module,
                         String entity,
                         String packageId) {}

    public Result resolveAndGrant(String poolId, String party) {
        if (poolId == null || poolId.isBlank()) {
            throw new IllegalArgumentException("poolId is required");
        }
        if (party == null || party.isBlank()) {
            throw new IllegalArgumentException("party is required");
        }
        String operator = Optional.ofNullable(System.getenv("APP_PROVIDER_PARTY")).orElse(party);

        // 1) Resolve freshest active Pool by poolId from operator scope (plus caller scope fallback)
        List<LedgerApi.ActiveContract<Pool>> poolsOp = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
        List<LedgerApi.ActiveContract<Pool>> poolsParty = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
        Map<String, LedgerApi.ActiveContract<Pool>> candidateMap = new LinkedHashMap<>();

        for (var p : poolsOp) {
            if (p.payload.getPoolId.equalsIgnoreCase(poolId)) {
                candidateMap.putIfAbsent(p.contractId.getContractId, p);
            }
        }
        if (candidateMap.isEmpty()) {
            String normalized = poolId.trim().toUpperCase(Locale.ROOT);
            String[] parts = normalized.split("[-_/]");
            if (parts.length == 2) {
                String first = parts[0];
                String second = parts[1];
                for (var p : poolsOp) {
                    String symA = p.payload.getSymbolA.toUpperCase(Locale.ROOT);
                    String symB = p.payload.getSymbolB.toUpperCase(Locale.ROOT);
                    if ((symA.equals(first) && symB.equals(second)) || (symA.equals(second) && symB.equals(first))) {
                        candidateMap.putIfAbsent(p.contractId.getContractId, p);
                    }
                }
            }
        }
        for (var p : poolsParty) {
            if (p.payload.getPoolId.equalsIgnoreCase(poolId)) {
                candidateMap.putIfAbsent(p.contractId.getContractId, p);
            }
        }
        if (candidateMap.isEmpty()) {
            throw new IllegalStateException("No pool candidates found for poolId=" + poolId);
        }

        List<LedgerApi.ActiveContract<Pool>> candidates = new ArrayList<>(candidateMap.values());
        candidates.sort((a, b) ->
                b.payload.getReserveA.multiply(b.payload.getReserveB)
                        .compareTo(a.payload.getReserveA.multiply(a.payload.getReserveB)));

        String directoryCid = Optional.ofNullable(directory.snapshot().get(poolId))
                .map(entry -> entry.get("poolCid"))
                .orElse(null);
        if (directoryCid != null) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).contractId.getContractId.equals(directoryCid)) {
                    LedgerApi.ActiveContract<Pool> preferred = candidates.remove(i);
                    candidates.add(0, preferred);
                    break;
                }
            }
        }

        // Prefer candidate with alive canonicals for poolParty
        LedgerApi.ActiveContract<Pool> chosen = null;
        for (var cand : candidates) {
            var pay = cand.payload;
            String poolParty = pay.getPoolParty.getParty;
            var toks = ledgerApi.getActiveContractsForParty(Token.class, poolParty).join();
            Set<String> alive = new HashSet<>();
            for (var t : toks) alive.add(t.contractId.getContractId);
            boolean aAlive = pay.getTokenACid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
            boolean bAlive = pay.getTokenBCid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
            if (aAlive && bAlive) {
                chosen = cand;
                break;
            }
        }
        if (chosen == null) {
            chosen = candidates.get(0);
        }

        String currentCid = chosen.contractId.getContractId;

        boolean granted = false;
        boolean partyVisible = isVisibleForParty(poolsParty, currentCid);
        if (!partyVisible) {
            for (var cand : candidates) {
                String candCid = cand.contractId.getContractId;
                if (isVisibleForParty(poolsParty, candCid)) {
                    currentCid = candCid;
                    partyVisible = true;
                    chosen = cand;
                    break;
                }
            }
        }

        if (!partyVisible && chosen != null) {
            try {
                var grantChoice = new Pool.GrantVisibility(new Party(party));
                var controllerParty = chosen.payload.getPoolOperator.getParty;
                var actAs = List.of(controllerParty);
                ledgerApi.exerciseAndGetTransaction(
                        chosen.contractId,
                        grantChoice,
                        UUID.randomUUID().toString(),
                        actAs,
                        actAs
                ).join();
                granted = true;
                poolsParty = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
                partyVisible = isVisibleForParty(poolsParty, currentCid);
            } catch (Exception e) {
                logger.warn("GrantVisibility failed for poolId {} cid {} party {}", poolId, currentCid, party, e);
            }
        }

        String module = null, entity = null, packageId = null;
        try {
            var id = ledgerApi.getTemplateIdForPoolCid(party, currentCid).join();
            module = id.getModuleName();
            entity = id.getEntityName();
            packageId = id.getPackageId();
        } catch (Exception ignored) {
            // Leave template fields null if not resolvable
        }
        if (!partyVisible) {
            logger.warn("resolveAndGrant: pool {} resolved to {}, but party {} cannot yet see it", poolId, currentCid, party);
        }
        return new Result(poolId, currentCid, granted, module, entity, packageId);
    }

    private boolean isVisibleForParty(List<LedgerApi.ActiveContract<Pool>> pools, String cid) {
        if (cid == null || cid.isBlank()) {
            return false;
        }
        return pools.stream().anyMatch(p -> p.contractId.getContractId.equals(cid));
    }
}


