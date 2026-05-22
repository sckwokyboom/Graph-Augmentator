package com.graphtipper.chop.pdg;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.graphtipper.chop.model.*;
import com.graphtipper.model.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class PdgBuilder {

    private final JavaParserContext ctx;

    public PdgBuilder(JavaParserContext ctx) { this.ctx = ctx; }

    /** Convenience overload — isTargetMethod defaults to false (used by tests). */
    public MethodPDG build(Node.Method method) throws Exception {
        return build(method, false);
    }

    public MethodPDG build(Node.Method method, boolean isTargetMethod) throws Exception {
        Path file = ctx.projectRoot().resolve(method.file());
        CompilationUnit cu = ctx.parser().parse(file).getResult()
            .orElseThrow(() -> new IllegalStateException("Could not parse " + file));
        MethodDeclaration md = locate(cu, method);
        MethodRef ref = new MethodRef(method.fqn(), method.signature());

        CfgConstructor.Result cfg = new CfgConstructor().build(md, ref, isTargetMethod);
        ExpressionExtractor.Result ee = new ExpressionExtractor().extract(md, ref, cfg);
        List<ChopEdge> cdg = new CdgConstructor().build(cfg);
        DdgConstructor.Result ddg = new DdgConstructor().build(md, ref, cfg, ee);

        boolean isTest = method.isTest();
        MethodNode mn = new MethodNode(ref, isTest, isTargetMethod, new HashSet<>());
        List<ChopEdge> intra = new ArrayList<>();
        intra.addAll(cfg.edges());
        intra.addAll(cdg);
        intra.addAll(ddg.edges());
        intra.addAll(ee.astEdges());

        Map<StatementId, List<ExprNode>> bodyByStmt = ee.expressions().stream()
            .collect(Collectors.groupingBy(ExprNode::enclosingStatement));

        return new MethodPDG(ref, mn, cfg.statements(), ee.expressions(),
            intra, ee.parameters(), ee.returnValues(), bodyByStmt);
    }

    private static MethodDeclaration locate(CompilationUnit cu, Node.Method method) {
        String simpleName = method.fqn().substring(method.fqn().lastIndexOf('.') + 1);
        var byName = cu.findAll(MethodDeclaration.class).stream()
            .filter(md -> md.getNameAsString().equals(simpleName))
            .toList();
        if (byName.isEmpty()) {
            throw new IllegalStateException("Method not found: " + method.fqn());
        }
        if (byName.size() == 1) return byName.get(0);
        // Multiple overloads — prefer matching line number when Joern recorded it.
        if (method.lineStart() > 0) {
            for (MethodDeclaration md : byName) {
                var range = md.getRange().orElse(null);
                if (range != null && range.begin.line == method.lineStart()) return md;
            }
        }
        // Fallback: nearest by line distance.
        if (method.lineStart() > 0) {
            return byName.stream()
                .min((a, b) -> Integer.compare(
                    Math.abs(a.getRange().map(r -> r.begin.line).orElse(0) - method.lineStart()),
                    Math.abs(b.getRange().map(r -> r.begin.line).orElse(0) - method.lineStart())))
                .orElseThrow();
        }
        return byName.get(0);
    }
}
