package com.graphtipper.chop.pdg;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionExtractorTest {

    @Test
    void extractsCallsiteAndPredicate() {
        var cu = StaticJavaParser.parse("""
            class C { int f(int x) { if (g(x) > 0) { return 1; } return 0; }
                      int g(int y) { return y; } }
            """);
        MethodDeclaration md = cu.getClassByName("C").orElseThrow()
            .getMethodsByName("f").get(0);
        MethodRef ref = new MethodRef("C", "f:int(int)");
        var cfg = new CfgConstructor().build(md, ref);
        ExpressionExtractor.Result r = new ExpressionExtractor().extract(md, ref, cfg);
        assertThat(r.expressions()).extracting(ExprNode::kind)
            .contains(ExpressionKind.CALLSITE, ExpressionKind.BRANCH_PREDICATE,
                      ExpressionKind.PARAM, ExpressionKind.RETURN_VALUE);
    }
}
