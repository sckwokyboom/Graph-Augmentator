package com.graphtipper.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphtipper.model.Node;
import com.graphtipper.slice.*;

import java.time.Instant;
import java.util.*;

/**
 * Emits a flat vertices+edges+chains JSON document tailored for LLM consumption.
 * Schema: see {@code src/test/resources/graph-schema.json}.
 *
 * <p>Design points worth knowing when extending:
 * <ul>
 *   <li>Vertices are deduplicated by id; intermediate methods shared across chains
 *       appear once. The id scheme is stable across runs ({@code v_test_<fqn>} /
 *       {@code v_method_<fqn>} / literal {@code "target"}).</li>
 *   <li>Edges are keyed by {@code (from, to, call-site-file, call-site-line, call-site-snippet)}
 *       so two different lines in the same method that both call the same callee produce
 *       two edges. Edge ids are sequential {@code e_N} and not used as the dedup key.</li>
 *   <li>Chains duplicate path info via {@code path} (vertex ids) and {@code edges}
 *       (edge ids) — redundant on purpose, both are useful for an LLM.</li>
 * </ul>
 */
public final class GraphJsonRenderer {

    private static final String SCHEMA_VERSION = "1";
    private final ObjectMapper M = new ObjectMapper();

    public String render(Artifact a, String projectKey, String projectName) {
        ObjectNode root = M.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);

        ObjectNode meta = root.putObject("generated_for");
        meta.put("project", projectName);
        meta.put("commit_hash_proxy", projectKey);
        meta.put("timestamp", Instant.now().toString());

        ObjectNode target = root.putObject("target");
        target.put("id", "target");
        target.put("fqn", a.target().fqn());
        target.put("signature", a.target().signature());
        target.put("file", a.target().file());
        target.put("line_start", a.target().lineStart());
        target.put("line_end", a.target().lineEnd());
        target.put("current_body", a.currentBody());

        // Method registry: collected up front so vertex emission and chain rendering
        // both have access to file/line for any method id referenced in a CallStep.
        Map<String, Node.Method> methodRegistry = new HashMap<>();
        methodRegistry.put(a.target().id(), a.target());
        for (Chain c : a.chains()) {
            methodRegistry.put(c.test().id(), c.test());
            for (CallStep s : c.steps()) {
                methodRegistry.computeIfAbsent(s.calleeMethodId(),
                        id -> synthesize(id, s.calleeFqn()));
                methodRegistry.computeIfAbsent(s.callerMethodId(),
                        id -> synthesize(id, s.callerFqn()));
            }
        }

        // ---- Vertices ----
        // Test vertices and intermediate-method vertices; deduplicated by id.
        Map<String, ObjectNode> vertices = new LinkedHashMap<>();
        Map<String, String> snippetForMethodId = new HashMap<>();
        for (Chain c : a.chains()) {
            String tid = idOfMethod(c.test(), true);
            vertices.computeIfAbsent(tid, k -> vertex(k, "test_method", c.test(), ""));
            for (CallStep s : c.steps()) {
                if (s.calleeMethodId().equals(a.target().id())) continue;
                Node.Method callee = methodRegistry.get(s.calleeMethodId());
                if (callee == null) continue;
                String vid = idOfMethod(callee, false);
                // Snippet attached to the vertex is the snippet of the callee's body as
                // seen from this incoming call. Use the first call step that targets this
                // method — earlier chains had higher rank so their snippet is preferred.
                snippetForMethodId.putIfAbsent(callee.id(), s.snippet() != null ? s.snippet() : "");
                vertices.computeIfAbsent(vid,
                        k -> vertex(k, "intermediate_method", callee,
                                snippetForMethodId.getOrDefault(callee.id(), "")));
            }
        }
        ArrayNode vertsArr = root.putArray("vertices");
        for (ObjectNode v : vertices.values()) vertsArr.add(v);

