package com.digitalasset.quickstart.common;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Functional-style container that represents either a success (Ok) or failure (Err).
 */
public final class Result<T, E> {

    private final T value;
    private final E error;
    private final boolean ok;

    private Result(final T value, final E error, final boolean ok) {
        this.value = value;
        this.error = error;
        this.ok = ok;
    }

    public static <T, E> Result<T, E> ok(final T value) {
        return new Result<>(value, null, true);
    }

    public static <T, E> Result<T, E> err(final E error) {
        return new Result<>(null, Objects.requireNonNull(error, "error"), false);
    }

    public boolean isOk() {
        return ok;
    }

    public boolean isErr() {
        return !ok;
    }

    public Optional<T> toOptional() {
        return Optional.ofNullable(value);
    }

    public T getOrElse(final T fallback) {
        return ok ? value : fallback;
    }

    public <U> Result<U, E> map(final Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (ok) {
            return Result.ok(mapper.apply(value));
        }
        return Result.err(error);
    }

    public <U> Result<U, E> flatMap(final Function<? super T, Result<U, E>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (ok) {
            return Objects.requireNonNull(mapper.apply(value), "flatMap result");
        }
        return Result.err(error);
    }

    public <F> Result<T, F> mapError(final Function<? super E, ? extends F> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (ok) {
            return Result.ok(value);
        }
        return Result.err(mapper.apply(error));
    }

    public Result<T, E> recover(final Function<? super E, ? extends T> recoverFn) {
        Objects.requireNonNull(recoverFn, "recoverFn");
        if (ok) {
            return this;
        }
        return Result.ok(recoverFn.apply(error));
    }

    public T orElseThrow(final Function<? super E, ? extends RuntimeException> exceptionFn) {
        Objects.requireNonNull(exceptionFn, "exceptionFn");
        if (ok) {
            return value;
        }
        throw exceptionFn.apply(error);
    }

    public T getValueUnsafe() {
        return value;
    }

    public E getErrorUnsafe() {
        return error;
    }
}
