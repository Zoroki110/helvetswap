package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.SwapConsumeRequest;
import com.digitalasset.quickstart.dto.SwapConsumeResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
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
 * Profile-agnostic HTTP surface for swap consume/inspect.
 *
 * Delegates to {@link DevNetSwapController} which owns the underlying logic.
 * The DevNet controller is optional here: on prod profile it is not loaded
 * (the demo endpoints at /api/devnet/* stay DevNet-gated). When the delegate
 * is absent, we return a structured 503-equivalent JSON error rather than a
 * Spring NoResourceFoundException — the frontend polling loop then sees a
 * proper ApiResponse instead of a 404.
 */
@RestController
@RequestMapping("/api/swap")
public class SwapConsumeController {

    @Autowired(required = false)
    private DevNetSwapController devNetSwapController;

    /**
     * POST /api/swap/consume (agnostic surface)
     */
    @PostMapping("/consume")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public CompletableFuture<ResponseEntity<ApiResponse<SwapConsumeResponse>>> consume(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SwapConsumeRequest request,
            HttpServletRequest httpRequest
    ) {
        if (devNetSwapController == null) {
            return CompletableFuture.completedFuture(
                    ApiSurfaceHeaders.withSurface(
                            ResponseEntity.status(503).body(ApiResponse.<SwapConsumeResponse>failure(
                                    request != null ? request.requestId : null,
                                    ApiError.of(ErrorCode.INTERNAL, "swap consume handler not available in this profile")
                            )),
                            ApiSurfaceHeaders.AGNOSTIC
                    )
            );
        }
        return devNetSwapController.consume(jwt, request, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }

    /**
     * GET /api/swap/inspect?requestId=...
     */
    @GetMapping("/inspect")
    public CompletableFuture<ResponseEntity<ApiResponse<Map<String, Object>>>> inspect(
            @RequestParam("requestId") String requestId,
            HttpServletRequest httpRequest
    ) {
        if (devNetSwapController == null) {
            return CompletableFuture.completedFuture(
                    ApiSurfaceHeaders.withSurface(
                            ResponseEntity.status(503).body(ApiResponse.<Map<String, Object>>failure(
                                    requestId,
                                    ApiError.of(ErrorCode.INTERNAL, "swap inspect handler not available in this profile")
                            )),
                            ApiSurfaceHeaders.AGNOSTIC
                    )
            );
        }
        return devNetSwapController.inspect(requestId, httpRequest)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }
}
