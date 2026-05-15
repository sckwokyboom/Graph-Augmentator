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
            switch (name) {
                case "assertEquals" -> handleAssertEquals(call, out);
                case "assertThrows" -> handleAssertThrows(call, out);
                default -> { /* not yet handled */ }
            }
        });
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

    /** Primary-oracle heuristic — implemented in Task 7. */
    public Oracle primaryFor(Path javaFile, String methodFqn, String targetFqn) {
        var all = extract(javaFile, methodFqn);
        return all.isEmpty() ? new Oracle.None() : all.get(0);
    }
}
