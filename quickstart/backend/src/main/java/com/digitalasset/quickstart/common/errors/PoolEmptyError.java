package com.digitalasset.quickstart.common.errors;

import com.digitalasset.quickstart.common.DomainError;

public final class PoolEmptyError extends DomainError {

    public PoolEmptyError(final String details) {
        super("POOL_EMPTY", details, 409);
    }
}
