package com.digitalasset.quickstart.dto;

/**
 * Inspect response for devnet liquidity requests.
 */
public class LiquidityInspectResponse {
    public String requestId;
    public String poolCid;
    public String providerParty;
    public String tiCidA;
    public String tiCidB;
    public String memoRaw;
    public String deadline;
    public boolean deadlineExpired;
    public String poolStatus;
    public boolean alreadyProcessed;
    public InstrumentInfo instrumentA;
    public InstrumentInfo instrumentB;
    public java.util.List<InstrumentInfo> requiredInstruments;
    public java.util.List<InstrumentInfo> foundInstruments;
    public java.util.List<InstrumentInfo> missingInstruments;
    public java.util.List<InboundTiInfo> foundTis;

    public LiquidityInspectResponse() {}

    public static class InstrumentInfo {
        public String admin;
        public String id;
        public String normalizedId;

        public InstrumentInfo() {}

        public InstrumentInfo(String admin, String id, String normalizedId) {
            this.admin = admin;
            this.id = id;
            this.normalizedId = normalizedId;
        }
    }

    public static class InboundTiInfo {
        public String tiCid;
        public String admin;
        public String instrumentId;
        public String normalizedId;
        public String memoInstrumentAdmin;
        public String memoInstrumentId;
        public String memoLeg;
        public String memoAmount;
        public String sender;
        public String receiver;
        public String executeBefore;
        public String memoRaw;

        public InboundTiInfo() {}
    }
}

