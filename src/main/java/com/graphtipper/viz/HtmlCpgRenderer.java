package com.graphtipper.viz;

import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a full {@link ProjectGraph} as a single self-contained HTML page. The page loads
 * Cytoscape.js from a CDN and inlines the entire graph as JSON; opening it in a browser
 * gives you an interactive view (drag, pan, zoom, edge-kind toggles, hover tooltips with
 * a description for every node/edge kind, search, and an in-browser chop highlighter).
 */
public final class HtmlCpgRenderer {

    private static final String CYTOSCAPE_CDN =
            "https://cdn.jsdelivr.net/npm/cytoscape@3.30.2/dist/cytoscape.min.js";

    private static final Map<String, NodeKindMeta> NODE_KINDS = nodeKinds();
    private static final Map<String, EdgeKindMeta> EDGE_KINDS = edgeKinds();
    private static final Map<String, String> NODE_DESCRIPTIONS = descriptions(NODE_KINDS, m -> m.description);
    private static final Map<String, String> EDGE_DESCRIPTIONS = descriptions(EDGE_KINDS, m -> m.description);

    public String render(ProjectGraph graph, String projectName) {
        StringBuilder json = new StringBuilder(64 * 1024);
        emitJson(graph, json);
        String safe = json.toString().replace("</", "<\\/");

        StringBuilder html = new StringBuilder(80 * 1024);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<title>CPG inspector — ").append(escapeHtml(projectName)).append("</title>\n");
        html.append("<script src=\"").append(CYTOSCAPE_CDN).append("\"></script>\n");
        html.append("<style>").append(css()).append("</style>\n");
        html.append("</head>\n<body>\n");
        html.append(sidebar(graph, projectName));
        // The overlay lives INSIDE #graph so its `position: absolute; inset: 0`
        // is anchored to the graph canvas only and does not eclipse the sidebar
        // (where the Highlight-chop input actually lives).
        html.append("<div id=\"graph\">\n");
        html.append("  <div id=\"graph-overlay\" class=\"graph-overlay\">\n");
        html.append("    <div class=\"graph-overlay-inner\">\n");
        html.append("      <h2>Pick a target method to render its chop</h2>\n");
        html.append("      <p>This project's CPG has <b id=\"overlay-node-count\">?</b> nodes — laying it out as a single force-directed graph would take ~tens of seconds and the result would be unreadable anyway. Use the <b>Highlight chop</b> panel on the left: type or pick a method FQN, then press <b>Highlight</b>. You'll see only that chop, laid out cleanly in &lt;1 s.</p>\n");
        html.append("      <p class=\"hint\">If you really need the full graph (slow on big projects), use the button below.</p>\n");
        html.append("      <button id=\"render-full-btn\">Render full graph anyway</button>\n");
        html.append("    </div>\n");
        html.append("  </div>\n");
        html.append("</div>\n");
        html.append("<div id=\"tooltip\" class=\"tooltip\" hidden></div>\n");
        html.append("<script>\nconst DATA = ").append(safe).append(";\n");
        html.append("const NODE_DESCRIPTIONS = ").append(asJsObject(NODE_DESCRIPTIONS)).append(";\n");
        html.append("const EDGE_DESCRIPTIONS = ").append(asJsObject(EDGE_DESCRIPTIONS)).append(";\n");
        html.append(scriptBody());
        html.append("\n</script>\n</body>\n</html>\n");
        return html.toString();
    }

    // ---- JSON emission ----------------------------------------------------------------

    private static void emitJson(ProjectGraph graph, StringBuilder out) {
        out.append("{\n  \"nodes\": [");
        boolean first = true;
        for (Node n : graph.allNodes()) {
            if (!first) out.append(',');
            first = false;
            out.append("\n    ");
            emitNode(n, out);
        }
        out.append("\n  ],\n  \"edges\": [");
        first = true;
        int edgeSeq = 0;
        for (Node n : graph.allNodes()) {
            for (Edge e : graph.outgoing(n.id())) {
                if (!first) out.append(',');
                first = false;
                out.append("\n    ");
                emitEdge(e, edgeSeq++, out);
            }
        }
        out.append("\n  ]\n}");
    }

    private static void emitNode(Node n, StringBuilder out) {
        out.append("{\"data\":{");
        out.append("\"id\":").append(jstr(n.id()));
        out.append(",\"kind\":").append(jstr(nodeKindOf(n)));
        out.append(",\"label\":").append(jstr(shortLabel(n)));
        switch (n) {
            case Node.Method m -> {
                out.append(",\"fqn\":").append(jstr(m.fqn()));
                out.append(",\"signature\":").append(jstr(m.signature()));
                if (m.file() != null) out.append(",\"file\":").append(jstr(m.file()));
                out.append(",\"line\":").append(m.lineStart());
                out.append(",\"isTest\":").append(m.isTest());
            }
            case Node.Type t -> {
                out.append(",\"fqn\":").append(jstr(t.fqn()));
                if (t.file() != null) out.append(",\"file\":").append(jstr(t.file()));
            }
            case Node.Field f -> {
                out.append(",\"name\":").append(jstr(f.name()));
                out.append(",\"type\":").append(jstr(f.type()));
                out.append(",\"owner\":").append(jstr(f.ownerTypeFqn()));
            }
            case Node.Parameter p -> {
                out.append(",\"name\":").append(jstr(p.name()));
                out.append(",\"type\":").append(jstr(p.type()));
                out.append(",\"index\":").append(p.index());
            }
            case Node.CallSite cs -> {
                out.append(",\"callee\":").append(jstr(cs.calleeFqn()));
                if (cs.codeSnippet() != null && !cs.codeSnippet().isEmpty()) {
                    out.append(",\"code\":").append(jstr(truncate(cs.codeSnippet(), 200)));
                }
                out.append(",\"line\":").append(cs.line());
            }
            case Node.Literal lit -> {
                out.append(",\"value\":").append(jstr(truncate(lit.value(), 80)));
                out.append(",\"line\":").append(lit.line());
            }
            case Node.Stmt s -> {
                out.append(",\"stmtKind\":").append(jstr(String.valueOf(s.kind())));
                if (s.codeSnippet() != null) {
                    out.append(",\"code\":").append(jstr(truncate(s.codeSnippet(), 200)));
                }
                out.append(",\"line\":").append(s.line());
            }
        }
        out.append("}}");
    }

    private static void emitEdge(Edge e, int seq, StringBuilder out) {
        out.append("{\"data\":{");
        out.append("\"id\":\"e").append(seq).append('"');
        out.append(",\"source\":").append(jstr(e.fromId()));
        out.append(",\"target\":").append(jstr(e.toId()));
        out.append(",\"kind\":").append(jstr(edgeKindOf(e)));
        out.append("}}");
    }

    private static String nodeKindOf(Node n) {
        return switch (n) {
            case Node.Method m -> m.isTest() ? "TestMethod" : "Method";
            case Node.Type __ -> "Type";
            case Node.Field __ -> "Field";
            case Node.Parameter __ -> "Parameter";
            case Node.CallSite __ -> "CallSite";
            case Node.Literal __ -> "Literal";
            case Node.Stmt __ -> "Stmt";
        };
    }

    private static String edgeKindOf(Edge e) {
        return switch (e) {
            case Edge.Calls __ -> "Calls";
            case Edge.AstContains __ -> "AstContains";
            case Edge.Ddg __ -> "Ddg";
            case Edge.Cdg __ -> "Cdg";
            case Edge.Reads __ -> "Reads";
            case Edge.Writes __ -> "Writes";
            case Edge.RefType __ -> "RefType";
            case Edge.Overrides __ -> "Overrides";
        };
    }

    private static String shortLabel(Node n) {
        return switch (n) {
            case Node.Method m -> simpleNameOf(m.fqn()) + "()";
            case Node.Type t -> simpleNameOf(t.fqn());
            case Node.Field f -> f.name();
            case Node.Parameter p -> p.name() + ":" + simpleNameOf(p.type());
            case Node.CallSite cs -> truncate(
                    cs.codeSnippet() != null && !cs.codeSnippet().isEmpty()
                            ? cs.codeSnippet()
                            : simpleNameOf(cs.calleeFqn()) + "(…)", 50);
            case Node.Literal lit -> truncate(lit.value(), 30);
            case Node.Stmt s -> truncate(s.codeSnippet(), 30);
        };
    }

