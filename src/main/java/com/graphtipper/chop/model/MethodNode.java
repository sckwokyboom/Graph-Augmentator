package com.graphtipper.chop.model;

import java.util.Set;

public record MethodNode(
    MethodRef owner,
    boolean isTest,
    boolean isTarget,
    Set<StatementId> touchedBy
) implements ChopNode {
    @Override public boolean isEntryPoint() { return isTest; }
    @Override public boolean equals(Object o) {
        return o instanceof MethodNode other && owner.equals(other.owner);
    }
    @Override public int hashCode() { return owner.hashCode(); }
}
