package com.digitalasset.quickstart.dto;

/**
 * Standard API envelope used by DevNet endpoints.
 *
 * Example error response:
 * {
 *   "ok": false,
 *   "requestId": "payout-amulet-1a2b3c4d",
 *   "error": {
 *     "code": "VALIDATION",
 *     "message": "amount must be a positive decimal",
 *     "details": { "field": "amount" },
 *     "retryable": false,
 *     "grpcStatus": null,
 *     "grpcDescription": null
 *   }
 * }
 */
public class ApiResponse<T> {
    public boolean ok;
    public String requestId;
    public T result;
    public ApiError error;

    public static <T> ApiResponse<T> success(String requestId, T value) {
        ApiResponse<T> r = new ApiResponse<>();
        r.ok = true;
        r.requestId = requestId;
        r.result = value;
        return r;
    }

    public static <T> ApiResponse<T> failure(String requestId, ApiError err) {
        ApiResponse<T> r = new ApiResponse<>();
        r.ok = false;
        r.requestId = requestId;
        r.error = err;
        return r;
    }
}

