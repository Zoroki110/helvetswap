package com.digitalasset.quickstart.common.errors;

import com.digitalasset.quickstart.common.DomainError;

public final class ContractNotActiveError extends DomainError {

    private final boolean tokenContract;

    public ContractNotActiveError(final String details) {
        this(details, false);
    }

    public ContractNotActiveError(final String details, final boolean tokenContract) {
        super("CONTRACT_NOT_ACTIVE", details, 409);
        this.tokenContract = tokenContract;
    }

    public boolean isTokenContract() {
        return tokenContract;
    }
}
