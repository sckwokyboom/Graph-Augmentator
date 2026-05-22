package com.graphtipper.chop.model;

import java.util.Set;

public sealed interface ChopNode permits StatementNode, ExprNode, MethodNode {
    MethodRef owner();
    Set<StatementId> touchedBy();
    boolean isTarget();
    boolean isEntryPoint();
}
