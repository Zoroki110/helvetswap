package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SwapTiProcessorServiceClassifierTest {

    private static final String TI_CID =
            "0099aed3cfe1d1741e4308a377ab53f6fb87cb0be5a7c7efd65474c12d51abbef5ca121220d6d98ce945256c76f761bd451d30afd0d3d493e3be520a0875469c3d31ea8b86";

    private static ApiError err(String message) {
        return new ApiError(ErrorCode.LEDGER_REJECTED, message, Map.of(), false, null, null);
    }

    @Test
    void mustBeUnlockedClassifierMatchesSplicePost() {
        // Verbatim form observed in production logs (Phase 3F.6, 3F.8B).
        ApiError e = err("io.grpc.StatusRuntimeException: FAILED_PRECONDITION: DAML_FAILURE(9,0d9b8177): "
                + "Interpretation error: Error: User failure: UNHANDLED_EXCEPTION/"
                + "DA.Exception.AssertionFailed:AssertionFailed (error category 9): Must be unlocked");
        assertTrue(SwapTiProcessorService.isMustBeUnlockedError(e));
    }

    @Test
    void mustBeUnlockedClassifierIgnoresUnrelated() {
        assertFalse(SwapTiProcessorService.isMustBeUnlockedError(err("INACTIVE_CONTRACTS something")));
        assertFalse(SwapTiProcessorService.isMustBeUnlockedError(err("")));
        assertFalse(SwapTiProcessorService.isMustBeUnlockedError(null));
    }

    @Test
    void deadlineExpiredClassifierMatches() {
        assertTrue(SwapTiProcessorService.isDeadlineExpiredErrorPostExecute(
                err("Swap deadline has expired")));
        assertTrue(SwapTiProcessorService.isDeadlineExpiredErrorPostExecute(
                err("AssertionFailed: Swap deadline has expired (deadline=...)")));
    }

    @Test
    void deadlineExpiredClassifierIgnoresUnrelated() {
        assertFalse(SwapTiProcessorService.isDeadlineExpiredErrorPostExecute(err("Must be unlocked")));
        assertFalse(SwapTiProcessorService.isDeadlineExpiredErrorPostExecute(null));
    }

    @Test
    void contractNotFoundFiresWhenTiCidIsTheMissingOne() {
        // Standard Canton form
        ApiError e = err("CONTRACT_NOT_FOUND: " + TI_CID + "::abcd");
        assertTrue(SwapTiProcessorService.isContractNotFoundError(e, TI_CID));
    }

    @Test
    void contractNotFoundIsLooseWhenNoCidsCanBeParsed() {
        // No cid present in the message — fail-open: classify as the missing TI to be safe.
        ApiError e = err("CONTRACT_NOT_FOUND: opaque");
        assertTrue(SwapTiProcessorService.isContractNotFoundError(e, TI_CID));
    }

    @Test
    void contractNotFoundIgnoresUnrelated() {
        assertFalse(SwapTiProcessorService.isContractNotFoundError(err("INACTIVE_CONTRACTS"), TI_CID));
        assertFalse(SwapTiProcessorService.isContractNotFoundError(null, TI_CID));
    }

    @Test
    void outputBelowMinOutClassifierMatches() {
        assertTrue(SwapTiProcessorService.isOutputBelowMinOutError(
                err("Output amount below minimum")));
        assertTrue(SwapTiProcessorService.isOutputBelowMinOutError(
                err("AssertionFailed: Output amount below minimum")));
    }

    @Test
    void outputBelowMinOutClassifierIgnoresUnrelated() {
        assertFalse(SwapTiProcessorService.isOutputBelowMinOutError(err("Must be unlocked")));
        assertFalse(SwapTiProcessorService.isOutputBelowMinOutError(null));
    }
}
