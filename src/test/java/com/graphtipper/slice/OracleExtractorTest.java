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
}
