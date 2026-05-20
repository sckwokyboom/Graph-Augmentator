package com.graphtipper.render;

import com.graphtipper.slice.ArgOrigin;
import com.graphtipper.slice.SliceResult;
import com.graphtipper.slice.UnresolvedReason;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ArgRendererTest {

    @Test
    void renders_literal_arg() {
        var origin = ArgOrigin.literal(0, "\"abc\"", "F.java", 10);
        assertThat(new ArgRenderer().render(origin)).isEqualTo("\"abc\"");
    }

    @Test
    void renders_method_call_arg() {
        var origin = ArgOrigin.methodCall(2, "Help.Ansi.OFF.text(\"abc\")");
        assertThat(new ArgRenderer().render(origin)).isEqualTo("Help.Ansi.OFF.text(\"abc\")");
    }

    @Test
    void renders_field_arg() {
        var origin = ArgOrigin.field(0, "picocli.Constants.EMPTY_TEXT");
        assertThat(new ArgRenderer().render(origin)).isEqualTo("Constants.EMPTY_TEXT");
    }

    @Test
    void renders_parameter_arg() {
        var origin = ArgOrigin.parameter(0, "row");
        assertThat(new ArgRenderer().render(origin)).isEqualTo("<param: row>");
    }

    @Test
    void renders_local_var_arg() {
        var origin = ArgOrigin.localVar(0, "rowIdx", "F.java", 42, "int rowIdx = 0;");
        assertThat(new ArgRenderer().render(origin)).isEqualTo("<local: rowIdx>");
    }

    @Test
    void renders_tuple() {
        var args = List.of(
            ArgOrigin.literal(0, "0", "F.java", 1),
            ArgOrigin.literal(1, "1", "F.java", 1),
            ArgOrigin.methodCall(2, "text(\"x\")"));
        assertThat(new ArgRenderer().renderTuple(args)).isEqualTo("(0, 1, text(\"x\"))");
    }

    @Test
    void renders_unknown_origin() {
        var origin = ArgOrigin.unknown(0);
        assertThat(new ArgRenderer().render(origin)).isEqualTo("<unknown>");
    }

    @Test
    void renders_resolved_string_literal_with_quotes() {
        var r = new SliceResult.Resolved("hello");
        assertThat(new ArgRenderer().renderSliceResult(r)).isEqualTo("\"hello\"");
    }

    @Test
    void renders_resolved_int_without_quotes() {
        var r = new SliceResult.Resolved(42);
        assertThat(new ArgRenderer().renderSliceResult(r)).isEqualTo("42");
    }

    @Test
    void renders_unresolved_with_reason_marker() {
        var r = new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "this.x");
        assertThat(new ArgRenderer().renderSliceResult(r)).isEqualTo("<UNRESOLVED: FIELD_READ>");
    }

    @Test
    void renders_loop_var_with_range() {
        var r = new SliceResult.LoopVar("i", "0..N-1");
        assertThat(new ArgRenderer().renderSliceResult(r)).isEqualTo("<loop i: 0..N-1>");
    }

    @Test
    void renders_branch_union_pipe_separated() {
        var r = new SliceResult.BranchUnion(java.util.List.of(
                new SliceResult.Resolved("a"), new SliceResult.Resolved("b")));
        assertThat(new ArgRenderer().renderSliceResult(r)).isEqualTo("(\"a\" | \"b\")");
    }
}
