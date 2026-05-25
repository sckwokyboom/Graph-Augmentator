package com.graphtipper.slice;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Derives {@link ConsumerContract}s from clusters by analyzing each consumer's body
 * around its call(s) to the target. Stateless; constructed with an {@link AstSnippetExtractor}
 * used for body slicing.
 */
public final class ConsumerDeriver {

    private final AstSnippetExtractor snippetExtractor;

    public ConsumerDeriver(AstSnippetExtractor snippetExtractor) {
        this.snippetExtractor = snippetExtractor;
    }

    /** Source-file resolver: maps a consumer FQN to the .java file that defines it. */
    @FunctionalInterface
    public interface FileResolver {
        java.nio.file.Path resolve(String consumerFqn);
    }

    /** Walk the consumer's method body, classify how target's return value is used. */
    public ReturnValueUsage classifyReturnValueUsage(Path file, String consumerFqn, String targetSimpleName) {
        Optional<MethodDeclaration> mdOpt = findMethod(file, consumerFqn, targetSimpleName);
        if (mdOpt.isEmpty()) return ReturnValueUsage.empty();
        MethodDeclaration md = mdOpt.get();

        EnumSet<UsageKind> kinds = EnumSet.noneOf(UsageKind.class);
        List<String> fieldsRead = new ArrayList<>();

        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals(targetSimpleName)) continue;
            classifySingleCall(call, kinds, fieldsRead, md);
        }
        return new ReturnValueUsage(kinds.isEmpty() ? EnumSet.noneOf(UsageKind.class) : kinds, fieldsRead);
    }

    private void classifySingleCall(MethodCallExpr call, EnumSet<UsageKind> kinds,
                                     List<String> fieldsRead, MethodDeclaration enclosing) {
        Node parent = call.getParentNode().orElse(null);
        if (parent == null) {
            kinds.add(UsageKind.DISCARDED);
            return;
        }

        // VariableDeclarator: `Cell c = target(...)`
        if (parent instanceof VariableDeclarator vd) {
            kinds.add(UsageKind.ASSIGNED_TO_LOCAL);
            String varName = vd.getNameAsString();
            scanUsesOfLocal(enclosing, varName, kinds, fieldsRead);
            return;
        }

        // AssignExpr: `this.field = target(...)` or `local = target(...)`
        if (parent instanceof AssignExpr ae && ae.getValue() == call) {
            kinds.add(UsageKind.ASSIGNED_TO_FIELD);
            return;
        }

        // ReturnStmt: `return target(...)`
        if (parent instanceof ReturnStmt) {
            kinds.add(UsageKind.RETURNED_UNCHANGED);
            return;
        }

        // ExpressionStmt where the call IS the expression: `target(...);` discarded
        if (parent instanceof ExpressionStmt es && es.getExpression() == call) {
            kinds.add(UsageKind.DISCARDED);
            return;
        }

        // MethodCallExpr where target is an argument: passed_as_arg
        if (parent instanceof MethodCallExpr) {
            kinds.add(UsageKind.PASSED_AS_ARG);
            return;
        }

        // FieldAccessExpr where call is the scope: target(...).field
        if (parent instanceof FieldAccessExpr fae && fae.getScope() == call) {
            kinds.add(UsageKind.FIELD_READ);
            fieldsRead.add(fae.getNameAsString());
            return;
        }

        // IfStmt / WhileStmt condition or its descendants
        if (call.findAncestor(IfStmt.class).filter(s -> isWithinCondition(call, s.getCondition())).isPresent()
                || call.findAncestor(WhileStmt.class).filter(s -> isWithinCondition(call, s.getCondition())).isPresent()) {
            kinds.add(UsageKind.USED_IN_CONDITION);
        }
        if (call.findAncestor(ForStmt.class).isPresent()
                || call.findAncestor(ForEachStmt.class).isPresent()) {
            kinds.add(UsageKind.USED_IN_LOOP);
        }
    }

    private static boolean isWithinCondition(Node call, Node condition) {
        Node cur = call;
        while (cur != null) {
            if (cur == condition) return true;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    /** After we know the call's return goes into local `varName`, scan rest of the method for uses. */
    private void scanUsesOfLocal(MethodDeclaration md, String varName,
                                  EnumSet<UsageKind> kinds, List<String> fieldsRead) {
        for (NameExpr n : md.findAll(NameExpr.class)) {
            if (!n.getNameAsString().equals(varName)) continue;
            Node parent = n.getParentNode().orElse(null);
            if (parent instanceof FieldAccessExpr fae && fae.getScope() == n) {
                kinds.add(UsageKind.FIELD_READ);
                String f = fae.getNameAsString();
                if (!fieldsRead.contains(f)) fieldsRead.add(f);
            }
            if (parent instanceof MethodCallExpr mc && mc.getScope().map(s -> s == n).orElse(false)) {
                kinds.add(UsageKind.METHOD_CALL_ON_RESULT);
            }
            if (parent instanceof ReturnStmt) {
                kinds.add(UsageKind.RETURNED_UNCHANGED);
            }
            if (n.findAncestor(IfStmt.class).filter(s -> isWithinCondition(n, s.getCondition())).isPresent()
                    || n.findAncestor(WhileStmt.class).filter(s -> isWithinCondition(n, s.getCondition())).isPresent()) {
                kinds.add(UsageKind.USED_IN_CONDITION);
            }
            if (parent instanceof ArrayAccessExpr aae && aae.getIndex() == n) {
                kinds.add(UsageKind.USED_IN_INDEX_EXPR);
            }
        }
    }

    /** Walk the consumer's method body, classify exception handling around the target call(s). */
    public ExceptionHandlingNearCall classifyExceptionHandling(
            java.nio.file.Path file, String consumerFqn, String targetSimpleName) {
        Optional<MethodDeclaration> mdOpt = findMethod(file, consumerFqn, targetSimpleName);
        if (mdOpt.isEmpty()) return ExceptionHandlingNearCall.none();
        MethodDeclaration md = mdOpt.get();
        List<String> caught = new ArrayList<>();
        boolean inTry = false;
        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals(targetSimpleName)) continue;
            Optional<TryStmt> tryAncestor = call.findAncestor(TryStmt.class);
            if (tryAncestor.isPresent()) {
                // The call must be inside the *try block*, not in a catch/finally of an unrelated try.
                TryStmt tryStmt = tryAncestor.get();
                if (isDescendant(call, tryStmt.getTryBlock())) {
                    inTry = true;
                    for (CatchClause cc : tryStmt.getCatchClauses()) {
                        String t = cc.getParameter().getType().asString();
                        for (String simple : t.split("\\s*\\|\\s*")) {
                            String s = simpleName(simple);
                            if (!caught.contains(s)) caught.add(s);
                        }
                    }
                }
            }
        }
        return new ExceptionHandlingNearCall(inTry, caught);
    }

    private static boolean isDescendant(Node child, Node ancestor) {
        Node cur = child;
        while (cur != null) {
            if (cur == ancestor) return true;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private static String simpleName(String typeName) {
        String t = typeName.trim();
        int dot = t.lastIndexOf('.');
        return dot < 0 ? t : t.substring(dot + 1);
    }

    /**
     * Resolves a consumer method by FQN, optionally disambiguating overloads by which
     * one actually contains a call to {@code targetSimpleName}. The CPG only gives us
     * {@code class.method} (no parameter types) for the immediate consumer, so when the
     * source has two methods named e.g. {@code addRowValues} — one delegating, one really
     * calling the target — picking by FQN alone is ambiguous.
     *
     * <p>If {@code targetSimpleName} is non-null, prefers the overload that contains a
     * direct {@link MethodCallExpr} to {@code targetSimpleName}. Falls back to any matching
     * overload if none contain the target call (preserves locateLine behavior for tests
     * that don't actually call target).
     */
    private Optional<MethodDeclaration> findMethod(Path file, String fqn, String targetSimpleName) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file.toFile());
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot < 0) return Optional.empty();
            String methodName = fqn.substring(lastDot + 1);
            String enclosingFqn = fqn.substring(0, lastDot);
            String simpleClass = enclosingFqn.substring(
                    Math.max(enclosingFqn.lastIndexOf('.'), enclosingFqn.lastIndexOf('$')) + 1);
            List<MethodDeclaration> candidates = cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(methodName))
                    .filter(m -> m.findAncestor(TypeDeclaration.class)
                            .map(t -> t.getNameAsString().equals(simpleClass)).orElse(false))
                    .toList();
            if (candidates.isEmpty()) return Optional.empty();
            if (targetSimpleName == null) return Optional.of(candidates.get(0));
            return candidates.stream()
                    .filter(m -> containsCallTo(m, targetSimpleName))
                    .findFirst()
                    .or(() -> Optional.of(candidates.get(0)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** True if the given method body has any direct call to {@code targetSimpleName}. */
    private static boolean containsCallTo(MethodDeclaration md, String targetSimpleName) {
        return md.findAll(MethodCallExpr.class).stream()
                .anyMatch(c -> c.getNameAsString().equals(targetSimpleName));
    }

    /**
     * Group clusters by their immediate consumer; for each consumer, build a
     * {@link ConsumerContract} with body slice + usage classification + implications.
     * Returns contracts sorted by total chains covered (desc).
     */
    public java.util.List<ConsumerContract> derive(
            java.util.List<PathCluster> clusters, String targetSimpleName, FileResolver resolver) {
        var byConsumer = new LinkedHashMap<String, java.util.List<PathCluster>>();
        for (PathCluster c : clusters) {
            byConsumer.computeIfAbsent(c.immediateConsumer(), k -> new ArrayList<>()).add(c);
        }
        var out = new ArrayList<ConsumerContract>();
        for (var e : byConsumer.entrySet()) {
            String consumerFqn = e.getKey();
            java.util.List<PathCluster> consumerClusters = e.getValue();
            int chainsCovered = consumerClusters.stream().mapToInt(PathCluster::chainsCovered).sum();
            java.nio.file.Path file = resolver.resolve(consumerFqn);
            String bodySlice = "(source unavailable)";
            int bodySliceStartLine = -1;
            ReturnValueUsage usage = ReturnValueUsage.empty();
            ExceptionHandlingNearCall exHandling = ExceptionHandlingNearCall.none();
            int line = -1;
            String fileStr = "";
            if (file != null) {
                var bodyResult = snippetExtractor.sliceConsumerBodyWithLine(file, consumerFqn, targetSimpleName);
                bodySlice = bodyResult.body() != null ? bodyResult.body() : "(unavailable)";
                bodySliceStartLine = bodyResult.startLine();
                usage = classifyReturnValueUsage(file, consumerFqn, targetSimpleName);
                exHandling = classifyExceptionHandling(file, consumerFqn, targetSimpleName);
                fileStr = file.toString();
                line = locateLine(file, consumerFqn, targetSimpleName);
            }
            var implications = ImpliedRequirementTemplates.derive(usage, exHandling);
            out.add(new ConsumerContract(consumerFqn, fileStr, line, bodySlice, bodySliceStartLine,
                    usage, exHandling, implications, consumerClusters, chainsCovered));
        }
        out.sort((a, b) -> Integer.compare(b.chainsCovered(), a.chainsCovered()));
        return out;
    }

    private static String nullSafe(String s) { return s == null ? "(unavailable)" : s; }

    private int locateLine(java.nio.file.Path file, String fqn, String targetSimpleName) {
        var mdOpt = findMethod(file, fqn, targetSimpleName);
        if (mdOpt.isEmpty()) return -1;
        return mdOpt.get().getBegin().map(p -> p.line).orElse(-1);
    }
}
