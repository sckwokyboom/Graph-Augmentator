package com.graphtipper.render;

import com.graphtipper.slice.ArgOrigin;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders a single {@link ArgOrigin} to a compact string suitable for a matrix cell.
 * Mapping per spec §4.5 "Args at putValue":
 *   LITERAL          → as-is ({@code 42}, {@code "text"}, {@code null})
 *   METHOD_CALL      → expression text
 *   FACTORY_CALL     → expression text or short FQN
 *   PARAMETER        → {@code <param: name>}
 *   FIELD            → short FQN ({@code Class.FIELD})
 *   FIELD_ACCESS     → expression text
 *   INDEXED_ACCESS   → expression text
 *   CONSTRUCTOR      → expression text
 *   LOCAL_VAR        → {@code <local: name>}
 *   LOOP_VAR         → {@code <loop: name>}
 *   UNKNOWN          → {@code <unknown>}
 */
public final class ArgRenderer {

    public String render(ArgOrigin o) {
        return switch (o.kind()) {
            case LITERAL -> nullSafe(o.value());
            case METHOD_CALL, FIELD_ACCESS, INDEXED_ACCESS, CONSTRUCTOR ->
                    nullSafe(o.exprText() != null ? o.exprText() : o.value());
            case FACTORY_CALL -> nullSafe(o.factoryFqn() != null ? o.factoryFqn() : o.exprText());
            case FIELD -> shortFqn(o.fieldFqn());
            case PARAMETER -> "<param: " + o.paramName() + ">";
            case LOCAL_VAR -> "<local: " + o.paramName() + ">";
            case LOOP_VAR -> "<loop: " + o.paramName() + ">";
            case UNKNOWN -> "<unknown>";
        };
    }

    public String renderTuple(List<ArgOrigin> args) {
        return "(" + args.stream().map(this::render).collect(Collectors.joining(", ")) + ")";
    }

    /** Render a {@link com.graphtipper.slice.SliceResult} to its compact Markdown form. */
    public String renderSliceResult(com.graphtipper.slice.SliceResult r) {
        return switch (r) {
            case com.graphtipper.slice.SliceResult.Resolved res -> renderValue(res.value());
            case com.graphtipper.slice.SliceResult.Unresolved u -> "<UNRESOLVED: " + u.reason() + ">";
            case com.graphtipper.slice.SliceResult.LoopVar lv ->
                    "<loop " + lv.name() + (lv.range() != null ? ": " + lv.range() : "") + ">";
            case com.graphtipper.slice.SliceResult.BranchUnion bu -> {
                var parts = new java.util.ArrayList<String>();
                for (var b : bu.branches()) parts.add(renderSliceResult(b));
                yield "(" + String.join(" | ", parts) + ")";
            }
            case com.graphtipper.slice.SliceResult.ParamFromCaller pf ->
                    renderSliceResult(pf.callerSlice());
            case com.graphtipper.slice.SliceResult.Derived d ->
                    renderDerived(d);
        };
    }

    private String renderDerived(com.graphtipper.slice.SliceResult.Derived d) {
        var parts = new java.util.ArrayList<String>();
        for (var p : d.parts()) parts.add(renderSliceResult(p));
        return switch (d.kind()) {
            case ARRAY_LITERAL -> "{" + String.join(", ", parts) + "}";
            case OBJECT_CREATION -> "new(" + String.join(", ", parts) + ")";
            case ARRAY_ACCESS -> parts.get(0) + "[" + parts.get(1) + "]";
            case CONCATENATION -> String.join(" + ", parts);
            case BINARY_OP -> String.join(" op ", parts);
            case CAST -> parts.get(0);
        };
    }

    private static String renderValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + s + "\"";
        return String.valueOf(v);
    }

    private static String shortFqn(String fqn) {
        if (fqn == null) return "<unknown>";
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return fqn;
        int prevDot = fqn.lastIndexOf('.', lastDot - 1);
        return prevDot < 0 ? fqn : fqn.substring(prevDot + 1);
    }

    private static String nullSafe(String s) { return s == null ? "<unknown>" : s; }
}
