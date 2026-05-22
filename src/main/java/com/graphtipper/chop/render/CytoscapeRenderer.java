package com.graphtipper.chop.render;

import com.graphtipper.chop.model.ChopGraph;

import java.io.Writer;

public final class CytoscapeRenderer {

    public void render(ChopGraph g, Writer out) {
        try {
            String json = new CytoscapeJson().build(g);
            String html = TEMPLATE
                .replace("/*__GRAPH_JSON__*/", json)
                .replace("/*__TARGET__*/", g.target().display());
            out.write(html);
        } catch (Exception e) {
            throw new RuntimeException("Cytoscape render failed", e);
        }
    }

    private static final String TEMPLATE = """
        <!doctype html><html><head><meta charset="utf-8">
        <title>Chop: /*__TARGET__*/</title>
        <style>
          body{font-family:Helvetica,Arial;margin:0;display:flex;height:100vh;background:#1a1a2e;color:#eee}
          #side{width:300px;padding:10px;border-right:1px solid #444;overflow:auto;background:#16213e;flex-shrink:0}
          #cy{flex:1;background:#0f3460}
          label{display:block;font-size:12px;margin:2px 0;cursor:pointer}
          h3{font-size:13px;margin:10px 0 4px;color:#e94560;text-transform:uppercase;letter-spacing:1px}
          #targetLabel{font-size:10px;color:#aaa;word-break:break-all;margin-bottom:6px}
          button{margin-top:8px;padding:4px 10px;background:#e94560;color:#fff;border:none;
                 border-radius:3px;cursor:pointer;font-size:12px}
          button:hover{background:#c73652}
          .view-btn{display:inline-block;margin:2px;padding:3px 7px;background:#0f3460;
                    color:#eee;border:1px solid #444;border-radius:3px;cursor:pointer;font-size:11px}
          .view-btn.active{background:#e94560;border-color:#e94560}
          #viewBar{margin-bottom:6px}
        </style>
        <script src="https://unpkg.com/cytoscape@3.30.2/dist/cytoscape.min.js"></script>
        <script src="https://unpkg.com/dagre@0.8.5/dist/dagre.min.js"></script>
        <script src="https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js"></script>
        </head><body>
        <div id="side">
          <h3>Target</h3>
          <div id="targetLabel">/*__TARGET__*/</div>
          <div id="viewBar">
            <span class="view-btn active" data-view="pdg">PDG</span>
            <span class="view-btn" data-view="cg">Call Graph</span>
            <span class="view-btn" data-view="full">Full</span>
          </div>
          <h3>Statements</h3><div id="stmtList"></div>
          <h3>Layers</h3><div id="layerList"></div>
          <button id="reset">Reset view</button>
        </div>
        <div id="cy"></div>
        <script>
          const data = /*__GRAPH_JSON__*/;
          if (typeof cytoscapeDagre !== 'undefined') cytoscape.use(cytoscapeDagre);
          const hasDagre = typeof cytoscapeDagre !== 'undefined';

          // Load graph WITHOUT compound parents for clean layout
          const flatNodes = data.nodes.map(n => ({
            data: Object.assign({}, n.data, { originalParent: n.data.parent }),
            // drop 'parent' so cy treats all nodes as top-level for layout
          }));
          // Remove parent from data so Cytoscape doesn't create compound nodes
          flatNodes.forEach(n => { delete n.data.parent; });

          const cy = cytoscape({
            container: document.getElementById('cy'),
            elements: { nodes: flatNodes, edges: data.edges },
            style: [
              { selector: 'node[kind="method"]', style: {
                  'shape':'roundrectangle', 'background-color':'#2a2a5a',
                  'border-color':'#555', 'border-width':2,
                  'label':'data(label)', 'color':'#eee', 'font-size':11,
                  'text-wrap':'wrap', 'text-max-width':160,
                  'width':180, 'height':40, 'text-valign':'center' } },
              { selector: 'node[kind="statement"]', style: {
                  'shape':'rectangle', 'background-color':'#1e3a5f',
                  'border-width':1, 'border-color':'#557',
                  'label':'data(label)', 'color':'#dde', 'font-size':9,
                  'text-wrap':'wrap', 'text-max-width':200,
                  'width':220, 'height':30, 'text-valign':'center' } },
              { selector: 'node[kind="expr"]', style: {
                  'shape':'ellipse', 'background-color':'#2d4a6e',
                  'border-width':1, 'border-color':'#446',
                  'label':'data(label)', 'color':'#bcd', 'font-size':8,
                  'text-wrap':'wrap', 'text-max-width':140,
                  'width':150, 'height':24 } },
              { selector: 'node[isTarget="true"]', style: { 'background-color':'#b8860b', 'border-color':'gold', 'border-width':2, 'color':'#fff' } },
              { selector: 'node[isTest="true"]', style:  { 'background-color':'#1a5276', 'border-color':'#5dade2', 'border-width':2 } },
              { selector: 'edge', style: {
                  'curve-style':'bezier', 'target-arrow-shape':'triangle',
                  'label':'data(layer)', 'font-size':7, 'color':'#aaa',
                  'text-background-color':'#0f3460', 'text-background-opacity':0.8, 'text-background-padding':1,
                  'width':1, 'line-color':'#555', 'target-arrow-color':'#555' } },
              { selector: 'edge[layer="DDG"]', style: { 'line-color':'#4a90d9', 'target-arrow-color':'#4a90d9' } },
              { selector: 'edge[layer="CFG"]', style: { 'line-color':'#777', 'target-arrow-color':'#777' } },
              { selector: 'edge[layer="CDG"]', style: { 'line-color':'#9b59b6', 'target-arrow-color':'#9b59b6', 'line-style':'dashed' } },
              { selector: 'edge[layer="CG"]', style:  { 'line-color':'#e74c3c', 'target-arrow-color':'#e74c3c', 'width':2 } },
              { selector: 'edge[layer="ARG_PASS"]', style: { 'line-color':'#2ecc71', 'target-arrow-color':'#2ecc71' } },
              { selector: 'edge[layer="RETURN_BIND"]', style: { 'line-color':'#1abc9c', 'target-arrow-color':'#1abc9c' } },
              { selector: 'edge[resolution="UNKNOWN"]', style: { 'line-style':'dotted', 'line-color':'#e74c3c', 'target-arrow-color':'#e74c3c' } },
              { selector: '.faded', style: { 'opacity':0.1 } },
              { selector: '.hidden', style: { 'display':'none' } }
            ],
            layout: { name: 'preset' }
          });

          // ── Layer toggles ──────────────────────────────────────────────────
          const layers = ['CG','DDG','CDG','CFG','AST','ARG_PASS','RETURN_BIND','OVERRIDES'];
          const layersOn = new Set(['CG','DDG','CDG','ARG_PASS','RETURN_BIND']);
          const layerList = document.getElementById('layerList');
          layers.forEach(l => {
            const wrap = document.createElement('label');
            const cb = document.createElement('input');
            cb.type='checkbox'; cb.checked=layersOn.has(l);
            cb.onchange = () => { cb.checked ? layersOn.add(l) : layersOn.delete(l); applyFilters(); };
            const dot = document.createElement('span');
            dot.style.cssText = 'display:inline-block;width:8px;height:8px;border-radius:50%;margin:0 4px;background:'
              + ({CG:'#e74c3c',DDG:'#4a90d9',CDG:'#9b59b6',CFG:'#777',ARG_PASS:'#2ecc71',RETURN_BIND:'#1abc9c'}[l]||'#aaa');
            wrap.appendChild(cb); wrap.appendChild(dot); wrap.appendChild(document.createTextNode(l));
            layerList.appendChild(wrap);
          });

          // ── Statement filter ───────────────────────────────────────────────
          const stmtList = document.getElementById('stmtList');
          cy.nodes('[kind="statement"][isTarget="true"]').forEach(n => {
            const wrap = document.createElement('label');
            const cb = document.createElement('input');
            cb.type='checkbox'; cb.dataset.id = n.id();
            cb.onchange = applyFilters;
            wrap.appendChild(cb);
            wrap.appendChild(document.createTextNode(' ' + n.data('label')));
            stmtList.appendChild(wrap);
          });

          function applyFilters() {
            // Layer filter
            cy.edges().forEach(e => {
              e.toggleClass('hidden', !layersOn.has(e.data('layer')));
            });
            // Statement (touchedBy) filter
            const sel = Array.from(stmtList.querySelectorAll('input:checked')).map(i=>i.dataset.id);
            if (sel.length === 0) { cy.elements().removeClass('faded'); return; }
            const ids = new Set(sel.map(id => id.replace('s_','')));
            cy.elements().addClass('faded');
            cy.elements().forEach(el => {
              const tb = (el.data('touchedBy')||'').split(',').filter(Boolean);
              if (tb.some(t => ids.has(t))) el.removeClass('faded');
            });
          }
          applyFilters();

          // ── Views ──────────────────────────────────────────────────────────
          const dagre = n => hasDagre ? { name:'dagre', rankDir:'TB', nodeSep:40, rankSep:70,
              animate:false, fit:true, padding:40, spacingFactor:1.3 } : { name:'cose', animate:false };

          function layoutAndFit(nodes, edges) {
            const hidden = cy.nodes().not(nodes);
            const hiddenEdges = cy.edges().not(edges);
            hidden.addClass('hidden'); hiddenEdges.addClass('hidden');
            nodes.removeClass('hidden'); edges.removeClass('hidden');
            nodes.layout(dagre()).run();
            cy.fit(nodes, 40);
          }

          function viewPDG() {
            // Flat: target-method statements + DDG/CDG/CFG between them
            const stmts = cy.nodes('[isTarget="true"]');
            const intra = cy.edges().filter(e =>
              ['CFG','CDG','DDG'].includes(e.data('layer')) && stmts.has(e.source()) && stmts.has(e.target()));
            layoutAndFit(stmts, intra);
          }

          function viewCG() {
            // Method-level call graph
            const methods = cy.nodes('[kind="method"]');
            const cg = cy.edges('[layer="CG"]');
            layoutAndFit(methods, cg);
          }

          function viewFull() {
            // All nodes except expr, active layers only
            const visible = cy.nodes('[kind!="expr"]');
            const edges = cy.edges().filter(e => layersOn.has(e.data('layer'))
              && visible.has(e.source()) && visible.has(e.target()));
            layoutAndFit(visible, edges);
          }

          document.querySelectorAll('.view-btn').forEach(btn => {
            btn.onclick = () => {
              document.querySelectorAll('.view-btn').forEach(b => b.classList.remove('active'));
              btn.classList.add('active');
              if (btn.dataset.view === 'pdg') viewPDG();
              else if (btn.dataset.view === 'cg') viewCG();
              else viewFull();
              applyFilters();
            };
          });

          document.getElementById('reset').onclick = () => {
            stmtList.querySelectorAll('input').forEach(i => i.checked=false);
            applyFilters();
            cy.fit(undefined, 40);
          };

          // Start with PDG view
          viewPDG();
        </script>
        </body></html>
        """;
}
