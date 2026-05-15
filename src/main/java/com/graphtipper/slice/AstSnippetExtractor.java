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
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

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
        // Also seed on the call's receiver (e.g. `layout` in `layout.layout(null, values)`)
        // — the LLM benefits from seeing where the receiver was constructed too. ArgOrigin
        // already covers explicit arguments; the receiver feeds the slice only.
        if (callNode instanceof MethodCallExpr mc) {
            mc.getScope().ifPresent(scope -> addAllNames(scope, seeds));
        }
        SliceResult sliced = backwardSlice(enclosing, callNode, argOrigins, seeds,
                maxSliceStmts, entry.rawLines);

        List<String> body = renderBody(signature, sliced.selected(), entry.rawLines);
        return new SnippetAt(signature, callLine, callColumn, body,
                sliced.refinedArgs(), sliced.truncated(), List.of());
    }

    private List<String> renderBody(String signature, LinkedHashSet<Statement> selected,
                                     List<String> rawLines) {
        List<String> body = new ArrayList<>();
        body.add(signature + " {");
        Statement prev = null;
        List<Statement> ordered = new ArrayList<>(selected);
        ordered.sort(Comparator.comparingInt(st -> st.getBegin().get().line));

        // Skip statements whose source range is fully covered by an already-emitted
        // statement on the same line(s). This avoids the "duplicate for-header" rendering
        // when a single-line `for (x : xs) { body(); }` selects BOTH the ForEachStmt
        // header and its inner ExpressionStmt — they map to the same source line.
        Set<Integer> emittedLines = new HashSet<>();

        for (Statement s : ordered) {
            int begLine = s.getBegin().get().line;
            int endLine = s.getEnd().get().line;
            if (begLine == endLine && emittedLines.contains(begLine)) continue;

            if (prev != null && begLine > prev.getEnd().get().line + 1) {
                body.add("    // ...");
            }
            String code;
            if (s instanceof IfStmt || s instanceof WhileStmt || s instanceof ForStmt
                    || s instanceof ForEachStmt || s instanceof TryStmt) {
                // Render only the header line for control structures — the slice walk has
                // already pulled in any inner statements that contribute to the call's data.
                code = rawLines.get(begLine - 1);
                if (!code.trim().endsWith("{")) code = code + " {";
            } else {
                code = String.join("\n", rawLines.subList(begLine - 1, endLine));
            }
            body.add(code);
            // For control structures we render only the header line, so only mark THAT
            // line — the inner statements live on subsequent lines and must still emit.
            if (s instanceof IfStmt || s instanceof WhileStmt || s instanceof ForStmt
                    || s instanceof ForEachStmt || s instanceof TryStmt) {
                emittedLines.add(begLine);
            } else {
                for (int ln = begLine; ln <= endLine; ln++) emittedLines.add(ln);
            }
            prev = s;
        }
        body.add("}");
        return body;
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

    private record SliceResult(LinkedHashSet<Statement> selected,
                                List<ArgOrigin> refinedArgs,
                                boolean truncated) {}

    /**
     * Walk the enclosing method's body in reverse from the call statement, collecting
     * statements that define values used by the call's arguments. Also refines provisional
     * LOCAL_VAR origins into PARAMETER or LOOP_VAR when the source is found.
     */
    private SliceResult backwardSlice(CallableDeclaration<?> enclosing,
                                       Node callNode,
                                       List<ArgOrigin> argOrigins,
                                       Set<String> initialSeeds,
                                       int maxSliceStmts,
                                       List<String> rawLines) {
        Map<String, Parameter> paramByName = new HashMap<>();
        for (Parameter p : enclosing.getParameters()) paramByName.put(p.getNameAsString(), p);

        List<ArgOrigin> refined = new ArrayList<>(argOrigins);
        Set<String> needed = new LinkedHashSet<>(initialSeeds);

        for (int i = 0; i < refined.size(); i++) {
            ArgOrigin o = refined.get(i);
            if (o.kind() == ArgOrigin.Kind.LOCAL_VAR && paramByName.containsKey(o.paramName())) {
                Parameter p = paramByName.get(o.paramName());
                refined.set(i, ArgOrigin.parameter(i, p.getNameAsString() + ":" + p.getType()));
                needed.remove(o.paramName());
            }
        }

        Statement callStmt = enclosingStatement(callNode);
        if (callStmt == null) return new SliceResult(new LinkedHashSet<>(), refined, false);

        BlockStmt body = bodyOf(enclosing).orElse(null);
        if (body == null) return new SliceResult(new LinkedHashSet<>(), refined, false);

        List<Statement> ordered = flattenStatements(body);
        int callIdx = ordered.indexOf(callStmt);
        if (callIdx < 0) return new SliceResult(new LinkedHashSet<>(), refined, false);

        LinkedHashSet<Statement> selected = new LinkedHashSet<>();
        selected.add(callStmt);
        boolean truncated = false;

        for (int i = callIdx - 1; i >= 0 && !needed.isEmpty(); i--) {
            if (selected.size() >= maxSliceStmts) { truncated = true; break; }
            Statement s = ordered.get(i);
            if (matchesAssignmentOf(s, needed)) {
                selected.add(s);
                String line = s.getBegin().isPresent() && rawLines.size() >= s.getBegin().get().line
                        ? rawLines.get(s.getBegin().get().line - 1).trim() : "";
                refineLocalVarOrigins(refined, s, line);
                Set<String> newSeeds = identifiersInRhs(s);
                needed.addAll(newSeeds);
                needed.removeAll(definedBy(s));
            }
            if (matchesLoopHeader(s, needed)) {
                selected.add(s);
                String line = s.getBegin().isPresent() && rawLines.size() >= s.getBegin().get().line
                        ? rawLines.get(s.getBegin().get().line - 1).trim() : "";
                refineLoopVarOrigins(refined, s, line);
                needed.removeAll(definedBy(s));
            }
        }
        if (!needed.isEmpty() && selected.size() >= maxSliceStmts) truncated = true;

        // Capture headers of enclosing control structures (if/while/for/try) so the
        // rendered snippet shows the code path that leads to the call.
        // ALSO capture catch/finally bodies of enclosing try blocks — for test methods
        // these contain the assert*(...) calls that specify exception behavior.
        Node ctxNode = callStmt;
        while (ctxNode != null) {
            ctxNode = ctxNode.getParentNode().orElse(null);
            if (ctxNode == null || ctxNode == body) break;
            if (ctxNode instanceof IfStmt || ctxNode instanceof WhileStmt
                    || ctxNode instanceof ForStmt || ctxNode instanceof ForEachStmt) {
                selected.add((Statement) ctxNode);
            }
            if (ctxNode instanceof TryStmt ts) {
                selected.add(ts);
                // Pull catch bodies in full — that's where assertEquals(...) usually lives.
                for (var cc : ts.getCatchClauses()) {
                    for (Statement cs : cc.getBody().getStatements()) selected.add(cs);
                }
                ts.getFinallyBlock().ifPresent(fb -> {
                    for (Statement fs : fb.getStatements()) selected.add(fs);
                });
            }
        }

        // Forward slice from the call statement to end of method:
        //   - Statements that use names produced by the call (e.g. the LHS variable).
        //   - Bare assertion/verification calls (assertX, expectX, verifyX, fail, assume*).
        // This is what turns "we showed the call" into "we showed the expected behavior",
        // which is the single biggest piece of context an LLM needs to satisfy tests.
        Set<String> produced = new LinkedHashSet<>(definedBy(callStmt));
        boolean passedCall = false;
        for (Statement s : ordered) {
            if (s == callStmt) { passedCall = true; continue; }
            if (!passedCall) continue;
            if (selected.contains(s)) continue;
            Set<String> usedNames = new LinkedHashSet<>();
            addAllNames(s, usedNames);
            boolean usesProduced = !produced.isEmpty() && usedNames.stream().anyMatch(produced::contains);
            if (usesProduced || isAssertionLike(s)) {
                selected.add(s);
                produced.addAll(definedBy(s));
            }
        }

        return new SliceResult(selected, refined, truncated);
    }

    /** Heuristic: a statement is "assertion-like" if its top-level expression is a method
     *  call whose name starts with assert / expect / verify / fail / assume. Covers JUnit,
     *  AssertJ, Hamcrest assertions, and TestNG patterns. */
    private boolean isAssertionLike(Statement s) {
        if (!(s instanceof ExpressionStmt es)) return false;
        Expression e = es.getExpression();
        if (!(e instanceof MethodCallExpr mc)) return false;
        String name = mc.getName().asString();
        return name.startsWith("assert") || name.startsWith("expect")
                || name.startsWith("verify") || name.startsWith("assume")
                || name.equals("fail") || name.equals("failBecauseExceptionWasNotThrown");
    }

    private Statement enclosingStatement(Node callNode) {
        Node n = callNode;
        while (n != null && !(n instanceof Statement)) n = n.getParentNode().orElse(null);
        return (Statement) n;
    }

    private List<Statement> flattenStatements(BlockStmt body) {
        List<Statement> out = new ArrayList<>();
        for (Statement s : body.getStatements()) collectStatements(s, out);
        return out;
    }

    private void collectStatements(Statement s, List<Statement> out) {
        out.add(s);
        if (s instanceof BlockStmt b) for (Statement c : b.getStatements()) collectStatements(c, out);
        else if (s instanceof IfStmt i) {
            collectStatements(i.getThenStmt(), out);
            i.getElseStmt().ifPresent(e -> collectStatements(e, out));
        } else if (s instanceof WhileStmt w) collectStatements(w.getBody(), out);
        else if (s instanceof ForStmt f) collectStatements(f.getBody(), out);
        else if (s instanceof ForEachStmt fe) collectStatements(fe.getBody(), out);
        else if (s instanceof TryStmt t) {
            collectStatements(t.getTryBlock(), out);
            t.getCatchClauses().forEach(c -> collectStatements(c.getBody(), out));
            t.getFinallyBlock().ifPresent(b -> collectStatements(b, out));
        }
    }

    private boolean matchesAssignmentOf(Statement s, Set<String> needed) {
        if (s instanceof ExpressionStmt es) {
            Expression e = es.getExpression();
            if (e instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator v : vde.getVariables()) {
                    if (needed.contains(v.getNameAsString())) return true;
                }
            }
            if (e instanceof AssignExpr ae && ae.getTarget() instanceof NameExpr ne) {
                if (needed.contains(ne.getNameAsString())) return true;
            }
        }
        return false;
    }

    private boolean matchesLoopHeader(Statement s, Set<String> needed) {
        if (s instanceof ForStmt f) {
            for (Expression e : f.getInitialization()) {
                if (e instanceof VariableDeclarationExpr vde) {
                    for (VariableDeclarator v : vde.getVariables()) {
                        if (needed.contains(v.getNameAsString())) return true;
                    }
                }
            }
        }
        if (s instanceof ForEachStmt fe
                && needed.contains(fe.getVariable().getVariable(0).getNameAsString())) return true;
        return false;
    }

    private Set<String> definedBy(Statement s) {
        Set<String> out = new LinkedHashSet<>();
        if (s instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr vde) {
            for (VariableDeclarator v : vde.getVariables()) out.add(v.getNameAsString());
        }
        if (s instanceof ExpressionStmt es && es.getExpression() instanceof AssignExpr ae
                && ae.getTarget() instanceof NameExpr ne) out.add(ne.getNameAsString());
        if (s instanceof ForStmt f) {
            for (Expression e : f.getInitialization()) {
                if (e instanceof VariableDeclarationExpr vde) {
                    for (VariableDeclarator v : vde.getVariables()) out.add(v.getNameAsString());
                }
            }
        }
        if (s instanceof ForEachStmt fe) out.add(fe.getVariable().getVariable(0).getNameAsString());
        return out;
    }

    private Set<String> identifiersInRhs(Statement s) {
        Set<String> out = new LinkedHashSet<>();
        if (s instanceof ExpressionStmt es) {
            Expression e = es.getExpression();
            if (e instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator v : vde.getVariables()) {
                    v.getInitializer().ifPresent(init -> addAllNames(init, out));
                }
            }
            if (e instanceof AssignExpr ae) addAllNames(ae.getValue(), out);
        }
        return out;
    }

    private void refineLocalVarOrigins(List<ArgOrigin> refined, Statement defStmt, String snippetLine) {
        Set<String> defined = definedBy(defStmt);
        int defLine = defStmt.getBegin().get().line;
        for (int i = 0; i < refined.size(); i++) {
            ArgOrigin o = refined.get(i);
            if (o.kind() == ArgOrigin.Kind.LOCAL_VAR && defined.contains(o.paramName())
                    && o.definedAtLine() <= 0) {
                refined.set(i, ArgOrigin.localVar(i, o.paramName(), null, defLine, snippetLine));
            }
        }
    }

    private void refineLoopVarOrigins(List<ArgOrigin> refined, Statement loopStmt, String snippetLine) {
        Set<String> defined = definedBy(loopStmt);
        int defLine = loopStmt.getBegin().get().line;
        for (int i = 0; i < refined.size(); i++) {
            ArgOrigin o = refined.get(i);
            if (o.kind() == ArgOrigin.Kind.LOCAL_VAR && defined.contains(o.paramName())) {
                refined.set(i, ArgOrigin.loopVar(i, o.paramName(), null, defLine, snippetLine));
            }
        }
    }

    /** CallableDeclaration supertype lacks a uniform getBody(); this adapter handles both. */
    private static Optional<BlockStmt> bodyOf(CallableDeclaration<?> decl) {
        if (decl instanceof MethodDeclaration md) return md.getBody();
        if (decl instanceof ConstructorDeclaration cd) return Optional.of(cd.getBody());
        return Optional.empty();
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

    /**
     * Slice a consumer's method body for artifact §4.4 rendering.
     * If the body has ≤ 30 statements, returns the full body (with signature line).
     * Otherwise, returns the signature line plus the block enclosing the first call
     * to {@code targetSimpleName} plus sibling return/break/throw statements.
     *
     * @return the slice, or null if the method or the call site is not found.
     */
    public String sliceConsumerBody(Path file, String methodFqn, String targetSimpleName) {
        CacheEntry entry = load(file);
        if (entry == null || !entry.parseOk) return null;
        CompilationUnit cu = entry.cu;

        Optional<MethodDeclaration> methodOpt = findMethodByFqn(cu, methodFqn);
        if (methodOpt.isEmpty()) return null;
        MethodDeclaration md = methodOpt.get();
        if (md.getBody().isEmpty()) return null;
        BlockStmt body = md.getBody().get();

        // Count statements (recursive count of Statement nodes within the body).
        long stmtCount = body.findAll(Statement.class).size();
        String signatureLine = md.getDeclarationAsString(false, false, false);

        if (stmtCount <= 30) {
            return signatureLine + " " + body.toString();
        }

        // Find the first call to targetSimpleName.
        Optional<MethodCallExpr> callOpt = body.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(targetSimpleName))
                .findFirst();
        if (callOpt.isEmpty()) return null;

        // Walk up from the call to the nearest enclosing BlockStmt.
        MethodCallExpr callExpr = callOpt.get();
        Optional<BlockStmt> blockOpt = callExpr.findAncestor(BlockStmt.class);
        if (blockOpt.isEmpty()) {
            return signatureLine + " { /* call: " + targetSimpleName + " */ }";
        }

        BlockStmt enclosingBlock = blockOpt.get();

        // Find the statement in the block that contains the call.
        Statement callStmt = null;
        for (Statement stmt : enclosingBlock.getStatements()) {
            if (stmt.findAll(MethodCallExpr.class).stream()
                    .anyMatch(c -> c == callExpr)) {
                callStmt = stmt;
                break;
            }
        }
        if (callStmt == null) {
            return signatureLine + " " + enclosingBlock.toString();
        }

        // Build minimal block: the call statement + sibling return/break/throw statements.
        LinkedHashSet<Statement> selected = new LinkedHashSet<>();
        selected.add(callStmt);

        for (Statement sibling : enclosingBlock.getStatements()) {
            if (isReturnBreakThrow(sibling)) {
                selected.add(sibling);
            }
        }

        // Serialize the selected statements as a block.
        StringBuilder result = new StringBuilder(signatureLine).append(" {");
        for (Statement s : selected) {
            result.append("\n        ").append(s.toString());
        }
        result.append("\n    }");
        return result.toString();
    }

    private boolean isReturnBreakThrow(Statement s) {
        return s.isReturnStmt() || s.isBreakStmt() || s.isThrowStmt();
    }

    /**
     * Look up a method declaration by fully qualified name within a CompilationUnit.
     * The FQN format is "package.ClassName.methodName" or "package.OuterClass.InnerClass.methodName".
     */
    private Optional<MethodDeclaration> findMethodByFqn(CompilationUnit cu, String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return Optional.empty();
        String methodName = fqn.substring(lastDot + 1);
        String enclosingFqn = fqn.substring(0, lastDot);
        String simpleClass = enclosingFqn.substring(
                Math.max(enclosingFqn.lastIndexOf('.'), enclosingFqn.lastIndexOf('$')) + 1);
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .filter(m -> m.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                        .map(t -> t.getNameAsString().equals(simpleClass)).orElse(false))
                .findFirst();
    }
}
