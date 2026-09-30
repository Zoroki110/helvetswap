package com.digitalasset.quickstart.dto;

import java.util.List;

public class HoldingLockDto {
    public List<String> lockers;        // Parties (party IDs as strings)
    public String expiresAt;            // ISO-8601 Instant, nullable
    public String holderHint;           // nullable
    public List<String> observers;      // nullable

    public HoldingLockDto() {}

    public HoldingLockDto(List<String> lockers, String expiresAt, String holderHint, List<String> observers) {
        this.lockers = lockers;
        this.expiresAt = expiresAt;
        this.holderHint = holderHint;
        this.observers = observers;
    }
}
