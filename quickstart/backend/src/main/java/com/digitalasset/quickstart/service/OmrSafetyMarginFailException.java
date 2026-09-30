package com.digitalasset.quickstart.service;

/**
 * Thrown by {@link OpenMiningRoundRefreshService#applySafetyMarginFromScan} when no
 * OpenMiningRound returned by scan satisfies the safety-margin selection rule
 * (opensAt &le; now AND latest opensAt AND targetClosesAt - now &gt; 30s) on the
 * first pass and again on the retry pass.
 *
 * Caught by {@link SwapTiProcessorService#prepareAndExecuteSwap} which dead-sets
 * the request via {@link com.digitalasset.quickstart.store.SwapInspectStore#markFailed}
 * with errorClass {@link #ERROR_CLASS}.
 */
public class OmrSafetyMarginFailException extends RuntimeException {

    public static final String ERROR_CLASS = "OMR_SAFETY_MARGIN_FAIL";

    private final String requestId;

    public OmrSafetyMarginFailException(String requestId, String message) {
        super(message);
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getErrorClass() {
        return ERROR_CLASS;
    }
}
