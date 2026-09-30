package com.digitalasset.quickstart.common.errors;

import com.digitalasset.quickstart.common.DomainError;

public final class ServiceUnavailableException extends DomainError {

    private final int retryAfterSeconds;

    public ServiceUnavailableException(final String details, final int retryAfterSeconds) {
        super("SERVICE_UNAVAILABLE", details, 503);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
