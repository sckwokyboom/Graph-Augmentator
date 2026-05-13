package com.graphtipper.model;

import java.util.List;

public sealed interface Node permits
        Node.Method, Node.Type, Node.Field, Node.Parameter,
        Node.CallSite, Node.Stmt, Node.Literal {

    String id();

    enum TypeKind { CLASS, INTERFACE, ENUM, ANNOTATION }
    enum StmtKind { IF, LOOP, RETURN, EXPR, OTHER }
    enum LiteralKind { INT, LONG, FLOAT, DOUBLE, STRING, BOOL, NULL, OTHER }

    record Method(
            String id,
            String fqn,
            String signature,
            List<String> paramTypes,
            String returnType,
            String file,
            int lineStart,
            int lineEnd,
            String javadoc,
            boolean isTest,
            boolean isAbstract,
            List<String> modifiers) implements Node {}

    record Type(
            String id,
            String fqn,
            TypeKind kind,
            String file,
            int lineStart,
            int lineEnd,
            List<String> enumConstants) implements Node {}

    record Field(
            String id,
            String ownerTypeFqn,
            String name,
            String type,
            List<String> modifiers,
            int lineStart,
            int lineEnd) implements Node {}

    record Parameter(
            String id,
            String ownerMethodId,
            String name,
            String type,
            int index) implements Node {}

    record CallSite(
            String id,
            String inMethodId,
            String calleeFqn,
            int argCount,
            int line,
            int col,
            String codeSnippet) implements Node {}

    record Stmt(
            String id,
            String inMethodId,
            int line,
            StmtKind kind,
            String codeSnippet) implements Node {}

    record Literal(
            String id,
            String inMethodId,
            LiteralKind kind,
            String value,
            int line) implements Node {}
}
