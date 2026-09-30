package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.ErrorMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Fallback handler for DevNet endpoints when the devnet profile is NOT active.
 */
@RestController
@RequestMapping("/api/devnet")
@Profile("!devnet")
public class DevNetDisabledController {

    @RequestMapping("/**")
    public ResponseEntity<ApiResponse<Void>> devnetDisabled(HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        ApiError error = new ApiError(
                ErrorCode.PRECONDITION_FAILED,
                "DevNet endpoints are disabled. Start backend with SPRING_PROFILES_ACTIVE=devnet",
                Map.of("requiredProfile", "devnet"),
                false,
                null,
                null
        );
        return ResponseEntity.status(ErrorMapper.toHttpStatus(error.code))
                .body(ApiResponse.failure(requestId, error));
    }

    private String resolveRequestId(HttpServletRequest request) {
        if (request == null) {
            return "req-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String header = request.getHeader("X-Request-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        return "req-" + UUID.randomUUID().toString().substring(0, 8);
    }
}

