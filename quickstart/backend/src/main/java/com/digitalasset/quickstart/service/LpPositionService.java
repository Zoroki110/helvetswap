package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.dto.HoldingPoolResponse;
import com.digitalasset.quickstart.dto.LpPositionResponse;
import com.digitalasset.quickstart.dto.LpTokenDTO;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.DomainError;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LpPositionService {

    private static final Logger LOG = LoggerFactory.getLogger(LpPositionService.class);
    private final LedgerReader ledgerReader;
    private final HoldingPoolService holdingPoolService;

    public LpPositionService(LedgerReader ledgerReader, HoldingPoolService holdingPoolService) {
        this.ledgerReader = ledgerReader;
        this.holdingPoolService = holdingPoolService;
    }

    public CompletableFuture<List<LpPositionResponse>> positions(String ownerParty, String poolCid) {
        if (ownerParty == null || ownerParty.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return ledgerReader.lpTokensForParty(ownerParty)
                .thenCompose(tokens -> {
                    List<LpTokenDTO> filtered = tokens.stream()
                            .filter(token -> token != null && token.amount != null && token.poolId != null)
                            .toList();
                    if (filtered.isEmpty()) {
                        return CompletableFuture.completedFuture(List.of());
                    }
                    List<CompletableFuture<LpPositionResponse>> futures = new ArrayList<>();
                    for (LpTokenDTO token : filtered) {
                        futures.add(buildPosition(token));
                    }
                    CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                    return all.thenApply(ignored -> futures.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .filter(pos -> poolCid == null || poolCid.isBlank()
                                    || (pos.poolCid != null && poolCid.equals(pos.poolCid))
                                    || (pos.poolId != null && poolCid.equals(pos.poolId)))
                            .toList());
                });
    }

    private CompletableFuture<LpPositionResponse> buildPosition(LpTokenDTO token) {
        String poolId = token.poolId;
        String lpBalance = token.amount;
        String updatedAt = Instant.now().toString();

        CompletableFuture<Result<HoldingPoolResponse, DomainError>> poolFuture;
        if (looksLikeCid(poolId)) {
            poolFuture = holdingPoolService.getByContractId(poolId);
        } else {
            poolFuture = holdingPoolService.resolveActiveByPoolId(poolId);
        }

        return poolFuture.handle((Result<HoldingPoolResponse, DomainError> poolResult, Throwable err) -> {
            if (err != null) {
                LOG.warn("Failed to load pool {} for LP position: {}", poolId, err.getMessage());
                return new LpPositionResponse(poolId, null, lpBalance, null, null, null, updatedAt);
            }
            if (poolResult != null && poolResult.isOk()) {
                HoldingPoolResponse pool = poolResult.getValueUnsafe();
                Long shareBps = computeShareBps(lpBalance, pool.lpSupply);
                return new LpPositionResponse(
                        poolId,
                        pool.contractId,
                        lpBalance,
                        shareBps,
                        pool.reserveAmountA,
                        pool.reserveAmountB,
                        updatedAt
                );
            }
            return new LpPositionResponse(poolId, null, lpBalance, null, null, null, updatedAt);
        });
    }

    private boolean looksLikeCid(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("00") && trimmed.length() > 20;
    }

    private Long computeShareBps(String lpBalance, String lpSupply) {
        if (lpBalance == null || lpSupply == null) {
            return null;
        }
        try {
            BigDecimal balance = new BigDecimal(lpBalance);
            BigDecimal supply = new BigDecimal(lpSupply);
            if (supply.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            BigDecimal share = balance.divide(supply, 18, RoundingMode.DOWN)
                    .multiply(new BigDecimal("10000"))
                    .setScale(0, RoundingMode.DOWN);
            return share.longValue();
        } catch (Exception e) {
            return null;
        }
    }
}

