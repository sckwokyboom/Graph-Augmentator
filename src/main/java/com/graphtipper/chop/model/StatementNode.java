package com.graphtipper.chop.model;

import java.util.Set;

public record StatementNode(
    StatementId id,
    MethodRef owner,
    StatementKind kind,
    String displayText,
    SourceRange src,
    Set<StatementId> touchedBy,
    boolean isTarget,
    boolean isEntryPoint
) implements ChopNode {
    @Override public boolean equals(Object o) {
        return o instanceof StatementNode other && id.equals(other.id);
    }
    @Override public int hashCode() { return id.hashCode(); }
}
