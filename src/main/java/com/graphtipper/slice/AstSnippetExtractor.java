package com.graphtipper.slice;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

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
        CompilationUnit cu = entry.cu;
        Node callNode = locateCallNode(cu, callLine, callColumn, calleeSimpleName);
        if (callNode == null) {
            return fallback(entry.rawLines, callLine, callColumn, List.of("call_not_found"));
        }
        // Slicing logic in Tasks 5-8 will replace this stub.
        List<String> body = new ArrayList<>();
        body.add("(call located at line " + callLine + ")");
        return new SnippetAt("(stub)", callLine, callColumn, body, List.of(), false, List.of());
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

    private Node locateCallNode(CompilationUnit cu, int line, int column, String calleeSimpleName) {
        Node best = null;
        int bestColDelta = Integer.MAX_VALUE;
        for (MethodCallExpr m : cu.findAll(MethodCallExpr.class)) {
            if (!m.getName().asString().equals(calleeSimpleName)) continue;
            if (!m.getBegin().isPresent()) continue;
            int begLine = m.getBegin().get().line;
            int begCol = m.getBegin().get().column;
            if (begLine != line) continue;
            int delta = Math.abs(begCol - column);
            if (delta < bestColDelta) { best = m; bestColDelta = delta; }
        }
        for (ObjectCreationExpr o : cu.findAll(ObjectCreationExpr.class)) {
            if (!o.getType().getName().asString().equals(calleeSimpleName)) continue;
            if (!o.getBegin().isPresent()) continue;
            int begLine = o.getBegin().get().line;
            int begCol = o.getBegin().get().column;
            if (begLine != line) continue;
            int delta = Math.abs(begCol - column);
            if (delta < bestColDelta) { best = o; bestColDelta = delta; }
        }
        return best;
    }
}
