package com.digitalasset.quickstart.common.errors;

import com.digitalasset.quickstart.common.DomainError;

public final class LedgerVisibilityError extends DomainError {

    public LedgerVisibilityError(final String details) {
        super("LEDGER_VISIBILITY_ERROR", details, 409);
    }
}
