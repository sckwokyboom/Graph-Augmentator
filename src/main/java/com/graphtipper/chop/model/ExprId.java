package com.graphtipper.chop.model;

import java.util.Objects;

public record ExprId(MethodRef owner, int astNodeId) {
    public ExprId { Objects.requireNonNull(owner, "owner"); }
}
