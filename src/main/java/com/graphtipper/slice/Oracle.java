package com.graphtipper.slice;

/**
 * Sealed hierarchy of test-oracle kinds extracted by {@link OracleExtractor}.
 * Each variant captures the assertion's observable shape; precise extraction
 * patterns are in the spec §5.2.
 */
public sealed interface Oracle {

    enum MatchKind { EXACT, CONTAINS }

    /** {@code assertEquals(expected, actual)}. Both arguments rendered as source text. */
    record Equals(String expected, String actualExpr) implements Oracle {}

    /** {@code assertThrows(Type.class, lambda)} with no message check. */
    record Exception(String type) implements Oracle {}

    /** {@code assertThrows(Type.class, lambda)} plus a captured message check,
     *  OR a {@code try/catch} block with {@code assertEquals(..., e.getMessage())}. */
    record ExceptionMessage(String type, MatchKind kind, String message) implements Oracle {}

    /** {@code assertTrue(expr)} / {@code assertFalse(expr)}. */
    record Boolean(boolean expected, String expr) implements Oracle {}

    /** {@code assertNull(x)} / {@code assertNotNull(x)}. */
    record Nullability(boolean expectNonNull, String expr) implements Oracle {}

    /** {@code assertThat(expr, containsString(s))}. */
    record Contains(String expr, String substring) implements Oracle {}

    /** No assertion found in the test method body. */
    record None() implements Oracle {}
}
