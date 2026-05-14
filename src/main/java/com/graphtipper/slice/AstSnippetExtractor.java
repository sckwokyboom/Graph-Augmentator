package com.graphtipper.slice;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class AstSnippetExtractor {

    public record SnippetAt(
            String enclosingMethodSignature,
            int callLine,
            int callColumn,
            List<String> renderedBody,
            List<ArgOrigin> argOrigins,
            boolean truncated,
            List<String> warnings) {}

    private static final int CACHE_LIMIT = 256;
    private static final int MAX_LINES_PER_SNIPPET = 60;

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    private final LinkedHashMap<Path, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, CacheEntry> e) {
            return size() > CACHE_LIMIT;
        }
    };

    private record CacheEntry(CompilationUnit cu, List<String> rawLines, boolean parseOk) {}

    public SnippetAt sliceAt(Path file, int callLine, int callColumn,
                              String calleeSimpleName, int maxSliceStmts) {
        CacheEntry entry = load(file);
        if (entry == null) {
            return new SnippetAt("", callLine, callColumn, List.of("(file not found)"),
                    List.of(), false, List.of("file_not_found"));
        }
        if (!entry.parseOk) {
            return fallback(entry.rawLines, callLine, callColumn, List.of("parse_failed"));
        }
        // Slicing logic in Tasks 4-8 will replace this stub.
        return fallback(entry.rawLines, callLine, callColumn, List.of("not_implemented_yet"));
    }

    private SnippetAt fallback(List<String> rawLines, int callLine, int callColumn,
                                List<String> warnings) {
        int from = Math.max(1, callLine - 3);
        int to = Math.min(rawLines.size(), callLine + 2);
        List<String> body = new ArrayList<>();
        for (int i = from; i <= to; i++) body.add(rawLines.get(i - 1));
        return new SnippetAt("(fallback)", callLine, callColumn, body, List.of(), false, warnings);
    }

    private CacheEntry load(Path file) {
        Path key = file.toAbsolutePath().normalize();
        CacheEntry hit = cache.get(key);
        if (hit != null) return hit;
        if (!Files.exists(key)) return null;
        try {
            List<String> raw = Files.readAllLines(key);
            ParseResult<CompilationUnit> result = parser.parse(key);
            CompilationUnit cu = result.getResult().orElse(null);
            CacheEntry entry = new CacheEntry(cu, raw, cu != null && result.isSuccessful());
            cache.put(key, entry);
            return entry;
        } catch (IOException io) {
            return new CacheEntry(null, List.of("(io error: " + io.getMessage() + ")"),
                    false);
        }
    }
}
