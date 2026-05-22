package com.graphtipper.cpg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphtipper.model.*;
import java.nio.file.*;
import java.util.*;

public final class CpgImporter {

    public ProjectGraph importFrom(Path exportFile) throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.newInputStream(exportFile));
        // joern-export `--format graphson` emits GraphSON v3, where the graph sits under
        // `@value` and every property is wrapped as g:VertexProperty → g:List → scalar
        // (with inner scalars themselves typed g:Int32/g:Int64/...). Older hand-rolled
        // fixtures use a flat, untyped layout. Detect once and unwrap on each access.
        JsonNode container = root.has("@value") ? root.path("@value") : root;
        var g = new ProjectGraph();

        Map<String, JsonNode> rawById = new HashMap<>();
        for (JsonNode v : container.path("vertices")) {
            rawById.put(idOf(v), v);
        }
        Map<String, List<String>> astChildren = new HashMap<>();
        for (JsonNode e : container.path("edges")) {
            if ("AST".equals(labelOf(e))) {
                astChildren.computeIfAbsent(unwrap(e.path("outV")).asText(), k -> new ArrayList<>())
                           .add(unwrap(e.path("inV")).asText());
            }
        }
        Map<String, String> astParent = new HashMap<>();
        for (var entry : astChildren.entrySet()) {
            String parentId = entry.getKey();
            for (String childId : entry.getValue()) {
                astParent.put(childId, parentId);
            }
        }

        // Methods (with @Test detection from explicit IS_TEST property if emitted
        // by our streaming exporter, else by walking AST → ANNOTATION children).
        for (JsonNode v : container.path("vertices")) {
            if (!"METHOD".equals(labelOf(v))) continue;
            String id = idOf(v);
            boolean isTest;
            JsonNode isTestNode = unwrap(v.path("properties").path("IS_TEST"));
            if (isTestNode.isBoolean()) {
                isTest = isTestNode.asBoolean();
            } else {
                isTest = false;
                for (String childId : astChildren.getOrDefault(id, List.of())) {
                    JsonNode ch = rawById.get(childId);
                    if (ch != null && "ANNOTATION".equals(labelOf(ch))) {
                        String aName = propStr(ch, "NAME");
                        String aFqn = propStr(ch, "FULL_NAME");
                        if ("Test".equals(aName) || aFqn.endsWith(".Test")
                                || aFqn.endsWith(".ParameterizedTest")
                                || aFqn.endsWith(".RepeatedTest")) {
                            isTest = true;
                            break;
                        }
                    }
                }
            }
            String fullName = propStr(v, "FULL_NAME");
            String fqnNoSig = fullName.contains(":") ? fullName.substring(0, fullName.indexOf(':')) : fullName;
            var m = new Node.Method(
                    "m:" + fullName,
                    fqnNoSig,
                    propStr(v, "SIGNATURE"),
                    List.of(),
                    "void",
                    propStr(v, "FILENAME"),
                    propInt(v, "LINE_NUMBER", -1),
                    propInt(v, "LINE_NUMBER_END", -1),
                    null,
                    isTest,
                    false,
                    List.of("public"));
            g.addNode(m);
        }

        // Types
        for (JsonNode v : container.path("vertices")) {
            if (!"TYPE_DECL".equals(labelOf(v))) continue;
            var t = new Node.Type(
                    "t:" + propStr(v, "FULL_NAME"),
                    propStr(v, "FULL_NAME"),
                    Node.TypeKind.CLASS,
                    propStr(v, "FILENAME"),
                    -1, -1, null);
            g.addNode(t);
        }

        // CALL nodes
        Map<String, String> callSiteIdByJoernId = new HashMap<>();
        for (JsonNode v : container.path("vertices")) {
            if (!"CALL".equals(labelOf(v))) continue;
            String inMethod = parentMethodIdOf(v, astParent, rawById);
            String calleeFull = propStr(v, "METHOD_FULL_NAME");
            String calleeFqn = calleeFull.contains(":") ? calleeFull.substring(0, calleeFull.indexOf(':')) : calleeFull;
            String csId = "cs:" + idOf(v);
            var cs = new Node.CallSite(csId, inMethod == null ? "" : "m:" + inMethod,
                    calleeFqn, 0,
                    propInt(v, "LINE_NUMBER", -1),
                    propInt(v, "COLUMN_NUMBER", -1),
                    propStr(v, "CODE"));
            g.addNode(cs);
            callSiteIdByJoernId.put(idOf(v), csId);
        }

        // LITERAL nodes
        Map<String, String> litIdByJoernId = new HashMap<>();
        for (JsonNode v : container.path("vertices")) {
            if (!"LITERAL".equals(labelOf(v))) continue;
            String inMethod = parentMethodIdOf(v, astParent, rawById);
            String id = "lit:" + idOf(v);
            var lit = new Node.Literal(id, inMethod == null ? "" : "m:" + inMethod,
                    Node.LiteralKind.OTHER, propStr(v, "CODE"),
                    propInt(v, "LINE_NUMBER", -1));
            g.addNode(lit);
            litIdByJoernId.put(idOf(v), id);
        }

        // METHOD_PARAMETER_IN nodes (Joern's label for formal parameters)
        Map<String, String> paramIdByJoernId = new HashMap<>();
        for (JsonNode v : container.path("vertices")) {
            if (!"METHOD_PARAMETER_IN".equals(labelOf(v))) continue;
            String parentFull = parentMethodIdOf(v, astParent, rawById);
            if (parentFull == null) continue;
            String id = "p:" + idOf(v);
            var p = new Node.Parameter(id, "m:" + parentFull,
                    propStr(v, "NAME"),
                    propStr(v, "TYPE_FULL_NAME"),
                    propInt(v, "INDEX", -1));
            g.addNode(p);
            paramIdByJoernId.put(idOf(v), id);
        }

        // MEMBER nodes (class fields)
        Map<String, String> memberIdByJoernId = new HashMap<>();
        for (JsonNode v : container.path("vertices")) {
            if (!"MEMBER".equals(labelOf(v))) continue;
            String ownerFqn = propStr(v, "OWNER_TYPE_FULL_NAME");
            String id = "f:" + idOf(v);
            int line = propInt(v, "LINE_NUMBER", -1);
            var field = new Node.Field(id, ownerFqn,
                    propStr(v, "NAME"),
                    propStr(v, "TYPE_FULL_NAME"),
                    List.of(),
                    line, line);
            g.addNode(field);
            memberIdByJoernId.put(idOf(v), id);
        }

        // RETURN statements
        Map<String, String> stmtIdByJoernId = new HashMap<>();
        for (JsonNode v : container.path("vertices")) {
            if (!"RETURN".equals(labelOf(v))) continue;
            String parentFull = parentMethodIdOf(v, astParent, rawById);
            String id = "s:" + idOf(v);
            var s = new Node.Stmt(id, parentFull == null ? "" : "m:" + parentFull,
                    propInt(v, "LINE_NUMBER", -1),
                    Node.StmtKind.RETURN,
                    propStr(v, "CODE"));
            g.addNode(s);
            stmtIdByJoernId.put(idOf(v), id);
        }

        // CONTROL_STRUCTURE: if / for / while / try / etc.
        for (JsonNode v : container.path("vertices")) {
            if (!"CONTROL_STRUCTURE".equals(labelOf(v))) continue;
            String parentFull = parentMethodIdOf(v, astParent, rawById);
            String id = "s:" + idOf(v);
            Node.StmtKind kind = stmtKindFor(propStr(v, "CONTROL_STRUCTURE_TYPE"));
            var s = new Node.Stmt(id, parentFull == null ? "" : "m:" + parentFull,
                    propInt(v, "LINE_NUMBER", -1),
                    kind,
                    propStr(v, "CODE"));
            g.addNode(s);
            stmtIdByJoernId.put(idOf(v), id);
        }

        // Resolve a Joern vertex id to a ProjectGraph node id, regardless of vertex kind.
        java.util.function.Function<String, String> nodeIdOf = (String joernId) -> {
            String r = callSiteIdByJoernId.get(joernId);
            if (r != null) return r;
            r = litIdByJoernId.get(joernId);
            if (r != null) return r;
            r = paramIdByJoernId.get(joernId);
            if (r != null) return r;
            r = memberIdByJoernId.get(joernId);
            if (r != null) return r;
            r = stmtIdByJoernId.get(joernId);
            if (r != null) return r;
            JsonNode v = rawById.get(joernId);
            if (v == null) return null;
            String label = labelOf(v);
            if ("METHOD".equals(label)) return "m:" + propStr(v, "FULL_NAME");
            if ("TYPE_DECL".equals(label)) return "t:" + propStr(v, "FULL_NAME");
            return null;
        };

        // Edges
        for (JsonNode e : container.path("edges")) {
            String lbl = labelOf(e);
            String src = unwrap(e.path("outV")).asText();
            String dst = unwrap(e.path("inV")).asText();
            switch (lbl) {
                case "CALL" -> {
                    String csId = callSiteIdByJoernId.get(src);
                    JsonNode dstNode = rawById.get(dst);
                    if (csId == null || dstNode == null) break;
                    if (!"METHOD".equals(labelOf(dstNode))) break;
                    String dstFull = propStr(dstNode, "FULL_NAME");
                    String csInMethod = ((Node.CallSite) g.byId(csId)).inMethodId();
                    if (!csInMethod.isEmpty()) {
                        g.addEdge(new Edge.Calls(csInMethod, "m:" + dstFull, false));
                    }
                }
                case "REACHING_DEF" -> {
                    String s = litIdByJoernId.getOrDefault(src, callSiteIdByJoernId.getOrDefault(src, null));
                    String d = callSiteIdByJoernId.getOrDefault(dst, litIdByJoernId.getOrDefault(dst, null));
                    if (s != null && d != null) g.addEdge(new Edge.Ddg(s, d));
                }
                case "AST" -> {
                    // Only keep AST edges where both endpoints are nodes the model represents;
                    // intermediate IDENTIFIER/BLOCK/etc. are dropped on purpose.
                    String s = nodeIdOf.apply(src);
                    String d = nodeIdOf.apply(dst);
                    if (s != null && d != null) g.addEdge(new Edge.AstContains(s, d));
                }
                case "CDG" -> {
                    String s = nodeIdOf.apply(src);
                    String d = nodeIdOf.apply(dst);
                    if (s != null && d != null) g.addEdge(new Edge.Cdg(s, d));
                }
                case "OVERRIDES" -> {
                    String s = nodeIdOf.apply(src);
                    String d = nodeIdOf.apply(dst);
                    if (s != null && d != null) g.addEdge(new Edge.Overrides(s, d));
                }
                case "INHERITS_FROM" -> {
                    String s = nodeIdOf.apply(src);
                    String d = nodeIdOf.apply(dst);
                    if (s != null && d != null) g.addEdge(new Edge.RefType(s, d));
                }
                case "READS" -> {
                    String s = nodeIdOf.apply(src);
                    String d = nodeIdOf.apply(dst);
                    if (s != null && d != null) g.addEdge(new Edge.Reads(s, d));
                }
                case "WRITES" -> {
                    String s = nodeIdOf.apply(src);
                    String d = nodeIdOf.apply(dst);
                    if (s != null && d != null) g.addEdge(new Edge.Writes(s, d));
                }
                default -> {}
            }
        }

        // Synthesize AstContains(method → child) for every CallSite/Literal/Parameter we
        // loaded. Joern's AST edges only connect methods to their *direct* AST children
        // (BLOCK, METHOD_PARAMETER_IN, ANNOTATION) — CALL and LITERAL are deep descendants,
        // so without this synthesis there is no way for a viz traversal to step from a
        // Method into its body. PARENT_METHOD_ID is already exported on every CALL/LITERAL/
        // METHOD_PARAMETER_IN, so we reuse what we have. Dedupe against any AST edges that
        // already produced the same parent→child pair.
        java.util.Set<String> astSeen = new java.util.HashSet<>();
        for (Node from : g.allNodes()) {
            for (Edge ex : g.outgoing(from.id())) {
                if (ex instanceof Edge.AstContains ac) astSeen.add(ac.fromId() + "→" + ac.toId());
            }
        }
        java.util.List<Node> snapshot = new java.util.ArrayList<>(g.allNodes());
        for (Node n : snapshot) {
            String parent = switch (n) {
                case Node.CallSite cs -> cs.inMethodId();
                case Node.Literal lit -> lit.inMethodId();
                case Node.Parameter p -> p.ownerMethodId();
                case Node.Stmt s -> s.inMethodId();
                default -> null;
            };
            if (parent == null || parent.isEmpty()) continue;
            if (!(g.byId(parent) instanceof Node.Method)) continue;
            String key = parent + "→" + n.id();
            if (astSeen.add(key)) {
                g.addEdge(new Edge.AstContains(parent, n.id()));
            }
        }

        return g;
    }

    private static String idOf(JsonNode v) { return unwrap(v.path("id")).asText(); }

    private static String labelOf(JsonNode v) { return unwrap(v.path("label")).asText(); }

    private static String propStr(JsonNode vertex, String key) {
        String raw = unwrap(vertex.path("properties").path(key)).asText("");
        return "FILENAME".equals(key) ? unstagePath(raw) : raw;
    }

    /** Reverse the rename applied by {@link ProjectStager} so downstream code sees
     *  original project paths (e.g. {@code src/test/...} instead of {@code src/__t__/...}). */
    static String unstagePath(String path) {
        if (path.isEmpty()) return path;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            final String component = parts[i];
            String replacement = ProjectStager.COMPONENT_RENAMES.entrySet().stream()
                    .filter(e -> e.getValue().equals(component))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            if (replacement != null) parts[i] = replacement;
        }
        return String.join("/", parts);
    }

    private static Node.StmtKind stmtKindFor(String controlStructureType) {
        if (controlStructureType == null) return Node.StmtKind.OTHER;
        return switch (controlStructureType) {
            case "IF", "ELSE", "SWITCH" -> Node.StmtKind.IF;
            case "FOR", "WHILE", "DO" -> Node.StmtKind.LOOP;
            case "RETURN" -> Node.StmtKind.RETURN;
            default -> Node.StmtKind.OTHER;
        };
    }

    private static int propInt(JsonNode vertex, String key, int defaultValue) {
        JsonNode n = unwrap(vertex.path("properties").path(key));
        return n.isNumber() ? n.asInt() : (n.isTextual() ? n.asInt(defaultValue) : defaultValue);
    }

    /**
     * Unwrap GraphSON v3 typed wrappers iteratively: g:VertexProperty → its @value,
     * g:List → first element, g:Int32/g:Int64/etc → their scalar @value. Returns the
     * node unchanged when there's nothing to unwrap (covers the untyped fixture path).
     */
    private static JsonNode unwrap(JsonNode node) {
        while (node != null && node.isObject() && node.has("@type") && node.has("@value")) {
            String type = node.path("@type").asText();
            JsonNode val = node.path("@value");
            if ("g:List".equals(type) || "g:Set".equals(type)) {
                if (val.isArray() && val.size() > 0) { node = val.get(0); continue; }
                return val;
            }
            node = val;
        }
        return node;
    }

    /**
     * Resolve the FULL_NAME of the METHOD containing this CALL/LITERAL vertex.
     * Prefers the precomputed PARENT_METHOD_ID property (emitted by our streaming
     * exporter); falls back to walking AST→...→METHOD for legacy fixtures that
     * include full AST.
     */
    private String parentMethodIdOf(JsonNode vertex, Map<String, String> astParent,
                                    Map<String, JsonNode> rawById) {
        JsonNode pmid = unwrap(vertex.path("properties").path("PARENT_METHOD_ID"));
        if (!pmid.isMissingNode() && !pmid.isNull()) {
            String parentId = pmid.asText("");
            if (!parentId.isEmpty() && !"-1".equals(parentId)) {
                JsonNode parent = rawById.get(parentId);
                if (parent != null && "METHOD".equals(labelOf(parent))) {
                    return propStr(parent, "FULL_NAME");
                }
            }
        }
        String parentId = astParent.get(idOf(vertex));
        while (parentId != null) {
            JsonNode parent = rawById.get(parentId);
            if (parent == null) return null;
            if ("METHOD".equals(labelOf(parent))) {
                return propStr(parent, "FULL_NAME");
            }
            parentId = astParent.get(parentId);
        }
        return null;
    }
}
