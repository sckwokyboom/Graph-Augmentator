package com.graphtipper.chop.pdg;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;
import com.graphtipper.chop.model.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CfgConstructor {

    public record Result(List<StatementNode> statements, List<ChopEdge> edges,
                         Map<StatementId, Statement> astByStatement) {}

    public Result build(MethodDeclaration md, MethodRef ref) {
        Map<StatementId, StatementNode> nodes = new LinkedHashMap<>();
        Map<StatementId, Statement> astMap = new LinkedHashMap<>();
        List<ChopEdge> edges = new ArrayList<>();
        if (md.getBody().isEmpty()) return new Result(List.of(), List.of(), Map.of());
        BlockStmt body = md.getBody().get();
        // Block statements are structural containers, not graph nodes — skip them
        // so that user statements remain as the unit of granularity.
        body.walk(Statement.class, s -> {
            if (s instanceof BlockStmt) return;
            StatementId id = new StatementId(ref, identityHash(s));
            StatementKind kind = classify(s);
            String text = oneLine(s.toString());
            SourceRange src = sourceRange(s, md);
            StatementNode sn = new StatementNode(id, ref, kind, text, src,
                new HashSet<>(), false, false);
            nodes.put(id, sn);
            astMap.put(id, s);
        });
        Cfg cfg = new Cfg(ref, nodes, edges);
        cfg.visit(body);
        return new Result(new ArrayList<>(nodes.values()), edges, astMap);
    }

    static int identityHash(Node n) {
        int rangeHash = n.getRange().map(r -> r.begin.line * 1000 + r.begin.column).orElse(0);
        return rangeHash * 31 + n.getClass().getSimpleName().hashCode();
    }

    static SourceRange sourceRange(Node s, MethodDeclaration md) {
        String file = md.findCompilationUnit()
            .flatMap(cu -> cu.getStorage().map(st -> st.getPath().toString()))
            .orElse("<unknown>");
        var r = s.getRange().orElse(null);
        if (r == null) return new SourceRange(file, 0, 0, 0, 0);
        return new SourceRange(file, r.begin.line, r.begin.column, r.end.line, r.end.column);
    }

    static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 117) + "..." : t;
    }

    static StatementKind classify(Statement s) {
        if (s instanceof IfStmt) return StatementKind.IF;
        if (s instanceof WhileStmt) return StatementKind.WHILE;
        if (s instanceof DoStmt) return StatementKind.DO;
        if (s instanceof ForStmt) return StatementKind.FOR;
        if (s instanceof ForEachStmt) return StatementKind.FOREACH;
        if (s instanceof ReturnStmt) return StatementKind.RETURN;
        if (s instanceof ThrowStmt) return StatementKind.THROW;
        if (s instanceof TryStmt) return StatementKind.TRY;
        if (s instanceof SwitchStmt) return StatementKind.SWITCH;
        if (s instanceof BlockStmt) return StatementKind.BLOCK;
        if (s instanceof AssertStmt) return StatementKind.ASSERT;
        if (s instanceof ExpressionStmt) return StatementKind.EXPR;
        return StatementKind.OTHER;
    }

    private static final class Cfg {
        final MethodRef ref;
        final Map<StatementId, StatementNode> nodes;
        final List<ChopEdge> edges;

        Cfg(MethodRef ref, Map<StatementId, StatementNode> nodes, List<ChopEdge> edges) {
            this.ref = ref;
            this.nodes = nodes;
            this.edges = edges;
        }

        List<StatementNode> visit(Statement s) {
            if (s instanceof BlockStmt b) return visitBlock(b);
            if (s instanceof IfStmt ifs) return visitIf(ifs);
            if (s instanceof WhileStmt w) return visitWhile(w);
            if (s instanceof ForStmt f) return visitFor(f);
            if (s instanceof ForEachStmt fe) return visitForEach(fe);
            if (s instanceof DoStmt d) return visitDo(d);
            if (s instanceof ReturnStmt || s instanceof ThrowStmt) {
                return List.of();
            }
            StatementNode n = nodeOf(s);
            return n == null ? List.of() : List.of(n);
        }

        private List<StatementNode> visitBlock(BlockStmt b) {
            List<StatementNode> prev = List.of();
            for (Statement s : b.getStatements()) {
                List<StatementNode> entry = entryPoints(s);
                connect(prev, entry);
                prev = visit(s);
            }
            return prev;
        }

        private List<StatementNode> visitIf(IfStmt ifs) {
            StatementNode predicate = nodeOf(ifs);
            if (predicate == null) return List.of();
            connect(List.of(predicate), entryPoints(ifs.getThenStmt()));
            List<StatementNode> thenExit = visit(ifs.getThenStmt());
            List<StatementNode> elseExit;
            if (ifs.getElseStmt().isPresent()) {
                Statement el = ifs.getElseStmt().get();
                connect(List.of(predicate), entryPoints(el));
                elseExit = visit(el);
            } else {
                elseExit = List.of(predicate);
            }
            List<StatementNode> joined = new ArrayList<>(thenExit);
            joined.addAll(elseExit);
            return joined;
        }

        private List<StatementNode> visitWhile(WhileStmt w) {
            StatementNode predicate = nodeOf(w);
            if (predicate == null) return List.of();
            connect(List.of(predicate), entryPoints(w.getBody()));
            List<StatementNode> bodyExit = visit(w.getBody());
            connect(bodyExit, List.of(predicate));
            return List.of(predicate);
        }

        private List<StatementNode> visitFor(ForStmt f) {
            StatementNode predicate = nodeOf(f);
            if (predicate == null) return List.of();
            connect(List.of(predicate), entryPoints(f.getBody()));
            List<StatementNode> bodyExit = visit(f.getBody());
            connect(bodyExit, List.of(predicate));
            return List.of(predicate);
        }

        private List<StatementNode> visitForEach(ForEachStmt fe) {
            StatementNode predicate = nodeOf(fe);
            if (predicate == null) return List.of();
            connect(List.of(predicate), entryPoints(fe.getBody()));
            List<StatementNode> bodyExit = visit(fe.getBody());
            connect(bodyExit, List.of(predicate));
            return List.of(predicate);
        }

        private List<StatementNode> visitDo(DoStmt d) {
            StatementNode predicate = nodeOf(d);
            if (predicate == null) return List.of();
            List<StatementNode> bodyExit = visit(d.getBody());
            connect(bodyExit, List.of(predicate));
            connect(List.of(predicate), entryPoints(d.getBody()));
            return List.of(predicate);
        }

        private StatementNode nodeOf(Statement s) {
            StatementId id = new StatementId(ref, identityHash(s));
            return nodes.get(id);
        }

        private List<StatementNode> entryPoints(Statement s) {
            if (s instanceof BlockStmt b && !b.getStatements().isEmpty()) {
                return entryPoints(b.getStatements().get(0));
            }
            StatementNode n = nodeOf(s);
            return n == null ? List.of() : List.of(n);
        }

        private void connect(List<StatementNode> from, List<StatementNode> to) {
            for (StatementNode a : from) {
                for (StatementNode b : to) {
                    edges.add(new ChopEdge(a, b, EdgeLayer.CFG, null, null, "", new HashSet<>()));
                }
            }
        }
    }
}
