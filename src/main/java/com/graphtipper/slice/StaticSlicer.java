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

    private static final java.util.Set<String> TRANSPARENT_WRAPPERS = java.util.Set.of(
            "String.valueOf",
            "Integer.parseInt",
            "Long.parseLong",
            "Double.parseDouble",
            "Boolean.parseBoolean"
    );

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
        if (expr instanceof FieldAccessExpr fae) {
            return new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, fae.toString());
        }
        if (expr instanceof ArrayInitializerExpr aie) {
            List<SliceResult> partResults = new java.util.ArrayList<>();
            for (var v : aie.getValues()) partResults.add(slice(v, method, callChain, depth + 1));
            return new SliceResult.Derived(SliceResult.DerivedKind.ARRAY_LITERAL, partResults);
        }
        if (expr instanceof ArrayCreationExpr ace) {
            // new T[]{a, b, c}
            if (ace.getInitializer().isPresent()) {
                return slice(ace.getInitializer().get(), method, callChain, depth + 1);
            }
            return new SliceResult.Unresolved(UnresolvedReason.UNSUPPORTED,
                    "array creation without initializer");
        }
        if (expr instanceof ArrayAccessExpr aae) {
            SliceResult arraySlice = slice(aae.getName(), method, callChain, depth + 1);
            SliceResult idxSlice = slice(aae.getIndex(), method, callChain, depth + 1);
            return new SliceResult.Derived(SliceResult.DerivedKind.ARRAY_ACCESS,
                    java.util.List.of(arraySlice, idxSlice));
        }
        if (expr instanceof BinaryExpr be) {
            return handleBinary(be, method, callChain, depth);
        }
        if (expr instanceof ConditionalExpr ce) {
            return handleConditional(ce, method, callChain, depth);
        }
        if (expr instanceof EnclosedExpr ee) {
            return slice(ee.getInner(), method, callChain, depth + 1);
        }
        if (expr instanceof CastExpr cae) {
            return slice(cae.getExpression(), method, callChain, depth + 1);
        }
        if (expr instanceof ObjectCreationExpr oce) {
            List<SliceResult> partResults = new java.util.ArrayList<>();
            for (var arg : oce.getArguments()) partResults.add(slice(arg, method, callChain, depth + 1));
            return new SliceResult.Derived(SliceResult.DerivedKind.OBJECT_CREATION, partResults);
        }
        if (expr instanceof MethodCallExpr mce) {
            return handleMethodCall(mce, method, callChain, depth);
        }
        // Tasks 6–14 expand this switch.
        return new SliceResult.Unresolved(UnresolvedReason.UNSUPPORTED,
                expr.getClass().getSimpleName());
    }

    /**
     * Slice all target args for every member of a cluster.
     *
     * @param targetCallsPerMember per-member {@link MethodCallExpr} pointing to the target's call site
     *                             inside the immediate consumer
     * @param callChainsPerMember  per-member list of methods from test to immediate consumer's caller
     *                             (exclusive of the immediate consumer itself, which is passed separately)
     * @param immediateConsumer    the consumer method containing the target call(s)
     * @param targetParamNames     formal parameter names of the target (for naming arg slices)
     * @param targetParamTypes     formal parameter type strings
     * @return per-member list of arg slices (size N args)
     */
    public List<List<ArgSlice>> sliceCluster(
            List<MethodCallExpr> targetCallsPerMember,
            List<List<MethodDeclaration>> callChainsPerMember,
            MethodDeclaration immediateConsumer,
            List<String> targetParamNames,
            List<String> targetParamTypes) {

        if (targetCallsPerMember.size() != callChainsPerMember.size()) {
            throw new IllegalArgumentException(
                    "calls (" + targetCallsPerMember.size() + ") != chains (" + callChainsPerMember.size() + ")");
        }
        cache.clear();
        List<List<ArgSlice>> out = new java.util.ArrayList<>();
        for (int i = 0; i < targetCallsPerMember.size(); i++) {
            MethodCallExpr call = targetCallsPerMember.get(i);
            List<MethodDeclaration> chain = callChainsPerMember.get(i);
            List<ArgSlice> argSlices = new java.util.ArrayList<>();
            for (int a = 0; a < call.getArguments().size(); a++) {
                String name = a < targetParamNames.size() ? targetParamNames.get(a) : "arg" + a;
                String type = a < targetParamTypes.size() ? targetParamTypes.get(a) : "?";
                SliceResult r = slice(call.getArgument(a), immediateConsumer, chain, 0);
                argSlices.add(new ArgSlice(a, name, type, r));
            }
            out.add(argSlices);
        }
        return out;
    }

    /**
     * Aggregate per-member arg slices into a per-cluster ClusterSlice. For each arg position,
     * if all members share the same {@link SliceResult}, use it directly. If they differ,
     * collapse the distinct results into a {@link SliceResult.BranchUnion} (capped at
     * {@code maxBranches}; above → {@code Unresolved(BRANCH_EXPLOSION)}).
     */
    public ClusterSlice aggregateCluster(List<List<ArgSlice>> perMemberArgs) {
        if (perMemberArgs.isEmpty() || perMemberArgs.get(0).isEmpty()) {
            return ClusterSlice.empty();
        }
        int numArgs = perMemberArgs.get(0).size();
        List<ArgSlice> aggregated = new java.util.ArrayList<>();
        for (int a = 0; a < numArgs; a++) {
            String name = perMemberArgs.get(0).get(a).argName();
            String type = perMemberArgs.get(0).get(a).argType();
            java.util.LinkedHashSet<SliceResult> distinct = new java.util.LinkedHashSet<>();
            for (var member : perMemberArgs) {
                if (a < member.size()) distinct.add(member.get(a).result());
            }
            SliceResult result;
            if (distinct.size() == 1) {
                result = distinct.iterator().next();
            } else if (distinct.size() > maxBranches) {
                result = new SliceResult.Unresolved(UnresolvedReason.BRANCH_EXPLOSION,
                        distinct.size() + " distinct member resolutions");
            } else {
                result = new SliceResult.BranchUnion(java.util.List.copyOf(distinct));
            }
            aggregated.add(new ArgSlice(a, name, type, result));
        }
        return new ClusterSlice(aggregated);
    }

    private SliceResult intraProcBackwardSlice(NameExpr nameRef, MethodDeclaration method,
                                                List<MethodDeclaration> callChain, int depth) {
        String varName = nameRef.getNameAsString();
        // Find the last assignment to varName before nameRef's position in the same method body.
        var body = method.getBody().orElse(null);
        if (body == null) return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND,
                "no body for " + method.getNameAsString());

        var refPos = nameRef.getBegin().orElseThrow();

        // If nameRef sits inside a for-stmt that declares varName in its init, treat as LoopVar.
        // Done before the local-var walk because the for-init's VariableDeclarator would otherwise
        // be picked up as a normal assignment and resolved to the init value.
        for (var fs : body.findAll(com.github.javaparser.ast.stmt.ForStmt.class)) {
            boolean fsContainsRef = fs.getRange()
                    .flatMap(fr -> nameRef.getRange().map(fr::contains))
                    .orElse(false);
            if (!fsContainsRef) continue;
            for (var initExpr : fs.getInitialization()) {
                if (initExpr instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr vde) {
                    for (var v : vde.getVariables()) {
                        if (v.getNameAsString().equals(varName)) {
                            return new SliceResult.LoopVar(varName, describeForLoopRange(fs, varName));
                        }
                    }
                }
            }
        }

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
        for (int i = 0; i < method.getParameters().size(); i++) {
            if (method.getParameter(i).getNameAsString().equals(varName)) {
                return stepUpToCaller(i, method, callChain, depth);
            }
        }
        return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND, varName);
    }

    private static String describeForLoopRange(com.github.javaparser.ast.stmt.ForStmt fs, String varName) {
        String init = "?";
        String bound = "?";
        for (var initExpr : fs.getInitialization()) {
            if (initExpr instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr vde) {
                for (var v : vde.getVariables()) {
                    if (v.getNameAsString().equals(varName) && v.getInitializer().isPresent()) {
                        init = v.getInitializer().get().toString();
                    }
                }
            }
        }
        if (fs.getCompare().isPresent()) bound = fs.getCompare().get().toString();
        return init + " < ... " + bound;
    }

    private SliceResult stepUpToCaller(int paramIdx, MethodDeclaration calleeMethod,
                                        List<MethodDeclaration> callChain, int depth) {
        if (callChain.isEmpty()) {
            return new SliceResult.Unresolved(UnresolvedReason.ENTRY_POINT_REACHED,
                    "param " + calleeMethod.getParameter(paramIdx).getNameAsString());
        }
        MethodDeclaration caller = callChain.get(callChain.size() - 1);
        List<MethodDeclaration> rest = callChain.subList(0, callChain.size() - 1);

        // Locate the call expression in caller.body that calls calleeMethod.
        String calleeName = calleeMethod.getNameAsString();
        var callOpt = caller.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(calleeName))
                .findFirst();
        if (callOpt.isEmpty()) {
            return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND,
                    "no call to " + calleeName + " in " + caller.getNameAsString());
        }
        var call = callOpt.get();
        if (paramIdx >= call.getArguments().size()) {
            return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND,
                    "param index " + paramIdx + " out of range at call site");
        }
        Expression actualArg = call.getArgument(paramIdx);
        SliceResult callerSlice = slice(actualArg, caller, rest, depth + 1);
        return new SliceResult.ParamFromCaller(callerSlice);
    }

    private SliceResult handleBinary(BinaryExpr be, MethodDeclaration method,
                                      List<MethodDeclaration> callChain, int depth) {
        SliceResult left = slice(be.getLeft(), method, callChain, depth + 1);
        SliceResult right = slice(be.getRight(), method, callChain, depth + 1);
        BinaryExpr.Operator op = be.getOperator();

        if (left instanceof SliceResult.Resolved lv && right instanceof SliceResult.Resolved rv) {
            Object computed = compute(lv.value(), op, rv.value());
            if (computed != null) return new SliceResult.Resolved(computed);
        }
        // Mixed or non-computable: emit Derived(CONCATENATION) so the renderer can show partial info.
        SliceResult.DerivedKind kind = op == BinaryExpr.Operator.PLUS
                ? SliceResult.DerivedKind.CONCATENATION
                : SliceResult.DerivedKind.BINARY_OP;
        return new SliceResult.Derived(kind, java.util.List.of(left, right));
    }

    private static Object compute(Object l, BinaryExpr.Operator op, Object r) {
        // String concatenation: "+" with at least one String operand.
        if (op == BinaryExpr.Operator.PLUS && (l instanceof String || r instanceof String)) {
            return String.valueOf(l) + String.valueOf(r);
        }
        if (l instanceof Number ln && r instanceof Number rn) {
            long lv = ln.longValue();
            long rv = rn.longValue();
            return switch (op) {
                case PLUS -> lv + rv;
                case MINUS -> lv - rv;
                case MULTIPLY -> lv * rv;
                case DIVIDE -> rv != 0 ? lv / rv : null;
                case REMAINDER -> rv != 0 ? lv % rv : null;
                default -> null;
            };
        }
        return null;
    }

    private SliceResult handleConditional(ConditionalExpr ce, MethodDeclaration method,
                                           List<MethodDeclaration> callChain, int depth) {
        SliceResult cond = slice(ce.getCondition(), method, callChain, depth + 1);
        if (cond instanceof SliceResult.Resolved r && r.value() instanceof Boolean b) {
            Expression chosen = b ? ce.getThenExpr() : ce.getElseExpr();
            return slice(chosen, method, callChain, depth + 1);
        }
        SliceResult thenS = slice(ce.getThenExpr(), method, callChain, depth + 1);
        SliceResult elseS = slice(ce.getElseExpr(), method, callChain, depth + 1);
        List<SliceResult> branches = new java.util.ArrayList<>();
        addBranches(thenS, branches);
        addBranches(elseS, branches);
        if (branches.size() > maxBranches) {
            return new SliceResult.Unresolved(UnresolvedReason.BRANCH_EXPLOSION,
                    branches.size() + " branches");
        }
        return new SliceResult.BranchUnion(branches);
    }

    private static void addBranches(SliceResult r, List<SliceResult> acc) {
        if (r instanceof SliceResult.BranchUnion bu) acc.addAll(bu.branches());
        else acc.add(r);
    }

    private SliceResult handleMethodCall(MethodCallExpr mce, MethodDeclaration method,
                                           List<MethodDeclaration> callChain, int depth) {
        String qual = mce.getScope().map(Object::toString).orElse("");
        String name = mce.getNameAsString();
        String full = qual.isEmpty() ? name : qual + "." + name;

        if (TRANSPARENT_WRAPPERS.contains(full) && mce.getArguments().size() == 1) {
            return slice(mce.getArgument(0), method, callChain, depth + 1);
        }

        // Reflection sentinels.
        if (full.endsWith(".invoke") || full.endsWith(".forName")
                || full.equals("Field.get") || full.equals("Field.set")) {
            return new SliceResult.Unresolved(UnresolvedReason.REFLECTION, full);
        }

        return new SliceResult.Unresolved(UnresolvedReason.METHOD_CALL, full + "(...)");
    }
}
