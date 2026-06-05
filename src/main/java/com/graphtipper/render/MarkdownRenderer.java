package com.graphtipper.render;

import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import com.graphtipper.model.Node;

public final class MarkdownRenderer {

    private final RenderOptions options;

    public MarkdownRenderer() { this(RenderOptions.defaults()); }

    public MarkdownRenderer(RenderOptions options) { this.options = options; }

    public String render(Artifact a, TokenBudget budget, String projectKey, String projectName) {
        var sb = new StringBuilder();
        sb.append("# Graph-Tipper Augmentation\n\n");
        sb.append("> Generated for: ").append(projectName).append(" @ ").append(projectKey).append("\n");
        sb.append("> Target: ").append(a.target().fqn()).append("\n");

        boolean bare = options != null && options.bare();
        if (bare) {
            sb.append("> Mode: bare (signature-only)\n\n");
            renderTarget(sb, a);
            return sb.toString();
        }

        if (options != null && options.specMode()) {
            return renderSpec(sb, a);
        }

        String maxLabel = budget.max() == Integer.MAX_VALUE ? "unlimited" : Integer.toString(budget.max());
        int consumerCount = a.consumers().size();
        int clusterCount = a.consumers().stream().mapToInt(c -> c.clusters().size()).sum();
        int coveredChains = a.consumers().stream().mapToInt(c -> c.chainsCovered()).sum();
        int totalChains = a.chains().size();
        int pct = totalChains == 0 ? 0 : (int) ((coveredChains * 100L) / totalChains);
        sb.append("> Budget: ").append(budget.used()).append(" / ").append(maxLabel).append(" tokens\n");
        sb.append("> Consumers: ").append(consumerCount)
          .append(" · Path clusters: ").append(clusterCount)
          .append(" (covering ").append(coveredChains).append("/").append(totalChains)
          .append(" chains, ").append(pct).append("%)\n");
        sb.append("> Direct tests: ").append(a.directTests().size())
          .append(" · Long-tail singletons: ").append(a.longTailSingletons().size())
          .append("\n\n");

        renderTarget(sb, a);
        renderDirectTests(sb, a);
        renderConsumerContracts(sb, a);
        renderLongTail(sb, a);
        renderLocalContext(sb, a);
        sb.append("## Negative Memory\n_(reserved — not populated in V1)_\n");
        return sb.toString();
    }

    /**
     * A readable Java signature for the target. {@link Node.Method#signature()} holds the raw
     * Joern SIGNATURE (`returnType(paramTypes)` with FQNs and no method name), which is unreadable.
     * Prefer the real declaration line from {@code currentBody} (carries modifiers + param names);
     * this is present even under --no-current-body / --bare (only body rendering is gated, not the
     * field). Fall back to a reconstruction from the Joern signature + fqn when no body is available.
     */
    private static String readableSignature(Artifact a) {
        String body = a.currentBody();
        if (body != null && !body.isBlank()) {
            int brace = body.indexOf('{');
            String decl = (brace >= 0 ? body.substring(0, brace) : body).strip()
                    .replaceAll("\\s+", " ");
            if (!decl.isBlank()) return decl;
        }
        return reconstructSignature(a.target());
    }

    /** Builds `<simpleReturn> <methodName>(<simpleParamTypes>)` from the Joern signature + fqn. */
    private static String reconstructSignature(Node.Method m) {
        String fqn = m.fqn() == null ? "" : m.fqn();
        int dot = fqn.lastIndexOf('.');
        String name = dot >= 0 ? fqn.substring(dot + 1) : fqn;
        String sig = m.signature();
        if (sig == null || sig.indexOf('(') < 0) {
            String params = m.paramTypes() == null ? ""
                    : m.paramTypes().stream().map(MarkdownRenderer::simpleType)
                        .reduce((x, y) -> x + ", " + y).orElse("");
            String ret = simpleType(m.returnType());
            return (ret.isEmpty() ? "" : ret + " ") + name + "(" + params + ")";
        }
        int paren = sig.indexOf('(');
        String ret = simpleType(sig.substring(0, paren));
        String inner = sig.substring(paren + 1, sig.endsWith(")") ? sig.length() - 1 : sig.length());
        String params = inner.isBlank() ? "" :
                java.util.Arrays.stream(inner.split(","))
                        .map(MarkdownRenderer::simpleType)
                        .reduce((x, y) -> x + ", " + y).orElse("");
        return (ret.isEmpty() ? "" : ret + " ") + name + "(" + params + ")";
    }

