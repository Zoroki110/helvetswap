package com.digitalasset.quickstart.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/**
 * Configuration for {@link ScanOmrCache}. Defaults are Mind-locked via Phase 3F.10
 * Step B OQ-Step-B-2 resolution (2026-05-11): 2 s poll, 5 bootstrap retries with
 * exponential backoff base 1 s (1+2+4+8+16=31 s max), 30 s stale-warn threshold,
 * 60 s fail-open threshold.
 *
 * Source: /root/STEP_B_SCAN_OMR_CACHE.md L132-148 and Mind's OQ-resolution dispatch.
 */
@Profile("devnet")
@ConfigurationProperties("scan-omr.cache")
public record ScanOmrCacheConfig(
        Integer pollIntervalMs,
        Integer bootstrapMaxRetries,
        Integer bootstrapRetryBackoffMs,
        Integer staleWarningThresholdSec,
        Integer failOpenAfterStaleSec
) {
    public ScanOmrCacheConfig {
        if (pollIntervalMs == null)           pollIntervalMs = 2000;
        if (bootstrapMaxRetries == null)      bootstrapMaxRetries = 5;
        if (bootstrapRetryBackoffMs == null)  bootstrapRetryBackoffMs = 1000;
        if (staleWarningThresholdSec == null) staleWarningThresholdSec = 30;
        if (failOpenAfterStaleSec == null)    failOpenAfterStaleSec = 60;
    }
}
