package com.graphtipper.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import com.graphtipper.slice.*;

import java.time.Instant;
import java.util.*;

/**
 * Schema-v2 graph renderer: a flat, chain-centric document tailored for LLM consumption.
 *
 * <p>Schema-v1 (the {@code vertices}/{@code edges}/{@code chains} layout) had three
 * semantic bugs that surfaced on real-world input:
 * <ol>
 *   <li>{@code call_site.line} was always {@code -1} because {@code CallStep} did not
 *       carry call-site coordinates.</li>
 *   <li>{@code call_site.code} held the method-signature header instead of the actual
 *       call expression.</li>
 *   <li>An intermediate method's vertex {@code snippet} was the caller's body (the
 *       enclosing slice context), not the method's own body.</li>
 * </ol>
 *
 * <p>Schema-v2 fixes all three by:
 * <ul>
 *   <li>Pulling {@code call_site} (file/line/column/code) from {@code CallStep.callSite},
 *       populated by {@code CallSiteSlicer.enrich} using the real {@code Node.CallSite}
 *       coordinates from Joern plus a one-line read of the source.</li>
 *   <li>Storing method bodies in a top-level {@code method_bodies} object keyed by FQN.
 *       Each chain step references {@code caller_ref}/{@code callee_ref} which resolve
 *       to:
 *       <ul>
 *         <li>{@code "test"} — the test body inlined in {@code chain.test.sliced_body},</li>
 *         <li>{@code "target"} — the target body in {@code target.current_body},</li>
 *         <li>any other string — an entry in {@code method_bodies}.</li>
 *       </ul></li>
 *   <li>Inlining the test body in its chain, not as a separate vertex. The 94% of
 *       schema-v1 vertices that were empty test-method name-bags are gone.</li>
 * </ul>
 *
 * <p>On picocli/TextTable.putValue this shrinks the rendered file from ~2.7 MB to
 * ~250 KB while making every field semantically correct.
 */
public final class GraphJsonRenderer {

    private static final String SCHEMA_VERSION = "2";
    private final ObjectMapper M = new ObjectMapper();

    public String render(Artifact a, ProjectGraph g, String projectKey, String projectName) {
        ObjectNode root = M.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);

        ObjectNode meta = root.putObject("generated_for");
        meta.put("project", projectName);
        meta.put("commit_hash_proxy", projectKey);
        meta.put("timestamp", Instant.now().toString());

        ObjectNode target = root.putObject("target");
        target.put("fqn", a.target().fqn());
        target.put("signature", a.target().signature());
        target.put("file", a.target().file());
        target.put("line_start", a.target().lineStart());
        target.put("line_end", a.target().lineEnd());
        target.put("current_body", a.currentBody());

        // Collect method_bodies in chain-traversal order, first occurrence wins.
        // The body of an intermediate method M living at step[k>=1] equals
        // step[k].snippet — which is M's body sliced around its call to step[k].callee.
        // (step[0].caller is the test; its body goes inline in chain.test below.)
        Map<String, MethodEntry> methodBodies = new LinkedHashMap<>();
        for (Chain c : a.chains()) {
            for (int k = 1; k < c.steps().size(); k++) {
                CallStep s = c.steps().get(k);
                String fqn = s.callerFqn();
                if (methodBodies.containsKey(fqn)) continue;
                Node.Method m = lookupMethod(g, s.callerMethodId());
                methodBodies.put(fqn, new MethodEntry(fqn, m, s.snippet() == null ? "" : s.snippet()));
            }
        }
        ObjectNode methodBodiesNode = root.putObject("method_bodies");
        for (var entry : methodBodies.entrySet()) writeMethodEntry(methodBodiesNode, entry.getValue());

