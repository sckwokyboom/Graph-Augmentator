package com.graphtipper.slice;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;

import java.util.List;

/**
 * Static back-slice analyzer (Tier 2). Resolves expressions to concrete values
 * where statically determinable; emits {@link SliceResult.Unresolved} with a precise
 * reason elsewhere. Spec §5.
 *
 * <p>Stateless aside from {@link SliceMemoCache} (per-cluster scope) — safe to construct
 * once per cluster enrichment. Not thread-safe within a single instance.
 */
public final class StaticSlicer {

    public static final int DEFAULT_MAX_DEPTH = 15;
    public static final int DEFAULT_MAX_BRANCHES = 3;

    private final int maxDepth;
    private final int maxBranches;
    private final SliceMemoCache cache = new SliceMemoCache();

    public StaticSlicer() { this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_BRANCHES); }
    public StaticSlicer(int maxDepth, int maxBranches) {
        this.maxDepth = maxDepth;
        this.maxBranches = maxBranches;
    }

    /**
     * Slice an expression to a {@link SliceResult}. Recursive; respects depth and branch caps.
     *
     * @param expr      AST expression to resolve
     * @param method    enclosing method (for backward slice context); may be null for synthetic calls
     * @param callChain stack of enclosing method calls from inner-most to outer-most
     * @param depth     current recursion depth
     */
    public SliceResult slice(Expression expr, MethodDeclaration method,
                              List<MethodDeclaration> callChain, int depth) {
        if (depth > maxDepth) {
            return new SliceResult.Unresolved(UnresolvedReason.DEPTH_LIMIT, "depth=" + depth);
        }
        if (expr instanceof StringLiteralExpr s) {
            return new SliceResult.Resolved(s.asString());
        }
        if (expr instanceof IntegerLiteralExpr i) {
            return new SliceResult.Resolved(i.asNumber());
        }
        if (expr instanceof LongLiteralExpr l) {
            return new SliceResult.Resolved(l.asNumber());
        }
        if (expr instanceof DoubleLiteralExpr d) {
            return new SliceResult.Resolved(d.asDouble());
        }
        if (expr instanceof BooleanLiteralExpr b) {
            return new SliceResult.Resolved(b.getValue());
        }
        if (expr instanceof CharLiteralExpr c) {
            return new SliceResult.Resolved(c.asChar());
        }
        if (expr instanceof NullLiteralExpr) {
            return new SliceResult.Resolved(null);
        }
        if (expr instanceof NameExpr name && method != null) {
            return intraProcBackwardSlice(name, method, callChain, depth);
        }
        // Tasks 6–14 expand this switch.
        return new SliceResult.Unresolved(UnresolvedReason.UNSUPPORTED,
                expr.getClass().getSimpleName());
    }

    private SliceResult intraProcBackwardSlice(NameExpr nameRef, MethodDeclaration method,
                                                List<MethodDeclaration> callChain, int depth) {
        String varName = nameRef.getNameAsString();
        // Find the last assignment to varName before nameRef's position in the same method body.
        var body = method.getBody().orElse(null);
        if (body == null) return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND,
                "no body for " + method.getNameAsString());

        var refPos = nameRef.getBegin().orElseThrow();

        // Walk all VariableDeclarator nodes and AssignExpr nodes that occur before refPos.
        Expression lastRhs = null;
        for (var vd : body.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            if (!vd.getNameAsString().equals(varName)) continue;
            var pos = vd.getBegin().orElse(null);
            if (pos == null || !pos.isBefore(refPos)) continue;
            if (vd.getInitializer().isPresent()) lastRhs = vd.getInitializer().get();
        }
        for (var ae : body.findAll(com.github.javaparser.ast.expr.AssignExpr.class)) {
            if (!(ae.getTarget() instanceof NameExpr ne) || !ne.getNameAsString().equals(varName)) continue;
            var pos = ae.getBegin().orElse(null);
            if (pos == null || !pos.isBefore(refPos)) continue;
            lastRhs = ae.getValue();
        }

        if (lastRhs != null) return slice(lastRhs, method, callChain, depth + 1);

        // Not found as local; check if it's a method parameter (Task 7 handles step-up).
        for (var p : method.getParameters()) {
            if (p.getNameAsString().equals(varName)) {
                return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND,
                        "parameter " + varName + " step-up not yet wired");
                // Will be replaced in Task 7.
            }
        }
        return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND, varName);
    }
}