    private static String simpleNameOf(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "";
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    // ---- Sidebar ----------------------------------------------------------------------

    private static String sidebar(ProjectGraph graph, String projectName) {
        int nodeCount = 0, edgeCount = 0;
        Map<String, Integer> nodeCounts = new LinkedHashMap<>();
        Map<String, Integer> edgeCounts = new LinkedHashMap<>();
        for (Node nd : graph.allNodes()) {
            nodeCount++;
            nodeCounts.merge(nodeKindOf(nd), 1, Integer::sum);
            for (Edge ed : graph.outgoing(nd.id())) {
                edgeCount++;
                edgeCounts.merge(edgeKindOf(ed), 1, Integer::sum);
            }
        }

        StringBuilder s = new StringBuilder(8 * 1024);
        s.append("<aside id=\"sidebar\">\n");
        s.append("  <h1>CPG inspector</h1>\n");
        s.append("  <p class=\"project\">").append(escapeHtml(projectName)).append("</p>\n");
        s.append("  <p class=\"stats\">").append(nodeCount).append(" nodes · ").append(edgeCount).append(" edges</p>\n");

        s.append("  <h2>Node types</h2>\n  <ul class=\"legend\">\n");
        for (var entry : NODE_KINDS.entrySet()) {
            String kind = entry.getKey();
            NodeKindMeta meta = entry.getValue();
            int count = nodeCounts.getOrDefault(kind, 0);
            s.append("    <li title=\"").append(escapeHtml(meta.description)).append("\">")
             .append("<span class=\"swatch swatch-node ").append(meta.cssClass).append("\"></span>")
             .append("<span class=\"name\">").append(kind).append("</span>")
             .append("<span class=\"count\">").append(count).append("</span></li>\n");
        }
        s.append("  </ul>\n");

        s.append("  <h2>Edge types <span class=\"hint\">(click to toggle)</span></h2>\n  <ul class=\"legend edges\">\n");
        for (var entry : EDGE_KINDS.entrySet()) {
            String kind = entry.getKey();
            EdgeKindMeta meta = entry.getValue();
            int count = edgeCounts.getOrDefault(kind, 0);
            s.append("    <li data-edge-kind=\"").append(kind).append("\" title=\"")
             .append(escapeHtml(meta.description)).append("\">")
             .append("<span class=\"swatch swatch-edge\" style=\"background:").append(meta.color).append(";\"></span>")
             .append("<span class=\"name\">").append(kind).append("</span>")
             .append("<span class=\"count\">").append(count).append("</span></li>\n");
        }
        s.append("  </ul>\n");

        s.append("  <h2>Highlight chop <span class=\"hint\">(asserts → target)</span></h2>\n");
        s.append("  <div class=\"chop-panel\">\n");
        s.append("    <input id=\"chop-target\" list=\"chop-target-list\" type=\"search\" placeholder=\"Method FQN, e.g. toy.Counter.multiply\">\n");
        s.append("    <datalist id=\"chop-target-list\"></datalist>\n");
        s.append("    <div class=\"chop-buttons\">\n");
        s.append("      <button id=\"chop-go\">Highlight</button>\n");
        s.append("      <button id=\"chop-clear\">Clear</button>\n");
        s.append("    </div>\n");
        s.append("    <label class=\"chop-mode\" title=\"Off: dim non-chop elements (keep them visible, greyed). On: hide them completely from the canvas — much lighter to render for big graphs.\">")
         .append("<input id=\"chop-isolate\" type=\"checkbox\"> Isolate (hide non-chop)</label>\n");
        s.append("    <button id=\"chop-download\" class=\"chop-download\" disabled title=\"Human-readable .txt of every test→target chain in the current chop, with node and edge metadata. Good for eyeballing against source code.\">Download chains (.txt)</button>\n");
        s.append("    <button id=\"chop-download-llm\" class=\"chop-download\" disabled title=\"GraphRAG-style markdown augmentation: typed entities (M/T/C/A/L/F refs), call paths, data flow, field accesses, overrides. Drop into an LLM prompt as code-slice context.\">Download LLM context (.md)</button>\n");
        s.append("    <button id=\"chop-download-json\" class=\"chop-download\" disabled title=\"Same slice in Microsoft GraphRAG-style JSON: entities[] with type/title/description/attributes + relationships[] with source/target/type. Machine-readable; ideal as input to RAG pipelines, function-calling tools, or programmatic LLM workflows.\">Download GraphRAG JSON (.json)</button>\n");
        s.append("    <p id=\"chop-summary\" class=\"chop-summary\">—</p>\n");
        s.append("  </div>\n");

        s.append("  <h2>Search</h2>\n");
        s.append("  <input id=\"search\" type=\"search\" placeholder=\"Filter by name…\">\n");

        s.append("  <h2>Selected</h2>\n");
        s.append("  <pre id=\"details\">(hover or click a node)</pre>\n");
        s.append("</aside>\n");
        return s.toString();
    }

    // ---- Metadata ---------------------------------------------------------------------

    private record NodeKindMeta(String cssClass, String description) {}
    private record EdgeKindMeta(String color, String description) {}

    private static Map<String, NodeKindMeta> nodeKinds() {
        Map<String, NodeKindMeta> m = new LinkedHashMap<>();
        m.put("Method", new NodeKindMeta("nk-method",
                "A method definition. Body anchor for Parameters, CallSites, Literals; receives Calls (incoming) and emits AstContains (outgoing)."));
        m.put("TestMethod", new NodeKindMeta("nk-test",
                "A JUnit test method (@Test / @ParameterizedTest / @RepeatedTest)."));
        m.put("Type", new NodeKindMeta("nk-type",
                "A class, interface, enum or annotation declaration. Source/target of INHERITS_FROM (RefType) edges."));
        m.put("Field", new NodeKindMeta("nk-field",
                "A class member field. Source/target of Reads and Writes edges."));
        m.put("Parameter", new NodeKindMeta("nk-param",
                "A formal parameter of a method. AST child of the owner method; data-flow sink for arguments at every incoming call site."));
        m.put("CallSite", new NodeKindMeta("nk-callsite",
                "A call expression in source. Outgoing Calls edge points at the resolved callee Method; participates in Ddg/Cdg with surrounding code."));
        m.put("Literal", new NodeKindMeta("nk-literal",
                "A literal value in source (number, string, null, …). Typical Ddg source: flows into the call site or assignment that consumes the value."));
        m.put("Stmt", new NodeKindMeta("nk-stmt",
                "A statement: return / if / loop / try. AST child of the owner method, participates in Cdg."));
        return m;
    }

    private static Map<String, EdgeKindMeta> edgeKinds() {
        Map<String, EdgeKindMeta> m = new LinkedHashMap<>();
        m.put("Calls", new EdgeKindMeta("#212121",
                "Call edge: the source invokes the target method. In a faithful CPG the source is a CallSite."));
        m.put("AstContains", new EdgeKindMeta("#9E9E9E",
                "AST containment: the parent (a Method) syntactically contains the child (Parameter, Literal, CallSite, Stmt). Read as «source has child target»."));
        m.put("Ddg", new EdgeKindMeta("#1565C0",
                "Data dependence (reaching definition): the value defined at source is used at target. A literal argument feeding a call site is a textbook example."));
        m.put("Cdg", new EdgeKindMeta("#EF6C00",
                "Control dependence: the target executes only because of the outcome at the source (branch / exception / loop)."));
        m.put("Reads", new EdgeKindMeta("#2E7D32",
                "Field read: the source method reads the target field. Synthesised from <operator>.fieldAccess CALL nodes."));
        m.put("Writes", new EdgeKindMeta("#C62828",
                "Field write: the source method writes the target field. Synthesised from <operator>.assignment* and <operator>.{pre,post}{Increment,Decrement} CALL nodes."));
        m.put("Overrides", new EdgeKindMeta("#000000",
                "Override: the source method overrides the target (subclass → superclass / interface). Drives virtual dispatch resolution."));
        m.put("RefType", new EdgeKindMeta("#6A1B9A",
                "Type reference (INHERITS_FROM): the source TypeDecl extends or implements the target TypeDecl."));
        return m;
    }

    // ---- CSS + JS ---------------------------------------------------------------------

    private static String css() {
        return """
                * { box-sizing: border-box; }
                html, body { height: 100%; margin: 0; font-family: -apple-system, "Helvetica Neue", Arial, sans-serif; color: #263238; }
                body { display: flex; }
                #sidebar { width: 320px; padding: 16px 18px; background: #FAFAFA; border-right: 1px solid #E0E0E0; overflow-y: auto; }
                #sidebar h1 { font-size: 16px; margin: 0; }
                #sidebar h2 { font-size: 12px; text-transform: uppercase; letter-spacing: 0.05em; color: #607D8B; margin: 18px 0 6px; }
                #sidebar p.project { margin: 2px 0 6px; color: #455A64; font-size: 12px; font-family: ui-monospace, monospace; }
                #sidebar p.stats { margin: 0 0 8px; color: #607D8B; font-size: 12px; }
                #sidebar .hint { font-weight: normal; text-transform: none; letter-spacing: 0; color: #90A4AE; }
                #sidebar ul.legend { list-style: none; padding: 0; margin: 0; }
                #sidebar ul.legend li { display: flex; align-items: center; gap: 8px; padding: 4px 6px; border-radius: 4px; font-size: 13px; }
                #sidebar ul.legend.edges li { cursor: pointer; user-select: none; }
                #sidebar ul.legend.edges li.off { opacity: 0.35; }
                #sidebar ul.legend li:hover { background: #ECEFF1; }
                #sidebar ul.legend .name { flex: 1; }
                #sidebar ul.legend .count { color: #90A4AE; font-size: 12px; }
                .swatch { display: inline-block; width: 18px; height: 14px; border-radius: 3px; }
                .swatch-node { border: 1px solid rgba(0,0,0,0.2); }
                .swatch-edge { width: 22px; height: 4px; border-radius: 2px; }
                .nk-method { background: #D1C4E9; } .nk-test { background: #FFD580; }
                .nk-type { background: #C5CAE9; } .nk-field { background: #F8BBD0; }
                .nk-param { background: #C8E6C9; } .nk-callsite { background: #FFF59D; }
                .nk-literal { background: #FFE0B2; } .nk-stmt { background: #B2DFDB; }
                #search, #chop-target { width: 100%; padding: 6px 8px; border: 1px solid #CFD8DC; border-radius: 4px; font-size: 13px; }
                .chop-panel { display: flex; flex-direction: column; gap: 6px; }
                .chop-buttons { display: flex; gap: 6px; }
                .chop-buttons button { flex: 1; padding: 6px 10px; border: 1px solid #CFD8DC; background: white; border-radius: 4px; cursor: pointer; font-size: 12px; }
                .chop-buttons button:hover { background: #ECEFF1; }
                #chop-go { background: #FFD580; border-color: #FFB74D; }
                .chop-mode { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #455A64; cursor: pointer; user-select: none; }
                .chop-mode input { margin: 0; }
                .chop-download { padding: 6px 10px; border: 1px solid #CFD8DC; background: white; border-radius: 4px; cursor: pointer; font-size: 12px; }
                .chop-download:hover:not(:disabled) { background: #ECEFF1; }
                .chop-download:disabled { opacity: 0.4; cursor: not-allowed; }
                .chop-summary { margin: 0; font-size: 12px; color: #607D8B; }
                #details { background: white; border: 1px solid #ECEFF1; border-radius: 4px; padding: 8px; margin: 0; font-size: 11px; white-space: pre-wrap; word-break: break-word; max-height: 240px; overflow-y: auto; }
                #graph { flex: 1; min-width: 0; height: 100vh; background: #FFFFFF; position: relative; }
                .graph-overlay { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: #FAFAFA; z-index: 5; }
                .graph-overlay-inner { max-width: 540px; padding: 32px 36px; text-align: center; color: #455A64; font-size: 14px; line-height: 1.5; }
                .graph-overlay-inner h2 { font-size: 18px; margin: 0 0 12px; color: #263238; }
                .graph-overlay-inner p { margin: 0 0 12px; }
                .graph-overlay-inner .hint { font-size: 12px; color: #90A4AE; }
                #render-full-btn { padding: 8px 16px; background: #ECEFF1; border: 1px solid #CFD8DC; border-radius: 4px; cursor: pointer; font-size: 13px; color: #455A64; }
                #render-full-btn:hover { background: #CFD8DC; }
                #render-full-btn:disabled { opacity: 0.5; cursor: progress; }
                .graph-overlay.hidden-overlay { display: none; }
                .tooltip { position: absolute; pointer-events: none; background: rgba(33,33,33,0.94); color: white; padding: 8px 10px; border-radius: 4px; font-size: 12px; max-width: 380px; z-index: 10; line-height: 1.45; }
                .tooltip .k { color: #80DEEA; font-weight: 600; }
                .tooltip .desc { color: #ECEFF1; margin: 4px 0 6px; padding: 4px 6px; background: rgba(255,255,255,0.06); border-left: 2px solid #80DEEA; border-radius: 2px; }
                .tooltip i { color: #B0BEC5; font-style: italic; font-size: 11px; }
                .tooltip .endpoints { margin-top: 4px; padding-top: 4px; border-top: 1px solid rgba(255,255,255,0.15); }
                .tooltip .endpoints .role { display: inline-block; min-width: 36px; color: #B0BEC5; }
                .tooltip .kgray { color: #B0BEC5; font-size: 10px; }
                """;
    }

    private static String scriptBody() {
        return """
                const NODE_STYLE = {
                  Method:     { shape:'round-rectangle', bg:'#D1C4E9', border:'#7E57C2' },
                  TestMethod: { shape:'round-rectangle', bg:'#FFD580', border:'#E65100' },
                  Type:       { shape:'hexagon',         bg:'#C5CAE9', border:'#3949AB' },
                  Field:      { shape:'diamond',         bg:'#F8BBD0', border:'#AD1457' },
                  Parameter:  { shape:'rhomboid',        bg:'#C8E6C9', border:'#2E7D32' },
                  CallSite:   { shape:'rectangle',       bg:'#FFF59D', border:'#F9A825' },
                  Literal:    { shape:'ellipse',         bg:'#FFE0B2', border:'#EF6C00' },
                  Stmt:       { shape:'cut-rectangle',   bg:'#B2DFDB', border:'#00695C' }
                };
                const EDGE_STYLE = {
                  Calls:       { color:'#212121', style:'solid',  width:2, label:'calls' },
                  AstContains: { color:'#9E9E9E', style:'dotted', width:1, label:'',          arrowless:true },
                  Ddg:         { color:'#1565C0', style:'solid',  width:2, label:'ddg' },
                  Cdg:         { color:'#EF6C00', style:'dashed', width:2, label:'cdg' },
                  Reads:       { color:'#2E7D32', style:'solid',  width:2, label:'reads' },
                  Writes:      { color:'#C62828', style:'solid',  width:2, label:'writes' },
                  Overrides:   { color:'#000000', style:'solid',  width:3, label:'overrides' },
                  RefType:     { color:'#6A1B9A', style:'dashed', width:2, label:'refType' }
                };

                const style = [];
                for (const [k, s] of Object.entries(NODE_STYLE)) {
                  style.push({ selector: `node[kind = "${k}"]`,
                    style: { 'shape': s.shape, 'background-color': s.bg, 'border-color': s.border, 'border-width': 1.5,
                      'label': 'data(label)', 'font-size': 10, 'text-wrap': 'wrap', 'text-max-width': 140,
                      'text-valign': 'center', 'text-halign': 'center', 'color': '#263238',
                      'padding': '6px', 'width': 'label', 'height': 'label' }});
                }
                for (const [k, s] of Object.entries(EDGE_STYLE)) {
                  style.push({ selector: `edge[kind = "${k}"]`,
                    style: { 'line-color': s.color, 'line-style': s.style, 'width': s.width,
                      'target-arrow-color': s.color, 'target-arrow-shape': s.arrowless ? 'none' : 'triangle',
                      'curve-style': 'bezier', 'label': s.label, 'font-size': 8, 'color': s.color,
                      'text-rotation': 'autorotate', 'text-background-color': 'white',
                      'text-background-opacity': 0.9, 'text-background-padding': 1 }});
                }
                style.push({ selector: 'node:selected', style: { 'border-width': 3, 'border-color': '#FF5252' }});
                style.push({ selector: '.dim',          style: { 'opacity': 0.08 }});
                style.push({ selector: '.chop-hidden',  style: { 'display': 'none' }});
                style.push({ selector: '.hidden',       style: { 'display': 'none' }});
                style.push({ selector: 'node.chop',     style: { 'opacity': 1, 'border-width': 3, 'border-color': '#FF6F00' }});
                style.push({ selector: 'edge.chop',     style: { 'opacity': 1, 'width': 3 }});
                style.push({ selector: 'node.chop-target', style: { 'border-width': 5, 'border-color': '#B71C1C', 'background-color': '#FFCDD2' }});
                style.push({ selector: 'node.chop-assert', style: { 'border-width': 4, 'border-color': '#F9A825', 'background-color': '#FFF59D' }});
                style.push({ selector: 'node.chop-test',   style: { 'border-width': 4, 'border-color': '#E65100' }});
                style.push({ selector: 'node.chop-anchor', style: { 'border-width': 3, 'border-color': '#1565C0' }});

                // Threshold below which we just lay the whole graph out eagerly with cose.
                // Above it, we start with everything hidden and only render the chop the
                // user picks — cose on >>1k nodes can take tens of seconds.
                const EAGER_LAYOUT_THRESHOLD = 600;
                const eagerMode = DATA.nodes.length <= EAGER_LAYOUT_THRESHOLD;
                // Tracks whether cose has been run on every node (i.e. is it safe to
                // un-isolate and show non-chop nodes without them all collapsing at 0,0?).
                let fullLayoutDone = eagerMode;
                const cy = cytoscape({
                  container: document.getElementById('graph'),
                  elements: DATA, style: style,
                  layout: eagerMode
                    ? { name: 'cose', animate: false, idealEdgeLength: 90, nodeRepulsion: 6000, padding: 30 }
                    : { name: 'preset' },
                  wheelSensitivity: 0.2
                });
                // Lazy mode: hide every element until the user picks a target.
                // The overlay covers the empty canvas with a "Pick a target" message.
                const graphOverlay = document.getElementById('graph-overlay');
                if (!eagerMode) {
                  cy.batch(() => cy.elements().addClass('chop-hidden'));
                } else if (graphOverlay) {
                  graphOverlay.classList.add('hidden-overlay');
                }
                const overlayCount = document.getElementById('overlay-node-count');
                if (overlayCount) overlayCount.textContent = String(DATA.nodes.length);
                // "Render full graph anyway" — pricy, but available on demand.
                const renderFullBtn = document.getElementById('render-full-btn');
                if (renderFullBtn) {
                  renderFullBtn.addEventListener('click', () => {
                    renderFullBtn.disabled = true;
                    renderFullBtn.textContent = 'Laying out ' + DATA.nodes.length + ' nodes…';
                    // Yield to the browser so the button-state update paints.
                    setTimeout(() => {
                      cy.batch(() => cy.elements().removeClass('chop-hidden'));
                      cy.layout({ name: 'cose', animate: false, idealEdgeLength: 90, nodeRepulsion: 6000, padding: 30 }).run();
                      cy.fit(cy.elements(), 30);
                      fullLayoutDone = true;
                      graphOverlay.classList.add('hidden-overlay');
                      renderFullBtn.disabled = false;
                      renderFullBtn.textContent = 'Render full graph anyway';
                    }, 30);
                  });
                }

                function escape(s) {
                  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;').replace(/\"/g, '&quot;');
                }

                const tip = document.getElementById('tooltip');
                function showTip(html, evt) {
                  tip.innerHTML = html;
                  tip.style.left = (evt.originalEvent.clientX + 12) + 'px';
                  tip.style.top  = (evt.originalEvent.clientY + 12) + 'px';
                  tip.hidden = false;
                }
                function hideTip() { tip.hidden = true; }

                function endpointSummary(id) {
                  const d = cy.getElementById(id).data() || {};
                  return `<b>${escape(d.label || id)}</b> <span class="kgray">(${escape(d.kind || '?')})</span>`;
                }
                cy.on('mouseover', 'node', evt => {
                  const d = evt.target.data();
                  const desc = NODE_DESCRIPTIONS[d.kind] || '';
                  let html = `<div><span class="k">${d.kind}</span> ${escape(d.label)}</div>`;
                  if (desc) html += `<div class="desc">${escape(desc)}</div>`;
                  if (d.fqn)        html += `<div><i>fqn</i>: ${escape(d.fqn)}</div>`;
                  if (d.signature)  html += `<div><i>signature</i>: ${escape(d.signature)}</div>`;
                  if (d.callee)     html += `<div><i>callee</i>: ${escape(d.callee)}</div>`;
                  if (d.code)       html += `<div><i>code</i>: ${escape(d.code)}</div>`;
                  if (d.value !== undefined) html += `<div><i>value</i>: ${escape(d.value)}</div>`;
                  if (d.type)       html += `<div><i>type</i>: ${escape(d.type)}</div>`;
                  if (d.owner)      html += `<div><i>owner</i>: ${escape(d.owner)}</div>`;
                  if (d.file)       html += `<div><i>at</i>: ${escape(d.file)}:${d.line}</div>`;
                  showTip(html, evt);
                });
                cy.on('mouseover', 'edge', evt => {
                  const d = evt.target.data();
                  const desc = EDGE_DESCRIPTIONS[d.kind] || '';
                  let html = `<div><span class="k">${d.kind}</span></div>`;
                  if (desc) html += `<div class="desc">${escape(desc)}</div>`;
                  html += `<div class="endpoints">`
                       +  `<div><span class="role">from</span> ${endpointSummary(d.source)}</div>`
                       +  `<div><span class="role">to</span>&nbsp;&nbsp; ${endpointSummary(d.target)}</div></div>`;
                  showTip(html, evt);
                });
                cy.on('mouseout', 'node, edge', hideTip);
                cy.on('drag pan zoom', hideTip);

                const details = document.getElementById('details');
                cy.on('tap', 'node, edge', evt => { details.textContent = JSON.stringify(evt.target.data(), null, 2); });
                cy.on('tap', evt => { if (evt.target === cy) details.textContent = '(hover or click a node)'; });

                document.querySelectorAll('#sidebar li[data-edge-kind]').forEach(li => {
                  li.addEventListener('click', () => {
                    li.classList.toggle('off');
                    cy.edges(`[kind = "${li.dataset.edgeKind}"]`).toggleClass('hidden');
                  });
                });

                document.getElementById('search').addEventListener('input', evt => {
                  const q = evt.target.value.trim().toLowerCase();
                  if (!q) { cy.elements().removeClass('dim'); return; }
                  cy.batch(() => {
                    cy.elements().addClass('dim');
                    const hits = cy.nodes().filter(n => {
                      const d = n.data();
                      const hay = [d.label, d.fqn, d.callee, d.value, d.name].filter(Boolean).join(' ').toLowerCase();
                      return hay.includes(q);
                    });
                    hits.removeClass('dim');
                    hits.connectedEdges().removeClass('dim');
                  });
                });

                // ---- Chop computation -----------------------------------------------------
                const CHOP_TRAVERSAL = new Set(['Calls','Ddg','AstContains','Cdg','Reads']);
                const ASSERT_PKGS = ['org.junit.','org.assertj.','org.hamcrest.MatcherAssert.'];
                const ASSERT_NAMES = ['assert','fail','verify','expect','then'];
                const adjOut = new Map(), adjIn = new Map();
                cy.edges().forEach(e => {
                  const d = e.data();
                  if (!CHOP_TRAVERSAL.has(d.kind)) return;
                  if (!adjOut.has(d.source)) adjOut.set(d.source, []);
                  adjOut.get(d.source).push(d);
                  if (!adjIn.has(d.target)) adjIn.set(d.target, []);
                  adjIn.get(d.target).push(d);
                });
                function simpleName(s) { if (!s) return ''; const i = s.lastIndexOf('.'); return i < 0 ? s : s.substring(i + 1); }
                function isAssertLike(csData) {
                  const fqn = csData.callee || ''; if (!fqn) return false;
                  const simple = simpleName(fqn);
                  const nameMatch = ASSERT_NAMES.some(p => simple.startsWith(p));
                  if (!nameMatch) return false;
                  return ASSERT_PKGS.some(p => fqn.startsWith(p)) || true; // tolerate unresolved class-path
                }
                function bfs(seeds, direction, maxDepth, maxNodes) {
                  const visited = new Set(seeds);
                  const queue = seeds.map(id => [id, 0]);
                  const adj = direction === 'out' ? adjOut : adjIn;
                  let truncated = false;
                  while (queue.length && !truncated) {
                    const [id, d] = queue.shift();
                    if (d >= maxDepth) continue;
                    const nodeData = cy.getElementById(id).data();
                    if (nodeData && (nodeData.fqn || '').match(/^(java|javax|sun|jdk|kotlin|scala)\\./)) continue;
                    for (const e of (adj.get(id) || [])) {
                      const other = direction === 'out' ? e.target : e.source;
                      if (visited.has(other)) continue;
                      if (visited.size >= maxNodes) { truncated = true; break; }
                      visited.add(other);
                      queue.push([other, d + 1]);
                    }
                  }
                  return { visited, truncated };
                }
                function computeChop(targetId, opts) {
                  opts = opts || {}; const maxDepth = opts.maxDepth || 12; const maxNodes = opts.maxNodes || 800;
                  const assertsByTest = new Map();
                  cy.nodes('[kind = "TestMethod"]').forEach(tm => {
                    const tmId = tm.id();
                    const out = adjOut.get(tmId) || [];
                    const asserts = [];
                    for (const e of out) {
                      if (e.kind !== 'AstContains') continue;
                      const child = cy.getElementById(e.target).data();
                      if (child && child.kind === 'CallSite' && isAssertLike(child)) asserts.push(child.id);
                    }
                    if (asserts.length) assertsByTest.set(tmId, asserts);
                  });
                  const back = bfs([targetId], 'in', maxDepth, maxNodes);
                  const forward = new Set();
                  for (const tmId of assertsByTest.keys()) {
                    bfs([tmId], 'out', maxDepth, maxNodes).visited.forEach(id => forward.add(id));
                  }
                  const chop = new Set();
                  back.visited.forEach(id => { if (forward.has(id)) chop.add(id); });
                  chop.add(targetId);
                  const testsInChop = []; const assertSites = [];
                  for (const [tmId, csList] of assertsByTest.entries()) {
                    if (chop.has(tmId)) { testsInChop.push(tmId); csList.forEach(cs => { chop.add(cs); assertSites.push(cs); }); }
                  }
                  if (testsInChop.length === 0) return null;
                  // Anchor Method→Method Calls through real CallSites.
                  const closed = new Set(chop);
                  const usedAnchors = new Set(); const anchored = new Set();
                  for (const callerId of Array.from(closed)) {
                    const callerData = cy.getElementById(callerId).data();
                    if (!callerData || (callerData.kind !== 'Method' && callerData.kind !== 'TestMethod')) continue;
                    for (const e of (adjOut.get(callerId) || [])) {
                      if (e.kind !== 'Calls') continue;
                      if (!closed.has(e.target)) continue;
                      const calleeData = cy.getElementById(e.target).data();
                      if (!calleeData || (calleeData.kind !== 'Method' && calleeData.kind !== 'TestMethod')) continue;
                      const calleeFqn = calleeData.fqn; const calleeSimple = simpleName(calleeFqn);
                      let anchor = null;
                      for (const ee of (adjOut.get(callerId) || [])) {
                        if (ee.kind !== 'AstContains') continue;
                        const child = cy.getElementById(ee.target).data();
                        if (!child || child.kind !== 'CallSite') continue;
                        if (usedAnchors.has(child.id)) continue;
                        const csFqn = child.callee || '';
                        if (csFqn === calleeFqn || simpleName(csFqn) === calleeSimple) { anchor = child.id; break; }
                      }
                      if (anchor) { usedAnchors.add(anchor); closed.add(anchor); anchored.add(anchor); }
                    }
                  }
                  // AST closure: Parameters + Literals via Ddg.
                  for (const id of Array.from(closed)) {
                    const n = cy.getElementById(id).data();
                    if (!n || (n.kind !== 'Method' && n.kind !== 'TestMethod')) continue;
                    for (const e of (adjOut.get(id) || [])) {
                      if (e.kind !== 'AstContains') continue;
                      const child = cy.getElementById(e.target).data();
                      if (!child) continue;
                      if (child.kind === 'Parameter') closed.add(child.id);
                      else if (child.kind === 'Literal') {
                        const outs = adjOut.get(child.id) || [];
                        if (outs.some(ee => ee.kind === 'Ddg' && closed.has(ee.target))) closed.add(child.id);
                      }
                    }
                  }
                  return { nodes: closed, target: targetId, tests: testsInChop,
                           asserts: new Set(assertSites), anchors: anchored, truncated: back.truncated };
                }
                function applyChop(chop) {
                  cy.batch(() => {
                    cy.elements().removeClass('dim chop chop-target chop-assert chop-test chop-anchor chop-hidden');
                    if (!chop) return;
                    const isolate = document.getElementById('chop-isolate').checked;
                    // Mark every element as either inside the chop or outside.
                    cy.elements().addClass('dim');
                    chop.nodes.forEach(id => cy.getElementById(id).removeClass('dim').addClass('chop'));
                    cy.getElementById(chop.target).removeClass('dim').addClass('chop-target');
                    chop.tests.forEach(id => cy.getElementById(id).addClass('chop-test'));
                    chop.asserts.forEach(id => cy.getElementById(id).addClass('chop-assert'));
                    chop.anchors.forEach(id => cy.getElementById(id).addClass('chop-anchor'));
                    cy.edges().forEach(e => {
                      const d = e.data();
                      if (chop.nodes.has(d.source) && chop.nodes.has(d.target)) {
                        e.removeClass('dim').addClass('chop');
                      }
                    });
                    // Isolate mode: hide everything that is still dim (= not in chop).
                    if (isolate) {
                      cy.elements('.dim').addClass('chop-hidden');
                    }
                  });
                }
                let savedPositions = null;
                function saveCurrentPositions() {
                  savedPositions = new Map();
                  cy.nodes().forEach(n => savedPositions.set(n.id(), { x: n.position('x'), y: n.position('y') }));
                }
                function restoreSavedPositions() {
                  if (!savedPositions) return;
                  cy.batch(() => savedPositions.forEach((p, id) => { const n = cy.getElementById(id); if (n.length) n.position(p); }));
                }
                function relayoutChop(chop) {
                  if (!chop) return;
                  const Y_SP = 220, X_M = 360, X_C = 140;
                  const depth = new Map(); depth.set(chop.target, 0);
                  const q = [chop.target];
                  while (q.length) {
                    const id = q.shift(); const d = depth.get(id);
                    for (const e of (adjIn.get(id) || [])) {
                      if (e.kind !== 'Calls') continue;
                      if (!chop.nodes.has(e.source)) continue;
                      const k = (cy.getElementById(e.source).data() || {}).kind;
                      if (k !== 'Method' && k !== 'TestMethod') continue;
                      if (depth.has(e.source)) continue;
                      depth.set(e.source, d + 1); q.push(e.source);
                    }
                  }
                  let maxD = 0; depth.forEach(d => { if (d > maxD) maxD = d; });
                  chop.tests.forEach(id => { if (!depth.has(id)) { depth.set(id, maxD + 1); maxD = Math.max(maxD, maxD + 1); } });
                  const ASSERT_Y = (maxD + 2) * Y_SP;
                  const methodsAtRow = new Map();
                  depth.forEach((d, id) => { if (!chop.nodes.has(id)) return;
                    if (!methodsAtRow.has(d)) methodsAtRow.set(d, []); methodsAtRow.get(d).push(id); });
                  const childrenByParent = new Map();
                  for (const id of chop.nodes) {
                    if (depth.has(id)) continue;
                    for (const e of (adjIn.get(id) || [])) {
                      if (e.kind !== 'AstContains') continue;
                      if (!depth.has(e.source)) continue;
                      if (!childrenByParent.has(e.source)) childrenByParent.set(e.source, []);
                      childrenByParent.get(e.source).push(id); break;
                    }
                  }
                  const positions = new Map();
                  methodsAtRow.forEach((methods, d) => {
                    const y = d * Y_SP; const total = (methods.length - 1) * X_M;
                    methods.forEach((m, i) => positions.set(m, { x: -total / 2 + i * X_M, y }));
                  });
                  childrenByParent.forEach((children, p) => {
                    const parent = positions.get(p); if (!parent) return;
                    const asserts = children.filter(c => chop.asserts.has(c));
                    const others  = children.filter(c => !chop.asserts.has(c));
                    others.forEach((c, i) => positions.set(c, { x: parent.x + (i + 1) * X_C, y: parent.y }));
                    asserts.forEach((c, i) => positions.set(c, { x: parent.x + (i - (asserts.length - 1) / 2) * X_C, y: ASSERT_Y }));
                  });
                  let bx = 0;
                  chop.asserts.forEach(a => { if (!positions.has(a)) { positions.set(a, { x: bx, y: ASSERT_Y }); bx += X_C; } });
                  cy.batch(() => positions.forEach((p, id) => { const n = cy.getElementById(id); if (n.length) n.position(p); }));
                  cy.fit(cy.elements('.chop'), 60);
                }

                const chopList = document.getElementById('chop-target-list');
                cy.nodes('[kind = "Method"], [kind = "TestMethod"]').forEach(n => {
                  const fqn = n.data().fqn; if (!fqn) return;
                  const opt = document.createElement('option'); opt.value = fqn; chopList.appendChild(opt);
                });
                const chopSummary = document.getElementById('chop-summary');
                const downloadBtn = document.getElementById('chop-download');
                const downloadLlmBtn = document.getElementById('chop-download-llm');
                const downloadJsonBtn = document.getElementById('chop-download-json');
                const isolateCb = document.getElementById('chop-isolate');
                let currentChop = null;
                function refreshChopState(chop) {
                  currentChop = chop;
                  downloadBtn.disabled = !chop;
                  downloadLlmBtn.disabled = !chop;
                  downloadJsonBtn.disabled = !chop;
                }
                document.getElementById('chop-go').addEventListener('click', () => {
                  const fqn = document.getElementById('chop-target').value.trim();
                  if (!fqn) { chopSummary.textContent = 'Pick a method first.'; return; }
                  let nodes = cy.nodes().filter(n => n.data().fqn === fqn);
                  if (nodes.length === 0) {
                    const simp = fqn.toLowerCase();
                    nodes = cy.nodes().filter(n => (n.data().fqn || '').toLowerCase().endsWith(simp));
                  }
                  if (nodes.length === 0) { chopSummary.textContent = 'No method matched.'; applyChop(null); refreshChopState(null); return; }
                  const target = nodes[0];
                  const chop = computeChop(target.id());
                  if (!chop) { chopSummary.textContent = 'No test asserts reach this method.'; applyChop(null); refreshChopState(null); return; }
                  // In lazy mode the whole graph is hidden and never had positions;
                  // force isolate on for the first chop so we don't try to fit empty space.
                  if (!eagerMode) document.getElementById('chop-isolate').checked = true;
                  if (!savedPositions) saveCurrentPositions();
                  applyChop(chop); relayoutChop(chop);
                  refreshChopState(chop);
                  // Hide the placeholder overlay once we have something on screen.
                  if (graphOverlay) graphOverlay.classList.add('hidden-overlay');
                  chopSummary.textContent = `${chop.nodes.size} nodes · ${chop.tests.length} test(s) · ${chop.asserts.size} assert site(s)${chop.truncated ? ' (truncated)' : ''}`;
                });
                document.getElementById('chop-clear').addEventListener('click', () => {
                  applyChop(null);
                  refreshChopState(null);
                  document.getElementById('chop-target').value = '';
                  if (eagerMode) {
                    // We had a real cose layout to begin with — restore and refit.
                    restoreSavedPositions(); cy.fit(cy.elements(), 30);
                    chopSummary.textContent = '—';
                  } else {
                    // Lazy mode: re-hide everything and bring the placeholder back.
                    cy.batch(() => cy.elements().addClass('chop-hidden'));
                    if (graphOverlay) graphOverlay.classList.remove('hidden-overlay');
                    chopSummary.textContent = '—';
                  }
                });
                // Toggle isolate ↔ dim mode without recomputing the chop.
                isolateCb.addEventListener('change', () => {
                  if (!currentChop) return;
                  // In lazy mode, switching OFF isolate would reveal non-chop nodes that
                  // have no positions (everything's at 0,0) — looks awful AND restores
                  // the lag the lazy mode exists to avoid. Run the full cose layout once
                  // before showing the dimmed background.
                  if (!isolateCb.checked && !fullLayoutDone) {
                    const ok = confirm('Showing non-chop nodes requires laying out the full graph ('
                            + DATA.nodes.length + ' nodes). This may take a while. Continue?');
                    if (!ok) { isolateCb.checked = true; return; }
                    cy.batch(() => cy.elements().removeClass('chop-hidden'));
                    cy.layout({ name: 'cose', animate: false, idealEdgeLength: 90, nodeRepulsion: 6000, padding: 30 }).run();
                    fullLayoutDone = true;
                  }
                  applyChop(currentChop);
                });
                // Download chains as human-readable text.
                function triggerDownload(text, prefix, ext, mime) {
                  const targetData = cy.getElementById(currentChop.target).data();
                  const fqn = targetData.fqn || 'target';
                  const safe = fqn.replace(/[^A-Za-z0-9_.-]+/g, '_');
                  const blob = new Blob([text], { type: mime + ';charset=utf-8' });
                  const a = document.createElement('a');
                  a.href = URL.createObjectURL(blob);
                  a.download = prefix + '-' + safe + '.' + ext;
                  document.body.appendChild(a); a.click();
                  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 0);
                }
                downloadBtn.addEventListener('click', () => {
                  if (!currentChop) return;
                  triggerDownload(renderChainsText(currentChop), 'chains', 'txt', 'text/plain');
                });
                downloadLlmBtn.addEventListener('click', () => {
                  if (!currentChop) return;
                  triggerDownload(renderLlmAugmentation(currentChop), 'slice', 'md', 'text/markdown');
                });
                downloadJsonBtn.addEventListener('click', () => {
                  if (!currentChop) return;
                  triggerDownload(renderGraphRagJson(currentChop), 'slice', 'json', 'application/json');
                });

                // ---- Chain enumeration & rendering ----------------------------------------
                /**
                 * Enumerate every simple path test → ... → target inside the chop, walking
                 * `Calls` edges restricted to chop.nodes.  For each step we attach the call
                 * site (an AstContains child of the caller whose callee matches the callee
                 * FQN) so the reader can locate the call in source.
                 */
                function enumerateChains(chop) {
                  const callsOut = new Map(); // methodId -> [{ calleeId, edge }]
                  cy.edges('[kind = "Calls"]').forEach(e => {
                    const d = e.data();
                    if (!chop.nodes.has(d.source) || !chop.nodes.has(d.target)) return;
                    if (!callsOut.has(d.source)) callsOut.set(d.source, []);
                    callsOut.get(d.source).push({ calleeId: d.target, edge: d });
                  });
                  const chains = [];
                  const MAX_DEPTH = 25; const MAX_CHAINS = 5000;
                  for (const testId of chop.tests) {
                    const stack = [{ id: testId, path: [], onPath: new Set([testId]) }];
                    while (stack.length && chains.length < MAX_CHAINS) {
                      const cur = stack.pop();
                      if (cur.id === chop.target && cur.path.length > 0) {
                        chains.push({ testId, steps: cur.path });
                        continue;
                      }
                      if (cur.path.length >= MAX_DEPTH) continue;
                      for (const { calleeId, edge } of (callsOut.get(cur.id) || [])) {
                        if (cur.onPath.has(calleeId)) continue;
                        const nextOnPath = new Set(cur.onPath); nextOnPath.add(calleeId);
                        stack.push({ id: calleeId, path: cur.path.concat([{ callerId: cur.id, calleeId, edge }]), onPath: nextOnPath });
                      }
                    }
                    if (chains.length >= MAX_CHAINS) break;
                  }
                  chains.sort((a, b) => a.steps.length - b.steps.length);
                  return chains;
                }
                function findCallSite(callerId, calleeId) {
                  const calleeData = cy.getElementById(calleeId).data();
                  if (!calleeData) return null;
                  const calleeFqn = calleeData.fqn || '';
                  const calleeSimple = simpleName(calleeFqn);
                  for (const e of (adjOut.get(callerId) || [])) {
                    if (e.kind !== 'AstContains') continue;
                    const child = cy.getElementById(e.target).data();
                    if (!child || child.kind !== 'CallSite') continue;
                    const csFqn = child.callee || '';
                    if (csFqn === calleeFqn || simpleName(csFqn) === calleeSimple) return child;
                  }
                  return null;
                }
                /**
                 * Render the chop's chains as text optimised for reading next to source
                 * code. Each chain shows EVERY CPG vertex it touches (TestMethod,
                 * intermediate Methods, the explicit CallSite anchor between each pair)
                 * with the connecting edge kind (AstContains, Calls, plus a "virtual"
                 * marker on Calls when applicable). Edge kinds that don't shape the call
                 * skeleton (Ddg, Cdg, Reads, Writes) are deliberately omitted to keep
                 * the file scannable — those are still inspectable in the HTML.
                 */
                function renderChainsText(chop) {
                  const chains = enumerateChains(chop);
                  const target = cy.getElementById(chop.target).data();
                  const lines = [];
                  const HR = '═════════════════════════════════════════════════════════════════════';

                  // ── Header ───────────────────────────────────────────────────────
                  lines.push(HR);
                  lines.push('  CHOP TARGET   ' + (target.fqn || target.label));
                  if (target.signature) lines.push('  signature     ' + target.signature);
                  if (target.file)      lines.push('  defined at    ' + target.file + (target.line > 0 ? ':' + target.line : ''));
                  lines.push(HR);
                  lines.push('');
                  const depths = chains.map(c => c.steps.length);
                  const minD = depths.length ? Math.min.apply(null, depths) : 0;
                  const maxD = depths.length ? Math.max.apply(null, depths) : 0;
                  lines.push('Scope    : ' + chop.nodes.size + ' CPG nodes · '
                          + chop.tests.length + ' test(s) · ' + chop.asserts.size + ' assert site(s)'
                          + (chop.truncated ? ' (truncated)' : ''));
                  lines.push('Chains   : ' + chains.length
                          + (chains.length ? ' (depths ' + minD + '..' + maxD + ')' : ''));
                  lines.push('Skeleton : Calls + AstContains only. Ddg / Cdg / Reads / Writes are');
                  lines.push('           in the chop but omitted here for brevity — see the HTML.');
                  lines.push('');

                  if (chains.length === 0) {
                    lines.push('(no Calls-path from any test reaches the target inside the chop scope)');
                    return lines.join('\\n');
                  }

                  // ── Chains ────────────────────────────────────────────────────────
                  chains.forEach((chain, idx) => {
                    lines.push('');
                    lines.push('─── Chain ' + (idx + 1) + ' of ' + chains.length
                            + '  ·  depth ' + chain.steps.length + '  ───');
                    lines.push('');
                    // First vertex is the test method itself.
                    appendMethodNode(lines, cy.getElementById(chain.testId).data(), false);

                    chain.steps.forEach((step, j) => {
                      const callee = cy.getElementById(step.calleeId).data();
                      const cs = findCallSite(step.callerId, step.calleeId);
                      // edge: caller --AstContains--> CallSite
                      if (cs) {
                        appendEdge(lines, 'AstContains', false);
                        appendCallSiteNode(lines, cs);
                        appendEdge(lines, 'Calls', step.edge.viaVirtual);
                      } else {
                        // No anchor found — collapse to a direct Calls edge.
                        appendEdge(lines, 'Calls', step.edge.viaVirtual);
                      }
                      const isTarget = step.calleeId === chop.target;
                      appendMethodNode(lines, callee, isTarget);
                    });
                    lines.push('');
                  });

                  // ── Assert sites ──────────────────────────────────────────────────
                  if (chop.asserts && chop.asserts.size > 0) {
                    lines.push('');
                    lines.push('─── Assert sites in chop (' + chop.asserts.size + ') ───');
                    lines.push('');
                    chop.asserts.forEach(id => {
                      const d = cy.getElementById(id).data();
                      if (!d) return;
                      const at = d.line > 0 ? ':' + d.line : '';
                      // The assert lives in some test method — look it up to give file context.
                      let file = '';
                      for (const e of (adjIn.get(id) || [])) {
                        if (e.kind !== 'AstContains') continue;
                        const owner = cy.getElementById(e.source).data();
                        if (owner && owner.file) { file = owner.file; break; }
                      }
                      const where = file ? file + at : ('line' + at);
                      const code = d.code ? '  `' + d.code.replace(/\\s+/g, ' ').trim() + '`' : '';
                      lines.push('  ' + (d.callee || d.label) + '  at ' + where + code);
                    });
                  }

                  return lines.join('\\n');
                }

                // ── Per-vertex / per-edge renderers ─────────────────────────────────
                function appendMethodNode(lines, d, isTarget) {
                  if (!d) { lines.push('  [?]  <missing vertex>'); return; }
                  const kind = d.kind === 'TestMethod' ? '[TestMethod]' : '[Method]';
                  const marker = isTarget ? '  ★ TARGET' : '';
                  const name = d.fqn || d.label || d.id || '?';
                  const sig  = d.signature ? '  ::  ' + d.signature : '';
                  lines.push('  ' + kind + '  ' + name + sig + marker);
                  if (d.file) lines.push('               at ' + d.file + (d.line > 0 ? ':' + d.line : ''));
                }
                function appendCallSiteNode(lines, d) {
                  const code = d.code ? d.code.replace(/\\s+/g, ' ').trim() : (d.callee || '(call)');
                  lines.push('  [CallSite]    ' + code);
                  if (d.line > 0) lines.push('               at line ' + d.line);
                }
                function appendEdge(lines, kind, viaVirtual) {
                  lines.push('       │');
                  lines.push('       │   ' + kind + (viaVirtual ? '  (virtual)' : ''));
                  lines.push('       ▼');
                }

                // ─────────────────────────────────────────────────────────────────
                // GraphRAG-style markdown augmentation for LLM prompts.
                //
                // Microsoft's GraphRAG (2024) showed that LLMs answer questions about
                // a corpus much better when given a typed graph of entities + relations
                // than when given embedding-retrieved text alone. We apply the same
                // idea to a code slice: instead of dumping raw source, we dump a
                // structured chop with stable refs (M1, T3, C7, …), an explicit
                // call-path index, data-flow / field / override relations, and a
                // short interpretation guide so the model can parse the format
                // without prior knowledge.
                // ─────────────────────────────────────────────────────────────────
                // For a CPG node that lives inside a method (CallSite, Literal, Stmt, Parameter),
                // walk its incoming AstContains edges to find the enclosing Method / TestMethod id.
                function containingMethodOf(nodeId) {
                  for (const e of (adjIn.get(nodeId) || [])) {
                    if (e.kind !== 'AstContains') continue;
                    const owner = cy.getElementById(e.source).data();
                    if (owner && (owner.kind === 'Method' || owner.kind === 'TestMethod')) return e.source;
                  }
                  return null;
                }
                function renderLlmAugmentation(chop) {
                  const target = cy.getElementById(chop.target).data();
                  const chains = enumerateChains(chop);

                  // Step 1 ─ assign typed refs to every node in chop.
                  const idToRef = new Map();
                  const tests = [], methods = [], callSites = [], asserts = [], literals = [], fields = [], types = [];
                  chop.nodes.forEach(id => {
                    const d = cy.getElementById(id).data();
                    if (!d) return;
                    let bucket = null, prefix = '';
                    if (d.kind === 'TestMethod')     { bucket = tests;     prefix = 'T'; }
                    else if (d.kind === 'Method')    { bucket = methods;   prefix = 'M'; }
                    else if (d.kind === 'CallSite') {
                      if (chop.asserts.has(id))      { bucket = asserts;   prefix = 'A'; }
                      else                           { bucket = callSites; prefix = 'C'; }
                    }
                    else if (d.kind === 'Literal')   { bucket = literals;  prefix = 'L'; }
                    else if (d.kind === 'Field')     { bucket = fields;    prefix = 'F'; }
                    else if (d.kind === 'Type')      { bucket = types;     prefix = 'Y'; }
                    if (!bucket) return; // Parameters and Stmts intentionally skipped — too noisy.
                    const ref = prefix + (bucket.length + 1);
                    idToRef.set(id, ref);
                    bucket.push({ ref, id, data: d });
                  });
                  // Order methods so the target comes first.
                  methods.sort((a, b) => (a.id === chop.target ? -1 : b.id === chop.target ? 1 : 0));

                  // Step 2 ─ render markdown.
                  const md = [];
                  md.push('# Code Slice Augmentation');
                  md.push('');
                  md.push('A graph-structured slice of a Java CPG, focused on a single target method.');
                  md.push('Use it as additional context when answering questions about how this method');
                  md.push('is reached, exercised, or tested. Every entity has a stable ref (e.g. `M3`,');
                  md.push('`T1`, `C5`) so you can cite specific nodes in your answer.');
                  md.push('');

                  // Target block
                  md.push('## Target');
                  md.push('');
                  md.push('- **fqn**: `' + (target.fqn || '?') + '`');
                  if (target.signature) md.push('- **signature**: `' + target.signature + '`');
                  if (target.file) md.push('- **location**: `' + target.file + (target.line > 0 ? ':' + target.line : '') + '`');
                  const targetRef = idToRef.get(chop.target);
                  if (targetRef) md.push('- **ref**: `' + targetRef + '`');
                  md.push('');

                  // Scope summary
                  md.push('## Scope');
                  md.push('');
                  md.push('- Chop size: **' + chop.nodes.size + '** CPG nodes (backward-from-target ∩ forward-from-tests-with-asserts)');
                  md.push('- Test methods reaching the target: **' + chop.tests.length + '**');
                  md.push('- Distinct call paths: **' + chains.length + '**'
                          + (chains.length ? ' (depths ' + Math.min.apply(null, chains.map(c => c.steps.length))
                                           + '‥' + Math.max.apply(null, chains.map(c => c.steps.length)) + ')' : ''));
                  md.push('- Assert sites in scope: **' + chop.asserts.size + '**');
                  if (chop.truncated) md.push('- ⚠ truncated: chop BFS hit its node budget; the slice is incomplete.');
                  md.push('');

                  // ── Entities ────────────────────────────────────────────────────
                  md.push('## Entities');
                  md.push('');

                  if (tests.length) {
                    md.push('### Test methods');
                    md.push('');
                    tests.forEach(e => {
                      const sig = e.data.signature ? ' — `' + e.data.signature + '`' : '';
                      const loc = e.data.file ? ' — at `' + e.data.file + (e.data.line > 0 ? ':' + e.data.line : '') + '`' : '';
                      md.push('- `' + e.ref + '` **' + (e.data.fqn || e.data.label) + '`()`**' + sig + loc);
                    });
                    md.push('');
                  }
                  if (methods.length) {
                    md.push('### Methods');
                    md.push('');
                    methods.forEach(e => {
                      const star = e.id === chop.target ? ' **(TARGET)**' : '';
                      const sig = e.data.signature ? ' — `' + e.data.signature + '`' : '';
                      const loc = e.data.file ? ' — at `' + e.data.file + (e.data.line > 0 ? ':' + e.data.line : '') + '`' : '';
                      md.push('- `' + e.ref + '` **' + (e.data.fqn || e.data.label) + '**' + sig + loc + star);
                    });
                    md.push('');
                  }
                  if (callSites.length) {
                    md.push('### Call sites');
                    md.push('');
                    md.push('Each entry: ref · source expression · location · invoked callee.');
                    md.push('');
                    callSites.forEach(e => {
                      const code = (e.data.code || e.data.label || '?').replace(/\\s+/g, ' ').trim();
                      const loc = e.data.line > 0 ? ' (line ' + e.data.line + ')' : '';
                      const inMethodId = containingMethodOf(e.id);
                      const inMethod = inMethodId ? (idToRef.get(inMethodId) || '?') : '?';
                      const callee = e.data.callee ? ' → calls `' + e.data.callee + '`' : '';
                      md.push('- `' + e.ref + '` in `' + inMethod + '`: `' + code + '`' + loc + callee);
                    });
                    md.push('');
                  }
                  if (asserts.length) {
                    md.push('### Assert sites');
                    md.push('');
                    asserts.forEach(e => {
                      const code = (e.data.code || e.data.label || '?').replace(/\\s+/g, ' ').trim();
                      const loc = e.data.line > 0 ? ' (line ' + e.data.line + ')' : '';
                      const inMethodId = containingMethodOf(e.id);
                      const inTest = inMethodId ? (idToRef.get(inMethodId) || '?') : '?';
                      md.push('- `' + e.ref + '` in `' + inTest + '`: `' + code + '`' + loc);
                    });
                    md.push('');
                  }
                  if (literals.length) {
                    md.push('### Literals (in scope, used by chop call sites)');
                    md.push('');
                    literals.forEach(e => {
                      const val = (e.data.value || '?').replace(/\\s+/g, ' ').trim();
                      const k = e.data.literalKind ? '[' + e.data.literalKind + '] ' : '';
                      const loc = e.data.line > 0 ? ' at line ' + e.data.line : '';
                      md.push('- `' + e.ref + '` ' + k + '`' + val + '`' + loc);
                    });
                    md.push('');
                  }
                  if (fields.length) {
                    md.push('### Fields');
                    md.push('');
                    fields.forEach(e => {
                      const type = e.data.type ? ': `' + e.data.type + '`' : '';
                      const owner = e.data.ownerTypeFqn ? ' in `' + e.data.ownerTypeFqn + '`' : '';
                      md.push('- `' + e.ref + '` `' + (e.data.name || e.data.label) + '`' + type + owner);
                    });
                    md.push('');
                  }
                  if (types.length) {
                    md.push('### Types');
                    md.push('');
                    types.forEach(e => {
                      const kind = e.data.typeKind ? ' (' + e.data.typeKind.toLowerCase() + ')' : '';
                      const loc = e.data.file ? ' at `' + e.data.file + (e.data.line > 0 ? ':' + e.data.line : '') + '`' : '';
                      md.push('- `' + e.ref + '` `' + (e.data.fqn || e.data.label) + '`' + kind + loc);
                    });
                    md.push('');
                  }

                  // ── Relations ───────────────────────────────────────────────────
                  md.push('## Relations');
                  md.push('');

                  // Call paths
                  if (chains.length) {
                    md.push('### Call paths (test → target)');
                    md.push('');
                    md.push('Each path lists every CPG node it touches as `T → C → M → C → … → M`.');
                    md.push('To inspect the actual code at a step, look up the `C` ref in **Call sites**.');
                    md.push('');
                    chains.forEach((c, i) => {
                      const seq = [idToRef.get(c.testId) || '?'];
                      const detail = [];
                      c.steps.forEach((step, j) => {
                        const cs = findCallSite(step.callerId, step.calleeId);
                        let csRef = cs ? (idToRef.get(cs.id) || null) : null;
                        if (csRef) seq.push(csRef);
                        seq.push(idToRef.get(step.calleeId) || '?');
                        if (step.edge.viaVirtual) detail.push('  - step ' + (j+1) + ': virtual dispatch');
                      });
                      md.push('- **Path ' + (i+1) + '** (depth ' + c.steps.length + '): ' + seq.join(' → '));
                      detail.forEach(d => md.push(d));
                    });
                    md.push('');
                  }

                  // Inter-entity edges (Ddg, Reads, Writes, Overrides, RefType)
                  const ddg = [], reads = [], writes = [], overs = [], reftypes = [];
                  cy.edges().forEach(e => {
                    const d = e.data();
                    if (!chop.nodes.has(d.source) || !chop.nodes.has(d.target)) return;
                    const sR = idToRef.get(d.source), tR = idToRef.get(d.target);
                    if (!sR || !tR) return; // skip edges involving filtered-out kinds (Parameter, Stmt)
                    if      (d.kind === 'Ddg')       ddg.push(sR + ' → ' + tR);
                    else if (d.kind === 'Reads')     reads.push(sR + ' reads ' + tR);
                    else if (d.kind === 'Writes')    writes.push(sR + ' writes ' + tR);
                    else if (d.kind === 'Overrides') overs.push(sR + ' overrides ' + tR);
                    else if (d.kind === 'RefType')   reftypes.push(sR + ' inherits from ' + tR);
                  });
                  if (ddg.length) {
                    md.push('### Data flow (Ddg — value defined at source is used at target)');
                    md.push('');
                    ddg.forEach(x => md.push('- ' + x));
                    md.push('');
                  }
                  if (reads.length || writes.length) {
                    md.push('### Field accesses');
                    md.push('');
                    reads.forEach(x => md.push('- ' + x));
                    writes.forEach(x => md.push('- ' + x));
                    md.push('');
                  }
                  if (overs.length) {
                    md.push('### Virtual dispatch (Overrides)');
                    md.push('');
                    overs.forEach(x => md.push('- ' + x));
                    md.push('');
                  }
                  if (reftypes.length) {
                    md.push('### Type hierarchy');
                    md.push('');
                    reftypes.forEach(x => md.push('- ' + x));
                    md.push('');
                  }

                  // ── Interpretation guide ─────────────────────────────────────────
                  md.push('## How to read this file');
                  md.push('');
                  md.push('**Entity ref prefixes**');
                  md.push('');
                  md.push('| prefix | meaning |');
                  md.push('|---|---|');
                  md.push('| `T<n>` | JUnit test method |');
                  md.push('| `M<n>` | production method (target gets the first slot) |');
                  md.push('| `C<n>` | call site — a specific call expression in source |');
                  md.push('| `A<n>` | assert site — a `C` that invokes `assert*` / `verify*` / `expect*` |');
                  md.push('| `L<n>` | literal value (number, string, …) |');
                  md.push('| `F<n>` | class field |');
                  md.push('| `Y<n>` | class / interface / enum |');
                  md.push('');
                  md.push('**Relation semantics**');
                  md.push('');
                  md.push('- A call path `T1 → C5 → M2 → C7 → M3` reads: test T1 contains call site C5 which');
                  md.push('  invokes M2; M2 contains call site C7 which invokes M3; … until the target.');
                  md.push('- `X → Y` under *Data flow* means a value defined at X is consumed at Y');
                  md.push('  (literal → call argument, or expression → next-statement use).');
                  md.push('- `M reads F` / `M writes F`: method `M` accesses field `F`.');
                  md.push('- `Ma overrides Mb`: `Ma` is the concrete override of `Mb` — needed to reason about');
                  md.push('  which method actually runs at a virtual call site.');
                  md.push('- Step markers like *step k: virtual dispatch* mean that particular call was');
                  md.push('  resolved through virtual dispatch (interface / abstract / overridden method).');
                  md.push('');
                  md.push('**What is NOT in this file**');
                  md.push('');
                  md.push('- Method bodies — refer to source via the `location` field on each `M<n>`/`T<n>`.');
                  md.push('- Parameters and intermediate `Stmt` nodes — folded into signatures and call codes.');
                  md.push('- Control dependence (Cdg) edges — informally captured by the call paths.');
                  md.push('');
                  md.push('**Suggested ways to use this context**');
                  md.push('');
                  md.push('1. To answer "which tests cover `' + (target.fqn || 'the target') + '`?", list every `T<n>`.');
                  md.push('2. To answer "how does test `T<n>` reach the target?", find the path starting at `T<n>` under *Call paths*.');
                  md.push('3. To answer "what values flow into the target?", combine *Call paths* with *Data flow* edges into the relevant `C<n>`s.');
                  md.push('4. To answer "what does this method touch?", look at `M<n>` and follow `reads` / `writes` / outgoing `C<n>`s.');
                  md.push('');

                  return md.join('\\n');
                }

                // ─────────────────────────────────────────────────────────────────
                // GraphRAG JSON export.
                //
                // Microsoft's GraphRAG (Edge et al. 2024) stores its knowledge graph
                // as two tables: `entities` (id, type, title, description, attributes)
                // and `relationships` (source, target, type, description). At query
                // time both are loaded and serialised into the LLM prompt — sometimes
                // as raw JSON when the consumer is a tool-using agent, sometimes as
                // formatted markdown for plain chat models.
                //
                // We follow the same schema so this slice is interoperable with any
                // pipeline that already speaks GraphRAG. Note: real LLMs were NOT
                // trained on a single canonical "GraphRAG format" — they were trained
                // on general JSON/markdown — but this schema is the closest thing to
                // a community standard for graph-RAG augmentation and is widely
                // recognised by retrieval / agent frameworks.
                // ─────────────────────────────────────────────────────────────────
                function renderGraphRagJson(chop) {
                  const target = cy.getElementById(chop.target).data();
                  const chains = enumerateChains(chop);

                  // ── 1. Index entities + refs ─────────────────────────────────────
                  const idToRef = new Map();
                  const ordered = []; // for stable output order
                  const counters = { T: 0, M: 0, C: 0, A: 0, L: 0, F: 0, Y: 0 };
                  function take(prefix, id, data) {
                    counters[prefix]++;
                    const ref = prefix + counters[prefix];
                    idToRef.set(id, ref);
                    ordered.push({ ref, id, data });
                  }
                  // First the target method (so it's M1), then the rest.
                  if (target && target.kind === 'Method') take('M', chop.target, target);
                  chop.nodes.forEach(id => {
                    if (idToRef.has(id)) return;
                    const d = cy.getElementById(id).data();
                    if (!d) return;
                    if (d.kind === 'TestMethod')     take('T', id, d);
                    else if (d.kind === 'Method')    take('M', id, d);
                    else if (d.kind === 'CallSite')  take(chop.asserts.has(id) ? 'A' : 'C', id, d);
                    else if (d.kind === 'Literal')   take('L', id, d);
                    else if (d.kind === 'Field')     take('F', id, d);
                    else if (d.kind === 'Type')      take('Y', id, d);
                    // Parameter / Stmt deliberately skipped (noise).
                  });

                  // ── 2. Synthesise entity objects ────────────────────────────────
                  const entities = ordered.map(e => {
                    const d = e.data;
                    const isTargetEntity = e.id === chop.target;
                    const kindToType = {
                      TestMethod: 'TEST_METHOD',
                      Method:     'METHOD',
                      CallSite:   chop.asserts.has(e.id) ? 'ASSERT_SITE' : 'CALL_SITE',
                      Literal:    'LITERAL',
                      Field:      'FIELD',
                      Type:       'TYPE'
                    };
                    const type = kindToType[d.kind] || d.kind.toUpperCase();
                    const title = entityTitle(d, type);
                    const description = entityDescription(d, type, isTargetEntity, idToRef);
                    const attributes = entityAttributes(d, type, isTargetEntity, idToRef);
                    return { id: e.ref, type, title, description, attributes };
                  });

                  // ── 3. Relationships ────────────────────────────────────────────
                  const KIND_TO_REL = {
                    Calls:       'CALLS',
                    AstContains: 'CONTAINS', // we'll keep only the interesting ones
                    Ddg:         'DATA_FLOW',
                    Reads:       'READS',
                    Writes:      'WRITES',
                    Overrides:   'OVERRIDES',
                    RefType:     'INHERITS_FROM'
                  };
                  const relationships = [];
                  cy.edges().forEach(edge => {
                    const d = edge.data();
                    if (!chop.nodes.has(d.source) || !chop.nodes.has(d.target)) return;
                    const sRef = idToRef.get(d.source);
                    const tRef = idToRef.get(d.target);
                    if (!sRef || !tRef) return;
                    const relType = KIND_TO_REL[d.kind];
                    if (!relType) return;
                    if (relType === 'CONTAINS') {
                      // Only emit Method/TestMethod → CallSite/AssertSite (call-site anchoring).
                      // Skip Method → Parameter/Literal/Stmt etc. — they get filtered out anyway.
                      const tData = cy.getElementById(d.target).data();
                      if (!tData || tData.kind !== 'CallSite') return;
                    }
                    const rel = {
                      source: sRef,
                      target: tRef,
                      type: relType === 'CONTAINS' ? 'CONTAINS_CALL' : relType,
                      description: relDescription(relType === 'CONTAINS' ? 'CONTAINS_CALL' : relType, sRef, tRef, idToRef, d)
                    };
                    if (d.kind === 'Calls' && d.viaVirtual) rel.attributes = { via_virtual: true };
                    relationships.push(rel);
                  });

                  // ── 4. Call paths (denormalised for convenience) ────────────────
                  const paths = chains.map((c, i) => {
                    const steps = c.steps.map(step => {
                      const cs = findCallSite(step.callerId, step.calleeId);
                      return {
                        caller: idToRef.get(step.callerId) || null,
                        via_call_site: cs ? (idToRef.get(cs.id) || null) : null,
                        callee: idToRef.get(step.calleeId) || null,
                        via_virtual: !!step.edge.viaVirtual
                      };
                    });
                    return {
                      id: 'path-' + (i + 1),
                      depth: c.steps.length,
                      test: idToRef.get(c.testId) || null,
                      target: idToRef.get(chop.target) || null,
                      steps
                    };
                  });

                  // ── 5. Assemble the document ────────────────────────────────────
                  const doc = {
                    schema: 'graphrag-code-slice/v1',
                    generator: 'graph-tipper',
                    description: 'A graph-RAG style augmentation for a Java code slice. '
                               + 'Schema follows Microsoft GraphRAG (entities + relationships), '
                               + 'with code-specific entity / relationship types. Use the `interpretation` '
                               + 'block at the end to decode types if you have not seen them before.',
                    target: {
                      ref: idToRef.get(chop.target) || null,
                      fqn: target.fqn || null,
                      signature: target.signature || null,
                      file: target.file || null,
                      line: target.line > 0 ? target.line : null
                    },
                    scope: {
                      chop_node_count: chop.nodes.size,
                      tests_reaching_target: chop.tests.length,
                      distinct_call_paths: chains.length,
                      assert_sites: chop.asserts.size,
                      truncated: !!chop.truncated
                    },
                    entities,
                    relationships,
                    paths,
                    interpretation: {
                      entity_types: {
                        TEST_METHOD: 'JUnit test method that exercises a path reaching the target.',
                        METHOD: 'Production (non-test) method on a path from a test to the target.',
                        CALL_SITE: 'A specific call expression in source code (the "where" of a call).',
                        ASSERT_SITE: 'A CALL_SITE whose callee matches assert*/verify*/expect* — drives test verification.',
                        LITERAL: 'A literal value (number, string, null, …) that flows into a CALL_SITE.',
                        FIELD: 'A class field read or written by a method in the slice.',
                        TYPE: 'A class / interface / enum referenced by the slice (inheritance, declarations).'
                      },
                      relationship_types: {
                        CONTAINS_CALL: 'Source method/test syntactically contains target call site (anchor for the call).',
                        CALLS: 'Source call site invokes target method. attributes.via_virtual = true means virtual dispatch.',
                        DATA_FLOW: 'Value defined at source is consumed at target (Ddg edge — typically literal → call argument).',
                        READS: 'Source method reads target field.',
                        WRITES: 'Source method writes target field.',
                        OVERRIDES: 'Source method overrides target method (resolves virtual dispatch).',
                        INHERITS_FROM: 'Source type extends or implements target type.'
                      },
                      conventions: [
                        'Entity refs (id) are stable within one slice: T<n> = test, M<n> = method (M1 is always the target), C<n> = call site, A<n> = assert site, L<n> = literal, F<n> = field, Y<n> = type.',
                        'Use the `paths` array to answer "how does a test reach the target?" without re-traversing the graph.',
                        'Use `relationships` to ground claims about data flow / virtual dispatch / field effects.',
                        'Method bodies are NOT in this document — use entity.attributes.file + .line to fetch the source if needed.',
                        'Control-dependence edges (Cdg) are omitted; their information is largely covered by `paths`.'
                      ]
                    }
                  };

                  return JSON.stringify(doc, null, 2);
                }

                // ── helpers for the GraphRAG JSON exporter ───────────────────────
                function entityTitle(d, type) {
                  if (type === 'METHOD' || type === 'TEST_METHOD') return d.fqn || d.label;
                  if (type === 'CALL_SITE' || type === 'ASSERT_SITE') return (d.code || d.callee || d.label || '').replace(/\\s+/g, ' ').trim();
                  if (type === 'LITERAL') return (d.value || d.label || '').toString();
                  if (type === 'FIELD') return (d.ownerTypeFqn ? d.ownerTypeFqn + '.' : '') + (d.name || d.label);
                  if (type === 'TYPE') return d.fqn || d.label;
                  return d.label || '';
                }
                function entityDescription(d, type, isTarget, idToRef) {
                  const loc = d.file ? ' (' + d.file + (d.line > 0 ? ':' + d.line : '') + ')' : '';
                  if (type === 'TEST_METHOD') {
                    return 'JUnit test method `' + (d.fqn || d.label) + '`'
                         + (d.signature ? ' with signature `' + d.signature + '`' : '') + loc + '.';
                  }
                  if (type === 'METHOD') {
                    const t = isTarget ? ' This is the TARGET of the slice.' : '';
                    return 'Production method `' + (d.fqn || d.label) + '`'
                         + (d.signature ? ' with signature `' + d.signature + '`' : '') + loc + '.' + t;
                  }
                  if (type === 'CALL_SITE' || type === 'ASSERT_SITE') {
                    const verb = type === 'ASSERT_SITE' ? 'Assertion call' : 'Call expression';
                    const inMid = containingMethodOf(d.id);
                    const inRef = inMid ? (idToRef.get(inMid) || null) : null;
                    const where = inRef ? ', inside ' + inRef : '';
                    const callee = d.callee ? ', invoking `' + d.callee + '`' : '';
                    return verb + ' `' + (d.code || '').replace(/\\s+/g, ' ').trim() + '`'
                         + (d.line > 0 ? ' at line ' + d.line : '') + where + callee + '.';
                  }
                  if (type === 'LITERAL') {
                    const k = d.literalKind ? d.literalKind.toLowerCase() + ' ' : '';
                    return 'Source-level ' + k + 'literal `' + d.value + '`'
                         + (d.line > 0 ? ' at line ' + d.line : '') + '.';
                  }
                  if (type === 'FIELD') {
                    return 'Class field `' + (d.name || d.label) + '`'
                         + (d.type ? ' of type `' + d.type + '`' : '')
                         + (d.ownerTypeFqn ? ' declared in `' + d.ownerTypeFqn + '`' : '') + '.';
                  }
                  if (type === 'TYPE') {
                    const k = d.typeKind ? d.typeKind.toLowerCase() : 'type';
                    return 'Source-level ' + k + ' `' + (d.fqn || d.label) + '`' + loc + '.';
                  }
                  return '';
                }
                function entityAttributes(d, type, isTarget, idToRef) {
                  const a = {};
                  if (type === 'METHOD' || type === 'TEST_METHOD') {
                    if (d.fqn) a.fqn = d.fqn;
                    if (d.signature) a.signature = d.signature;
                    if (d.file) a.file = d.file;
                    if (d.line > 0) a.line = d.line;
                    if (type === 'TEST_METHOD' || d.isTest) a.is_test = true;
                    if (isTarget) a.is_target = true;
                  } else if (type === 'CALL_SITE' || type === 'ASSERT_SITE') {
                    if (d.code) a.code = (d.code).replace(/\\s+/g, ' ').trim();
                    if (d.callee) a.callee_fqn = d.callee;
                    if (d.line > 0) a.line = d.line;
                    if (d.argCount != null) a.arg_count = d.argCount;
                    const inMid = containingMethodOf(d.id);
                    const inRef = inMid ? (idToRef.get(inMid) || null) : null;
                    if (inRef) a.in_method = inRef;
                  } else if (type === 'LITERAL') {
                    if (d.value != null) a.value = d.value;
                    if (d.literalKind) a.literal_kind = d.literalKind;
                    if (d.line > 0) a.line = d.line;
                  } else if (type === 'FIELD') {
                    if (d.name) a.name = d.name;
                    if (d.type) a.type = d.type;
                    if (d.ownerTypeFqn) a.owner_type = d.ownerTypeFqn;
                  } else if (type === 'TYPE') {
                    if (d.fqn) a.fqn = d.fqn;
                    if (d.typeKind) a.type_kind = d.typeKind;
                    if (d.file) a.file = d.file;
                    if (d.line > 0) a.line = d.line;
                  }
                  return a;
                }
                function relDescription(relType, sRef, tRef, idToRef, edgeData) {
                  if (relType === 'CONTAINS_CALL') return sRef + ' syntactically contains call site ' + tRef + '.';
                  if (relType === 'CALLS')         return 'Call site ' + sRef + ' invokes method ' + tRef
                                                          + (edgeData && edgeData.viaVirtual ? ' (via virtual dispatch).' : '.');
                  if (relType === 'DATA_FLOW')     return 'Value at ' + sRef + ' flows into ' + tRef + ' (reaching-definitions).';
                  if (relType === 'READS')         return sRef + ' reads field ' + tRef + '.';
                  if (relType === 'WRITES')        return sRef + ' writes field ' + tRef + '.';
                  if (relType === 'OVERRIDES')     return sRef + ' overrides ' + tRef + '.';
                  if (relType === 'INHERITS_FROM') return sRef + ' inherits from ' + tRef + '.';
                  return '';
                }
                """;
    }

    // ---- Escaping ---------------------------------------------------------------------

    private static <T> Map<String, String> descriptions(Map<String, T> meta, java.util.function.Function<T, String> get) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : meta.entrySet()) out.put(e.getKey(), get.apply(e.getValue()));
        return out;
    }

    private static String asJsObject(Map<String, String> map) {
        StringBuilder sb = new StringBuilder().append('{');
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jstr(e.getKey())).append(':').append(jstr(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String jstr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
