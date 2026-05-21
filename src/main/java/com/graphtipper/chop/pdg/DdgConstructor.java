package com.graphtipper.chop.pdg;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.graphtipper.chop.model.ChopEdge;
import com.graphtipper.chop.model.DataKind;
import com.graphtipper.chop.model.EdgeLayer;
import com.graphtipper.chop.model.ExprNode;
import com.graphtipper.chop.model.MethodRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class DdgConstructor {

    public record Result(List<ChopEdge> edges) {}

    public Result build(MethodDeclaration md, MethodRef ref,
                        CfgConstructor.Result cfg, ExpressionExtractor.Result ee) {
        Map<String, ExprNode> latestDef = new HashMap<>();
        for (ExprNode pn : ee.parameters()) {
            String[] parts = pn.displayText().split(":");
            latestDef.put(parts[0].trim(), pn);
        }
        List<ChopEdge> edges = new ArrayList<>();
        md.walk(Node.class, n -> {
            if (n instanceof VariableDeclarator vd) {
                ExprNode def = ee.exprFor(vd);
                if (def != null) latestDef.put(vd.getNameAsString(), def);
            } else if (n instanceof NameExpr ne) {
                ExprNode def = latestDef.get(ne.getNameAsString());
                ExprNode use = ee.exprFor(ne);
                if (def != null && use != null && !def.equals(use)) {
                    edges.add(new ChopEdge(def, use, EdgeLayer.DDG, null, DataKind.DEF_USE,
                        ne.getNameAsString(), new HashSet<>()));
                }
            }
        });
        return new Result(edges);
    }
}
