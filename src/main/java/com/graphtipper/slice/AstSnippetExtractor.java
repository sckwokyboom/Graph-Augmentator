package com.graphtipper.slice;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
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
        CallableDeclaration<?> enclosing = findEnclosingMethod(callNode);
        if (enclosing == null) {
            List<String> warns = new ArrayList<>();
            warns.add(inInitializerBlock(callNode) ? "no_enclosing_method:initializer"
                                                   : "no_enclosing_method");
            return fallback(entry.rawLines, callLine, callColumn, warns);
        }
        String signature = signatureOf(enclosing);

        Set<String> seeds = new LinkedHashSet<>();
        List<ArgOrigin> argOrigins = classifyArguments(callNode, seeds);

        // Slice walk (Task 7) refines LOCAL_VAR provisional origins into PARAMETER/
        // LOOP_VAR/FIELD with definition lines, and selects supporting statements
        // for renderedBody (Task 8 polishes emission).
        return new SnippetAt(signature, callLine, callColumn,
                List.of(signature + " { /* not yet sliced */ }"),
                argOrigins, false, List.of());
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

    /**
     * Walk parents to find the closest enclosing method/constructor declaration.
     * Returns null if the call lives outside any callable (e.g. a field initializer
     * or static block); the caller handles that via {@link #inInitializerBlock}.
     */
    private CallableDeclaration<?> findEnclosingMethod(Node callNode) {
        Node n = callNode;
        while (n != null) {
            if (n instanceof MethodDeclaration md) return md;
            if (n instanceof ConstructorDeclaration cd) return cd;
            n = n.getParentNode().orElse(null);
        }
        return null;
    }

    /** Format the method signature as a single readable line (modifiers + return + name + params). */
    private String signatureOf(CallableDeclaration<?> decl) {
        return decl.getDeclarationAsString(true, false, true);
    }

    /** True if the call lives inside an instance/static initializer block. */
    private boolean inInitializerBlock(Node callNode) {
        Node n = callNode;
        while (n != null) {
            if (n instanceof InitializerDeclaration) return true;
            n = n.getParentNode().orElse(null);
        }
        return false;
    }

    private List<Expression> argumentsOf(Node callNode) {
        if (callNode instanceof MethodCallExpr m) return m.getArguments();
        if (callNode instanceof ObjectCreationExpr o) return o.getArguments();
        return new NodeList<>();
    }

    /**
     * Classify each argument by AST shape, and collect seed identifiers that the
     * backward slice (Task 7) will use to find definitions. NameExpr args start as
     * LOCAL_VAR provisional; Task 7 reclassifies to PARAMETER / LOOP_VAR if the
     * lookup finds the source in a parameter list or for-header.
     */
    private List<ArgOrigin> classifyArguments(Node callNode, Set<String> seedsOut) {
        List<Expression> args = argumentsOf(callNode);
        List<ArgOrigin> origins = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) origins.add(classifyOne(i, args.get(i), seedsOut));
        return origins;
    }

    private ArgOrigin classifyOne(int idx, Expression arg, Set<String> seedsOut) {
        if (arg instanceof NullLiteralExpr) return ArgOrigin.literal(idx, "null", null, -1);
        if (arg instanceof LiteralExpr lit) return ArgOrigin.literal(idx, lit.toString(), null, -1);
        if (arg instanceof NameExpr ne) {
            seedsOut.add(ne.getNameAsString());
            return ArgOrigin.localVar(idx, ne.getNameAsString(), null, -1, null);
        }
        if (arg instanceof FieldAccessExpr fa) {
            addLeftmostName(fa, seedsOut);
            return ArgOrigin.fieldAccess(idx, fa.toString());
        }
        if (arg instanceof ArrayAccessExpr aa) {
            addAllNames(aa, seedsOut);
            return ArgOrigin.indexedAccess(idx, aa.toString());
        }
        if (arg instanceof MethodCallExpr mc) {
            addAllNames(mc, seedsOut);
            return ArgOrigin.methodCall(idx, mc.toString());
        }
        if (arg instanceof ObjectCreationExpr oc) {
            addAllNames(oc, seedsOut);
            return ArgOrigin.constructor(idx, oc.toString());
        }
        // BinaryExpr, UnaryExpr, CastExpr, ConditionalExpr, etc.: harvest identifiers,
        // record as METHOD_CALL kind with the literal expression text.
        addAllNames(arg, seedsOut);
        return ArgOrigin.methodCall(idx, arg.toString());
    }

    private void addLeftmostName(Node n, Set<String> seedsOut) {
        Node cur = n;
        while (cur instanceof FieldAccessExpr fa) cur = fa.getScope();
        if (cur instanceof NameExpr ne) seedsOut.add(ne.getNameAsString());
    }

    private void addAllNames(Node n, Set<String> seedsOut) {
        for (NameExpr ne : n.findAll(NameExpr.class)) seedsOut.add(ne.getNameAsString());
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
