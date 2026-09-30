package com.digitalasset.quickstart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class HoldingPoolCreateRequest {
    @NotBlank(message = "poolId is required")
    public String poolId;

    @NotNull(message = "instrumentA is required")
    public InstrumentRef instrumentA;

    @NotNull(message = "instrumentB is required")
    public InstrumentRef instrumentB;

    @NotNull(message = "feeBps is required")
    public Integer feeBps;

    public HoldingPoolCreateRequest() {}

    public HoldingPoolCreateRequest(String poolId, InstrumentRef instrumentA, InstrumentRef instrumentB, Integer feeBps) {
        this.poolId = poolId;
        this.instrumentA = instrumentA;
        this.instrumentB = instrumentB;
        this.feeBps = feeBps;
    }

    public static class InstrumentRef {
        @NotBlank(message = "admin is required")
        public String admin;
        @NotBlank(message = "id is required")
        public String id;

        public InstrumentRef() {}

        public InstrumentRef(String admin, String id) {
            this.admin = admin;
            this.id = id;
        }
    }
}
