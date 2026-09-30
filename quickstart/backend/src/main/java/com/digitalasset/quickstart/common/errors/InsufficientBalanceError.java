package com.digitalasset.quickstart.common.errors;

import com.digitalasset.quickstart.common.DomainError;

public final class InsufficientBalanceError extends DomainError {

    public InsufficientBalanceError(final String details) {
        super("INSUFFICIENT_BALANCE", details, 422);
    }
}
