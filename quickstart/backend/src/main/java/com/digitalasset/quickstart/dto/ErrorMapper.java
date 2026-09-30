package com.digitalasset.quickstart.dto;

import org.springframework.http.HttpStatus;

public class ErrorMapper {
    public static HttpStatus toHttpStatus(ErrorCode code) {
        return switch (code) {
            case VALIDATION, MISSING_INBOUND_TIS_FOR_POOL_INSTRUMENT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN, LEDGER_AUTH -> HttpStatus.FORBIDDEN;
            case PRECONDITION_FAILED, LEGACY_LP_POOL_NOT_FOUND -> HttpStatus.PRECONDITION_FAILED;
            case CONFLICT -> HttpStatus.CONFLICT;
            case LEDGER_REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}