        // Chains: each chain owns its test body and a flat list of step records.
        ArrayNode chainsArr = root.putArray("chains");
        int idx = 0;
        for (Chain c : a.chains()) {
            ObjectNode cn = chainsArr.addObject();
            cn.put("id", "chain_" + (idx++));
            cn.put("depth", c.depth());
            cn.put("virtual_steps", c.virtualSteps());

            // Test info + body inline.
            ObjectNode test = cn.putObject("test");
            test.put("fqn", c.test().fqn());
            test.put("file", c.test().file());
            test.put("line", c.test().lineStart());
            String testBody = c.steps().isEmpty() ? ""
                    : (c.steps().get(0).snippet() == null ? "" : c.steps().get(0).snippet());
            test.put("sliced_body", testBody);

            ArrayNode steps = cn.putArray("steps");
            for (int k = 0; k < c.steps().size(); k++) {
                CallStep s = c.steps().get(k);
                ObjectNode sn = steps.addObject();
                sn.put("caller_ref", k == 0 ? "test" : s.callerFqn());
                sn.put("callee_ref",
                        s.calleeMethodId().equals(a.target().id()) ? "target" : s.calleeFqn());

                ObjectNode csn = sn.putObject("call_site");
                CallStep.CallSite cs = s.callSite();
                if (cs != null && cs.file() != null) csn.put("file", cs.file());
                else csn.putNull("file");
                csn.put("line", cs == null ? -1 : cs.line());
                csn.put("column", cs == null ? -1 : cs.column());
                csn.put("code", cs == null ? "" : cs.code());

                ArrayNode args = sn.putArray("args");
                for (ArgOrigin o : s.argOrigins()) args.add(originJson(o));
                sn.put("virtual", s.viaVirtual());
            }
        }

        // Local context — siblings + used types + production call sites.
        ObjectNode lc = root.putObject("local_context");
        ArrayNode sibs = lc.putArray("siblings");
        for (var s : a.localContext().siblings()) {
            ObjectNode sn = sibs.addObject();
            sn.put("signature", s.signature());
            sn.put("javadoc", s.javadoc());
            sn.put("body", s.body());
            sn.put("truncated", s.truncated());
        }
        ArrayNode usedTypes = lc.putArray("used_types");
        for (var u : a.localContext().usedTypes()) {
            ObjectNode un = usedTypes.addObject();
            un.put("fqn", u.type().fqn());
            un.put("kind", u.type().kind().name().toLowerCase());
            if (u.type().enumConstants() != null) {
                ArrayNode ec = un.putArray("enum_constants");
                for (String c : u.type().enumConstants()) ec.add(c);
            }
            ArrayNode sigs = un.putArray("public_method_signatures");
            for (String sig : u.publicMethodSignatures()) sigs.add(sig);
        }

        ObjectNode stats = root.putObject("stats");
        stats.put("total_chains", a.chains().size());
        stats.put("distinct_tests", (int) a.chains().stream().map(Chain::test).distinct().count());
        stats.put("distinct_method_bodies", methodBodies.size());
        stats.put("truncated", a.truncated());

        root.putArray("degradations");

        try {
            // No pretty-printing — graph.json is for LLM consumption, not human reading.
            // Pretty-print roughly doubles file size with no information gain (the
            // human-readable form lives in budget.md / full.md).
            return M.writeValueAsString(root) + "\n";
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private record MethodEntry(String fqn, Node.Method method, String slicedBody) {}

    private void writeMethodEntry(ObjectNode container, MethodEntry e) {
        ObjectNode mb = container.putObject(e.fqn);
        mb.put("fqn", e.fqn);
        mb.put("signature", e.method != null ? e.method.signature() : "");
        if (e.method != null && e.method.file() != null) mb.put("file", e.method.file());
        else mb.putNull("file");
        mb.put("line_start", e.method != null ? e.method.lineStart() : -1);
        mb.put("line_end", e.method != null ? e.method.lineEnd() : -1);
        mb.put("sliced_body", e.slicedBody);
        mb.put("sliced_body_truncated", false);
        mb.putArray("warnings");
    }

    private static Node.Method lookupMethod(ProjectGraph g, String methodId) {
        if (g == null) return null;
        return g.byId(methodId) instanceof Node.Method m ? m : null;
    }

    private ObjectNode originJson(ArgOrigin o) {
        ObjectNode n = M.createObjectNode();
        n.put("index", o.argIndex());
        n.put("origin", o.kind().name().toLowerCase());
        if (o.value() != null) n.put("value", o.value());
        if (o.paramName() != null) n.put("name", o.paramName());
        if (o.exprText() != null) n.put("expr", o.exprText());
        if (o.definedAtLine() > 0) {
            ObjectNode def = n.putObject("defined_at");
            def.put("line", o.definedAtLine());
            if (o.definedAtSnippet() != null) def.put("snippet", o.definedAtSnippet());
        }
        if (o.factoryFqn() != null) n.put("factory_fqn", o.factoryFqn());
        if (o.fieldFqn() != null) n.put("field_fqn", o.fieldFqn());
        return n;
    }
}
