// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Standardized error response for all API endpoints.
 *
 * Provides consistent error format for frontend error handling.
 *
 * Example:
 * {
 *   "error": "RATE_LIMIT_EXCEEDED",
 *   "message": "Global limit is 0.4 TPS ",
 *   "timestamp": "2025-10-21T15:30:45.123Z",
 *   "path": "/api/swap/atomic",
 *   "status": 429,
 *   "retryAfter": 3
 * }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    /**
     * Error code (uppercase snake_case).
     * Examples: RATE_LIMIT_EXCEEDED, INSUFFICIENT_LIQUIDITY, CONTRACT_NOT_FOUND
     */
    private String error;

    /**
     * Human-readable error message for debugging.
     */
    private String message;

    /**
     * ISO-8601 timestamp when error occurred.
     */
    private String timestamp;

    /**
     * Request path that caused the error.
     */
    private String path;

    /**
     * HTTP status code.
     */
    private int status;

    /**
     * Optional: Retry-After seconds (for 429 rate limit errors).
     */
    private Integer retryAfter;

    /**
     * Optional: Request ID for tracing (if X-Request-ID header provided).
     */
    private String requestId;

    /**
     * Optional: Additional details (e.g., validation errors).
     */
    private Object details;

    public ErrorResponse() {
        this.timestamp = Instant.now().toString();
    }

    public ErrorResponse(String error, String message) {
        this();
        this.error = error;
        this.message = message;
    }

    public ErrorResponse(String error, String message, int status) {
        this(error, message);
        this.status = status;
    }

    public ErrorResponse(String error, String message, int status, String path) {
        this(error, message, status);
        this.path = path;
    }

    // Getters and Setters

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public Integer getRetryAfter() {
        return retryAfter;
    }

    public void setRetryAfter(Integer retryAfter) {
        this.retryAfter = retryAfter;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Object getDetails() {
        return details;
    }

    public void setDetails(Object details) {
        this.details = details;
    }
}
