package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorMapper;
import com.digitalasset.quickstart.dto.LiquidityRemoveConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityRemoveConsumeResponse;
import com.digitalasset.quickstart.dto.LiquidityRemoveInspectResponse;
import com.digitalasset.quickstart.service.LiquidityRemoveService;
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
@RequestMapping("/api/devnet/liquidity/remove")
public class DevNetLiquidityRemoveController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetLiquidityRemoveController.class);
    private final LiquidityRemoveService removeService;

    public DevNetLiquidityRemoveController(final LiquidityRemoveService removeService) {
        this.removeService = removeService;
    }

    /**
     * POST /api/devnet/liquidity/remove/consume
     */
    @PostMapping("/consume")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<LiquidityRemoveConsumeResponse>>> consume(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody LiquidityRemoveConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = request != null ? request.requestId : null;
        if (requestId == null || requestId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond("liquidity-remove-consume",
                            Result.err(validationError("requestId is required", "requestId"))));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }
        logger.info("[DevNetLiquidityRemove] POST /liquidity/remove/consume requestId={}", requestId);
        return removeService.consume(request)
                .thenApply(result -> respond(requestId, result));
    }

    /**
     * GET /api/devnet/liquidity/remove/inspect
     */
    @GetMapping("/inspect")
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<LiquidityRemoveInspectResponse>>> inspect(
            @RequestParam("requestId") String requestId,
            @RequestParam(value = "poolCid", required = false) String poolCid,
            @RequestParam("lpCid") String lpCid,
            @RequestParam("receiverParty") String receiverParty,
            @RequestParam(value = "lpBurnAmount", required = false) String lpBurnAmount,
            HttpServletRequest httpRequest
    ) {
        if (requestId == null || requestId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    respond("liquidity-remove-inspect",
                            Result.err(validationError("requestId is required", "requestId"))));
        }
        if (httpRequest != null) {
            httpRequest.setAttribute("requestId", requestId);
        }
        logger.info("[DevNetLiquidityRemove] GET /liquidity/remove/inspect requestId={} poolCid={} lpCid={}",
                requestId, poolCid, lpCid);
        return removeService.inspect(requestId, poolCid, lpCid, receiverParty, lpBurnAmount)
                .thenApply(result -> respond(requestId, result));
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(String requestId, Result<T, ApiError> result) {
        ResponseEntity<ApiResponse<T>> response;
        if (result.isOk()) {
            response = ResponseEntity.ok(ApiResponse.success(requestId, result.getValueUnsafe()));
        } else {
            ApiError error = result.getErrorUnsafe();
            logger.error("[DevNetLiquidityRemove] requestId={} failed code={} message={} details={}",
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

