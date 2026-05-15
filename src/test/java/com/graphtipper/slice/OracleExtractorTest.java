package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OracleExtractorTest {

    @Test
    void oracle_variants_construct_correctly() {
        var eq = new Oracle.Equals("\"foo\"", "obj.bar()");
        var ex = new Oracle.Exception("IllegalArgumentException");
        var em = new Oracle.ExceptionMessage("IAE", Oracle.MatchKind.CONTAINS, "cannot");
        var bo = new Oracle.Boolean(true, "x > 0");
        var nu = new Oracle.Nullability(true, "result");
        var co = new Oracle.Contains("output", "expected substring");
        var no = new Oracle.None();
        assertThat(eq.expected()).isEqualTo("\"foo\"");
        assertThat(ex.type()).isEqualTo("IllegalArgumentException");
        assertThat(em.kind()).isEqualTo(Oracle.MatchKind.CONTAINS);
        assertThat(bo.expected()).isTrue();
        assertThat(nu.expectNonNull()).isTrue();
        assertThat(co.substring()).isEqualTo("expected substring");
        assertThat(no).isNotNull();
    }

    private java.nio.file.Path fixture(String name) {
        return java.nio.file.Paths.get("src/test/resources/oracle-fixtures", name);
    }

    @Test
    void extracts_assertEquals_with_literal_expected() {
        var ex = new OracleExtractor();
        var oracles = ex.extract(fixture("AssertEqualsTests.java"), "oraclefix.AssertEqualsTests.testReturnEquals");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Equals.class, e -> {
            assertThat(e.expected()).isEqualTo("42");
            assertThat(e.actualExpr()).isEqualTo("x");
        });
    }

    @Test
    void extracts_assertEquals_with_string_literal() {
        var ex = new OracleExtractor();
        var oracles = ex.extract(fixture("AssertEqualsTests.java"), "oraclefix.AssertEqualsTests.testStringEquals");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Equals.class, e ->
            assertThat(e.expected()).isEqualTo("\"hello\""));
    }

    @Test
    void extracts_assertThrows_with_class_literal() {
        var ex = new OracleExtractor();
        var oracles = ex.extract(fixture("AssertThrowsTests.java"), "oraclefix.AssertThrowsTests.testThrowsLambda");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Exception.class, e ->
            assertThat(e.type()).isEqualTo("IllegalArgumentException"));
    }

    @Test
    void extracts_try_catch_with_exact_message() {
        var ex = new OracleExtractor();
        var oracles = ex.extract(fixture("TryCatchTests.java"), "oraclefix.TryCatchTests.testTryCatchExactMessage");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.ExceptionMessage.class, e -> {
            assertThat(e.type()).isEqualTo("IllegalArgumentException");
            assertThat(e.kind()).isEqualTo(Oracle.MatchKind.EXACT);
            assertThat(e.message()).isEqualTo("neg value: -1");
        });
    }

    @Test
    void extracts_try_catch_with_contains_message() {
        var ex = new OracleExtractor();
        var oracles = ex.extract(fixture("TryCatchTests.java"), "oraclefix.TryCatchTests.testTryCatchContainsMessage");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.ExceptionMessage.class, e -> {
            assertThat(e.kind()).isEqualTo(Oracle.MatchKind.CONTAINS);
            assertThat(e.message()).isEqualTo("neg");
        });
    }

    @Test
    void extracts_assertTrue() {
        var oracles = new OracleExtractor().extract(
                fixture("HamcrestTests.java"), "oraclefix.HamcrestTests.testAssertTrue");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Boolean.class, b -> {
            assertThat(b.expected()).isTrue();
            assertThat(b.expr()).isEqualTo("value() > 0");
        });
    }

    @Test
    void extracts_assertFalse() {
        var oracles = new OracleExtractor().extract(
                fixture("HamcrestTests.java"), "oraclefix.HamcrestTests.testAssertFalse");
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Boolean.class, b ->
                assertThat(b.expected()).isFalse());
    }

    @Test
    void extracts_assertNull_and_assertNotNull() {
        var ex = new OracleExtractor();
        var nul = ex.extract(fixture("HamcrestTests.java"), "oraclefix.HamcrestTests.testAssertNull");
        var nnul = ex.extract(fixture("HamcrestTests.java"), "oraclefix.HamcrestTests.testAssertNotNull");
        assertThat(nul.get(0)).isInstanceOfSatisfying(Oracle.Nullability.class, n ->
                assertThat(n.expectNonNull()).isFalse());
        assertThat(nnul.get(0)).isInstanceOfSatisfying(Oracle.Nullability.class, n ->
                assertThat(n.expectNonNull()).isTrue());
    }

    @Test
    void extracts_assertThat_containsString() {
        var oracles = new OracleExtractor().extract(
                fixture("HamcrestTests.java"), "oraclefix.HamcrestTests.testAssertThatContains");
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Contains.class, c -> {
            assertThat(c.expr()).isEqualTo("text()");
            assertThat(c.substring()).isEqualTo("hello");
        });
    }
}
