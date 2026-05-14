package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ArgOriginTest {

    @Test
    void localVarOriginCarriesDefinitionSiteAndSnippet() {
        var o = ArgOrigin.localVar(1, "values", "src/test/java/X.java", 17,
                "final Text[][] values = textArray;");
        assertThat(o.kind()).isEqualTo(ArgOrigin.Kind.LOCAL_VAR);
        assertThat(o.paramName()).isEqualTo("values");
        assertThat(o.definedAtLine()).isEqualTo(17);
        assertThat(o.definedAtSnippet()).isEqualTo("final Text[][] values = textArray;");
    }

    @Test
    void loopVarOriginCarriesForHeader() {
        var o = ArgOrigin.loopVar(0, "col", "src/main/java/CL.java", 17378,
                "for (int col = 0; col < values.length; col++)");
        assertThat(o.kind()).isEqualTo(ArgOrigin.Kind.LOOP_VAR);
        assertThat(o.definedAtLine()).isEqualTo(17378);
    }

    @Test
    void indexedAccessOriginCarriesExprText() {
        var o = ArgOrigin.indexedAccess(2, "values[col]");
        assertThat(o.kind()).isEqualTo(ArgOrigin.Kind.INDEXED_ACCESS);
        assertThat(o.exprText()).isEqualTo("values[col]");
    }

    @Test
    void literalOriginUnchanged() {
        var o = ArgOrigin.literal(0, "null", "src/test/java/X.java", 1992);
        assertThat(o.kind()).isEqualTo(ArgOrigin.Kind.LITERAL);
        assertThat(o.value()).isEqualTo("null");
        assertThat(o.line()).isEqualTo(1992);
    }
}
