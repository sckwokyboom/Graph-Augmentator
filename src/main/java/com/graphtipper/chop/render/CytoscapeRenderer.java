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
          body { font-family: Helvetica, Arial; margin:0; display:flex; height:100vh; }
          #side { width:280px; padding:8px; border-right:1px solid #ccc; overflow:auto; }
          #cy { flex:1; }
          label { display:block; font-size:12px; }
          h3 { font-size:14px; margin:8px 0 4px; }
        </style>
        <script src="https://unpkg.com/cytoscape@3.30.2/dist/cytoscape.min.js"></script>
        <script src="https://unpkg.com/dagre@0.8.5/dist/dagre.min.js"></script>
        <script src="https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js"></script>
        </head><body>
        <div id="side">
          <h3>Target</h3><div id="targetLabel">/*__TARGET__*/</div>
          <h3>Statements</h3><div id="stmtList"></div>
          <h3>Layers</h3><div id="layerList"></div>
          <h3>Resolution</h3><div id="resList"></div>
          <button id="reset">Reset view</button>
        </div>
        <div id="cy"></div>
        <script>
          const data = /*__GRAPH_JSON__*/;
          if (typeof cytoscapeDagre !== 'undefined') cytoscape.use(cytoscapeDagre);
          const cy = cytoscape({
            container: document.getElementById('cy'),
            elements: { nodes: data.nodes, edges: data.edges },
            style: [
              { selector: 'node[kind="method"]', style: { 'shape':'roundrectangle',
                  'background-color':'#eee', 'label':'data(label)', 'text-valign':'top',
                  'padding': '14px' } },
              { selector: 'node[kind="statement"]', style: { 'shape':'rectangle',
                  'background-color':'#fff', 'border-width':1, 'border-color':'#666',
                  'label':'data(label)', 'font-size':10 } },
              { selector: 'node[kind="expr"]', style: { 'shape':'ellipse',
                  'background-color':'#cce', 'label':'data(label)', 'font-size':9 } },
              { selector: 'node[isTarget = "true"]', style: { 'background-color':'gold' } },
              { selector: 'node[isTest = "true"]', style: { 'background-color':'#cdf' } },
              { selector: 'edge', style: { 'curve-style':'bezier', 'target-arrow-shape':'triangle',
                  'label':'data(layer)', 'font-size':8, 'width':1 } },
              { selector: 'edge[layer="DDG"]', style: { 'line-color':'blue', 'target-arrow-color':'blue' } },
              { selector: 'edge[layer="CFG"]', style: { 'line-color':'gray', 'target-arrow-color':'gray' } },
              { selector: 'edge[layer="CDG"]', style: { 'line-color':'purple', 'target-arrow-color':'purple',
                  'line-style':'dashed' } },
              { selector: 'edge[layer="CG"]', style: { 'line-color':'#000', 'target-arrow-color':'#000', 'width':2 } },
              { selector: 'edge[layer="ARG_PASS"], edge[layer="RETURN_BIND"]',
                  style: { 'line-color':'green', 'target-arrow-color':'green' } },
              { selector: 'edge[resolution="CHA"]', style: { 'line-style':'dashed' } },
              { selector: 'edge[resolution="UNKNOWN"]',
                  style: { 'line-style':'dotted', 'line-color':'red', 'target-arrow-color':'red' } },
              { selector: '.faded', style: { 'opacity':0.15 } }
            ],
            layout: typeof cytoscapeDagre !== 'undefined' ? { name: 'dagre', rankDir: 'TB' } : { name: 'cose' }
          });

          const layers = ['CG','DDG','CDG','CFG','AST','ARG_PASS','RETURN_BIND','OVERRIDES'];
          const layersOn = new Set(['CG','DDG','CDG','ARG_PASS','RETURN_BIND']);
          const layerList = document.getElementById('layerList');
          layers.forEach(l => {
            const wrap = document.createElement('label');
            const cb = document.createElement('input');
            cb.type = 'checkbox'; cb.checked = layersOn.has(l);
            cb.onchange = () => { cb.checked ? layersOn.add(l) : layersOn.delete(l); applyLayerFilter(); };
            wrap.appendChild(cb); wrap.appendChild(document.createTextNode(' '+l));
            layerList.appendChild(wrap);
          });
          function applyLayerFilter() {
            cy.edges().forEach(e => { e.style('display', layersOn.has(e.data('layer')) ? 'element' : 'none'); });
          }
          applyLayerFilter();

          const stmts = cy.nodes('node[kind="statement"][isTarget = "true"]').map(n => n.data());
          const stmtList = document.getElementById('stmtList');
          stmts.forEach(s => {
            const wrap = document.createElement('label');
            const cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.onchange = () => applyStmtFilter();
            cb.dataset.id = s.id;
            wrap.appendChild(cb); wrap.appendChild(document.createTextNode(' '+s.label));
            stmtList.appendChild(wrap);
          });
          function applyStmtFilter() {
            const selected = Array.from(stmtList.querySelectorAll('input:checked')).map(i => i.dataset.id);
            if (selected.length === 0) { cy.elements().removeClass('faded'); return; }
            const selStmtSet = new Set(selected.map(id => id.replace('s_','')));
            cy.elements().addClass('faded');
            cy.elements().forEach(el => {
              const tb = (el.data('touchedBy') || '').split(',').filter(x => x);
              if (tb.some(t => selStmtSet.has(t))) el.removeClass('faded');
            });
          }
          document.getElementById('reset').onclick = () => {
            stmtList.querySelectorAll('input').forEach(i => i.checked = false);
            applyStmtFilter();
          };
        </script>
        </body></html>
        """;
}
