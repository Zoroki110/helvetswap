package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.HoldingSelectRequest;
import com.digitalasset.quickstart.dto.HoldingSelectResponse;
import com.digitalasset.quickstart.dto.HoldingUtxoDto;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for UTXO selection of Token Standard holdings (CBTC, CC, etc.).
 *
 * <h2>Selection Rule (Deterministic)</h2>
 * <ol>
 *   <li>Filter holdings by owner, instrumentAdmin, instrumentId</li>
 *   <li>Filter holdings with amount >= minAmount</li>
 *   <li>Sort by amount ascending (smallest first)</li>
 *   <li>If amounts are equal, sort by contractId ascending (lexicographic)</li>
 *   <li>Select the first matching holding</li>
 * </ol>
 *
 * <h2>Rationale</h2>
 * "Smallest-amount-first" selection minimizes UTXO fragmentation by consuming
 * smaller holdings first, leaving larger holdings for larger operations.
 * Lexicographic contractId tiebreaker ensures determinism across runs.
 *
 * <h2>Polling Behavior</h2>
 * When polling is enabled (timeout > 0), the service will repeatedly query
 * holdings at the specified interval until a matching holding is found or
 * the timeout expires. Each iteration is logged for observability.
 */
@Service
public class HoldingSelectorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HoldingSelectorService.class);

    private final HoldingsService holdingsService;

    public HoldingSelectorService(final HoldingsService holdingsService) {
        this.holdingsService = holdingsService;
    }

    /**
     * Select a holding matching the criteria, with optional polling/wait.
     *
     * @param request Selection criteria including owner, instrument, minAmount, and timeout
     * @return CompletableFuture containing the selection result
     */
    @WithSpan
    public CompletableFuture<HoldingSelectResponse> selectHolding(final HoldingSelectRequest request) {
        final long startTime = System.currentTimeMillis();
        final long timeoutMs = request.getTimeoutSeconds() * 1000L;
        final int pollIntervalMs = request.getPollIntervalMs();

        LOGGER.info("[HoldingSelector] Starting selection: owner={}, admin={}, id={}, minAmount={}, timeout={}s, poll={}ms",
                request.ownerParty(),
                request.instrumentAdmin(),
                request.instrumentId(),
                request.getMinAmount(),
                request.getTimeoutSeconds(),
                pollIntervalMs);

        return pollUntilFound(request, startTime, timeoutMs, pollIntervalMs, 1);
    }

    /**
     * Single-shot selection without polling (timeout = 0).
     */
    @WithSpan
    public CompletableFuture<HoldingSelectResponse> selectHoldingOnce(final HoldingSelectRequest request) {
        final long startTime = System.currentTimeMillis();
        return attemptSelection(request, startTime, 1)
                .thenApply(result -> {
                    if (result.isPresent()) {
                        return result.get();
                    }
                    return HoldingSelectResponse.notFound(
                            1,
                            System.currentTimeMillis() - startTime,
                            0,
                            0,
                            "No matching holding found (single attempt)"
                    );
                });
    }

    private CompletableFuture<HoldingSelectResponse> pollUntilFound(
            final HoldingSelectRequest request,
            final long startTime,
            final long timeoutMs,
            final int pollIntervalMs,
            final int attempt
    ) {
        return attemptSelection(request, startTime, attempt)
                .thenCompose(result -> {
                    if (result.isPresent()) {
                        return CompletableFuture.completedFuture(result.get());
                    }

                    long elapsed = System.currentTimeMillis() - startTime;
                    if (elapsed >= timeoutMs) {
                        LOGGER.warn("[HoldingSelector] Timeout after {}ms and {} attempts. No matching holding found.",
                                elapsed, attempt);
                        return CompletableFuture.completedFuture(HoldingSelectResponse.notFound(
                                attempt,
                                elapsed,
                                0,
                                0,
                                String.format("Timeout after %dms. No matching holding found for owner=%s, admin=%s, id=%s, minAmount=%s",
                                        elapsed, request.ownerParty(), request.instrumentAdmin(),
                                        request.instrumentId(), request.getMinAmount())
                        ));
                    }

                    // Schedule next poll
                    LOGGER.debug("[HoldingSelector] Attempt {} failed, scheduling retry in {}ms (elapsed={}ms, remaining={}ms)",
                            attempt, pollIntervalMs, elapsed, timeoutMs - elapsed);

                    return CompletableFuture.supplyAsync(() -> null,
                                    CompletableFuture.delayedExecutor(pollIntervalMs, TimeUnit.MILLISECONDS))
                            .thenCompose(ignored -> pollUntilFound(request, startTime, timeoutMs, pollIntervalMs, attempt + 1));
                });
    }

    private CompletableFuture<Optional<HoldingSelectResponse>> attemptSelection(
            final HoldingSelectRequest request,
            final long startTime,
            final int attempt
    ) {
        LOGGER.info("[HoldingSelector] Attempt #{}: Querying holdings for owner={}", attempt, request.ownerParty());

        return holdingsService.getHoldingUtxos(request.ownerParty())
                .thenApply(result -> processHoldingsResult(result, request, startTime, attempt));
    }

    private Optional<HoldingSelectResponse> processHoldingsResult(
            final Result<List<HoldingUtxoDto>, DomainError> result,
            final HoldingSelectRequest request,
            final long startTime,
            final int attempt
    ) {
        long elapsed = System.currentTimeMillis() - startTime;

        if (result.isErr()) {
            LOGGER.warn("[HoldingSelector] Attempt #{}: Error querying holdings: {}",
                    attempt, result.getErrorUnsafe().message());
            return Optional.empty();
        }

        List<HoldingUtxoDto> allHoldings = result.getValueUnsafe();
        int totalScanned = allHoldings.size();

        LOGGER.info("[HoldingSelector] Attempt #{}: Found {} total holdings for owner={}",
                attempt, totalScanned, request.ownerParty());

        // Filter by owner, instrument, and minimum amount
        List<HoldingUtxoDto> matchingHoldings = allHoldings.stream()
                .filter(h -> matchesCriteria(h, request))
                .filter(h -> h.lock == null)  // exclude locked holdings (utility-registry-holding-v0/Holding.daml:173 asserts mustBeUnlocked downstream)
                .filter(h -> h.amount.compareTo(request.getMinAmount()) >= 0)
                .toList();

        int matchingCount = matchingHoldings.size();

        LOGGER.info("[HoldingSelector] Attempt #{}: {} holdings match criteria (admin={}, id={}, minAmount>={})",
                attempt, matchingCount, request.instrumentAdmin(), request.instrumentId(), request.getMinAmount());

        if (matchingHoldings.isEmpty()) {
            // Log details for debugging
            if (LOGGER.isDebugEnabled()) {
                allHoldings.forEach(h -> LOGGER.debug(
                        "[HoldingSelector] Holding: cid={}, admin={}, id={}, amount={}, owner={}",
                        truncateCid(h.contractId), h.instrumentAdmin, h.instrumentId, h.amount, h.owner));
            }
            return Optional.empty();
        }

        // Apply selection rule: smallest amount first, then lexicographic contractId
        Optional<HoldingUtxoDto> selected = matchingHoldings.stream()
                .sorted(Comparator
                        .comparing((HoldingUtxoDto h) -> h.amount)
                        .thenComparing(h -> h.contractId))
                .findFirst();

        if (selected.isEmpty()) {
            return Optional.empty();
        }

        HoldingUtxoDto holding = selected.get();
        LOGGER.info("[HoldingSelector] Attempt #{}: SELECTED holding cid={}, amount={}, admin={}, id={} (elapsed={}ms)",
                attempt, truncateCid(holding.contractId), holding.amount,
                holding.instrumentAdmin, holding.instrumentId, elapsed);

        return Optional.of(HoldingSelectResponse.success(
                holding.contractId,
                holding.instrumentAdmin,
                holding.instrumentId,
                holding.amount,
                holding.owner,
                attempt,
                elapsed,
                totalScanned,
                matchingCount
        ));
    }

    private boolean matchesCriteria(final HoldingUtxoDto holding, final HoldingSelectRequest request) {
        // CRITICAL: Filter by owner - only select holdings owned by the specified party
        // This is essential because getHoldingUtxos returns ALL visible holdings,
        // including those where the party is an observer but not owner
        if (request.ownerParty() != null && !request.ownerParty().isBlank()) {
            if (!request.ownerParty().equals(holding.owner)) {
                return false;
            }
        }

        // Match instrumentAdmin if specified
        if (request.instrumentAdmin() != null && !request.instrumentAdmin().isBlank()) {
            if (!request.instrumentAdmin().equals(holding.instrumentAdmin)) {
                return false;
            }
        }

        // Match instrumentId if specified
        if (request.instrumentId() != null && !request.instrumentId().isBlank()) {
            if (!request.instrumentId().equals(holding.instrumentId)) {
                return false;
            }
        }

        return true;
    }

    private String truncateCid(final String cid) {
        if (cid == null || cid.length() <= 20) {
            return cid;
        }
        return cid.substring(0, 16) + "...";
    }
}
