// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Multi-asset registry routing configuration for TransferInstruction acceptance.
 *
 * Supports:
 * - Default registry (Amulet/CC via DevNet Scan)
 * - Fallback registries for multi-token discovery
 * - Admin-to-registry mapping for known token admins (e.g., CBTC)
 */
@Component
@ConfigurationProperties(prefix = "ledger.registry")
public class RegistryRoutingConfig {

    private static final Logger logger = LoggerFactory.getLogger(RegistryRoutingConfig.class);

    private String defaultBaseUri;
    private String fallbackBaseUris;
    private String byAdmin;

    private List<RegistryEndpoint> parsedFallbacks = new ArrayList<>();
    private Map<String, RegistryEndpoint> parsedByAdmin = new HashMap<>();

    @PostConstruct
    public void init() {
        // Parse fallback URIs (comma-separated)
        if (fallbackBaseUris != null && !fallbackBaseUris.isBlank()) {
            parsedFallbacks = Arrays.stream(fallbackBaseUris.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(uri -> new RegistryEndpoint(uri, RegistryKind.SCAN, null))
                    .toList();
        }

        // Parse admin-to-registry mapping (JSON). Supports two shapes:
        // { "admin": "https://base" }  -> kind=SCAN
        // { "admin": { "baseUri": "...", "kind": "UTILITIES_TOKEN_STANDARD" } }
        if (byAdmin != null && !byAdmin.isBlank() && !byAdmin.equals("{}")) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> raw = mapper.readValue(byAdmin, new TypeReference<Map<String, Object>>() {});
                Map<String, RegistryEndpoint> parsed = new HashMap<>();
                for (var entry : raw.entrySet()) {
                    String admin = entry.getKey();
                    Object val = entry.getValue();
                    if (val instanceof String s) {
                        parsed.put(admin, new RegistryEndpoint(s, RegistryKind.SCAN, admin));
                    } else if (val instanceof Map<?,?> m) {
                        String base = m.get("baseUri") != null ? m.get("baseUri").toString() : null;
                        String kindStr = m.get("kind") != null ? m.get("kind").toString() : "SCAN";
                        RegistryKind kind = RegistryKind.from(kindStr);
                        parsed.put(admin, new RegistryEndpoint(base, kind, admin));
                    } else {
                        logger.warn("Unsupported by-admin value for {}: {}", admin, val);
                    }
                }
                parsedByAdmin = parsed;
            } catch (Exception e) {
                logger.warn("Failed to parse ledger.registry.by-admin as JSON: {}. Using empty map.", e.getMessage());
                parsedByAdmin = new HashMap<>();
            }
        }

        logger.info("RegistryRoutingConfig initialized: defaultBaseUri={}, fallbacks={}, byAdminMappings={}",
                defaultBaseUri,
                parsedFallbacks.size(),
                parsedByAdmin.size());

        if (!parsedByAdmin.isEmpty()) {
            parsedByAdmin.forEach((admin, ep) ->
                logger.info("  Admin mapping: {} -> {} ({})", abbreviate(admin), ep.baseUri(), ep.kind()));
        }
    }

    public String getDefaultBaseUri() {
        return defaultBaseUri;
    }

    public void setDefaultBaseUri(String defaultBaseUri) {
        this.defaultBaseUri = defaultBaseUri;
    }

    public String getFallbackBaseUris() {
        return fallbackBaseUris;
    }

    public void setFallbackBaseUris(String fallbackBaseUris) {
        this.fallbackBaseUris = fallbackBaseUris;
    }

    public String getByAdmin() {
        return byAdmin;
    }

    public void setByAdmin(String byAdmin) {
        this.byAdmin = byAdmin;
    }

    /**
     * Get registry base URI for a specific admin party.
     * Returns null if no specific mapping exists.
     */
    public RegistryEndpoint getRegistryForAdmin(String adminParty) {
        if (adminParty == null || parsedByAdmin.isEmpty()) {
            return null;
        }
        return parsedByAdmin.get(adminParty);
    }

    /**
     * Get ordered list of registries to try for TransferInstruction lookup.
     * Order: admin-specific (if known) -> default -> fallbacks
     */
    public List<RegistryEndpoint> getRegistriesToTry(String adminPartyHint) {
        List<RegistryEndpoint> result = new ArrayList<>();

        // 1. If admin is known and mapped, try that first
        if (adminPartyHint != null) {
            RegistryEndpoint adminSpecific = parsedByAdmin.get(adminPartyHint);
            if (adminSpecific != null && adminSpecific.baseUri() != null && !adminSpecific.baseUri().isBlank()) {
                result.add(adminSpecific);
            }
        }

        // 2. Default registry
        if (defaultBaseUri != null && !defaultBaseUri.isBlank()) {
            RegistryEndpoint def = new RegistryEndpoint(defaultBaseUri, RegistryKind.SCAN, null);
            if (!containsEndpoint(result, def)) {
                result.add(def);
            }
        }

        // 3. Fallbacks
        for (RegistryEndpoint fallback : parsedFallbacks) {
            if (!containsEndpoint(result, fallback)) {
                result.add(fallback);
            }
        }

        return result;
    }

    /**
     * Get all configured registries for probing.
     */
    public List<RegistryEndpoint> getAllRegistries() {
        Set<RegistryEndpoint> all = new LinkedHashSet<>();
        if (defaultBaseUri != null && !defaultBaseUri.isBlank()) {
            all.add(new RegistryEndpoint(defaultBaseUri, RegistryKind.SCAN, null));
        }
        all.addAll(parsedFallbacks);
        all.addAll(parsedByAdmin.values());
        return new ArrayList<>(all);
    }

    /**
     * Get parsed admin mappings (for debug/info).
     */
    public Map<String, String> getAdminMappings() {
        Map<String, String> simple = new HashMap<>();
        parsedByAdmin.forEach((k, v) -> simple.put(k, v.baseUri()));
        return Collections.unmodifiableMap(simple);
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        if (s.length() <= 20) return s;
        return s.substring(0, 10) + "..." + s.substring(s.length() - 10);
    }

    private boolean containsEndpoint(List<RegistryEndpoint> list, RegistryEndpoint candidate) {
        return list.stream().anyMatch(e ->
                Objects.equals(e.baseUri(), candidate.baseUri()) &&
                        e.kind() == candidate.kind() &&
                        Objects.equals(e.admin(), candidate.admin()));
    }

    public enum RegistryKind {
        SCAN,
        UTILITIES_TOKEN_STANDARD,
        LOOP_TOKEN_STANDARD_V1;

        public static RegistryKind from(String s) {
            if (s == null) return SCAN;
            String t = s.trim().toUpperCase();
            if ("UTILITIES_TOKEN_STANDARD".equals(t)) return UTILITIES_TOKEN_STANDARD;
            if ("LOOP_TOKEN_STANDARD_V1".equals(t)) return LOOP_TOKEN_STANDARD_V1;
            return SCAN;
        }
    }

    public record RegistryEndpoint(String baseUri, RegistryKind kind, String admin) { }
}
