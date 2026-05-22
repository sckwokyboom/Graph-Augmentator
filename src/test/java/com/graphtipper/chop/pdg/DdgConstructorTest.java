package com.graphtipper.chop.pdg;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DdgConstructorTest {

    @Test
    void definitionFlowsToUse() {
        var cu = StaticJavaParser.parse("""
            class C { int f(int x) { int y = x + 1; return y; } }
            """);
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class).orElseThrow();
        MethodRef ref = new MethodRef("C", "f:int(int)");
        var cfg = new CfgConstructor().build(md, ref);
        var ee = new ExpressionExtractor().extract(md, ref, cfg);
        DdgConstructor.Result r = new DdgConstructor().build(md, ref, cfg, ee);
        long defUse = r.edges().stream()
            .filter(e -> e.layer() == EdgeLayer.DDG
                && e.dataKind() == DataKind.DEF_USE
                && e.label().contains("y"))
            .count();
        assertThat(defUse).isGreaterThanOrEqualTo(1);
    }
}
