package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.LpPositionResponse;
import com.digitalasset.quickstart.service.LpPositionService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lp")
public class LpPositionController {

    private static final Logger LOG = LoggerFactory.getLogger(LpPositionController.class);
    private final LpPositionService lpPositionService;

    public LpPositionController(LpPositionService lpPositionService) {
        this.lpPositionService = lpPositionService;
    }

    /**
     * GET /api/lp/positions?ownerParty=...&poolCid=...
     */
    @GetMapping("/positions")
    @WithSpan
    @PreAuthorize("isAuthenticated()")
    public CompletableFuture<List<LpPositionResponse>> positions(
            @RequestParam("ownerParty") String ownerParty,
            @RequestParam(value = "poolCid", required = false) String poolCid
    ) {
        LOG.info("GET /api/lp/positions ownerParty={} poolCid={}", ownerParty, poolCid);
        return lpPositionService.positions(ownerParty, poolCid);
    }
}

