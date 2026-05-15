package com.graphtipper.render;

import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import com.graphtipper.model.Node;

import java.util.HashMap;
import java.util.Map;

public final class MarkdownRenderer {

    public String render(Artifact a, TokenBudget budget, String projectKey, String projectName) {
        var sb = new StringBuilder();
        sb.append("# Graph-Tipper Augmentation\n\n");
        sb.append("> Generated for: ").append(projectName).append(" @ ").append(projectKey).append("\n");
        sb.append("> Target: ").append(a.target().fqn()).append("\n");
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
        renderChains(sb, a);
        renderLocalContext(sb, a);
        sb.append("## Negative Memory\n_(reserved — not populated in V1)_\n");
        return sb.toString();
    }

    private void renderTarget(StringBuilder sb, Artifact a) {
        var t = a.target();
        sb.append("## Target\n\n");
        sb.append("**File:** `").append(t.file()).append("` (lines ").append(t.lineStart())
          .append("–").append(t.lineEnd()).append(")\n\n");
        if (t.javadoc() != null && !t.javadoc().isBlank()) {
            sb.append("**Javadoc:**\n> ").append(t.javadoc().replace("\n", "\n> ")).append("\n\n");
        }
        sb.append("**Signature:**\n```java\n").append(t.signature()).append("\n```\n\n");
        if (a.currentBody() != null && !a.currentBody().isBlank()) {
            sb.append("**Current body:**\n```java\n").append(a.currentBody()).append("\n```\n\n");
        }
    }

    private void renderChains(StringBuilder sb, Artifact a) {
        sb.append("## Test Chains\n\n");
        if (a.chains().isEmpty()) {
            sb.append("> No tests transitively reach this target.\n\n");
            return;
        }

        // Convergent-chain dedup: most large projects have several chains that funnel
        // through the same intermediate methods (e.g. picocli's 13 test paths all end in
        // addRowValues → putValue). Without dedup the same caller-body snippet and
        // arg-origins block repeat per chain, wasting context and burying signal.
        // Strategy: key each step by (callerFqn → calleeFqn → snippet-hash). The first
        // chain that emits a given key gets the full render; later chains get a one-line
        // back-reference. The key includes the snippet so distinct call sites between the
        // same pair (different line/different dataflow) still render fully.
        Map<String, Integer> stepFirstSeenInChain = new HashMap<>();
        int chainNumber = 0;

        for (Chain c : a.chains()) {
            chainNumber++;
            sb.append("### Chain ").append(chainNumber).append(" (depth=").append(c.depth())
              .append(", virtual=").append(c.virtualSteps()).append(")\n");
            sb.append("**Test:** `").append(c.test().fqn()).append("` — `")
              .append(c.test().file()).append(":").append(c.test().lineStart()).append("`\n\n");
            for (CallStep s : c.steps()) {
                String key = stepKey(s);
                Integer firstSeen = stepFirstSeenInChain.get(key);
                if (firstSeen != null) {
                    // Already shown in full. Emit a short back-reference so the chain
                    // remains traceable but doesn't repeat content.
                    sb.append("> _`").append(s.callerFqn()).append("` → `")
                      .append(s.calleeFqn()).append("` — same as Chain ")
                      .append(firstSeen).append("_\n\n");
                    continue;
                }
                stepFirstSeenInChain.put(key, chainNumber);
                sb.append("```java\n// ").append(s.callerFqn()).append("\n");
                sb.append(s.snippet() == null ? "(snippet unavailable)" : s.snippet()).append("\n```\n");
                if (!s.argOrigins().isEmpty()) {
                    sb.append("**Arg origins at `").append(s.calleeFqn()).append("` call:**\n");
                    for (ArgOrigin o : s.argOrigins()) {
                        sb.append("- `arg").append(o.argIndex()).append("` = ");
                        sb.append(renderArgOrigin(o));
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }
        }
    }

    private static String stepKey(CallStep s) {
        String snippet = s.snippet() == null ? "" : s.snippet();
        return s.callerFqn() + "→" + s.calleeFqn() + "#" + Integer.toHexString(snippet.hashCode());
    }

    /**
     * Renders a single ArgOrigin into its markdown fragment. Uses a switch expression so
     * the compiler enforces exhaustiveness over {@link ArgOrigin.Kind} — adding a new Kind
     * without updating this method becomes a compile-time error rather than a silently
     * empty rendering.
     */
    private static String renderArgOrigin(ArgOrigin o) {
        return switch (o.kind()) {
            case LITERAL -> {
                var b = new StringBuilder();
                b.append("`").append(o.value()).append("` (literal");
                if (o.file() != null) b.append(", ").append(o.file()).append(":").append(o.line());
                b.append(")");
                yield b.toString();
            }
            case PARAMETER -> "parameter `" + o.paramName() + "`";
            case FIELD -> "field `" + o.fieldFqn() + "`";
            case FACTORY_CALL -> {
                var b = new StringBuilder();
                b.append("factory `").append(o.factoryFqn()).append("(...)`");
                if (o.file() != null) b.append(" — ").append(o.file()).append(":").append(o.line());
                yield b.toString();
            }
            case LOCAL_VAR -> o.definedAtLine() > 0
                    ? "local `" + o.paramName() + "` (defined at line " + o.definedAtLine() + ")"
                    : "local `" + o.paramName() + "`";
            case LOOP_VAR -> o.definedAtLine() > 0
                    ? "loop var `" + o.paramName() + "` (defined at line " + o.definedAtLine() + ")"
                    : "loop var `" + o.paramName() + "`";
            case FIELD_ACCESS -> "field access `" + o.exprText() + "`";
            case METHOD_CALL -> "call `" + o.exprText() + "`";
            case INDEXED_ACCESS -> "indexed access `" + o.exprText() + "`";
            case CONSTRUCTOR -> "new `" + o.exprText() + "`";
            case UNKNOWN -> "unknown";
        };
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
          .append(c.bodySlice()).append("\n```\n\n");

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

        // Path clusters rendered in Task 28.
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
        if (!lc.siblings().isEmpty()) {
            sb.append("### Sibling members used by target\n```java\n");
            for (var s : lc.siblings()) {
                if (s.javadoc() != null && !s.javadoc().isBlank()) {
                    sb.append("/** ").append(s.javadoc().replace("\n", " ")).append(" */\n");
                }
                sb.append(s.signature()).append("\n");
                if (!s.body().isBlank()) sb.append(s.body()).append("\n");
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
