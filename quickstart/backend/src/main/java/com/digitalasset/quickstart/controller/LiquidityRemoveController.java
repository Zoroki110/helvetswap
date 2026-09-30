package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.LiquidityRemoveConsumeRequest;
import com.digitalasset.quickstart.dto.LiquidityRemoveConsumeResponse;
import com.digitalasset.quickstart.dto.LiquidityRemoveInspectResponse;
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
 * Profile-agnostic HTTP surface for remove-liquidity consume/inspect.
 *
 * Delegates to {@link DevNetLiquidityRemoveController} which owns the
 * underlying logic. On prod the delegate is absent; callers receive a
 * structured 503 JSON error rather than a Spring NoResourceFoundException 404.
 */
@RestController
@RequestMapping("/api/liquidity/remove")
public class LiquidityRemoveController {

    @Autowired(required = false)
    private DevNetLiquidityRemoveController devNetLiquidityRemoveController;

    /**
     * POST /api/liquidity/remove/consume (agnostic surface)
     */
    @PostMapping("/consume")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public CompletableFuture<ResponseEntity<ApiResponse<LiquidityRemoveConsumeResponse>>> consume(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody LiquidityRemoveConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        if (devNetLiquidityRemoveController == null) {
            return CompletableFuture.completedFuture(
                    ApiSurfaceHeaders.withSurface(
                            ResponseEntity.status(503).body(ApiResponse.<LiquidityRemoveConsumeResponse>failure(
                                    request != null ? request.requestId : null,
                                    ApiError.of(ErrorCode.INTERNAL, "remove-liquidity consume handler not available in this profile")
                            )),
                            ApiSurfaceHeaders.AGNOSTIC
                    )
            );
        }
        return devNetLiquidityRemoveController.consume(jwt, request, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }

    /**
     * GET /api/liquidity/remove/inspect
     */
    @GetMapping("/inspect")
    public CompletableFuture<ResponseEntity<ApiResponse<LiquidityRemoveInspectResponse>>> inspect(
            @RequestParam("requestId") String requestId,
            @RequestParam(value = "poolCid", required = false) String poolCid,
            @RequestParam("lpCid") String lpCid,
            @RequestParam("receiverParty") String receiverParty,
            @RequestParam(value = "lpBurnAmount", required = false) String lpBurnAmount,
            HttpServletRequest httpRequest
    ) {
        if (devNetLiquidityRemoveController == null) {
            return CompletableFuture.completedFuture(
                    ApiSurfaceHeaders.withSurface(
                            ResponseEntity.status(503).body(ApiResponse.<LiquidityRemoveInspectResponse>failure(
                                    requestId,
                                    ApiError.of(ErrorCode.INTERNAL, "remove-liquidity inspect handler not available in this profile")
                            )),
                            ApiSurfaceHeaders.AGNOSTIC
                    )
            );
        }
        return devNetLiquidityRemoveController.inspect(requestId, poolCid, lpCid, receiverParty, lpBurnAmount, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }
}
