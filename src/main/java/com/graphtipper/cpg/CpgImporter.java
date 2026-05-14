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
                default -> {}
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
