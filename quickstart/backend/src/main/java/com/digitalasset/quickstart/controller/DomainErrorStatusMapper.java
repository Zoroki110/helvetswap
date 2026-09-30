package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.errors.ContractNotActiveError;
import com.digitalasset.quickstart.common.errors.InputTokenStaleOrNotVisibleError;
import com.digitalasset.quickstart.common.errors.InsufficientBalanceError;
import com.digitalasset.quickstart.common.errors.LedgerVisibilityError;
import com.digitalasset.quickstart.common.errors.PoolEmptyError;
import com.digitalasset.quickstart.common.errors.PriceImpactTooHighError;
import com.digitalasset.quickstart.common.errors.ValidationError;
import org.springframework.http.HttpStatus;

/**
 * Centralizes DomainError -> HttpStatus mapping so all controllers respond consistently.
 */
final class DomainErrorStatusMapper {

    private DomainErrorStatusMapper() {
    }

    static HttpStatus map(final DomainError error) {
        if (error instanceof ValidationError validationError) {
            return validationError.type() == ValidationError.Type.AUTHENTICATION
                    ? HttpStatus.UNAUTHORIZED
                    : HttpStatus.BAD_REQUEST;
        }
        if (error instanceof InputTokenStaleOrNotVisibleError
                || error instanceof PoolEmptyError
                || error instanceof InsufficientBalanceError) {
            return HttpStatus.CONFLICT;
        }
        if (error instanceof PriceImpactTooHighError) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        if (error instanceof ContractNotActiveError || error instanceof LedgerVisibilityError) {
            return HttpStatus.CONFLICT;
        }
        HttpStatus derived = HttpStatus.resolve(error.httpStatus());
        return derived != null ? derived : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
