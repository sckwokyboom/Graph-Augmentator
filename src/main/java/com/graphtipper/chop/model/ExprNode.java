package com.graphtipper.chop.model;

import java.util.Set;

public record ExprNode(
    ExprId id,
    MethodRef owner,
    StatementId enclosingStatement,
    ExpressionKind kind,
    String displayText,
    SourceRange src,
    Set<StatementId> touchedBy,
    boolean isTarget,
    boolean isEntryPoint
) implements ChopNode {
    @Override public boolean equals(Object o) {
        return o instanceof ExprNode other && id.equals(other.id);
    }
    @Override public int hashCode() { return id.hashCode(); }
}
