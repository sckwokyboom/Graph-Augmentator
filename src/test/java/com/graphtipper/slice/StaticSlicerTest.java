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

    @Test
    void argSlice_carries_position_name_type_and_result() {
        var slice = new ArgSlice(0, "row", "int",
                new SliceResult.Resolved("rowCount()-1"));
        assertThat(slice.argPosition()).isZero();
        assertThat(slice.argName()).isEqualTo("row");
        assertThat(slice.argType()).isEqualTo("int");
        assertThat(slice.result()).isInstanceOf(SliceResult.Resolved.class);
    }

    @Test
    void clusterSlice_carries_per_arg_common_prefixes() {
        var args = java.util.List.of(
                new ArgSlice(0, "row", "int", new SliceResult.Resolved("rowCount()-1")),
                new ArgSlice(1, "col", "int", new SliceResult.LoopVar("col", "0..N-1")),
                new ArgSlice(2, "value", "Text",
                        new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "commandSpec")));
        var cs = new ClusterSlice(args);
        assertThat(cs.args()).hasSize(3);
        assertThat(cs.args().get(0).argName()).isEqualTo("row");
    }

    @Test
    void sliceMemoCache_caches_and_retrieves() {
        var cache = new SliceMemoCache();
        var key = "M.foo:x:chain123";
        var result = new SliceResult.Resolved("hello");
        assertThat(cache.get(key)).isNull();
        cache.put(key, result);
        assertThat(cache.get(key)).isEqualTo(result);
        cache.clear();
        assertThat(cache.get(key)).isNull();
    }
}
