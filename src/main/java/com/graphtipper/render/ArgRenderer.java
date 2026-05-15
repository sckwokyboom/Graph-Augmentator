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

    private static String shortFqn(String fqn) {
        if (fqn == null) return "<unknown>";
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return fqn;
        int prevDot = fqn.lastIndexOf('.', lastDot - 1);
        return prevDot < 0 ? fqn : fqn.substring(prevDot + 1);
    }

    private static String nullSafe(String s) { return s == null ? "<unknown>" : s; }
}
