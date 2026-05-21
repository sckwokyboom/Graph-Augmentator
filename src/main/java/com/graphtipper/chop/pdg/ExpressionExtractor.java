package com.graphtipper.chop.pdg;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.graphtipper.chop.model.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ExpressionExtractor {

    public record Result(
        List<ExprNode> expressions,
        List<ExprNode> parameters,
        List<ExprNode> returnValues,
        List<ChopEdge> astEdges,
        Map<Node, ExprNode> astToExpr
    ) {
        public ExprNode exprFor(Node n) { return astToExpr.get(n); }
    }

    public Result extract(MethodDeclaration md, MethodRef ref, CfgConstructor.Result cfg) {
        Map<Node, ExprNode> map = new IdentityHashMap<>();
        List<ExprNode> all = new ArrayList<>();
        List<ExprNode> params = new ArrayList<>();
        List<ExprNode> returns = new ArrayList<>();
        List<ChopEdge> ast = new ArrayList<>();

        StatementId synthStmt = new StatementId(ref, -1);
        for (Parameter p : md.getParameters()) {
            ExprNode pn = mkExpr(p, ref, synthStmt, ExpressionKind.PARAM,
                p.getNameAsString() + ":" + p.getTypeAsString(), md);
            params.add(pn); all.add(pn); map.put(p, pn);
        }
        for (StatementNode sn : cfg.statements()) {
            Statement astStmt = cfg.astByStatement().get(sn.id());
            if (astStmt == null) continue;
            astStmt.walk(Node.class, n -> {
                ExprNode created = null;
                if (n instanceof MethodCallExpr mc) {
                    created = mkExpr(mc, ref, sn.id(), ExpressionKind.CALLSITE,
                        callSig(mc), md);
                } else if (n instanceof VariableDeclarator vd) {
                    created = mkExpr(vd, ref, sn.id(), ExpressionKind.LOCAL_DEF,
                        vd.getNameAsString() + ":" + vd.getTypeAsString(), md);
                } else if (n instanceof LiteralExpr le) {
                    created = mkExpr(le, ref, sn.id(), ExpressionKind.LITERAL,
                        le.toString(), md);
                } else if (n instanceof FieldAccessExpr fa) {
                    created = mkExpr(fa, ref, sn.id(), ExpressionKind.FIELD_REF,
                        fa.toString(), md);
                }
                if (created != null) {
                    all.add(created); map.put(n, created);
                    ast.add(new ChopEdge(sn, created, EdgeLayer.AST, null, null, "",
                        new HashSet<>()));
                }
            });
            Expression predicate = predicateOf(astStmt);
            if (predicate != null) {
                ExprNode bp = mkExpr(predicate, ref, sn.id(), ExpressionKind.BRANCH_PREDICATE,
                    oneLine(predicate.toString()), md);
                all.add(bp); map.putIfAbsent(predicate, bp);
                ast.add(new ChopEdge(sn, bp, EdgeLayer.AST, null, null, "predicate", new HashSet<>()));
            }
            if (astStmt instanceof ReturnStmt rs && rs.getExpression().isPresent()) {
                Expression rex = rs.getExpression().get();
                ExprNode rv = mkExpr(rex, ref, sn.id(), ExpressionKind.RETURN_VALUE,
                    oneLine(rex.toString()), md);
                all.add(rv); returns.add(rv); map.put(rex, rv);
                ast.add(new ChopEdge(sn, rv, EdgeLayer.AST, null, null, "return", new HashSet<>()));
            }
        }
        return new Result(all, params, returns, ast, map);
    }

    private static Expression predicateOf(Statement s) {
        if (s instanceof IfStmt is) return is.getCondition();
        if (s instanceof WhileStmt w) return w.getCondition();
        if (s instanceof ForStmt f) return f.getCompare().orElse(null);
        if (s instanceof DoStmt d) return d.getCondition();
        if (s instanceof ForEachStmt fe) return fe.getIterable();
        return null;
    }

    private static ExprNode mkExpr(Node n, MethodRef ref, StatementId stmt,
                                   ExpressionKind kind, String text, MethodDeclaration md) {
        ExprId id = new ExprId(ref, CfgConstructor.identityHash(n));
        SourceRange src = CfgConstructor.sourceRange(n, md);
        return new ExprNode(id, ref, stmt, kind, text, src, new HashSet<>(), false, false);
    }

    private static String callSig(MethodCallExpr mc) {
        return mc.getNameAsString() + "(" + mc.getArguments().size() + ")";
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 80 ? t.substring(0, 77) + "..." : t;
    }
}