    private static String simpleType(String t) {
        if (t == null) return "";
        t = t.strip();
        if (t.isEmpty()) return "";
        int sep = Math.max(t.lastIndexOf('.'), t.lastIndexOf('$'));
        return sep < 0 ? t : t.substring(sep + 1);
    }

    private void renderTarget(StringBuilder sb, Artifact a) {
        var t = a.target();
        boolean bare = options != null && options.bare();
        sb.append("## Target\n\n");
        sb.append("**File:** `").append(t.file()).append("` (lines ").append(t.lineStart())
          .append("–").append(t.lineEnd()).append(")\n\n");
        if (t.javadoc() != null && !t.javadoc().isBlank()) {
            sb.append("**Javadoc:**\n> ").append(t.javadoc().replace("\n", "\n> ")).append("\n\n");
        }
        sb.append("**Signature:**\n```java\n").append(readableSignature(a)).append("\n```\n\n");
        // In bare mode the harness uses this as the "no-context" baseline; emitting the
        // current body would leak the reference solution and make the gt-current vs
        // no-context comparison degenerate. Also gated by --no-current-body for demo flows
        // where the LLM should be generating the body from scratch.
        boolean suppressCurrentBody = bare || (options != null && options.noCurrentBody());
        if (!suppressCurrentBody && a.currentBody() != null && !a.currentBody().isBlank()) {
            sb.append("**Current body:**\n```java\n").append(a.currentBody()).append("\n```\n\n");
        }
    }

    // -----------------------------------------------------------------------
    // Spec mode (§ --spec): target + scoped test command + behavioral examples
    // + return contract + trimmed helpers. No call-path clusters.
    // -----------------------------------------------------------------------

    private String renderSpec(StringBuilder sb, Artifact a) {
        sb.append("> Mode: spec\n\n");
        renderTarget(sb, a);

        // How to verify — scoped test command over the test classes that reach the target.
        var classes = reachingTestClasses(a);
        sb.append("## How to verify (run ONLY these — they pin this method's behavior)\n\n");
        sb.append("```\n./gradlew test");
        for (String c : classes) sb.append(" \\\n  --tests ").append(c);
        sb.append("\n```\n\n");

        // Behavioral spec — input→output examples (direct + owner-class unit tests).
        sb.append("## Behavioral spec (input → expected output)\n\n");
        if (a.directTests().isEmpty() && a.behavioralTests().isEmpty()) {
            sb.append("_(no test examples resolved)_\n\n");
        } else {
            for (var t : a.directTests())     renderTestExample(sb, t, "direct");
            for (var t : a.behavioralTests()) renderTestExample(sb, t, "behavioral");
        }

        renderReturnContract(sb, a);
        renderHelpers(sb, a);
        return sb.toString();
    }

    /**
     * Owner-class members the target is likely to need, minus noise. Body-agnostic trim:
     * drops {@code @Deprecated} members and {@code static} factories (a data-structure class's
     * static methods are almost always factories/utilities, not what an instance method calls).
     * Keeps instance methods (with bodies — the bodies carry semantics like
     * {@code rowCount() = columnValues.size()/columns.length}) and field declarations.
     */
    private void renderHelpers(StringBuilder sb, Artifact a) {
        var siblings = a.localContext() == null ? null : a.localContext().siblings();
        if (siblings == null || siblings.isEmpty()) return;
        var kept = new java.util.ArrayList<com.graphtipper.slice.LocalContext.SiblingMember>();
        for (var s : siblings) {
            String decl = firstNonBlankLine(s.body());
            if (decl.contains("@Deprecated")) continue;
            if (decl.contains(" static ")) continue;          // factories / static utils
            kept.add(s);
        }
        if (kept.isEmpty()) return;
        sb.append("## Helpers available on the owner class\n\n```java\n");
        for (var s : kept) {
            if (s.body() != null && !s.body().isBlank()) {
                sb.append(s.body()).append("\n");
            } else {
                sb.append(s.signature()).append("\n");        // fields: no body
            }
        }
        sb.append("```\n\n");
    }

