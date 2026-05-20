package com.graphtipper.slice;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticSlicerIntegrationTest {

    private static MethodDeclaration findMethod(String fixture, String name) throws Exception {
        Path file = Paths.get("src/test/resources/slice-fixtures", fixture);
        var cu = StaticJavaParser.parse(file.toFile());
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(name))
                .findFirst().orElseThrow();
    }

    private static MethodCallExpr findCallTo(MethodDeclaration in, String name) {
        return in.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void literal_passthrough_resolves_to_literal() throws Exception {
        var caller = findMethod("LiteralPassthrough.java", "caller");
        var targetCall = findCallTo(caller, "target");
        var slicer = new StaticSlicer();
        var result = slicer.slice(targetCall.getArgument(0), caller, List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("hello"));
    }

    @Test
    void intra_proc_local_var_resolves_via_backward_slice() throws Exception {
        var caller = findMethod("IntraProcLocalVar.java", "caller");
        var targetCall = findCallTo(caller, "target");
        var slicer = new StaticSlicer();
        var result = slicer.slice(targetCall.getArgument(0), caller, List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("world"));
    }

    @Test
    void param_step_up_traces_through_call_chain() throws Exception {
        var top = findMethod("ParamStepUp.java", "top");
        var mid = findMethod("ParamStepUp.java", "mid");
        var targetCall = findCallTo(mid, "target");
        var slicer = new StaticSlicer();
        var result = slicer.slice(targetCall.getArgument(0), mid, List.of(top), 0);
        // Should walk: s (mid param) → "from-top" (top's actual arg to mid)
        assertThat(result).isInstanceOfSatisfying(SliceResult.ParamFromCaller.class, pf ->
                assertThat(pf.callerSlice()).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                        assertThat(r.value()).isEqualTo("from-top")));
    }

    @Test
    void array_init_and_access_resolves_first_element() throws Exception {
        var caller = findMethod("ArrayInitAndAccess.java", "caller");
        var targetCall = findCallTo(caller, "target");
        var slicer = new StaticSlicer();
        var result = slicer.slice(targetCall.getArgument(0), caller, List.of(), 0);
        // arr[0] → Derived(ARRAY_ACCESS) with arraySlice = Derived(ARRAY_LITERAL, parts:[Resolved("first"), Resolved("second")])
        assertThat(result).isInstanceOfSatisfying(SliceResult.Derived.class, d ->
                assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.ARRAY_ACCESS));
    }

    @Test
    void field_read_fails_with_field_read_reason() throws Exception {
        var caller = findMethod("FieldReadFails.java", "caller");
        var targetCall = findCallTo(caller, "target");
        var slicer = new StaticSlicer();
        var result = slicer.slice(targetCall.getArgument(0), caller, List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Unresolved.class, u ->
                assertThat(u.reason()).isEqualTo(UnresolvedReason.FIELD_READ));
    }
}
