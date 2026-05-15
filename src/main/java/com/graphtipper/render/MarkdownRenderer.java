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