    private static String firstNonBlankLine(String body) {
        if (body == null) return "";
        for (String line : body.split("\n", -1)) {
            if (!line.isBlank()) return line.strip();
        }
        return "";
    }

    private static java.util.List<String> reachingTestClasses(Artifact a) {
        var set = new java.util.LinkedHashSet<String>();
        for (var t : a.directTests())     set.add(classFqnOf(t.testMethod().fqn()));
        for (var t : a.behavioralTests()) set.add(classFqnOf(t.testMethod().fqn()));
        set.remove("");
        return new java.util.ArrayList<>(set);
    }

    private static String classFqnOf(String methodFqn) {
        if (methodFqn == null) return "";
        int dot = methodFqn.lastIndexOf('.');
        return dot < 0 ? methodFqn : methodFqn.substring(0, dot);
    }

    private void renderTestExample(StringBuilder sb, com.graphtipper.slice.DirectTest t, String kind) {
        var m = t.testMethod();
        sb.append("### ").append(m.fqn()).append("  [").append(kind).append("]\n");
        sb.append("Oracle: ").append(renderOracle(t.oracle())).append("\n\n");
        sb.append("```java\n// ").append(m.file()).append(":").append(m.lineStart()).append("\n");
        sb.append(t.snippet() == null || t.snippet().isBlank() ? "(snippet unavailable)" : t.snippet());
        sb.append("\n```\n\n");
    }

    private void renderReturnContract(StringBuilder sb, Artifact a) {
        if (a.consumers().isEmpty()) return;
        var c = a.consumers().get(0);
        if (c.implications() == null || c.implications().isEmpty()) return;
        sb.append("## Return-value contract (from consumer ").append(c.consumerFqn()).append(")\n\n");
        for (var imp : c.implications()) {
            sb.append("- ").append(imp.text()).append("\n");
        }
        sb.append("\n");
    }

    private void renderDirectTests(StringBuilder sb, Artifact a) {
        if (a.directTests().isEmpty()) return;
        sb.append("## Direct tests\n\n");
        sb.append("| Test (file:line) | Args | Oracle |\n");
        sb.append("|---|---|---|\n");
        var argRenderer = new ArgRenderer();
        for (var dt : a.directTests()) {
            sb.append("| `").append(dt.testMethod().fqn()).append("` (")
              .append(dt.testMethod().file()).append(":").append(dt.testMethod().lineStart()).append(") | ")
              .append(escapePipes(argRenderer.renderTuple(dt.args()))).append(" | ")
              .append(escapePipes(renderOracle(dt.oracle()))).append(" |\n");
        }
        sb.append("\n**Test sources:**\n");
        for (var dt : a.directTests()) {
            sb.append("```java\n// ").append(dt.testMethod().file()).append(":")
              .append(dt.testMethod().lineStart()).append("\n");
            sb.append(dt.snippet() == null ? "(snippet unavailable)" : dt.snippet()).append("\n```\n\n");
        }
    }

    private void renderConsumerContracts(StringBuilder sb, Artifact a) {
        if (a.consumers().isEmpty()) {
            sb.append("## Consumer contracts\n");
            sb.append("_(target has no production callers; behavior is defined only by direct tests above)_\n\n");
            return;
        }
        sb.append("## Consumer contracts\n\n");
        int n = 1;
        for (var c : a.consumers()) {
            renderConsumerBlock(sb, c, n++);
        }
    }

