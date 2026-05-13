package com.graphtipper.detect;

import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import java.util.*;
import java.util.stream.Collectors;

public final class MethodLocator {

    public static final class TargetNotFoundException extends RuntimeException {
        public TargetNotFoundException(String msg) { super(msg); }
    }

    public static final class AmbiguousTargetException extends RuntimeException {
        public AmbiguousTargetException(String msg) { super(msg); }
    }

    public Node.Method locate(ProjectGraph g, TargetSpec spec) {
        List<Node.Method> pool = new ArrayList<>();
        for (Node n : g.allNodes()) {
            if (n instanceof Node.Method m) pool.add(m);
        }
        // Filter by file (path form)
        if (spec.file() != null) {
            pool = pool.stream().filter(m -> spec.file().equals(m.file())).collect(Collectors.toList());
        }
        // Filter by simple class (last $ or . segment of fqn before method)
        if (spec.simpleClass() != null) {
            pool = pool.stream().filter(m -> simpleClass(m).equals(spec.simpleClass())).collect(Collectors.toList());
        }
        // Filter by method name
        pool = pool.stream().filter(m -> m.fqn().endsWith("." + spec.methodName())).collect(Collectors.toList());

        if (pool.isEmpty()) {
            throw new TargetNotFoundException("No method matches " + spec.methodName()
                    + "; near: " + nearest(g, spec.methodName()));
        }

        // Filter by paramTypes if specified
        if (!spec.paramTypes().isEmpty()) {
            var exact = pool.stream()
                    .filter(m -> matches(m.paramTypes(), spec.paramTypes()))
                    .collect(Collectors.toList());
            if (exact.size() == 1) return exact.get(0);
            if (!exact.isEmpty()) pool = exact;
        }

        if (pool.size() == 1) return pool.get(0);
        if (pool.size() > 1) {
            var sigs = pool.stream().map(Node.Method::signature).collect(Collectors.joining(", "));
            throw new AmbiguousTargetException("Multiple matches: " + sigs);
        }
        throw new TargetNotFoundException("No match for spec");
    }

    private String simpleClass(Node.Method m) {
        int dot = m.fqn().lastIndexOf('.');
        String beforeDot = dot < 0 ? m.fqn() : m.fqn().substring(0, dot);
        int dollar = beforeDot.lastIndexOf('$');
        int lastSep = Math.max(dot, dollar);
        String cls = lastSep < 0 ? beforeDot : beforeDot.substring(beforeDot.lastIndexOf('.') + 1);
        int innerSep = cls.lastIndexOf('$');
        return innerSep < 0 ? cls : cls.substring(innerSep + 1);
    }

    private boolean matches(List<String> actual, List<String> requested) {
        if (actual.size() != requested.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            String a = simpleName(actual.get(i));
            String r = simpleName(requested.get(i));
            if (!a.equalsIgnoreCase(r) && !actual.get(i).equalsIgnoreCase(requested.get(i))) return false;
        }
        return true;
    }

    private String simpleName(String typeFqn) {
        int dot = Math.max(typeFqn.lastIndexOf('.'), typeFqn.lastIndexOf('$'));
        return dot < 0 ? typeFqn : typeFqn.substring(dot + 1);
    }

    private String nearest(ProjectGraph g, String name) {
        var candidates = new ArrayList<String>();
        for (Node n : g.allNodes()) {
            if (n instanceof Node.Method m) {
                int d = levenshtein(name, m.fqn().substring(m.fqn().lastIndexOf('.') + 1));
                if (d <= 2) candidates.add(m.fqn());
            }
        }
        Collections.sort(candidates);
        return candidates.isEmpty() ? "(no near matches)" : String.join(", ", candidates.stream().limit(5).toList());
    }

    static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
