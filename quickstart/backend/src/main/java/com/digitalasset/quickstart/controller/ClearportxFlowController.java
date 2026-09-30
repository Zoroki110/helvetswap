package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ContractNotActiveError;
import com.digitalasset.quickstart.common.errors.InputTokenStaleOrNotVisibleError;
import com.digitalasset.quickstart.common.errors.LedgerVisibilityError;
import com.digitalasset.quickstart.common.errors.PoolEmptyError;
import com.digitalasset.quickstart.common.errors.PriceImpactTooHighError;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.ErrorResponse;
import com.digitalasset.quickstart.dto.ShowcasePoolResetRequest;
import com.digitalasset.quickstart.dto.ShowcasePoolResetResponse;
import com.digitalasset.quickstart.dto.SwapByCidRequest;
import com.digitalasset.quickstart.dto.SwapByCidResponse;
import com.digitalasset.quickstart.dto.WalletDrainRequest;
import com.digitalasset.quickstart.dto.WalletDrainResponse;
import com.digitalasset.quickstart.security.JwtAuthService;
import com.digitalasset.quickstart.security.JwtAuthService.AuthenticatedUser;
import com.digitalasset.quickstart.service.ClearportxFlowService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Profile("local")
@RestController
@RequestMapping("/api/clearportx/debug")
public class ClearportxFlowController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClearportxFlowController.class);

    private final ClearportxFlowService flowService;
    private final JwtAuthService jwtAuthService;

    public ClearportxFlowController(ClearportxFlowService flowService, JwtAuthService jwtAuthService) {
        this.flowService = flowService;
        this.jwtAuthService = jwtAuthService;
    }

    @PostMapping("/showcase/reset-pool")
    @WithSpan
    public CompletableFuture<ResponseEntity<?>> resetShowcasePool(
            @Valid @RequestBody ShowcasePoolResetRequest request,
            @RequestHeader(value = "X-Party", required = false) String xParty
    ) {
        Result<ClearportxFlowService.ResetShowcasePoolCommand, DomainError> commandResult = parseResetRequest(request, xParty);
        if (commandResult.isErr()) {
            return completedError(commandResult.getErrorUnsafe(), "/api/clearportx/debug/showcase/reset-pool");
        }
        return flowService.resetShowcasePool(commandResult.getValueUnsafe())
                .thenApply(result -> toHttpResponse(result, "/api/clearportx/debug/showcase/reset-pool"));
    }

    @PostMapping("/wallet/drain")
    @WithSpan
    public CompletableFuture<ResponseEntity<?>> drainWallet(
            @RequestBody(required = false) WalletDrainRequest request,
            @RequestHeader(value = "X-Party", required = false) String xParty
    ) {
        Result<ClearportxFlowService.WalletDrainCommand, DomainError> commandResult = parseDrainRequest(request, xParty);
        if (commandResult.isErr()) {
            return completedError(commandResult.getErrorUnsafe(), "/api/clearportx/debug/wallet/drain");
        }
        return flowService.drainWallet(commandResult.getValueUnsafe())
                .thenApply(result -> toHttpResponse(result, "/api/clearportx/debug/wallet/drain"));
    }

    @PostMapping("/swap-by-cid")
    @WithSpan
    public CompletableFuture<ResponseEntity<?>> swapByCid(
            @Valid @RequestBody SwapByCidRequest request,
            @RequestHeader(value = "X-Party", required = false) String trader,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        Result<String, DomainError> traderResult = resolveTrader(trader, authorization);
        if (traderResult.isErr()) {
            return completedError(traderResult.getErrorUnsafe(), "/api/clearportx/debug/swap-by-cid");
        }
        Result<ClearportxFlowService.SwapByCidCommand, DomainError> commandResult = parseSwapRequest(request, traderResult.getValueUnsafe());
        if (commandResult.isErr()) {
            return completedError(commandResult.getErrorUnsafe(), "/api/clearportx/debug/swap-by-cid");
        }
        return flowService.swapByCid(commandResult.getValueUnsafe())
                .thenApply(result -> toHttpResponse(result, "/api/clearportx/debug/swap-by-cid"));
    }

    private Result<ClearportxFlowService.ResetShowcasePoolCommand, DomainError> parseResetRequest(
            final ShowcasePoolResetRequest request,
            final String headerParty
    ) {
        String operator = resolveOperator(headerParty);
        if (operator == null || operator.isBlank()) {
            return Result.err(new ValidationError("APP_PROVIDER_PARTY unset. Provide X-Party header or env.", ValidationError.Type.REQUEST));
        }
        String provider = request.getProviderParty();
        if (provider == null || provider.isBlank()) {
            provider = headerParty != null && !headerParty.isBlank() ? headerParty : operator;
        }
        String poolId = request.getPoolId() != null && !request.getPoolId().isBlank()
                ? request.getPoolId()
                : "cc-cbtc-showcase";

        try {
            BigDecimal cbtcAmount = parseAmountOrDefault(request.getCbtcAmount(), "5");
            BigDecimal ccAmount = request.getCcAmount() != null && !request.getCcAmount().isBlank()
                    ? parseAmountOrDefault(request.getCcAmount(), request.getCcAmount())
                    : null;
            BigDecimal price = request.getPricePerCbtc() != null && !request.getPricePerCbtc().isBlank()
                    ? parseAmountOrDefault(request.getPricePerCbtc(), request.getPricePerCbtc())
                    : null;

            long maxInBps = clampBps(request.getMaxInBps(), 10000L);
            long maxOutBps = clampBps(request.getMaxOutBps(), 10000L);

            return Result.ok(new ClearportxFlowService.ResetShowcasePoolCommand(
                    operator,
                    provider,
                    poolId,
                    ccAmount,
                    cbtcAmount,
                    price,
                    maxInBps,
                    maxOutBps,
                    request.getArchiveExisting() == null || request.getArchiveExisting()
            ));
        } catch (IllegalArgumentException ex) {
            return Result.err(new ValidationError(ex.getMessage(), ValidationError.Type.REQUEST));
        }
    }

    private Result<ClearportxFlowService.WalletDrainCommand, DomainError> parseDrainRequest(
            final WalletDrainRequest request,
            final String headerParty
    ) {
        WalletDrainRequest effective = request != null ? request : new WalletDrainRequest();
        String party = effective.getParty();
        if (party == null || party.isBlank()) {
            party = headerParty != null && !headerParty.isBlank()
                    ? headerParty
                    : System.getenv("APP_PROVIDER_PARTY");
        }

        if (party == null || party.isBlank()) {
            return Result.err(new ValidationError("Provide party in request body or X-Party header", ValidationError.Type.REQUEST));
        }

        Set<String> symbols = new LinkedHashSet<>();
        if (effective.getSymbols() != null && !effective.getSymbols().isEmpty()) {
            for (String symbol : effective.getSymbols()) {
                if (symbol != null && !symbol.isBlank()) {
                    symbols.add(symbol.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (symbols.isEmpty()) {
            symbols.add("CC");
            symbols.add("CBTC");
        }

        try {
            BigDecimal floor = parseAmountOrDefault(effective.getFloorAmount(), "0.0000000000");
            boolean archive = effective.getArchive() == null || effective.getArchive();
            return Result.ok(new ClearportxFlowService.WalletDrainCommand(
                    party,
                    symbols,
                    floor,
                    archive
            ));
        } catch (IllegalArgumentException ex) {
            return Result.err(new ValidationError(ex.getMessage(), ValidationError.Type.REQUEST));
        }
    }

    private Result<ClearportxFlowService.SwapByCidCommand, DomainError> parseSwapRequest(
            final SwapByCidRequest request,
            final String trader
    ) {
        if (request.getPoolCid() == null || request.getPoolCid().isBlank()) {
            return Result.err(new ValidationError("poolCid is required", ValidationError.Type.REQUEST));
        }
        if (request.getInputSymbol() == null || request.getInputSymbol().isBlank()) {
            return Result.err(new ValidationError("inputSymbol is required", ValidationError.Type.REQUEST));
        }
        if (request.getOutputSymbol() == null || request.getOutputSymbol().isBlank()) {
            return Result.err(new ValidationError("outputSymbol is required", ValidationError.Type.REQUEST));
        }
        try {
            BigDecimal amountIn = parseAmountOrDefault(request.getAmountIn(), "0.0000000001");
            BigDecimal minOutput = parseAmountOrDefault(request.getMinOutput(), "0.0000000001");
            return Result.ok(new ClearportxFlowService.SwapByCidCommand(
                    trader,
                    request.getPoolCid(),
                    request.getPoolId(),
                    request.getInputSymbol(),
                    request.getOutputSymbol(),
                    amountIn,
                    minOutput
            ));
        } catch (IllegalArgumentException ex) {
            return Result.err(new ValidationError(ex.getMessage(), ValidationError.Type.REQUEST));
        }
    }

    private ResponseEntity<?> toHttpResponse(Result<?, DomainError> result, String path) {
        if (result.isOk()) {
            return ResponseEntity.ok(result.getValueUnsafe());
        }
        return mapDomainErrorToResponse(result.getErrorUnsafe(), path);
    }

    private ResponseEntity<ErrorResponse> mapDomainErrorToResponse(DomainError error, String path) {
        HttpStatus status = DomainErrorStatusMapper.map(error);
        ErrorResponse payload = new ErrorResponse(
                error.code(),
                error.message(),
                status.value(),
                path
        );
        return ResponseEntity.status(status).body(payload);
    }

    private CompletableFuture<ResponseEntity<?>> completedError(DomainError error, String path) {
        return CompletableFuture.completedFuture(mapDomainErrorToResponse(error, path));
    }

    private String resolveOperator(String headerParty) {
        if (headerParty != null && !headerParty.isBlank()) {
            return headerParty;
        }
        String env = System.getenv("APP_PROVIDER_PARTY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return null;
    }

    private static long clampBps(Long rawValue, long defaultValue) {
        long value = rawValue != null ? rawValue : defaultValue;
        if (value < 1L) {
            return 1L;
        }
        if (value > 10000L) {
            return 10000L;
        }
        return value;
    }

    private Result<String, DomainError> resolveTrader(final String headerParty, final String authorizationHeader) {
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            Result<AuthenticatedUser, DomainError> authResult = jwtAuthService.authenticate(authorizationHeader);
            if (authResult.isOk()) {
                return Result.ok(authResult.getValueUnsafe().partyId());
            }
            return Result.err(authResult.getErrorUnsafe());
        }
        if (headerParty == null || headerParty.isBlank()) {
            return Result.err(new ValidationError("Provide X-Party header or Authorization token", ValidationError.Type.AUTHENTICATION));
        }
        return Result.ok(headerParty);
    }

    private static BigDecimal parseAmountOrDefault(String raw, String fallback) {
        String candidate = (raw == null || raw.isBlank()) ? fallback : raw;
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("Amount is required");
        }
        return new BigDecimal(candidate.trim(), MathContext.DECIMAL64).setScale(10, RoundingMode.HALF_UP);
    }
}

