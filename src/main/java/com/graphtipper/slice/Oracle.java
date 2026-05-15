package com.graphtipper.slice;

/** Sealed interface for oracles extracted from test methods.
 *  Variants are added in Tasks 4–6. The {@link None} variant always exists. */
public sealed interface Oracle {
    record None() implements Oracle {}
}
