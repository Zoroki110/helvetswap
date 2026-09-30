package com.digitalasset.quickstart.common.errors;

import com.digitalasset.quickstart.common.DomainError;

public final class ValidationError extends DomainError {

    public enum Type {
        REQUEST,
        AUTHENTICATION
    }

    private final Type type;

    public ValidationError(final String details) {
        this(details, Type.REQUEST);
    }

    public ValidationError(final String details, final Type type) {
        super("VALIDATION_ERROR", details, type == Type.AUTHENTICATION ? 401 : 400);
        this.type = type;
    }

    public Type type() {
        return type;
    }
}
