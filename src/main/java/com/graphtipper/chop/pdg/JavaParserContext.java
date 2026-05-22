package com.graphtipper.chop.pdg;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class JavaParserContext {

    private final JavaParser parser;
    private final Path projectRoot;

    private JavaParserContext(JavaParser parser, Path projectRoot) {
        this.parser = parser;
        this.projectRoot = projectRoot;
    }

    public JavaParser parser() { return parser; }
    public Path projectRoot() { return projectRoot; }

    public static JavaParserContext forProject(Path projectRoot) {
        Objects.requireNonNull(projectRoot);
        CombinedTypeSolver ts = new CombinedTypeSolver();
        ts.add(new ReflectionTypeSolver());
        Path mainSrc = projectRoot.resolve("src/main/java");
        Path testSrc = projectRoot.resolve("src/test/java");
        if (Files.isDirectory(mainSrc)) ts.add(new JavaParserTypeSolver(mainSrc));
        if (Files.isDirectory(testSrc)) ts.add(new JavaParserTypeSolver(testSrc));
        ParserConfiguration config = new ParserConfiguration()
            .setSymbolResolver(new JavaSymbolSolver(ts))
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        return new JavaParserContext(new JavaParser(config), projectRoot);
    }
}
