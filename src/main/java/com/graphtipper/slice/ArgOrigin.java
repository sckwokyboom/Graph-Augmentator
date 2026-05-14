package com.graphtipper.slice;

public record ArgOrigin(
        int argIndex,
        Kind kind,
        String value,           // LITERAL
        String factoryFqn,      // FACTORY_CALL
        String paramName,       // PARAMETER, LOCAL_VAR, LOOP_VAR (the identifier name)
        String fieldFqn,        // FIELD
        String file,
        int line,
        int definedAtLine,      // LOCAL_VAR / LOOP_VAR: line of definition; else -1
        String definedAtSnippet,// LOCAL_VAR / LOOP_VAR: one-line snippet of definition; else null
        String exprText         // FIELD_ACCESS / METHOD_CALL / INDEXED_ACCESS / CONSTRUCTOR
) {
    public enum Kind {
        LITERAL,
        PARAMETER,
        FIELD,
        FACTORY_CALL,
        LOCAL_VAR,
        LOOP_VAR,
        FIELD_ACCESS,
        METHOD_CALL,
        INDEXED_ACCESS,
        CONSTRUCTOR,
        UNKNOWN
    }

    // Factory helpers keep call sites short and ensure unused fields stay null/-1.
    public static ArgOrigin literal(int arg, String value, String file, int line) {
        return new ArgOrigin(arg, Kind.LITERAL, value, null, null, null, file, line, -1, null, null);
    }
    public static ArgOrigin parameter(int arg, String paramSignature) {
        return new ArgOrigin(arg, Kind.PARAMETER, null, null, paramSignature, null, null, -1, -1, null, null);
    }
    public static ArgOrigin field(int arg, String fieldFqn) {
        return new ArgOrigin(arg, Kind.FIELD, null, null, null, fieldFqn, null, -1, -1, null, null);
    }
    public static ArgOrigin factoryCall(int arg, String factoryFqn, String file, int line) {
        return new ArgOrigin(arg, Kind.FACTORY_CALL, null, factoryFqn, null, null, file, line, -1, null, null);
    }
    public static ArgOrigin localVar(int arg, String name, String file, int defLine, String defSnippet) {
        return new ArgOrigin(arg, Kind.LOCAL_VAR, null, null, name, null, file, defLine, defLine, defSnippet, null);
    }
    public static ArgOrigin loopVar(int arg, String name, String file, int defLine, String defSnippet) {
        return new ArgOrigin(arg, Kind.LOOP_VAR, null, null, name, null, file, defLine, defLine, defSnippet, null);
    }
    public static ArgOrigin fieldAccess(int arg, String exprText) {
        return new ArgOrigin(arg, Kind.FIELD_ACCESS, null, null, null, null, null, -1, -1, null, exprText);
    }
    public static ArgOrigin methodCall(int arg, String exprText) {
        return new ArgOrigin(arg, Kind.METHOD_CALL, null, null, null, null, null, -1, -1, null, exprText);
    }
    public static ArgOrigin indexedAccess(int arg, String exprText) {
        return new ArgOrigin(arg, Kind.INDEXED_ACCESS, null, null, null, null, null, -1, -1, null, exprText);
    }
    public static ArgOrigin constructor(int arg, String exprText) {
        return new ArgOrigin(arg, Kind.CONSTRUCTOR, null, null, null, null, null, -1, -1, null, exprText);
    }
    public static ArgOrigin unknown(int arg) {
        return new ArgOrigin(arg, Kind.UNKNOWN, null, null, null, null, null, -1, -1, null, null);
    }
}
