package com.graphtipper.chop.pdg;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class JavaParserContextTest {

    @Test
    void parsesAndResolvesLocalSymbols(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/java/p/C.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package p;
            public class C {
                int f(int x) { int y = x + 1; return y; }
            }
            """);
        JavaParserContext ctx = JavaParserContext.forProject(tmp);
        Optional<CompilationUnit> cu = ctx.parser().parse(src).getResult();
        assertThat(cu).isPresent();
        MethodDeclaration md = cu.get().findFirst(MethodDeclaration.class).orElseThrow();
        assertThat(md.getNameAsString()).isEqualTo("f");
    }
}