    private void renderConsumerBlock(StringBuilder sb, com.graphtipper.slice.ConsumerContract c, int n) {
        sb.append("### Consumer ").append(n).append(": ").append(c.consumerFqn()).append("\n");
        sb.append("**Chains covered:** ").append(c.chainsCovered()).append("\n");
        if (c.file() != null && !c.file().isBlank()) {
            sb.append("**Defined at:** ").append(c.file()).append(":").append(c.line()).append("\n\n");
        } else {
            sb.append("\n");
        }
        sb.append("**Body slice around call to target:**\n```java\n")
          .append(maybePruneBody(c)).append("\n```\n\n");

        sb.append("**Return-value usage (AST-derived):**\n");
        for (var k : c.returnValueUsage().kinds()) {
            sb.append("- ").append(humanizeKind(k));
            if (k == com.graphtipper.slice.UsageKind.FIELD_READ
                    && !c.returnValueUsage().fieldsRead().isEmpty()) {
                sb.append(": `").append(String.join("`, `", c.returnValueUsage().fieldsRead())).append("`");
            }
            sb.append("\n");
        }
        sb.append("\n");

        sb.append("**Exception handling around call:**\n");
        if (c.exceptionHandling().inTryCatch()) {
            sb.append("- In try/catch; types caught: ")
              .append(String.join(", ", c.exceptionHandling().caughtTypes())).append("\n");
        } else {
            sb.append("- No try/catch → exceptions propagate to caller as-is\n");
        }
        sb.append("\n");

        sb.append("**Implied requirements on target:**\n");
        for (var r : c.implications()) {
            sb.append("- ").append(r.text()).append("\n");
        }
        sb.append("\n");

        // Path clusters
        int ci = 1;
        for (var cluster : c.clusters()) {
            renderPathCluster(sb, cluster, n, ci++);
        }
    }

    private static String humanizeKind(com.graphtipper.slice.UsageKind k) {
        return switch (k) {
            case ASSIGNED_TO_LOCAL -> "Assigned to local";
            case ASSIGNED_TO_FIELD -> "Assigned to field";
            case FIELD_READ -> "Field-read";
            case METHOD_CALL_ON_RESULT -> "Method called on result";
            case USED_IN_CONDITION -> "Used in branch condition";
            case USED_IN_LOOP -> "Used in loop bound";
            case USED_IN_INDEX_EXPR -> "Used in index expression";
            case PASSED_AS_ARG -> "Passed as argument to another method";
            case RETURNED_UNCHANGED -> "Returned unchanged by caller";
            case DISCARDED -> "Discarded (no LHS, no dotted access)";
        };
    }

