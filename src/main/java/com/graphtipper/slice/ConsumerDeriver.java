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

    /** Walk the consumer's method body, classify how target's return value is used. */
    public ReturnValueUsage classifyReturnValueUsage(Path file, String consumerFqn, String targetSimpleName) {
        Optional<MethodDeclaration> mdOpt = findMethod(file, consumerFqn);
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
        Optional<MethodDeclaration> mdOpt = findMethod(file, consumerFqn);
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

    private Optional<MethodDeclaration> findMethod(Path file, String fqn) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file.toFile());
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot < 0) return Optional.empty();
            String methodName = fqn.substring(lastDot + 1);
            String enclosingFqn = fqn.substring(0, lastDot);
            String simpleClass = enclosingFqn.substring(
                    Math.max(enclosingFqn.lastIndexOf('.'), enclosingFqn.lastIndexOf('$')) + 1);
            return cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(methodName))
                    .filter(m -> m.findAncestor(TypeDeclaration.class)
                            .map(t -> t.getNameAsString().equals(simpleClass)).orElse(false))
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
