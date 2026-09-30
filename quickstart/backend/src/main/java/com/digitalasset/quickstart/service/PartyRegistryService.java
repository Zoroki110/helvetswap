package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.admin.PartyManagementServiceGrpc;
import com.daml.ledger.api.v2.admin.PartyManagementServiceOuterClass;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DevNet party registry that resolves display names to fully-qualified party IDs.
 * Periodically refreshes from the ledger's party management API.
 */
@Service
@Profile("devnet")
public class PartyRegistryService {

    private static final Logger logger = LoggerFactory.getLogger(PartyRegistryService.class);

    @Value("${canton.ledger.host:localhost}")
    private String ledgerHost;

    @Value("${canton.ledger.port:5001}")
    private int ledgerPort;

    private final Map<String, String> byName = new ConcurrentHashMap<>();
    private final Map<String, String> byId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private ManagedChannel channel;
    private PartyManagementServiceGrpc.PartyManagementServiceBlockingStub partyMgmtStub;

    @PostConstruct
    public void init() {
        // Create gRPC channel to participant
        this.channel = ManagedChannelBuilder
                .forAddress(ledgerHost, ledgerPort)
                .usePlaintext()
                .build();

        this.partyMgmtStub = PartyManagementServiceGrpc.newBlockingStub(channel);

        // Initial refresh
        refresh();

        // Schedule periodic refresh every 30 seconds
        scheduler.scheduleAtFixedRate(this::safeRefresh, 30, 30, TimeUnit.SECONDS);
        logger.info("PartyRegistryService initialized - refreshing every 30s from {}:{}", ledgerHost, ledgerPort);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        if (channel != null) {
            channel.shutdown();
        }
    }

    /**
     * Resolve a party name or ID to a fully-qualified party ID.
     * @param nameOrId Display name (e.g., "Alice", "PoolOperator") or fully-qualified ID
     * @return Fully-qualified party ID if found
     */
    public Optional<String> resolve(String nameOrId) {
        if (nameOrId == null || nameOrId.trim().isEmpty()) {
            return Optional.empty();
        }

        // If it's already a fully-qualified ID, return it
        if (byId.containsKey(nameOrId)) {
            return Optional.of(nameOrId);
        }

        // Try to resolve by display name
        String normalized = normalize(nameOrId);
        String fullyQualified = byName.get(normalized);

        if (fullyQualified != null) {
            logger.debug("Resolved '{}' -> '{}'", nameOrId, fullyQualified);
            return Optional.of(fullyQualified);
        }

        logger.warn("Failed to resolve party: '{}'", nameOrId);
        return Optional.empty();
    }

    /**
     * Refresh party mappings from the ledger.
     */
    private void refresh() {
        try {
            var request = PartyManagementServiceOuterClass.ListKnownPartiesRequest.newBuilder().build();
            var response = partyMgmtStub.listKnownParties(request);

            int localCount = 0;
            int totalCount = 0;

            byName.clear();
            byId.clear();

            for (var partyDetails : response.getPartyDetailsList()) {
                String partyId = partyDetails.getParty();
                boolean isLocal = partyDetails.getIsLocal();

                totalCount++;
                if (isLocal) {
                    localCount++;
                }

                // Always store by ID (idempotent)
                byId.put(partyId, partyId);

                // Store by party hint (short prefix before ::)
                // e.g., "Alice-9cefe94d::1220..." -> store "Alice-9cefe94d" and "alice" mappings
                int separatorIdx = partyId.indexOf("::");
                if (separatorIdx > 0) {
                    String hint = partyId.substring(0, separatorIdx);
                    byName.put(normalize(hint), partyId);

                    // Also store just the name part without the hash
                    // e.g., "Alice-9cefe94d" -> "alice"
                    int dashIdx = hint.indexOf('-');
                    if (dashIdx > 0) {
                        String nameOnly = hint.substring(0, dashIdx);
                        byName.put(normalize(nameOnly), partyId);
                    }
                }

                if (isLocal) {
                    logger.debug("Registered local party: {}", partyId);
                }
            }

            logger.info("Refreshed party registry: {} local parties, {} total parties", localCount, totalCount);

        } catch (Exception e) {
            logger.error("Failed to refresh party registry", e);
        }
    }

    private void safeRefresh() {
        try {
            refresh();
        } catch (Exception e) {
            logger.warn("Scheduled party refresh failed (will retry)", e);
        }
    }

    private String normalize(String s) {
        return s.trim().toLowerCase();
    }

    /**
     * Get all registered party IDs (for debugging).
     */
    public Map<String, String> getAllMappings() {
        return Map.copyOf(byName);
    }
}
