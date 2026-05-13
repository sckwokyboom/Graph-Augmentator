package com.graphtipper.cpg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphtipper.model.*;
import java.nio.file.*;
import java.util.*;

public final class CpgImporter {

    public ProjectGraph importFrom(Path exportFile) throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.newInputStream(exportFile));
        var g = new ProjectGraph();

        Map<String, JsonNode> rawById = new HashMap<>();
        for (JsonNode v : root.path("vertices")) {
            rawById.put(v.path("id").asText(), v);
        }
        Map<String, List<String>> astChildren = new HashMap<>();
        for (JsonNode e : root.path("edges")) {
            if ("AST".equals(e.path("label").asText())) {
                astChildren.computeIfAbsent(e.path("outV").asText(), k -> new ArrayList<>())
                           .add(e.path("inV").asText());
            }
        }
        Map<String, String> astParent = new HashMap<>();
        for (var entry : astChildren.entrySet()) {
            String parentId = entry.getKey();
            for (String childId : entry.getValue()) {
                astParent.put(childId, parentId);
            }
        }

        // Methods (with @Test detection via AST annotation children)
        for (JsonNode v : root.path("vertices")) {
            if (!"METHOD".equals(v.path("label").asText())) continue;
            String id = v.path("id").asText();
            boolean isTest = false;
            for (String childId : astChildren.getOrDefault(id, List.of())) {
                JsonNode ch = rawById.get(childId);
                if (ch != null && "ANNOTATION".equals(ch.path("label").asText())) {
                    String aName = ch.path("properties").path("NAME").asText();
                    String aFqn = ch.path("properties").path("FULL_NAME").asText();
                    if ("Test".equals(aName) || aFqn.endsWith(".Test")
                            || aFqn.endsWith(".ParameterizedTest")
                            || aFqn.endsWith(".RepeatedTest")) {
                        isTest = true;
                        break;
                    }
                }
            }
            JsonNode p = v.path("properties");
            String fullName = p.path("FULL_NAME").asText();
            String fqnNoSig = fullName.contains(":") ? fullName.substring(0, fullName.indexOf(':')) : fullName;
            var m = new Node.Method(
                    "m:" + fullName,
                    fqnNoSig,
                    p.path("SIGNATURE").asText(),
                    List.of(),
                    "void",
                    p.path("FILENAME").asText(),
                    p.path("LINE_NUMBER").asInt(-1),
                    p.path("LINE_NUMBER_END").asInt(-1),
                    null,
                    isTest,
                    false,
                    List.of("public"));
            g.addNode(m);
        }

        // Types
        for (JsonNode v : root.path("vertices")) {
            if (!"TYPE_DECL".equals(v.path("label").asText())) continue;
            JsonNode p = v.path("properties");
            var t = new Node.Type(
                    "t:" + p.path("FULL_NAME").asText(),
                    p.path("FULL_NAME").asText(),
                    Node.TypeKind.CLASS,
                    p.path("FILENAME").asText(),
                    -1, -1, null);
            g.addNode(t);
        }

        // CALL nodes
        Map<String, String> callSiteIdByJoernId = new HashMap<>();
        for (JsonNode v : root.path("vertices")) {
            if (!"CALL".equals(v.path("label").asText())) continue;
            JsonNode p = v.path("properties");
            String inMethod = parentMethodOf(v.path("id").asText(), astParent, rawById);
            String calleeFull = p.path("METHOD_FULL_NAME").asText();
            String calleeFqn = calleeFull.contains(":") ? calleeFull.substring(0, calleeFull.indexOf(':')) : calleeFull;
            String csId = "cs:" + v.path("id").asText();
            var cs = new Node.CallSite(csId, inMethod == null ? "" : "m:" + inMethod,
                    calleeFqn, 0,
                    p.path("LINE_NUMBER").asInt(-1),
                    p.path("COLUMN_NUMBER").asInt(-1),
                    p.path("CODE").asText());
            g.addNode(cs);
            callSiteIdByJoernId.put(v.path("id").asText(), csId);
        }

        // LITERAL nodes
        Map<String, String> litIdByJoernId = new HashMap<>();
        for (JsonNode v : root.path("vertices")) {
            if (!"LITERAL".equals(v.path("label").asText())) continue;
            JsonNode p = v.path("properties");
            String inMethod = parentMethodOf(v.path("id").asText(), astParent, rawById);
            String id = "lit:" + v.path("id").asText();
            var lit = new Node.Literal(id, inMethod == null ? "" : "m:" + inMethod,
                    Node.LiteralKind.OTHER, p.path("CODE").asText(),
                    p.path("LINE_NUMBER").asInt(-1));
            g.addNode(lit);
            litIdByJoernId.put(v.path("id").asText(), id);
        }

        // Edges
        for (JsonNode e : root.path("edges")) {
            String lbl = e.path("label").asText();
            String src = e.path("outV").asText();
            String dst = e.path("inV").asText();
            switch (lbl) {
                case "CALL" -> {
                    String csId = callSiteIdByJoernId.get(src);
                    JsonNode dstNode = rawById.get(dst);
                    if (csId == null || dstNode == null) break;
                    if (!"METHOD".equals(dstNode.path("label").asText())) break;
                    String dstFull = dstNode.path("properties").path("FULL_NAME").asText();
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

    private String parentMethodOf(String childId, Map<String, String> astParent,
                                  Map<String, JsonNode> rawById) {
        String parentId = astParent.get(childId);
        while (parentId != null) {
            JsonNode parent = rawById.get(parentId);
            if (parent == null) return null;
            if ("METHOD".equals(parent.path("label").asText())) {
                return parent.path("properties").path("FULL_NAME").asText();
            }
            parentId = astParent.get(parentId);
        }
        return null;
    }
}
