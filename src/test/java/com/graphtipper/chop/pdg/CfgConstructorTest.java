package com.graphtipper.chop.pdg;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CfgConstructorTest {

    @Test
    void linearMethodHasSequentialCfgEdges() {
        CompilationUnit cu = StaticJavaParser.parse("""
            class C { void f() { int a = 1; int b = 2; int c = a + b; } }
            """);
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class).orElseThrow();
        MethodRef ref = new MethodRef("C", "f:void()");
        CfgConstructor.Result r = new CfgConstructor().build(md, ref);
        assertThat(r.statements()).hasSize(3);
        assertThat(r.edges()).hasSize(2);
        assertThat(r.edges()).allMatch(e -> e.layer() == EdgeLayer.CFG);
    }

    @Test
    void ifStatementProducesPredicateAndTwoBranches() {
        CompilationUnit cu = StaticJavaParser.parse("""
            class C { int f(int x) { if (x > 0) { return 1; } else { return -1; } } }
            """);
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class).orElseThrow();
        MethodRef ref = new MethodRef("C", "f:int(int)");
        CfgConstructor.Result r = new CfgConstructor().build(md, ref);
        assertThat(r.statements()).extracting(StatementNode::kind)
            .contains(StatementKind.IF, StatementKind.RETURN, StatementKind.RETURN);
        StatementNode ifNode = r.statements().stream()
            .filter(s -> s.kind() == StatementKind.IF).findFirst().orElseThrow();
        long outDeg = r.edges().stream().filter(e -> e.src().equals(ifNode)).count();
        assertThat(outDeg).isEqualTo(2);
    }
}
