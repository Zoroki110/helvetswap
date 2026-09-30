package com.digitalasset.quickstart.common.errors;

import com.digitalasset.quickstart.common.DomainError;

public final class PriceImpactTooHighError extends DomainError {

    public PriceImpactTooHighError(final String details) {
        super("PRICE_IMPACT_TOO_HIGH", details, 422);
    }
}
