// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import helvet_amm.amm.pool.Pool;
import helvet_amm.lptoken.lptoken.LPToken;
import helvet_amm.token.token.Token;
import com.digitalasset.quickstart.dto.LpTokenDTO;
import com.digitalasset.quickstart.dto.PoolDTO;
import com.digitalasset.quickstart.dto.TokenDTO;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.pqs.Contract;
import com.digitalasset.quickstart.pqs.Pqs;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * LedgerReader - Authoritative contract reads via Ledger API
 *
 * This service reads contracts directly from the Canton Ledger API, providing:
 * - Immediate consistency (no PQS lag)
 * - No package allowlist issues
 * - Guaranteed active contracts only
 *
 * Use this for all core app reads (tokens, pools, LP tokens).
 * Reserve PQS for analytics queries (TVL, volume, fees).
 */
@Service
public class LedgerReader {
    private static final Logger logger = LoggerFactory.getLogger(LedgerReader.class);
    private final LedgerApi ledger;
    private final VolumeMetricsService volumeMetricsService;
    @Nullable
    private final Pqs pqs;
    private final PoolDirectoryService poolDirectoryService;
    private final String appProviderPartyId;
    private final String dexPartyId;

    @Autowired
    public LedgerReader(
            LedgerApi ledger,
            VolumeMetricsService volumeMetricsService,
            @Autowired(required = false) @Nullable Pqs pqs,
            PoolDirectoryService poolDirectoryService,
            @Value("${application.tenants.AppProvider.partyId:}") String appProviderPartyId,
            @Value("${application.clearportx.dexPartyId:}") String dexPartyId
    ) {
        this.ledger = ledger;
        this.volumeMetricsService = volumeMetricsService;
        this.pqs = pqs;
        this.poolDirectoryService = poolDirectoryService;
        this.appProviderPartyId = appProviderPartyId;
        this.dexPartyId = dexPartyId;
        if (pqs == null) {
            logger.info("PQS not available - using Ledger API only");
        }
    }

