package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.LiquidityConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityConsumeResponse;
import com.digitalasset.quickstart.dto.LiquidityInspectResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * Profile-agnostic HTTP surface for add-liquidity consume/inspect.
 *
 * Delegates to {@link DevNetLiquidityController} which owns the underlying
 * logic. On prod the delegate is absent; callers receive a structured 503
 * JSON error rather than a Spring NoResourceFoundException 404.
 */
@RestController
@RequestMapping("/api/liquidity/add")
public class LiquidityAddController {

    @Autowired(required = false)
    private DevNetLiquidityController devNetLiquidityController;

    /**
     * POST /api/liquidity/add/consume (agnostic surface)
     */
    @PostMapping("/consume")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public CompletableFuture<ResponseEntity<ApiResponse<LiquidityConsumeResponse>>> consume(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody LiquidityConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        if (devNetLiquidityController == null) {
            return CompletableFuture.completedFuture(
                    ApiSurfaceHeaders.withSurface(
                            ResponseEntity.status(503).body(ApiResponse.<LiquidityConsumeResponse>failure(
                                    request != null ? request.requestId : null,
                                    ApiError.of(ErrorCode.INTERNAL, "add-liquidity consume handler not available in this profile")
                            )),
                            ApiSurfaceHeaders.AGNOSTIC
                    )
            );
        }
        return devNetLiquidityController.consume(jwt, request, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }

    /**
     * GET /api/liquidity/add/inspect?requestId=...
     */
    @GetMapping("/inspect")
    public CompletableFuture<ResponseEntity<ApiResponse<LiquidityInspectResponse>>> inspect(
            @RequestParam("requestId") String requestId,
            @RequestParam(value = "poolCid", required = false) String poolCid,
            HttpServletRequest httpRequest
    ) {
        if (devNetLiquidityController == null) {
            return CompletableFuture.completedFuture(
                    ApiSurfaceHeaders.withSurface(
                            ResponseEntity.status(503).body(ApiResponse.<LiquidityInspectResponse>failure(
                                    requestId,
                                    ApiError.of(ErrorCode.INTERNAL, "add-liquidity inspect handler not available in this profile")
                            )),
                            ApiSurfaceHeaders.AGNOSTIC
                    )
            );
        }
        return devNetLiquidityController.inspect(requestId, poolCid, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }
}
