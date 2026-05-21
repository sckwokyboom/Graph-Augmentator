package com.graphtipper.chop.pdg;

import com.graphtipper.chop.model.ChopEdge;
import com.graphtipper.chop.model.ExprNode;
import com.graphtipper.chop.model.MethodNode;
import com.graphtipper.chop.model.MethodRef;
import com.graphtipper.chop.model.StatementId;
import com.graphtipper.chop.model.StatementNode;

import java.util.List;
import java.util.Map;

public record MethodPDG(
    MethodRef ref,
    MethodNode methodNode,
    List<StatementNode> statements,
    List<ExprNode> expressions,
    List<ChopEdge> intraEdges,
    List<ExprNode> parameters,
    List<ExprNode> returnValues,
    Map<StatementId, List<ExprNode>> bodyByStatement
) {}