    /**
     * Get all tokens owned by a specific party
     * Returns tokens in frontend-friendly DTO format
     */
    @WithSpan
    public CompletableFuture<List<TokenDTO>> tokensForParty(String party) {
        logger.info("Fetching tokens for party: {}", party);
        // Use party override to read the caller's authoritative ACS (not just app provider's view)
        return ledger.getActiveContractsForParty(Token.class, party)
                .thenApply(contracts -> contracts.stream()
                        .filter(c -> c.payload.getOwner.getParty.equals(party))
                        .map(c -> new TokenDTO(
                                c.payload.getSymbol,
                                c.payload.getSymbol + " Token",
                                10,
                                c.payload.getAmount.toPlainString(),
                                c.payload.getOwner.getParty
                        ))
                        .toList())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        logger.error("Failed to fetch tokens for party {}: {}", party, ex.getMessage());
                    } else {
                        logger.info("Fetched {} tokens for party {}", result.size(), party);
                    }
                });
    }

    /**
     * Get wallet-only tokens for a party (excludes pool canonical tokens when party is also pool operator)
     */
    @WithSpan
    public CompletableFuture<List<TokenDTO>> walletTokensForParty(String party) {
        logger.info("Fetching wallet tokens for party (excluding canonicals): {}", party);
        // 1) Build set of canonical token CIDs for pools where poolParty == party
        CompletableFuture<java.util.Set<String>> canonSetFut = ledger.getActiveContractsForParty(Pool.class, party)
                .thenApply(pools -> {
                    java.util.Set<String> canon = new java.util.HashSet<>();
                    for (var pac : pools) {
                        var pay = pac.payload;
                        if (party.equals(pay.getPoolParty.getParty)) {
                            pay.getTokenACid.ifPresent(cid -> canon.add(cid.getContractId));
                            pay.getTokenBCid.ifPresent(cid -> canon.add(cid.getContractId));
                        }
                    }
                    return canon;
                });
        // 2) Fetch party tokens and filter out canonicals
        return canonSetFut.thenCompose(canon -> ledger.getActiveContractsForParty(Token.class, party)
                .thenApply(contracts -> contracts.stream()
                        .filter(c -> c.payload.getOwner.getParty.equals(party))
                        .filter(c -> !canon.contains(c.contractId.getContractId))
                        .map(c -> new TokenDTO(
                                c.payload.getSymbol,
                                c.payload.getSymbol + " Token",
                                10,
                                c.payload.getAmount.toPlainString(),
                                c.payload.getOwner.getParty
                        ))
                        .toList())
        ).whenComplete((result, ex) -> {
            if (ex != null) {
                logger.error("Failed to fetch wallet tokens for party {}: {}", party, ex.getMessage());
            } else {
                logger.info("Fetched {} wallet tokens for party {}", result.size(), party);
            }
        });
    }

    /**
     * Get LP tokens owned by a party (liquidity positions)
     */
    @WithSpan
    public CompletableFuture<java.util.List<LpTokenDTO>> lpTokensForParty(String party) {
        String viewerParty = (appProviderPartyId != null && !appProviderPartyId.isBlank())
                ? appProviderPartyId
                : party;
        logger.info("Fetching LP tokens for party: {} (viewer={})", party, viewerParty);
        return ledger.getActiveContractsForParty(LPToken.class, viewerParty)
                .thenApply(contracts -> contracts.stream()
                        .filter(c -> c.payload.getOwner.getParty.equals(party))
                        .map(c -> new LpTokenDTO(
                                c.payload.getPoolId,
                                c.payload.getAmount.toPlainString(),
                                c.contractId.getContractId,
                                c.payload.getOwner.getParty
                        ))
                        .toList())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        logger.error("Failed to fetch LP tokens for party {}: {}", party, ex.getMessage());
                    } else {
                        logger.info("Fetched {} LP tokens for party {}", result.size(), party);
                    }
                });
    }

    /**
     * Get all active liquidity pools
     * Returns pools in frontend-friendly DTO format
     * Primary: Ledger API (Canton 3.4.7 EventFormat)
     * Fallback: PQS if Ledger API fails
     */
    @WithSpan
    public CompletableFuture<List<PoolDTO>> pools() {
        logger.info("Fetching all active pools (party-aware Ledger API)");
        final java.util.Set<String> showcasePoolIds = java.util.Set.of("cc-cbtc-showcase", "pool-cbtc-amulet-prod");

        LinkedHashSet<String> partyCandidates = new LinkedHashSet<>();
        if (appProviderPartyId != null && !appProviderPartyId.isBlank()) {
            partyCandidates.add(appProviderPartyId);
        }
        if (dexPartyId != null && !dexPartyId.isBlank()) {
            partyCandidates.add(dexPartyId);
        }
        Map<String, Map<String, String>> directorySnapshot = poolDirectoryService.snapshot();
        directorySnapshot.values().stream()
                .map(entry -> entry.get("party"))
                .filter(party -> party != null && !party.isBlank())
                .forEach(partyCandidates::add);

        if (partyCandidates.isEmpty()) {
            logger.warn("No parties available for pool lookup; falling back to default app provider scope.");
            partyCandidates.add(appProviderPartyId);
        }
        logger.info("Pool lookup partyCandidates: {}", partyCandidates);

        Map<String, CompletableFuture<List<LedgerApi.ActiveContract<Pool>>>> partyFetches = partyCandidates.stream()
                .filter(party -> party != null && !party.isBlank())
                .collect(Collectors.toMap(
                        party -> party,
                        party -> ledger.getActiveContractsForParty(Pool.class, party)
                                .exceptionally(ex -> {
                                    logger.warn("Failed to fetch pools for party {}: {}", party, ex.getMessage());
                                    return List.of();
                                }),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        if (partyFetches.isEmpty()) {
            partyFetches.put("fallback-any-party", ledger.getActiveContracts(Pool.class)
                    .exceptionally(ex -> {
                        logger.warn("Fallback pool fetch failed: {}", ex.getMessage());
                        return List.of();
                    }));
        }

        CompletableFuture<Void> allFetches = CompletableFuture.allOf(
                partyFetches.values().toArray(new CompletableFuture[0])
        );

        return allFetches
                .thenApply(ignored -> {
                    partyFetches.forEach((party, fut) -> {
                        try {
                            var list = fut.join();
                            logger.info("Pools found for party {}: {}", party, list.size());
                        } catch (Exception ex) {
                            logger.warn("Pools fetch failed for party {}: {}", party, ex.getMessage());
                        }
                    });
                    return partyFetches.values().stream()
                            .flatMap(future -> future.join().stream())
                            .collect(Collectors.toList());
                })
                .thenApply(contracts -> {
                    List<PoolDTO> dto = mapPoolsToDto(contracts, showcasePoolIds);
                    logger.info("Merged pool count: {}", dto.size());
                    return dto;
                })
                .exceptionallyCompose(ex -> {
                    if (pqs != null) {
                        logger.warn("Ledger API failed, falling back to PQS: {}", ex.getMessage());
                        return pqs.active(Pool.class)
                                .thenApply(contracts -> mapPoolsToDto(
                                        contracts.stream()
                                                .map(c -> new LedgerApi.ActiveContract<>(c.contractId, c.payload))
                                                .collect(Collectors.toList()),
                                        showcasePoolIds));
                    } else {
                        return CompletableFuture.failedFuture(ex);
                    }
                })
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        logger.error("Failed to fetch pools: {}", ex.getMessage());
                    } else {
                        logger.info("Fetched {} active pools", result.size());
                    }
                });
    }

    private List<PoolDTO> mapPoolsToDto(List<LedgerApi.ActiveContract<Pool>> contracts,
                                        java.util.Set<String> showcasePoolIds) {
        LinkedHashMap<String, PoolDTO> bestPools = new LinkedHashMap<>();
        for (var c : contracts) {
            if (c == null || c.payload == null || !showcasePoolIds.contains(c.payload.getPoolId)) {
                continue;
            }
            double volume24h = volumeMetricsService.getVolume24h(
                    c.payload.getSymbolA,
                    c.payload.getSymbolB
            );
            PoolDTO dto = new PoolDTO(
                    c.payload.getPoolId,
                    new PoolDTO.TokenInfoDTO(c.payload.getSymbolA, c.payload.getSymbolA, 10),
                    new PoolDTO.TokenInfoDTO(c.payload.getSymbolB, c.payload.getSymbolB, 10),
                    c.payload.getReserveA.toPlainString(),
                    c.payload.getReserveB.toPlainString(),
                    c.payload.getTotalLPSupply.toPlainString(),
                    convertFeeBpsToRate(c.payload.getFeeBps),
                    String.format("%.2f", volume24h)
            );
            bestPools.merge(
                    dto.poolId,
                    dto,
                    (existing, incoming) -> {
                        BigDecimal existingTvl = new BigDecimal(existing.reserveA)
                                .multiply(new BigDecimal(existing.reserveB));
                        BigDecimal incomingTvl = new BigDecimal(incoming.reserveA)
                                .multiply(new BigDecimal(incoming.reserveB));
                        return incomingTvl.compareTo(existingTvl) > 0 ? incoming : existing;
                    }
            );
        }
        return List.copyOf(bestPools.values());
    }

    /**
     * Convert fee in basis points (e.g., 30) to decimal rate (e.g., "0.003" for 0.3%)
     */
    private String convertFeeBpsToRate(Long feeBps) {
        return String.valueOf(feeBps / 10000.0);
    }
}
