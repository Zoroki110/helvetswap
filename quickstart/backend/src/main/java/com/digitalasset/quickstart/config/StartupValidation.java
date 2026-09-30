// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup validation to ensure critical DAML templates are available.
 *
 * This performs fail-fast validation that:
 * 1. AtomicSwapProposal template exists in DAML bindings
 * 2. Required templates (Pool, Token, etc.) are available
 *
 * If validation fails, the application logs warnings but continues.
 * For production, consider making this fail-fast by throwing exceptions.
 */
@Component
public class StartupValidation {

    private static final Logger logger = LoggerFactory.getLogger(StartupValidation.class);

    /**
     * Validate DAML bindings after application is fully started.
     * This runs after all beans are initialized and health checks are ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateDamlBindings() {
        logger.info("🔍 Running startup validation for DAML bindings...");

        boolean allValid = true;

        // Validate AtomicSwapProposal (critical for atomic swap feature)
        if (!checkClassExists("helvet_amm.amm.atomicswap.AtomicSwapProposal")) {
            logger.error("❌ CRITICAL: AtomicSwapProposal template NOT found in bindings!");
            logger.error("   → Atomic swap endpoint will not work");
            logger.error("   → Ensure clearportx-amm-1.0.1.dar is built and code-generated");
            allValid = false;
        } else {
            logger.info("✅ AtomicSwapProposal template found");
        }

        // Validate core templates
        if (!checkClassExists("helvet_amm.amm.pool.Pool")) {
            logger.error("❌ CRITICAL: Pool template NOT found in bindings!");
            allValid = false;
        } else {
            logger.info("✅ Pool template found");
        }

        if (!checkClassExists("helvet_amm.token.token.Token")) {
            logger.error("❌ CRITICAL: Token template NOT found in bindings!");
            allValid = false;
        } else {
            logger.info("✅ Token template found");
        }

        if (!checkClassExists("helvet_amm.lptoken.lptoken.LPToken")) {
            logger.error("❌ CRITICAL: LPToken template NOT found in bindings!");
            allValid = false;
        } else {
            logger.info("✅ LPToken template found");
        }

        if (!checkClassExists("helvet_amm.amm.swaprequest.SwapRequest")) {
            logger.error("❌ CRITICAL: SwapRequest template NOT found in bindings!");
            allValid = false;
        } else {
            logger.info("✅ SwapRequest template found");
        }

        // Log final status
        if (allValid) {
            logger.info("✅ All DAML template validations passed");
            logger.info("📦 DAR Version: 1.0.0 (Drain+Credit / Canton 3.4.7 ready)");
        } else {
            logger.error("❌ Some DAML template validations FAILED");
            logger.error("⚠️  Application may not function correctly");
            logger.error("💡 Run: cd clearportx && daml build && cd ../backend && ../gradlew build");
        }
    }

    /**
     * Check if a class exists in the classpath (i.e., DAML bindings were generated).
     *
     * @param className Fully qualified class name
     * @return true if class exists
     */
    private boolean checkClassExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
