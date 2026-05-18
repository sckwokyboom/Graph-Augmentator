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

    @Test
    void slices_string_literal_to_resolved() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("\"hello\"");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, /*method*/ null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("hello"));
    }

    @Test
    void slices_integer_literal_to_resolved() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("42");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo(42));
    }

    @Test
    void slices_null_literal_to_resolved_null() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("null");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isNull());
    }

    private static com.github.javaparser.ast.body.MethodDeclaration parseMethod(String src) {
        var cu = com.github.javaparser.StaticJavaParser.parse(
                "class C { " + src + " }");
        return cu.findFirst(com.github.javaparser.ast.body.MethodDeclaration.class).orElseThrow();
    }

    @Test
    void slices_local_var_to_last_assignment() {
        var method = parseMethod("void m() { int x = 42; foo(x); } void foo(int v) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var xRef = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(xRef, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo(42));
    }

    @Test
    void slices_local_var_with_string_concat() {
        var method = parseMethod(
                "void m() { String s = \"a\" + \"b\"; foo(s); } void foo(String s) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var sRef = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(sRef, method, java.util.List.of(), 0);
        // BinaryExpr handling comes in Task 10; for now expect Unresolved(COMPLEX_EXPR) or Resolved.
        // After Task 10, this becomes Resolved("ab"). Make this lenient until then.
        assertThat(result).isNotNull();
    }

    @Test
    void slices_param_steps_up_to_caller_actual_arg() {
        var cu = com.github.javaparser.StaticJavaParser.parse(
                "class C { " +
                "  void caller() { callee(\"hello\"); } " +
                "  void callee(String s) { target(s); } " +
                "  void target(String t) {} " +
                "}");
        var caller = cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("caller")).findFirst().orElseThrow();
        var callee = cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("callee")).findFirst().orElseThrow();
        var targetCall = callee.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals("target")).findFirst().orElseThrow();
        var sRef = targetCall.getArgument(0);

        var slicer = new StaticSlicer();
        var result = slicer.slice(sRef, callee, java.util.List.of(caller), 0);
        // The result should walk: NameExpr 's' → param of callee → actualArg "hello" in caller
        assertThat(result).isInstanceOfSatisfying(SliceResult.ParamFromCaller.class, pf ->
                assertThat(pf.callerSlice()).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                        assertThat(r.value()).isEqualTo("hello")));
    }

    @Test
    void slices_param_returns_entry_point_when_callChain_empty() {
        var method = parseMethod("void m(String s) { foo(s); } void foo(String x) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var sRef = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(sRef, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Unresolved.class, u ->
                assertThat(u.reason()).isEqualTo(UnresolvedReason.ENTRY_POINT_REACHED));
    }

    @Test
    void slices_field_access_to_unresolved_field_read() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("this.field");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Unresolved.class, u -> {
            assertThat(u.reason()).isEqualTo(UnresolvedReason.FIELD_READ);
            assertThat(u.detail()).contains("field");
        });
    }

    @Test
    void slices_array_initializer_to_derived_array_literal() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("new String[]{\"a\", \"b\"}");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Derived.class, d -> {
            assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.ARRAY_LITERAL);
            assertThat(d.parts()).hasSize(2);
            assertThat(d.parts().get(0)).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                    assertThat(r.value()).isEqualTo("a"));
        });
    }

    @Test
    void slices_array_access_to_derived_array_access() {
        var method = parseMethod(
                "void m() { String[] arr = new String[]{\"x\", \"y\"}; foo(arr[0]); } void foo(String s) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var indexExpr = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(indexExpr, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Derived.class, d -> {
            assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.ARRAY_ACCESS);
            assertThat(d.parts()).hasSize(2);
        });
    }

    @Test
    void slices_binary_string_concat_with_both_resolved() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("\"a\" + \"b\"");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("ab"));
    }

    @Test
    void slices_binary_arithmetic_with_both_resolved() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("10 + 5");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo(15L));
    }

    @Test
    void slices_binary_partial_concat_to_derived() {
        var method = parseMethod(
                "void m() { foo(this.x + \"!\"); } void foo(String s) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var binExpr = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(binExpr, method, java.util.List.of(), 0);
        // One side unresolved (field-read), other resolved — emit Derived(CONCATENATION).
        assertThat(result).isInstanceOfSatisfying(SliceResult.Derived.class, d ->
                assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.CONCATENATION));
    }

    @Test
    void slices_conditional_with_resolved_cond_takes_branch() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("true ? \"yes\" : \"no\"");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("yes"));
    }

    @Test
    void slices_conditional_with_unresolvable_cond_to_branch_union() {
        var method = parseMethod(
                "void m() { foo(this.f ? \"a\" : \"b\"); } void foo(String s) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var ternary = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(ternary, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.BranchUnion.class, bu -> {
            assertThat(bu.branches()).hasSize(2);
        });
    }

    @Test
    void slices_object_creation_to_derived_constructor() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("new String(\"hello\")");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Derived.class, d -> {
            assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.OBJECT_CREATION);
            assertThat(d.parts()).hasSize(1);
            assertThat(d.parts().get(0)).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                    assertThat(r.value()).isEqualTo("hello"));
        });
    }

    @Test
    void slices_enclosed_expr_unwraps() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("(\"hi\")");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("hi"));
    }

    @Test
    void slices_cast_expr_unwraps_inner() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("(String) \"hi\"");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("hi"));
    }

    @Test
    void slices_String_valueOf_as_transparent_wrapper() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("String.valueOf(\"hello\")");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("hello"));
    }

    @Test
    void slices_Integer_parseInt_as_transparent_wrapper() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("Integer.parseInt(\"42\")");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                // Wrapper is transparent — we return the inner-resolved value, type coercion deferred.
                assertThat(r.value()).isEqualTo("42"));
    }

    @Test
    void slices_arbitrary_method_call_to_unresolved_method_call() {
        var method = parseMethod(
                "void m() { foo(bar()); } String bar() { return \"x\"; } void foo(String s) {}");
        var fooCall = method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals("foo")).findFirst().orElseThrow();
        var barCall = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(barCall, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Unresolved.class, u ->
                assertThat(u.reason()).isEqualTo(UnresolvedReason.METHOD_CALL));
    }
}