        // ---- Edges ----
        // Dedup by (from, to, snippet-hash) — two distinct call sites between the same
        // pair (different lines / different snippet text) become distinct edges.
        Map<String, ObjectNode> edges = new LinkedHashMap<>();
        List<String> orderedEdgeKeys = new ArrayList<>();
        for (Chain c : a.chains()) {
            for (CallStep s : c.steps()) {
                String from = idOfCallStepEndpoint(s.callerMethodId(), s.callerFqn(), methodRegistry);
                String to = s.calleeMethodId().equals(a.target().id()) ? "target"
                        : "v_method_" + sanitize(s.calleeFqn());
                String tupleKey = edgeTupleKey(from, to, s);
                if (!edges.containsKey(tupleKey)) {
                    ObjectNode e = M.createObjectNode();
                    e.put("id", "e_" + edges.size());
                    e.put("from", from);
                    e.put("to", to);
                    e.put("kind", "calls");
                    ObjectNode cs = e.putObject("call_site");
                    Node.Method caller = methodRegistry.get(s.callerMethodId());
                    cs.put("file", caller != null ? caller.file() : null);
                    cs.put("line", -1);
                    cs.put("code", firstLineOf(s.snippet()));
                    ArrayNode args = e.putArray("args");
                    for (ArgOrigin origin : s.argOrigins()) args.add(originJson(origin));
                    e.put("virtual", s.viaVirtual());
                    edges.put(tupleKey, e);
                    orderedEdgeKeys.add(tupleKey);
                }
            }
        }
        ArrayNode edgesArr = root.putArray("edges");
        for (String key : orderedEdgeKeys) edgesArr.add(edges.get(key));

        // ---- Chains ----
        ArrayNode chainsArr = root.putArray("chains");
        int idx = 0;
        for (Chain c : a.chains()) {
            ObjectNode cn = chainsArr.addObject();
            cn.put("id", "chain_" + idx++);
            cn.put("depth", c.depth());
            cn.put("virtual_steps", c.virtualSteps());

            ArrayNode path = cn.putArray("path");
            path.add(idOfMethod(c.test(), true));
            for (CallStep s : c.steps()) {
                if (s.calleeMethodId().equals(a.target().id())) {
                    path.add("target");
                } else {
                    Node.Method callee = methodRegistry.get(s.calleeMethodId());
                    String fqn = callee != null ? callee.fqn() : s.calleeFqn();
                    path.add("v_method_" + sanitize(fqn));
                }
            }

            ArrayNode edgeIds = cn.putArray("edges");
            for (CallStep s : c.steps()) {
                String from = idOfCallStepEndpoint(s.callerMethodId(), s.callerFqn(), methodRegistry);
                String to = s.calleeMethodId().equals(a.target().id()) ? "target"
                        : "v_method_" + sanitize(s.calleeFqn());
                ObjectNode edgeNode = edges.get(edgeTupleKey(from, to, s));
                if (edgeNode != null) edgeIds.add(edgeNode.get("id").asText());
            }
        }

        // ---- Stats ----
        ObjectNode stats = root.putObject("stats");
        stats.put("total_chains", a.chains().size());
        stats.put("distinct_tests", (int) a.chains().stream().map(Chain::test).distinct().count());
        stats.put("vertices", vertices.size());
        stats.put("edges", edges.size());
        stats.put("truncated", a.truncated());

        root.putArray("degradations");

        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String idOfMethod(Node.Method m, boolean isTest) {
        String prefix = isTest ? "v_test_" : "v_method_";
        return prefix + sanitize(m.fqn());
    }

    private static String idOfCallStepEndpoint(String methodId, String fallbackFqn,
                                                Map<String, Node.Method> registry) {
        Node.Method m = registry.get(methodId);
        boolean isTest = m != null && m.isTest();
        String prefix = isTest ? "v_test_" : "v_method_";
        return prefix + sanitize(m != null ? m.fqn() : fallbackFqn);
    }

    private static String sanitize(String fqn) {
        return fqn.replace('.', '_').replace('$', '_');
    }

    private static String edgeTupleKey(String from, String to, CallStep s) {
        return from + "->" + to + "#" + Integer.toHexString(Objects.hashCode(s.snippet()));
    }

    private ObjectNode vertex(String id, String kind, Node.Method m, String snippet) {
        ObjectNode v = M.createObjectNode();
        v.put("id", id);
        v.put("kind", kind);
        v.put("fqn", m.fqn());
        v.put("file", m.file());
        v.put("line", m.lineStart());
        v.put("snippet", snippet);
        v.put("snippet_truncated", false);
        v.putArray("warnings");
        return v;
    }

    private static Node.Method synthesize(String id, String fqn) {
        return new Node.Method(id, fqn, "()", List.of(), "void",
                "(unknown)", -1, -1, null, false, false, List.of());
    }

    private static String firstLineOf(String snippet) {
        if (snippet == null) return "";
        int nl = snippet.indexOf('\n');
        return nl < 0 ? snippet : snippet.substring(0, nl);
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
