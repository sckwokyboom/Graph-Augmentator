package com.graphtipper.chop.pdg;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CdgConstructorTest {

    @Test
    void ifThenStatementsAreControlDependentOnPredicate() {
        CompilationUnit cu = StaticJavaParser.parse("""
            class C { int f(int x) { if (x > 0) { int y = x + 1; return y; } return 0; } }
            """);
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class).orElseThrow();
        MethodRef ref = new MethodRef("C", "f:int(int)");
        CfgConstructor.Result cfg = new CfgConstructor().build(md, ref);
        List<ChopEdge> cdg = new CdgConstructor().build(cfg);
        StatementNode ifNode = cfg.statements().stream()
            .filter(s -> s.kind() == StatementKind.IF).findFirst().orElseThrow();
        long fromIf = cdg.stream().filter(e -> e.src().equals(ifNode)
            && e.layer() == EdgeLayer.CDG).count();
        assertThat(fromIf).isGreaterThanOrEqualTo(2);
    }
}
