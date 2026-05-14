package com.graphtipper.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

public final class SourceFragmentReader {
    private final Path projectRoot;
    private final Map<String, List<String>> cache = new HashMap<>();

    public SourceFragmentReader(Path projectRoot) { this.projectRoot = projectRoot; }

    private List<String> load(String relPath) {
        return cache.computeIfAbsent(relPath, p -> {
            try {
                return Files.readAllLines(projectRoot.resolve(p));
            } catch (IOException e) {
                throw new UncheckedIOException("read " + p, e);
            }
        });
    }

    public String readLines(String relPath, int startLine, int endLine) {
        var lines = load(relPath);
        int s = Math.max(1, startLine);
        int e = Math.min(lines.size(), endLine);
        if (s > e) return "";
        var sb = new StringBuilder();
        for (int i = s; i <= e; i++) {
            if (i > s) sb.append('\n');
            sb.append(lines.get(i - 1));
        }
        return sb.toString();
    }

    public String readAround(String relPath, int line, int before, int after) {
        return readLines(relPath, line - before, line + after);
    }

    /** Resolve a CPG-relative path against the project root for downstream consumers
     *  that need a filesystem Path (e.g. {@code AstSnippetExtractor.sliceAt}). */
    public Path resolveProject(String relPath) {
        return projectRoot.resolve(relPath);
    }
}
