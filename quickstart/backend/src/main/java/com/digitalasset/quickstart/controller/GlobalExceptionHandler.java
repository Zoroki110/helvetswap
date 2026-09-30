package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.errors.ServiceUnavailableException;
import com.digitalasset.quickstart.ledger.StaleAcsRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Global exception handler — prevents stack trace leaks in HTTP responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleServiceUnavailable(ServiceUnavailableException error) {
        logger.warn("Service unavailable [{}]: {}", error.code(), error.message());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(error.retryAfterSeconds()))
                .body(errorBody(error.code(), error.message(), error.retryAfterSeconds() * 1000));
    }

    @ExceptionHandler(DomainError.class)
    public ResponseEntity<Map<String, Object>> handleDomainError(DomainError error) {
        HttpStatus status = DomainErrorStatusMapper.map(error);
        logger.warn("Domain error [{}]: {}", error.code(), error.message());
        return ResponseEntity.status(status).body(errorBody(error.code(), error.message(), null));
    }

    @ExceptionHandler(StaleAcsRetry.ContractNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleContractNotFound(StaleAcsRetry.ContractNotFoundException ex) {
        logger.error("Contract not found after retry: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorBody("CONTRACT_NOT_FOUND", ex.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(err -> {
            String field = ((FieldError) err).getField();
            fieldErrors.put(field, err.getDefaultMessage());
        });
        logger.warn("Validation failed: {}", fieldErrors);
        Map<String, Object> body = errorBody("VALIDATION_ERROR", "Validation failed", null);
        body.put("fields", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({TimeoutException.class, java.util.concurrent.TimeoutException.class})
    public ResponseEntity<Map<String, Object>> handleTimeout(Exception ex) {
        logger.error("Request timed out: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(errorBody("GATEWAY_TIMEOUT", "The request timed out", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        logger.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("INTERNAL_ERROR", "An unexpected error occurred", null));
    }

    private Map<String, Object> errorBody(String code, String message, Integer retryAfterMs) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("requestId", MDC.get("requestId"));
        error.put("retryAfterMs", retryAfterMs);

        Map<String, Object> body = new HashMap<>();
        body.put("error", error);
        return body;
    }
}
