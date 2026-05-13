package com.graphtipper.slice;

public record ArgOrigin(
        int argIndex,
        Kind kind,
        String value,        // literal value, or null
        String factoryFqn,   // for FACTORY_CALL, else null
        String paramName,    // for PARAMETER (caller's param)
        String fieldFqn,     // for FIELD
        String file,
        int line
) {
    public enum Kind { LITERAL, PARAMETER, FIELD, FACTORY_CALL, UNKNOWN }
}
