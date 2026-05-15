package com.graphtipper.slice;

import java.util.EnumSet;
import java.util.List;

/**
 * AST-derived summary of how the target's return value is used at a consumer's call site.
 * {@code kinds} is the set of patterns observed; {@code fieldsRead} lists field identifiers
 * read off the result (e.g., {@code cell.row}, {@code cell.column}).
 */
public record ReturnValueUsage(EnumSet<UsageKind> kinds, List<String> fieldsRead) {
    public ReturnValueUsage {
        kinds = EnumSet.copyOf(kinds);
        fieldsRead = List.copyOf(fieldsRead);
    }
    public static ReturnValueUsage empty() {
        return new ReturnValueUsage(EnumSet.noneOf(UsageKind.class), List.of());
    }
}
