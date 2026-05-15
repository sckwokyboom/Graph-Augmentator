package com.graphtipper.slice;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Walks a test method's AST and extracts the oracle(s) it asserts.
 * Detection coverage in v1 (narrow whitelist): assertEquals, assertThrows.
 * Task 6 adds try/catch + assertEquals(msg). Task 7 adds assertNull/assertTrue/assertThat.
 */
public final class OracleExtractor {

    public List<Oracle> extract(Path javaFile, String methodFqn) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(javaFile);
        } catch (Exception e) {
            return List.of(new Oracle.None());
        }
        Optional<MethodDeclaration> mdo = findMethod(cu, methodFqn);
        if (mdo.isEmpty()) return List.of(new Oracle.None());
        MethodDeclaration md = mdo.get();
        List<Oracle> out = new ArrayList<>();
        md.findAll(MethodCallExpr.class).forEach(call -> {
            String name = call.getNameAsString();
            // Skip assertEquals inside try/catch catch clauses (let try/catch handler process them)
            if (name.equals("assertEquals") && isInsideCatchClause(call)) {
                return;
            }
            // Skip boolean assertions inside try/catch catch clauses (let try/catch handler process them)
            if ((name.equals("assertTrue") || name.equals("assertFalse")) && isInsideCatchClause(call)) {
                return;
            }
            switch (name) {
                case "assertEquals" -> handleAssertEquals(call, out);
                case "assertThrows" -> handleAssertThrows(call, out);
                case "assertTrue" -> handleAssertBoolean(call, true, out);
                case "assertFalse" -> handleAssertBoolean(call, false, out);
                case "assertNull" -> handleAssertNullability(call, false, out);
                case "assertNotNull" -> handleAssertNullability(call, true, out);
                case "assertThat" -> handleAssertThat(call, out);
                default -> { /* not yet handled */ }
            }
        });
        // try/catch oracle extraction (run after MethodCallExpr scan to allow upgrade):
        md.findAll(TryStmt.class).forEach(t -> handleTryCatch(t, out));
        return out.isEmpty() ? List.of(new Oracle.None()) : out;
    }

    private void handleAssertEquals(MethodCallExpr call, List<Oracle> out) {
        var args = call.getArguments();
        if (args.size() < 2) return;
        // JUnit5: (expected, actual). Older style: (actual, expected). We can't distinguish
        // without semantic info; choose the literal-side as `expected` if exactly one arg is a literal.
        String a0 = args.get(0).toString();
        String a1 = args.get(1).toString();
        boolean lit0 = isLikelyLiteral(args.get(0));
        boolean lit1 = isLikelyLiteral(args.get(1));
        String expected, actual;
        if (lit0 && !lit1) { expected = a0; actual = a1; }
        else if (lit1 && !lit0) { expected = a1; actual = a0; }
        else { expected = a0; actual = a1; }  // JUnit5 default order
        out.add(new Oracle.Equals(expected, actual));
    }

    private void handleAssertThrows(MethodCallExpr call, List<Oracle> out) {
        var args = call.getArguments();
        if (args.isEmpty()) return;
        Expression first = args.get(0);
        if (first instanceof ClassExpr ce) {
            out.add(new Oracle.Exception(simpleName(ce.getType().asString())));
        }
    }

    private static boolean isLikelyLiteral(Expression e) {
        return e instanceof LiteralExpr
                || e instanceof UnaryExpr u && u.getExpression() instanceof LiteralExpr;
    }

    private void handleTryCatch(TryStmt tryStmt, List<Oracle> out) {
        for (CatchClause cc : tryStmt.getCatchClauses()) {
            String typeName = simpleName(cc.getParameter().getType().asString());
            String varName = cc.getParameter().getNameAsString();
            // Search body for assertEquals(<literal>, e.getMessage()) or assertTrue(e.getMessage().contains(<literal>))
            boolean found = false;
            for (MethodCallExpr call : cc.getBody().findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                var args = call.getArguments();
                if (name.equals("assertEquals") && args.size() >= 2) {
                    // (expected, actual) — actual should be e.getMessage()
                    if (isGetMessageOn(args.get(1), varName) && args.get(0) instanceof StringLiteralExpr s) {
                        out.add(new Oracle.ExceptionMessage(typeName, Oracle.MatchKind.EXACT, s.asString()));
                        found = true;
                        break;
                    }
                    if (isGetMessageOn(args.get(0), varName) && args.get(1) instanceof StringLiteralExpr s) {
                        out.add(new Oracle.ExceptionMessage(typeName, Oracle.MatchKind.EXACT, s.asString()));
                        found = true;
                        break;
                    }
                }
                if (name.equals("assertTrue") && !args.isEmpty()) {
                    // assertTrue(e.getMessage().contains("..."))
                    if (args.get(0) instanceof MethodCallExpr inner
                            && inner.getNameAsString().equals("contains")
                            && inner.getScope().isPresent()
                            && isGetMessageOn(inner.getScope().get(), varName)
                            && !inner.getArguments().isEmpty()
                            && inner.getArgument(0) instanceof StringLiteralExpr s) {
                        out.add(new Oracle.ExceptionMessage(typeName, Oracle.MatchKind.CONTAINS, s.asString()));
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                out.add(new Oracle.Exception(typeName));
            }
        }
    }

    private static boolean isGetMessageOn(Expression expr, String varName) {
        return expr instanceof MethodCallExpr mc
                && mc.getNameAsString().equals("getMessage")
                && mc.getScope().filter(s -> s.toString().equals(varName)).isPresent();
    }

    private static boolean isInsideCatchClause(MethodCallExpr call) {
        return call.findAncestor(CatchClause.class).isPresent();
    }

    private void handleAssertBoolean(MethodCallExpr call, boolean expected, List<Oracle> out) {
        if (call.getArguments().isEmpty()) return;
        out.add(new Oracle.Boolean(expected, call.getArgument(0).toString()));
    }

    private void handleAssertNullability(MethodCallExpr call, boolean expectNonNull, List<Oracle> out) {
        if (call.getArguments().isEmpty()) return;
        out.add(new Oracle.Nullability(expectNonNull, call.getArgument(0).toString()));
    }

    private void handleAssertThat(MethodCallExpr call, List<Oracle> out) {
        var args = call.getArguments();
        if (args.size() < 2) return;
        String actualExpr = args.get(0).toString();
        Expression matcher = args.get(1);
        // containsString("...") pattern
        if (matcher instanceof MethodCallExpr mc
                && mc.getNameAsString().equals("containsString")
                && !mc.getArguments().isEmpty()
                && mc.getArgument(0) instanceof StringLiteralExpr s) {
            out.add(new Oracle.Contains(actualExpr, s.asString()));
        }
        // equalTo("...") / equalTo(literal) — emit as Equals
        else if (matcher instanceof MethodCallExpr mc
                && mc.getNameAsString().equals("equalTo")
                && !mc.getArguments().isEmpty()) {
            out.add(new Oracle.Equals(mc.getArgument(0).toString(), actualExpr));
        }
    }

    private static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    private Optional<MethodDeclaration> findMethod(CompilationUnit cu, String fqn) {
        // FQN format: "pkg.Class.method" or "pkg.Class$Inner.method". Match on simple method name
        // and (best-effort) the simple class name from the FQN.
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return Optional.empty();
        String methodName = fqn.substring(lastDot + 1);
        String enclosingFqn = fqn.substring(0, lastDot);
        String simpleClass = enclosingFqn.substring(
                Math.max(enclosingFqn.lastIndexOf('.'), enclosingFqn.lastIndexOf('$')) + 1);
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .filter(m -> m.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                        .map(t -> t.getNameAsString().equals(simpleClass))
                        .orElse(false))
                .findFirst();
    }

    /**
     * Choose the oracle most semantically related to the target call.
     * V1 heuristic: priority order
     *   ExceptionMessage > Exception > Equals > Contains > Boolean > Nullability > None.
     * (Data-flow-based heuristic deferred to v2.)
     */
    public Oracle primaryFor(Path javaFile, String methodFqn, String targetFqn) {
        var all = extract(javaFile, methodFqn);
        if (all.isEmpty()) return new Oracle.None();
        return all.stream().min((a, b) -> Integer.compare(priority(a), priority(b))).orElse(all.get(0));
    }

    private static int priority(Oracle o) {
        return switch (o) {
            case Oracle.ExceptionMessage __ -> 0;
            case Oracle.Exception __ -> 1;
            case Oracle.Equals __ -> 2;
            case Oracle.Contains __ -> 3;
            case Oracle.Boolean __ -> 4;
            case Oracle.Nullability __ -> 5;
            case Oracle.None __ -> 6;
        };
    }
}
