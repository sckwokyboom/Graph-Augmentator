package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StaticSlicerTest {

    @Test
    void unresolvedReason_covers_all_documented_categories() {
        // Spec §5.1: 12 reason categories.
        assertThat(UnresolvedReason.values())
                .containsExactlyInAnyOrder(
                        UnresolvedReason.FIELD_READ,
                        UnresolvedReason.METHOD_CALL,
                        UnresolvedReason.REFLECTION,
                        UnresolvedReason.BRANCH_EXPLOSION,
                        UnresolvedReason.DEPTH_LIMIT,
                        UnresolvedReason.PARSE_ERROR,
                        UnresolvedReason.NOT_FOUND,
                        UnresolvedReason.ENTRY_POINT_REACHED,
                        UnresolvedReason.COMPLEX_EXPR,
                        UnresolvedReason.CYCLE,
                        UnresolvedReason.FILE_TOO_LARGE,
                        UnresolvedReason.UNSUPPORTED);
    }

    @Test
    void sliceResult_variants_construct_correctly() {
        var r = new SliceResult.Resolved("abc");
        assertThat(r.value()).isEqualTo("abc");

        var u = new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "this.x");
        assertThat(u.reason()).isEqualTo(UnresolvedReason.FIELD_READ);
        assertThat(u.detail()).isEqualTo("this.x");

        var d = new SliceResult.Derived(
                SliceResult.DerivedKind.ARRAY_LITERAL,
                java.util.List.of(new SliceResult.Resolved("a"), new SliceResult.Resolved("b")));
        assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.ARRAY_LITERAL);
        assertThat(d.parts()).hasSize(2);

        var lv = new SliceResult.LoopVar("i", "0..N-1");
        assertThat(lv.name()).isEqualTo("i");
        assertThat(lv.range()).isEqualTo("0..N-1");

        var pf = new SliceResult.ParamFromCaller(new SliceResult.Resolved("hi"));
        assertThat(pf.callerSlice()).isInstanceOf(SliceResult.Resolved.class);

        var bu = new SliceResult.BranchUnion(java.util.List.of(
                new SliceResult.Resolved("a"), new SliceResult.Resolved("b")));
        assertThat(bu.branches()).hasSize(2);
    }
}
