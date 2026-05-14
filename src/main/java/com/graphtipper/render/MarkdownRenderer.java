package com.graphtipper.render;

import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import com.graphtipper.model.Node;

public final class MarkdownRenderer {

    public String render(Artifact a, TokenBudget budget, String projectKey, String projectName) {
        var sb = new StringBuilder();
        sb.append("# Graph-Tipper Augmentation\n\n");
        sb.append("> Generated for: ").append(projectName).append(" @ ").append(projectKey).append("\n");
        sb.append("> Target: ").append(a.target().fqn()).append("\n");
        String maxLabel = budget.max() == Integer.MAX_VALUE ? "unlimited" : Integer.toString(budget.max());
        sb.append("> Budget: ").append(budget.used()).append(" / ").append(maxLabel).append(" tokens · Chains: ")
          .append(a.chains().size()).append(" · Truncated: ").append(a.truncated()).append("\n\n");

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
        int idx = 1;
        for (Chain c : a.chains()) {
            sb.append("### Chain ").append(idx++).append(" (depth=").append(c.depth())
              .append(", virtual=").append(c.virtualSteps()).append(")\n");
            sb.append("**Test:** `").append(c.test().fqn()).append("` — `")
              .append(c.test().file()).append(":").append(c.test().lineStart()).append("`\n\n");
            for (CallStep s : c.steps()) {
                sb.append("```java\n// ").append(s.callerFqn()).append("\n");
                sb.append(s.snippet() == null ? "(snippet unavailable)" : s.snippet()).append("\n```\n");
                if (!s.argOrigins().isEmpty()) {
                    sb.append("**Arg origins at `").append(s.calleeFqn()).append("` call:**\n");
                    for (ArgOrigin o : s.argOrigins()) {
                        sb.append("- `arg").append(o.argIndex()).append("` = ");
                        switch (o.kind()) {
                            case LITERAL -> {
                                sb.append("`").append(o.value()).append("` (literal");
                                if (o.file() != null) sb.append(", ").append(o.file()).append(":").append(o.line());
                                sb.append(")");
                            }
                            case PARAMETER -> sb.append("parameter `").append(o.paramName()).append("`");
                            case FIELD -> sb.append("field `").append(o.fieldFqn()).append("`");
                            case FACTORY_CALL -> {
                                sb.append("factory `").append(o.factoryFqn()).append("(...)`");
                                if (o.file() != null) sb.append(" — ").append(o.file()).append(":").append(o.line());
                            }
                            case UNKNOWN -> sb.append("unknown");
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }
        }
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
        if (!lc.productionCallSites().isEmpty()) {
            sb.append("### Production call-sites of target (non-test, up to 5)\n");
            for (var p : lc.productionCallSites()) {
                sb.append("- `").append(p.callerFqn()).append("` — `").append(p.file()).append(":").append(p.line()).append("`\n");
                if (p.snippet() != null && !p.snippet().isBlank()) {
                    sb.append("  ```java\n  ").append(p.snippet()).append("\n  ```\n");
                }
            }
            sb.append("\n");
        }
    }
}
