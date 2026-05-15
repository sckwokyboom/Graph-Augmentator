package com.graphtipper.slice;

import java.util.List;

/**
 * Whether the target call sits inside a try/catch in the consumer, and which types are caught.
 * Empty {@code caughtTypes} with {@code inTryCatch=false} means exceptions propagate as-is.
 */
public record ExceptionHandlingNearCall(boolean inTryCatch, List<String> caughtTypes) {
    public ExceptionHandlingNearCall {
        caughtTypes = List.copyOf(caughtTypes);
    }
    public static ExceptionHandlingNearCall none() {
        return new ExceptionHandlingNearCall(false, List.of());
    }
}
