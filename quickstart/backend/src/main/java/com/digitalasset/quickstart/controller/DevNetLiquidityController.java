package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorMapper;
import com.digitalasset.quickstart.dto.LiquidityConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityConsumeResponse;
import com.digitalasset.quickstart.dto.LiquidityInspectResponse;
import com.digitalasset.quickstart.service.LiquidityTiProcessorService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devnet/liquidity")
public class DevNetLiquidityController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetLiquidityController.class);
    private final LiquidityTiProcessorService liquidityService;

    public DevNetLiquidityController(final LiquidityTiProcessorService liquidityService) {
        this.liquidityService = liquidityService;
    }

    /**
     * POST /api/devnet/liquidity/consume
     * Consume inbound liquidity TIs and mint LP.
     */
    @PostMapping("/consume")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<LiquidityConsumeResponse>>> consume(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody LiquidityConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = request != null ? request.requestId : null;
        if (requestId == null || requestId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond("liquidity-consume", Result.err(validationError("requestId is required", "requestId"))));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }
        logger.info("[DevNetLiquidity] POST /liquidity/consume requestId={}", requestId);
        return liquidityService.consume(request)
                .thenApply(result -> respond(requestId, result));
    }

    /**
     * GET /api/devnet/liquidity/inspect?requestId=...&poolCid=...
     */
    @GetMapping("/inspect")
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<LiquidityInspectResponse>>> inspect(
            @RequestParam("requestId") String requestId,
            @RequestParam(value = "poolCid", required = false) String poolCid,
            HttpServletRequest httpRequest
    ) {
        if (requestId == null || requestId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond("liquidity-inspect", Result.err(validationError("requestId is required", "requestId"))));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }
        logger.info("[DevNetLiquidity] GET /liquidity/inspect requestId={} poolCid={}", requestId, poolCid);
        return liquidityService.inspect(requestId, poolCid)
                .thenApply(result -> respond(requestId, result));
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(String requestId, Result<T, ApiError> result) {
        ResponseEntity<ApiResponse<T>> response;
        if (result.isOk()) {
            response = ResponseEntity.ok(ApiResponse.success(requestId, result.getValueUnsafe()));
        } else {
            ApiError error = result.getErrorUnsafe();
            logger.error("[DevNetLiquidity] requestId={} failed code={} message={} details={}",
                    requestId, error.code, error.message, error.details);
            response = ResponseEntity.status(ErrorMapper.toHttpStatus(error.code))
                    .body(ApiResponse.failure(requestId, error));
        }
        return ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.DEVNET);
    }

    private ApiError validationError(String message, String field) {
        return new ApiError(
                com.digitalasset.quickstart.dto.ErrorCode.VALIDATION,
                message,
                java.util.Map.of("field", field),
                false,
                null,
                null
        );
    }
}

