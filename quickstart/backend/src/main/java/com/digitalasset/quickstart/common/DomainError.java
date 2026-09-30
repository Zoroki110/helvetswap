package com.digitalasset.quickstart.common;

/**
 * Base type for domain-level errors.
 */
public abstract class DomainError extends RuntimeException {

    private final String code;
    private final int httpStatus;

    protected DomainError(final String code, final String message, final int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return getMessage();
    }

    public int httpStatus() {
        return httpStatus;
    }
}
