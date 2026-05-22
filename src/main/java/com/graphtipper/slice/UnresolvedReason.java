package com.graphtipper.slice;

/**
 * Categories of why static slice analysis (Tier 2) could not resolve an expression
 * to a concrete value or derivation. Surfaced via {@link SliceResult.Unresolved} and
 * rendered as {@code <UNRESOLVED: reason>} markers in the Markdown artifact.
 *
 * <p>Spec §5.1.
 */
public enum UnresolvedReason {
    /** Read of {@code this.f} or {@code obj.f}; Tier 2 does not model heap state. */
    FIELD_READ,
    /** Non-trivial method call (not a whitelisted wrapper, not a constructor). */
    METHOD_CALL,
    /** {@code Method.invoke}, {@code Field.get}, {@code Class.forName}, etc. */
    REFLECTION,
    /** {@code BranchUnion} size exceeded {@code MAX_BRANCHES}. */
    BRANCH_EXPLOSION,
    /** Recursive {@code slice()} exceeded {@code MAX_DEPTH}. */
    DEPTH_LIMIT,
    /** Source file failed to parse via JavaParser. */
    PARSE_ERROR,
    /** Couldn't locate variable/parameter declaration. */
    NOT_FOUND,
    /** Recursed up callChain to test method body without finding source of var. */
    ENTRY_POINT_REACHED,
    /** Expression kind Tier 2 doesn't understand (lambda body, anonymous class, etc.). */
    COMPLEX_EXPR,
    /** Recursion through a method already visited in this slice path. */
    CYCLE,
    /** Source file exceeds {@code MAX_FILE_SIZE_FOR_SLICE_BYTES}. */
    FILE_TOO_LARGE,
    /** Fallback for unexpected AST shapes. */
    UNSUPPORTED
}
