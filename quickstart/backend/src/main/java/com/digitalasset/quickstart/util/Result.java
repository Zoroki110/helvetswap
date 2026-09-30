package com.digitalasset.quickstart.util;

/**
 * A simple Result type for handling success/failure cases
 */
public sealed interface Result<T> {

    record Ok<T>(T value) implements Result<T> {}

    record Err<T>(String code, String message) implements Result<T> {
        public String code() { return code; }
        public String message() { return message; }
    }

    static <T> Result<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Result<T> err(String code, String message) {
        return new Err<>(code, message);
    }
}