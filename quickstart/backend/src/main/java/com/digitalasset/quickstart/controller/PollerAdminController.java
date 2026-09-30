package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.service.SwapPollerService;
import com.digitalasset.quickstart.service.poller.LiquidityPollerService;
import com.digitalasset.quickstart.service.poller.PollerRetryGuard;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/poller")
@Profile("prod")
public class PollerAdminController {

    private final LiquidityPollerService liquidityPollerService;
    private final SwapPollerService swapPollerService;

    public PollerAdminController(LiquidityPollerService liquidityPollerService,
                                 SwapPollerService swapPollerService) {
        this.liquidityPollerService = liquidityPollerService;
        this.swapPollerService = swapPollerService;
    }

    // Read-only, admin-gated dead-list for both pollers. requestIds are prefixed
    // (liq-… / swap-…) so the source poller is distinguishable in the response.
    @GetMapping("/dead")
    @PreAuthorize("@partyGuard.isAuthenticated(#jwt)")
    public List<PollerRetryGuard.DeadEntry> listDead(@AuthenticationPrincipal Jwt jwt) {
        List<PollerRetryGuard.DeadEntry> all = new ArrayList<>();
        all.addAll(liquidityPollerService.getRetryGuard().listDead());
        all.addAll(swapPollerService.getRetryGuard().listDead());
        return all;
    }
}
