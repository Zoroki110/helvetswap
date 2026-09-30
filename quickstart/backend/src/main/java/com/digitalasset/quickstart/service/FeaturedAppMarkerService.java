package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.ledger.LedgerApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Creates FeaturedAppActivityMarker contracts on the ledger after economically
 * important events (swaps, liquidity deposits, withdrawals).
 *
 * CIP-0104: Until Increment 4 (end of June 2026), apps must create markers
 * manually to earn Featured App rewards. After Increment 4, this is automatic
 * via traffic-based measurement.
 *
 * Requires:
 * - featured-app.enabled=true in application config
 * - FeaturedAppRight contract granted to app provider party by the DSO
 * - splice-api-featured-app-v1-1.0.0.dar uploaded to participant
 *
 * NOTE: The actual ledger interaction (querying FeaturedAppRight, exercising
 * CreateActivityMarker) is stubbed as LOG-only until the FeaturedAppRight
 * contract is granted by the DSO. Once granted, implement the gRPC calls
 * using LedgerApi to query the FeaturedAppRight active contract and exercise
 * the FeaturedAppRight_CreateActivityMarker choice.
 *
 * Template qualified name: Splice.Api.FeaturedAppV1:FeaturedAppRight
 * (from splice-api-featured-app-v1-1.0.0.dar)
 */
@Service
@ConditionalOnProperty(name = "featured-app.enabled", havingValue = "true", matchIfMissing = false)
public class FeaturedAppMarkerService {

    private static final Logger LOG = LoggerFactory.getLogger(FeaturedAppMarkerService.class);

    private final String appProviderParty;
    private final LedgerApi ledgerApi;

    public FeaturedAppMarkerService(
            @Value("${featured-app.provider-party:}") String appProviderParty,
            LedgerApi ledgerApi) {
        this.appProviderParty = appProviderParty;
        this.ledgerApi = ledgerApi;
        LOG.info("FeaturedAppMarkerService initialized — activity markers ENABLED. "
                + "Markers are logged until FeaturedAppRight is granted by DSO.");
    }

    /**
     * Create an activity marker after a successful economic event.
     * Fails silently with WARN log — NEVER breaks a production transaction.
     *
     * @param requestId   stable per-business-event id used as marker dedup discriminator (Phase 2)
     * @param eventType   e.g., "SWAP", "LIQUIDITY_ADD", "LIQUIDITY_REMOVE"
     * @param description human-readable description (e.g., "swap:CC/CBTC:100.5")
     */
    public void createMarker(String requestId, String eventType, String description) {
        try {
            // TODO(CIP-0104): Once FeaturedAppRight is granted by DSO, replace this
            // log-only stub with actual ledger interaction:
            //
            // 1. Query active FeaturedAppRight contracts for appProviderParty
            //    using LedgerApi.getActiveContracts() with template ID
            //    "Splice.Api.FeaturedAppV1:FeaturedAppRight"
            //
            // 2. Exercise FeaturedAppRight_CreateActivityMarker choice with:
            //    - provider = appProviderParty
            //    - description = eventType + ":" + description
            //
            // Until then, log the marker event for audit trail.
            LOG.info("FeaturedAppActivityMarker [PENDING DSO GRANT]: type={}, desc={}, requestId={}",
                    eventType, description, requestId);
        } catch (Exception e) {
            // NEVER let marker creation break a production transaction
            LOG.warn("Failed to create FeaturedAppActivityMarker (non-fatal): type={}, requestId={}, error={}",
                    eventType, requestId, e.getMessage());
        }
    }
}
