package com.digitalasset.quickstart.common.errors;

import com.digitalasset.quickstart.common.DomainError;

public final class InputTokenStaleOrNotVisibleError extends DomainError {

    public InputTokenStaleOrNotVisibleError(final String details) {
        super("INPUT_TOKEN_STALE_OR_NOT_VISIBLE", details, 409);
    }
}
