package com.graphtipper.slice;

import java.util.List;

/**
 * Outcome of attempting to resolve an expression statically (Tier 2).
 * Sealed: six variants cover all possible outcomes.
 *
 * <p>Spec §5.1.
 */
public sealed interface SliceResult {

    /** Kind tag for {@link Derived} variants. */
    enum DerivedKind { ARRAY_LITERAL, OBJECT_CREATION, ARRAY_ACCESS, BINARY_OP, CONCATENATION, CAST }

    /** Statically determined value (string, number, boolean, null, char, etc.). */
    record Resolved(Object value) implements SliceResult {}

    /** Could not resolve; carries a categorized reason and optional detail. */
    record Unresolved(UnresolvedReason reason, String detail) implements SliceResult {}

    /** Composite: e.g., an array literal whose elements have their own slice results. */
    record Derived(DerivedKind kind, List<SliceResult> parts) implements SliceResult {
        public Derived {
            parts = List.copyOf(parts);
        }
    }

    /** Loop variable in a for-loop; optional range when bounds are statically known. */
    record LoopVar(String name, String range) implements SliceResult {}

    /** Stepped up through a method boundary to the caller's actual argument. */
    record ParamFromCaller(SliceResult callerSlice) implements SliceResult {}

    /** Multiple possible values from conditional branches (size ≤ MAX_BRANCHES). */
    record BranchUnion(List<SliceResult> branches) implements SliceResult {
        public BranchUnion {
            branches = List.copyOf(branches);
        }
    }
}
