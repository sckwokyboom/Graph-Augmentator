package com.graphtipper.slice;

/**
 * Classifications of how a method's return value is used by its caller's body.
 * Detected by {@link ConsumerDeriver} via AST walk over the caller's method body
 * starting from the call site to the target.
 */
public enum UsageKind {
    ASSIGNED_TO_LOCAL,
    ASSIGNED_TO_FIELD,
    FIELD_READ,
    METHOD_CALL_ON_RESULT,
    USED_IN_CONDITION,
    USED_IN_LOOP,
    USED_IN_INDEX_EXPR,
    PASSED_AS_ARG,
    RETURNED_UNCHANGED,
    DISCARDED
}
