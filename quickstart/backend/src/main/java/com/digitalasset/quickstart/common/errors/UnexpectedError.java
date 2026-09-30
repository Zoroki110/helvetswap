package com.digitalasset.quickstart.common.errors;

import com.digitalasset.quickstart.common.DomainError;

public final class UnexpectedError extends DomainError {

    public UnexpectedError(final String details) {
        super("UNEXPECTED_ERROR", details, 500);
    }
}
