package com.graphtipper.chop.model;

import java.util.Objects;

public record StatementId(MethodRef owner, int astNodeId) {
    public StatementId { Objects.requireNonNull(owner, "owner"); }
}
