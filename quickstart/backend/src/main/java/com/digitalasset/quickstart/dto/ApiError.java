package com.digitalasset.quickstart.dto;

import java.util.Map;

public class ApiError {
    public ErrorCode code;
    public String message;
    public Map<String, Object> details;
    public Boolean retryable;
    public String grpcStatus;
    public String grpcDescription;

    public ApiError() {}

    public ApiError(ErrorCode code, String message, Map<String, Object> details, Boolean retryable, String grpcStatus, String grpcDescription) {
        this.code = code;
        this.message = message;
        this.details = details;
        this.retryable = retryable;
        this.grpcStatus = grpcStatus;
        this.grpcDescription = grpcDescription;
    }

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code, message, null, null, null, null);
    }

    public static ApiError of(ErrorCode code, String message, Map<String, Object> details) {
        return new ApiError(code, message, details, null, null, null);
    }
}

