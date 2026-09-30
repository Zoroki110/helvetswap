package com.digitalasset.quickstart.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PoolDirectoryService {
    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private volatile Instant lastUpdated = Instant.EPOCH;

    public void update(String poolId, String poolCid, String party) {
        if (poolId == null || poolId.isBlank() || poolCid == null || poolCid.isBlank()) return;
        map.put(poolId, new Entry(poolCid, party, Instant.now()));
        lastUpdated = Instant.now();
    }

    public Map<String, Map<String, String>> snapshot() {
        Map<String, Map<String, String>> out = new ConcurrentHashMap<>();
        for (var e : map.entrySet()) {
            out.put(e.getKey(), Map.of(
                "poolCid", e.getValue().poolCid,
                "party", e.getValue().party
            ));
        }
        return Collections.unmodifiableMap(out);
    }

    public Instant lastUpdated() {
        return lastUpdated;
    }

    private record Entry(String poolCid, String party, Instant updatedAt) {}
}


