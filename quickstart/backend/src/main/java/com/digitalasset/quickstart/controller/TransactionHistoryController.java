package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.service.TransactionHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
public class TransactionHistoryController {

    @Autowired
    private TransactionHistoryService transactionHistoryService;

    @Autowired
    private Environment environment;

    @GetMapping("/recent")
    public ResponseEntity<?> recentTransactions(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return ResponseEntity.ok(transactionHistoryService.getRecent(safeLimit));
    }

    @PostMapping("/debug/record")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public ResponseEntity<?> recordDebugEvent(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Debug-Token", required = false) String debugToken) {
        if (!isDebugProfile()) {
            return ResponseEntity.status(403).body(Map.of("success", false, "error", "not_available"));
        }
        if (!"devnet".equals(debugToken)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "error", "invalid_token"));
        }
        String poolId = String.valueOf(body.getOrDefault("poolId", ""));
        String poolCid = String.valueOf(body.getOrDefault("poolCid", ""));
        String tokenA = String.valueOf(body.getOrDefault("tokenA", ""));
        String tokenB = String.valueOf(body.getOrDefault("tokenB", ""));
        BigDecimal amountA = new BigDecimal(String.valueOf(body.getOrDefault("amountA", "0")));
        BigDecimal amountB = new BigDecimal(String.valueOf(body.getOrDefault("amountB", "0")));
        BigDecimal minLp = new BigDecimal(String.valueOf(body.getOrDefault("minLpAmount", "0")));
        BigDecimal amountOut = new BigDecimal(String.valueOf(body.getOrDefault("amountOut", "0")));
        BigDecimal mintedLp = new BigDecimal(String.valueOf(body.getOrDefault("mintedLp", "0")));
        String actor = String.valueOf(body.getOrDefault("actor", "debug"));
        String eventType = String.valueOf(body.getOrDefault("type", "ADD_LIQUIDITY")).toUpperCase();

        switch (eventType) {
            case "POOL_CREATION" -> {
                // Ignore pool creation for history per latest requirements
                return ResponseEntity.ok(Map.of("success", true, "type", eventType, "message", "pool creation events are not recorded"));
            }
            case "SWAP" -> transactionHistoryService.recordSwap(
                    poolId,
                    poolCid,
                    tokenA,
                    tokenB,
                    amountA,
                    amountOut,
                    actor
            );
            default -> transactionHistoryService.recordAddLiquidity(
                    poolId,
                    poolCid,
                    tokenA,
                    tokenB,
                    amountA,
                    amountB,
                    minLp,
                    mintedLp,
                    actor
            );
        }
        return ResponseEntity.ok(Map.of("success", true, "type", eventType));
    }

    private boolean isDebugProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("debug"));
    }
}