    private void renderPathCluster(StringBuilder sb, com.graphtipper.slice.PathCluster cluster,
                                    int consumerNum, int clusterNum) {
        String clusterAnchor = "4.4." + consumerNum + "." + (char) ('a' + clusterNum - 1);
        String entrySimple = simpleMethodName(cluster.entryPoint());
        sb.append("#### ").append(clusterAnchor)
          .append(" Cluster: ").append(entrySimple).append(" path (")
          .append(cluster.chainsCovered()).append(" chains)\n\n");
        String hubMarker = renderHubMarker(cluster, options == null ? null : options.scorer());
        if (!hubMarker.isEmpty()) sb.append(hubMarker).append("\n\n");
        sb.append("**Entry-point:** `").append(cluster.entryPoint()).append("`\n");
        sb.append("**Path:** ").append(renderPathSignature(cluster.signature())).append("\n");
        sb.append("**Depth:** ").append(cluster.depth()).append("\n\n");

        renderStaticSlice(sb, cluster);

        if (cluster.members().isEmpty()) {
            sb.append("_(no member tests resolved)_\n\n");
            return;
        }

        var argRenderer = new ArgRenderer();

        // Singleton: compact rendering, skip differential matrix.
        if (cluster.members().size() == 1) {
            var only = cluster.members().get(0);
            sb.append("**Single observation:** `").append(only.testMethod().fqn())
              .append("` (").append(only.testMethod().file()).append(":")
              .append(only.testMethod().lineStart()).append(")\n");
            sb.append("**Args at target:** ").append(argRenderer.renderTuple(only.argsAtTarget())).append("\n");
            sb.append("**Oracle:** ").append(renderOracle(only.oracle())).append("\n\n");
            if (!cluster.signals().isEmpty()) {
                sb.append("**Behavior signals:**\n");
                for (var s : cluster.signals()) sb.append("- `").append(s.tag()).append("`\n");
                sb.append("\n");
            }
            return;
        }

        // Primary representative = first member.
        var primary = cluster.members().get(0);
        sb.append("**Primary representative:** `").append(primary.testMethod().fqn())
          .append("` — `").append(primary.testMethod().file()).append(":")
          .append(primary.testMethod().lineStart()).append("`\n\n");

        // Differential matrix — up to 5 rows.
        sb.append("**Differential matrix (").append(Math.min(cluster.members().size(), 5))
          .append(" representatives of ").append(cluster.members().size()).append("):**\n\n");
        sb.append("| Test | Sliced args | Oracle |\n");
        sb.append("|---|---|---|\n");
        int rows = Math.min(cluster.members().size(), 5);
        for (int i = 0; i < rows; i++) {
            var m = cluster.members().get(i);
            String slicedArgs = m.argSlices().isEmpty()
                    ? argRenderer.renderTuple(m.argsAtTarget())   // fallback to legacy
                    : renderSlicedArgsTuple(m.argSlices(), argRenderer);
            sb.append("| `").append(m.testMethod().fqn()).append("` | ")
              .append(escapePipes(slicedArgs)).append(" | ")
              .append(escapePipes(renderOracle(m.oracle()))).append(" |\n");
        }
        if (cluster.members().size() > 5) {
            sb.append("\n**+ ").append(cluster.members().size() - 5)
              .append(" more tests with similar profile** (see JSON sidecar)\n");
        }
        sb.append("\n");

        if (!cluster.signals().isEmpty()) {
            sb.append("**Behavior signals (from differential analysis):**\n");
            for (var s : cluster.signals()) {
                sb.append("- `").append(s.tag()).append("`");
                if (s.evidence() != null && !s.evidence().isBlank()) {
                    sb.append(": ").append(s.evidence());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
    }

    private static String renderSlicedArgsTuple(
            java.util.List<com.graphtipper.slice.ArgSlice> argSlices, ArgRenderer renderer) {
        var parts = new java.util.ArrayList<String>();
        for (var as : argSlices) parts.add(renderer.renderSliceResult(as.result()));
        return "(" + String.join(", ", parts) + ")";
    }

    private void renderStaticSlice(StringBuilder sb, com.graphtipper.slice.PathCluster cluster) {
        var cs = cluster.clusterSlice();
        if (cs == null || cs.args().isEmpty()) return;
        sb.append("**Static slice (Tier 2):**\n\n");

        // Collapse policy: if all args are Unresolved with the same reason → one-line summary.
        com.graphtipper.slice.UnresolvedReason commonReason = null;
        boolean allUnresolvedSameReason = !cs.args().isEmpty();
        for (var as : cs.args()) {
            if (!(as.result() instanceof com.graphtipper.slice.SliceResult.Unresolved u)) {
                allUnresolvedSameReason = false; break;
            }
            if (commonReason == null) commonReason = u.reason();
            else if (commonReason != u.reason()) { allUnresolvedSameReason = false; break; }
        }
        if (allUnresolvedSameReason) {
            sb.append("all args unresolved (").append(commonReason)
              .append("); inspect direct tests / test method literals to understand actual values.\n\n");
            return;
        }

        // Full per-arg form.
        var argRenderer = new ArgRenderer();
        for (var argSlice : cs.args()) {
            sb.append(argSlice.argName());
            if (argSlice.argType() != null && !argSlice.argType().isBlank()
                    && !"?".equals(argSlice.argType())) {
                sb.append(" (").append(argSlice.argType()).append(")");
            }
            sb.append(":\n  ← ");
            sb.append(argRenderer.renderSliceResult(argSlice.result()));
            sb.append("\n\n");
        }
    }

    private static String renderPathSignature(com.graphtipper.slice.PathSignature sig) {
        // Collapse consecutive identical simple-method-names to a single occurrence:
        // parse, parse, parse → parse. The repetition count (recursion depth / overload
        // chaining) carries no signal for the LLM and only adds visual noise.
        var simples = sig.fqns().stream().map(MarkdownRenderer::simpleMethodName).toList();
        var out = new StringBuilder();
        int i = 0;
        while (i < simples.size()) {
            int j = i;
            while (j + 1 < simples.size() && simples.get(j + 1).equals(simples.get(i))) j++;
            out.append(simples.get(i));
            if (j + 1 < simples.size()) out.append(" → ");
            i = j + 1;
        }
        return out.toString();
    }

    private static String simpleMethodName(String fqn) {
        if (fqn == null) return "?";
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return fqn;
        String simple = fqn.substring(lastDot + 1);
        // include enclosing simple class for clarity: ClassName.method
        int prevDot = fqn.lastIndexOf('.', lastDot - 1);
        int prevDollar = fqn.lastIndexOf('$', lastDot - 1);
        int prev = Math.max(prevDot, prevDollar);
        // If no previous separator: fqn is already "ClassName.method" — return as-is
        return prev < 0 ? fqn : fqn.substring(prev + 1, lastDot) + "." + simple;
    }

    private void renderLongTail(StringBuilder sb, Artifact a) {
        int singletons = a.longTailSingletons().size();
        if (singletons == 0) return;
        sb.append("## Long tail\n\n");
        sb.append(singletons).append(" additional uncovered singleton paths (each represents 1 chain). ");
        sb.append("See `<hash>.json` → `clusters[].singletons` for the full list.\n\n");
    }

    /**
     * Returns "[hub: M1, M2]" where M1, M2 are the top-2 Katz-scored methods touched by
     * this cluster's path signature. Empty string when scorer is null or all scores are zero.
     */
    public static String renderHubMarker(com.graphtipper.slice.PathCluster cluster,
                                          com.graphtipper.chop.score.KatzScorer scorer) {
        if (scorer == null) return "";
        var fqns = cluster.signature().fqns();
        var scored = new java.util.ArrayList<java.util.Map.Entry<String, Double>>();
        for (String fqn : fqns) {
            scored.add(java.util.Map.entry(fqn,
                scorer.score(new com.graphtipper.chop.model.MethodRef(fqn, ""))));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        var top = new java.util.ArrayList<String>();
        for (var e : scored) {
            if (top.size() >= 2) break;
            if (e.getValue() <= 0.0) continue;
            top.add(e.getKey());
        }
        if (top.isEmpty()) return "";
        return "[hub: " + String.join(", ", top) + "]";
    }

    /** Pair of (original sibling, body text to render after coverage pruning). */
    private record SiblingRender(com.graphtipper.slice.LocalContext.SiblingMember member,
                                  String renderedBody) {}

    /**
     * When a JaCoCo pruner is in scope, drops siblings whose entire line range has zero
     * executed lines, and annotates the body of partially-executed siblings via
     * {@link com.graphtipper.slice.AstSnippetExtractor#annotateLines}. Without a pruner,
     * passes siblings through unchanged.
     */
    private java.util.List<SiblingRender> pruneSiblings(
            java.util.List<com.graphtipper.slice.LocalContext.SiblingMember> siblings) {
        var out = new java.util.ArrayList<SiblingRender>();
        var pruner = (options == null) ? null : options.pruner();
        for (var s : siblings) {
            // Keep unchanged when we lack the info needed to decide. Joern frequently omits
            // LINE_NUMBER_END for short methods → lineEnd = -1; treat as "don't know, keep".
            if (pruner == null || s.file() == null || s.lineStart() <= 0
                    || s.lineEnd() < s.lineStart()) {
                out.add(new SiblingRender(s, s.body()));
                continue;
            }
            String fileKey = packageQualifiedSourcePath(s.file());
            if (fileKey.isEmpty()) { out.add(new SiblingRender(s, s.body())); continue; }
            // Drop the sibling entirely if no line in [lineStart, lineEnd] was executed.
            boolean anyExecuted = false;
            for (int ln = s.lineStart(); ln <= s.lineEnd(); ln++) {
                if (pruner.isExecuted(fileKey, ln)) { anyExecuted = true; break; }
            }
            if (!anyExecuted) continue;
            // Otherwise, annotate body lines.
            var rawLines = java.util.Arrays.asList(s.body().split("\n", -1));
            var annotated = com.graphtipper.slice.AstSnippetExtractor.annotateLines(
                    rawLines, fileKey, s.lineStart(), pruner);
            out.add(new SiblingRender(s, String.join("\n", annotated)));
        }
        return out;
    }

    private String maybePruneBody(com.graphtipper.slice.ConsumerContract c) {
        if (options == null || options.pruner() == null) return c.bodySlice();
        if (c.bodySliceStartLine() <= 0) {
            return c.bodySlice() + "\n// (coverage pruning skipped: source line tracking unavailable)";
        }
        String fileKey = packageQualifiedSourcePath(c.file());
        if (fileKey.isEmpty()) return c.bodySlice();
        java.util.List<String> lines = java.util.Arrays.asList(c.bodySlice().split("\n", -1));
        java.util.List<String> annotated = com.graphtipper.slice.AstSnippetExtractor.annotateLines(
                lines, fileKey, c.bodySliceStartLine(), options.pruner());
        return String.join("\n", annotated);
    }

    /** Mirror of SliceCommand.packageQualifiedSourcePath — kept local so renderer stays self-contained. */
    private static String packageQualifiedSourcePath(String filePath) {
        if (filePath == null) return "";
        int idx = filePath.indexOf("src/main/java/");
        if (idx >= 0) return filePath.substring(idx + "src/main/java/".length());
        idx = filePath.indexOf("src/test/java/");
        if (idx >= 0) return filePath.substring(idx + "src/test/java/".length());
        return filePath;
    }

    private static String escapePipes(String s) { return s.replace("|", "\\|"); }

    private static String renderOracle(com.graphtipper.slice.Oracle o) {
        return switch (o) {
            case com.graphtipper.slice.Oracle.Equals eq -> "returns " + eq.expected();
            case com.graphtipper.slice.Oracle.Exception ex -> "throws " + ex.type();
            case com.graphtipper.slice.Oracle.ExceptionMessage em -> "throws " + em.type() + ".msg "
                    + (em.kind() == com.graphtipper.slice.Oracle.MatchKind.EXACT ? "==" : "contains")
                    + " \"" + em.message() + "\"";
            case com.graphtipper.slice.Oracle.Boolean b -> (b.expected() ? "assertTrue(" : "assertFalse(") + b.expr() + ")";
            case com.graphtipper.slice.Oracle.Nullability n -> n.expr() + (n.expectNonNull() ? " is non-null" : " is null");
            case com.graphtipper.slice.Oracle.Contains c -> c.expr() + " contains \"" + c.substring() + "\"";
            case com.graphtipper.slice.Oracle.None __ -> "<no assertion found>";
        };
    }

    private void renderLocalContext(StringBuilder sb, Artifact a) {
        sb.append("## Local Context\n\n");
        var lc = a.localContext();
        var visibleSiblings = pruneSiblings(lc.siblings());
        if (!visibleSiblings.isEmpty()) {
            sb.append("### Sibling members used by target\n```java\n");
            for (var entry : visibleSiblings) {
                var s = entry.member();
                if (s.javadoc() != null && !s.javadoc().isBlank()) {
                    sb.append("/** ").append(s.javadoc().replace("\n", " ")).append(" */\n");
                }
                sb.append(s.signature()).append("\n");
                if (!entry.renderedBody().isBlank()) sb.append(entry.renderedBody()).append("\n");
            }
            sb.append("```\n\n");
        }
        if (!lc.usedTypes().isEmpty()) {
            sb.append("### Used types\n");
            for (var u : lc.usedTypes()) {
                sb.append("**`").append(u.type().fqn()).append("`** (").append(u.type().kind().name().toLowerCase()).append(")\n");
                if (u.type().enumConstants() != null && !u.type().enumConstants().isEmpty()) {
                    sb.append("```java\nenum ").append(u.type().fqn()).append(" { ")
                      .append(String.join(", ", u.type().enumConstants())).append(" }\n```\n");
                } else if (!u.publicMethodSignatures().isEmpty()) {
                    sb.append("```java\n");
                    for (String sig : u.publicMethodSignatures()) sb.append(sig).append("\n");
                    sb.append("```\n");
                }
                sb.append("\n");
            }
        }
    }
}
