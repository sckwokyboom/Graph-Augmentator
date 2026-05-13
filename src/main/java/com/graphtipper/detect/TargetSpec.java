package com.graphtipper.detect;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record TargetSpec(
        String file,           // null for FQN-only
        String classFqn,       // null for path-form
        String simpleClass,
        String methodName,
        List<String> paramTypes  // may be empty when omitted
) {
    private static final Pattern PATH_FORM = Pattern.compile(
            "^(?<file>[^#]+)#(?<cls>[A-Za-z_][A-Za-z_0-9$]*)\\.(?<name>[A-Za-z_][A-Za-z_0-9]*)(?:\\((?<params>[^)]*)\\))?$");
    private static final Pattern FQN_FORM = Pattern.compile(
            "^(?<fqn>[A-Za-z_][A-Za-z_0-9.$]*)#(?<name>[A-Za-z_][A-Za-z_0-9]*)(?:\\((?<params>[^)]*)\\))?$");

    public static TargetSpec parse(String raw) {
        Matcher m = PATH_FORM.matcher(raw.trim());
        if (m.matches()) {
            return new TargetSpec(m.group("file"), null, m.group("cls"),
                    m.group("name"), splitParams(m.group("params")));
        }
        m = FQN_FORM.matcher(raw.trim());
        if (m.matches()) {
            String fqn = m.group("fqn");
            String simple = fqn.substring(Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$')) + 1);
            return new TargetSpec(null, fqn, simple, m.group("name"),
                    splitParams(m.group("params")));
        }
        throw new IllegalArgumentException("Invalid target spec: " + raw);
    }

    private static List<String> splitParams(String params) {
        if (params == null || params.isBlank()) return List.of();
        String[] parts = params.split("\\s*,\\s*");
        return List.of(parts);
    }
}
