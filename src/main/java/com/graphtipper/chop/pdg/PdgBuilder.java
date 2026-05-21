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

    public MethodPDG build(Node.Method method) throws Exception {
        Path file = ctx.projectRoot().resolve(method.file());
        CompilationUnit cu = ctx.parser().parse(file).getResult()
            .orElseThrow(() -> new IllegalStateException("Could not parse " + file));
        MethodDeclaration md = locate(cu, method);
        MethodRef ref = new MethodRef(method.fqn(), method.signature());

        CfgConstructor.Result cfg = new CfgConstructor().build(md, ref);
        ExpressionExtractor.Result ee = new ExpressionExtractor().extract(md, ref, cfg);
        List<ChopEdge> cdg = new CdgConstructor().build(cfg);
        DdgConstructor.Result ddg = new DdgConstructor().build(md, ref, cfg, ee);

        boolean isTest = method.isTest();
        MethodNode mn = new MethodNode(ref, isTest, false, new HashSet<>());
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
        return cu.findAll(MethodDeclaration.class).stream()
            .filter(md -> md.getNameAsString().equals(simpleName))
            .filter(md -> md.getParameters().size() == method.paramTypes().size())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Method not found: " + method.fqn()));
    }
}
