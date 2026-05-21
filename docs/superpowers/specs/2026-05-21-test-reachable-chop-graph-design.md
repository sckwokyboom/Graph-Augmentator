# Test-reachable Chop Graph — Design

**Date:** 2026-05-21
**Status:** Brainstormed, awaiting implementation plan
**Scope:** Proof-of-Concept inside Graph-Tipper. Target codebase for PoC: JGraphT-Builder. Production target (future, out of scope here): picocli `TextTable#putValue`.

## 1. Problem & Motivation

For a given method `M` (e.g. `TextTable#putValue` in picocli, or `BackwardSlicer#slicePerReturn` in JGraphT-Builder) and each statement `S` in `M`, we want a graph that simultaneously contains:

- **backward chop** — all statements/expressions across the project that deliver control and/or data into `S`, walking up the call graph from `M` to test entry points,
- **forward chop** — all statements/expressions across the project that `S` can affect through return values and outgoing data dependencies, all the way up to test assertions,

merged into a single graph (the "glue") with per-statement attribution so each `S`'s individual chop is recoverable as a sub-graph via filter.

The graph is the union of these chops over every `S ∈ M`, expressed on top of a full CPG (AST + CFG + CDG + DDG + CG + Overrides) but visualized with a controllable subset of layers, so it stays interpretable.

### Why this matters

This is not academic. The graph is the core artefact for two product directions:

1. **Static slicing of `putValue` for downstream artefact generation** in Graph-Tipper — extending the existing Tier 2 slicer with a fuller, interactive view.
2. **Context retrieval primitive for LLM coding agents / IDEs** — a precise, ranked, semantically-typed alternative to keyword/embedding search. See section 11.

## 2. Decisions (brainstorm summary)

| Aspect | Decision |
|---|---|
| Chop semantics | Full chop: backward (what delivers into `S`) AND forward (what `S` affects) in a single graph |
| Node granularity | Hybrid: `Statement` is a container/cluster, with `Expression` nodes (callsite, param, def, literal, predicate) visible inside |
| Edge layers (in model) | All of: AST, CFG, CDG, DDG, CG, Overrides, ARG_PASS, RETURN_BIND |
| Edge layers (default render) | CG + DDG + CDG + ARG_PASS + RETURN_BIND; toggleable in UI |
| Storage strategy | One union graph + `Set<StatementId> touchedBy` attribute on every node and edge (per-statement view recoverable by filter) |
| Entry-point definition | JUnit-annotated methods (`@Test`, `@ParameterizedTest`, `@RepeatedTest`, JUnit4 `@Test`) with heuristic fallback (path under `src/test/`, class name ends with `Test`/`Tests`/`IT`, method starts with `test`) |
| Virtual-call resolution | JavaParser SymbolSolver first; CHA over project class hierarchy as fallback; each call edge annotated with `ResolutionKind ∈ {EXACT, CHA, UNKNOWN}` |
| Location of code | New module `com.graphtipper.chop` inside Graph-Tipper; new CLI subcommand `graph-tipper chop` |
| Visualization backends | All three: Graphviz DOT → SVG, GraphML, Cytoscape.js standalone HTML |
| PoC target method | `com.github.sckwoky.typegraph.flow.BackwardSlicer#slicePerReturn` in JGraphT-Builder |
| Algorithmic approach | Hybrid: Joern provides inter-procedural call graph + virtual-call resolution; JavaParser provides per-method PDG; JGraphT composes them and runs reachability for chops |

## 3. Architecture

Pipeline in four phases. Each phase has a single responsibility and outputs a typed artefact consumed by the next.

```
┌──────────────────┐    ┌─────────────────────┐    ┌──────────────────┐
│  Joern Runner    │───▶│  Joern CPG (JSON)    │───▶│ CallGraphLoader  │
│  (existing)      │    │  Methods, Calls,    │    │  (new, com.gt    │
│                  │    │  Overrides          │    │   .chop.cg)      │
└──────────────────┘    └─────────────────────┘    └────────┬─────────┘
                                                            │ DirectedGraph<MethodRef, CallEdge>
                                                            ▼
┌──────────────────┐    ┌─────────────────────┐    ┌──────────────────┐
│  EntryPointFinder│    │  Target Resolver     │    │ ReachabilityScan │
│  (JUnit + heur)  │───▶│  --target FQN#m()    │───▶│  BFS reverse-CG  │
└──────────────────┘    └─────────────────────┘    │  target ↦ tests  │
                                                    └────────┬─────────┘
                                                             │ Set<MethodRef> involvedMethods
                                                             ▼
                                       ┌─────────────────────────────────────┐
                                       │  PdgBuilder (per involved method)    │
                                       │  JavaParser + SymbolSolver           │
                                       │  emits: MethodPDG                    │
                                       └────────────────┬────────────────────┘
                                                        │ Map<MethodRef, MethodPDG>
                                                        ▼
                                       ┌─────────────────────────────────────┐
                                       │  ChopComposer                        │
                                       │  - union per-method PDGs             │
                                       │  - splice callsites → params,        │
                                       │    returns → callsite use            │
                                       │  - annotate edges (Layer, Resolution)│
                                       │  emits: DirectedMultigraph<ChopNode, │
                                       │          ChopEdge>                   │
                                       └────────────────┬────────────────────┘
                                                        │
                                                        ▼
                                       ┌─────────────────────────────────────┐
                                       │  ChopAnnotator                       │
                                       │  for each target statement S:        │
                                       │    backward+forward reachability,    │
                                       │    add S to node.touchedBy[],       │
                                       │    edge.touchedBy[]                  │
                                       └────────────────┬────────────────────┘
                                                        │
                                                        ▼
                                       ┌─────────────────────────────────────┐
                                       │  Renderers (parallel)                │
                                       │  DotRenderer   → chop.dot           │
                                       │  GraphMLRenderer → chop.graphml     │
                                       │  CytoscapeRenderer → chop.html      │
                                       └─────────────────────────────────────┘
```

### Reuse from existing Graph-Tipper

- `JoernRunner`, `ProcessJoernInvoker`, `CpgImporter` — already in `com.graphtipper.cpg`.
- `ReverseCallChainExtractor` — same reverse-BFS pattern; we adapt it to emit JGraphT graph instead of chain list.
- JavaParser + SymbolSolver wiring used by `StaticSlicer`.
- picocli subcommand pattern; output directory + caching conventions; fuzzy-match for ambiguous target.

### What is new

`com.graphtipper.chop.*`:
- `cg.CallGraphLoader`
- `entry.EntryPointFinder`
- `pdg.PdgBuilder`, `pdg.MethodPDG`
- `model.{ChopNode, ChopEdge, ChopGraph, MethodRef, StatementId, ExprId, SourceRange}`
- `compose.ChopComposer`
- `annotate.ChopAnnotator`
- `render.{DotRenderer, GraphMLRenderer, CytoscapeRenderer}`
- `cli.ChopCommand`

## 4. Data Model

```java
record MethodRef(String fqn, String signature) {}     // com.example.Foo#bar(int,String)
record StatementId(MethodRef owner, int astNodeId) {} // stable JavaParser node fingerprint
record ExprId(MethodRef owner, int astNodeId) {}
record SourceRange(String filePath, int startLine, int startCol, int endLine, int endCol) {}

sealed interface ChopNode {
    MethodRef owner();
    Set<StatementId> touchedBy();  // mutable; filled by ChopAnnotator
    boolean isTarget();
    boolean isEntryPoint();
}

record StatementNode(
    StatementId id, MethodRef owner,
    Kind kind,                      // IF, WHILE, FOR, RETURN, EXPR, THROW, TRY, CATCH, SWITCH, BLOCK
    String displayText, SourceRange src,
    Set<StatementId> touchedBy,
    boolean isTarget, boolean isEntryPoint
) implements ChopNode {}

record ExprNode(
    ExprId id, MethodRef owner,
    StatementId enclosingStatement,
    Kind kind,                      // CALLSITE, PARAM, LOCAL_DEF, FIELD_REF,
                                    // LITERAL, RETURN_VALUE, BRANCH_PREDICATE
    String displayText, SourceRange src,
    Set<StatementId> touchedBy,
    boolean isTarget, boolean isEntryPoint
) implements ChopNode {}

record MethodNode(                  // compound boundary node for layout
    MethodRef ref,
    boolean isTest, boolean isTarget,
    Set<StatementId> touchedBy
) implements ChopNode {}

enum EdgeLayer { AST, CFG, CDG, DDG, CG, OVERRIDES, ARG_PASS, RETURN_BIND }
enum ResolutionKind { EXACT, CHA, UNKNOWN }    // for CG/OVERRIDES only
enum DataKind { DEF_USE, KILL, ARG, RETURN }   // for DDG-family only

record ChopEdge(
    ChopNode src, ChopNode dst,
    EdgeLayer layer,
    ResolutionKind resolution,      // nullable; set when layer ∈ {CG, OVERRIDES}
    DataKind dataKind,              // nullable; set when layer ∈ {DDG, ARG_PASS, RETURN_BIND}
    String label,                   // e.g. "values[col]"
    Set<StatementId> touchedBy
) {}

final class ChopGraph {
    DirectedMultigraph<ChopNode, ChopEdge> jgraph;
    MethodRef target;
    List<StatementId> targetStatements;     // source order
    Set<MethodRef> entryPoints;
    // metadata: build timings, sizes, options
}
```

### Notes on model choices

- `touchedBy` is mutable for performance (annotator writes in place; copying a multi-thousand-node graph N times would be wasteful). Treated as effectively-final after the annotation phase.
- `MethodNode` is a compound boundary used by renderers for clustering. Statement/Expr nodes record `owner: MethodRef`, which links them to their `MethodNode`.
- Stable AST IDs are `hash(filePath, JavaParser Range)`, so re-parsing the same file yields the same IDs (necessary for cross-pass joins).

## 5. Algorithm

### Phase 1 — Reachability scan

Produces `involvedMethods` (every method on at least one path from target up to an entry point) and `entryPoints`.

```text
involvedMethods = { target }
worklist = queue([target])
while worklist not empty:
    m = worklist.pop()
    for caller in CG.reverseEdges(m):            // Joern Calls + Overrides
        if caller not in involvedMethods:
            involvedMethods.add(caller)
            worklist.push(caller)
        if EntryPointFinder.isEntry(caller):
            entryPoints.add(caller)
            // do not stop: caller may itself have callers (test base classes, helpers)
```

Cut-offs: `--max-depth` (default unlimited), `--max-methods` (default 500; exit code 3 if exceeded).

### Phase 2 — PDG enrichment

For each `m ∈ involvedMethods`, parse with JavaParser and produce a `MethodPDG`:

- **CFG**: walk body, emit `StatementNode` per `Statement`; emit CFG edges per Java semantics (`if`/`while`/`for`/`try`/`return`/`throw`/`switch`).
- **CDG**: classical Ferrante-Ottenstein-Warren control-dependence (immediate post-dominator algorithm). Emits `predicate-node --CDG--> S` for every `S` whose execution is controlled by `predicate-node`.
- **DDG (intra-procedural)**: for each use `u` of variable `v` in `S`, find reaching definitions `d`; emit `d --DDG{DEF_USE}--> u`. SymbolSolver resolves names to parameters/locals/fields.
- **Callsites**: every method call inside `m` becomes an `ExprNode{kind=CALLSITE}` inside its enclosing statement cluster. Cross-method resolution is deferred to phase 3.
- **AST**: between expressions within a statement cluster — only for the "expand inside" UI affordance; not used for chop reachability.

Output: `Map<MethodRef, MethodPDG>`.

### Phase 3 — Composition (inter-procedural splice)

For each `(M1, callsite_expr)` in `MethodPDG(M1)` and each callee `M2` per Joern (with `ResolutionKind`):

```text
for (i, argExpr) in callsite_expr.arguments:
    paramNode = pdgs[M2].paramAt(i)
    add edge argExpr --ARG_PASS{DataKind=ARG, resolution}--> paramNode

for returnNode in pdgs[M2].returnNodes:
    add edge returnNode --RETURN_BIND{DataKind=RETURN, resolution}--> callsite_expr

add edge M1.MethodNode --CG{resolution}--> M2.MethodNode
```

Output: single `DirectedMultigraph<ChopNode, ChopEdge>` carrying all layers simultaneously.

### Phase 4 — Per-statement annotation

For each `S ∈ targetStatements`:

```text
BACKWARD_LAYERS = { DDG, CDG, CFG, ARG_PASS, CG, OVERRIDES }
FORWARD_LAYERS  = { DDG, CFG, RETURN_BIND, CG, OVERRIDES }

backwardReachable = reverseBFS(S, edge -> edge.layer ∈ BACKWARD_LAYERS)
forwardReachable  = forwardBFS(S, edge -> edge.layer ∈ FORWARD_LAYERS)
chopOfS = backwardReachable ∪ forwardReachable

for node in chopOfS:
    node.touchedBy.add(S.id)
for edge in inducedSubgraph(chopOfS).edges:
    edge.touchedBy.add(S.id)
```

AST edges are excluded from reachability — they exist solely for visual "expand inside statement".

Complexity: `O(|targetStatements| × |graph|)` worst-case. For `slicePerReturn` (~12 statements, expected ~200 node graph) trivial. For picocli scale, future work: cache SCC condensation and dominator relations, reuse across `S`.

### Deferred (not in PoC)

- **Context-sensitivity via SDG summary edges** — currently `touchedBy` is a flat union across calling contexts. Different test paths into the same library method are not distinguished. Adding Reps-Horwitz-Sagiv summary edges is future work.
- **Inter-procedural alias / heap analysis** — data flow through object fields is approximated. Emit `UNKNOWN`-marked edges where SymbolSolver fails.
- **Lambdas / method references** — resolved as `UNKNOWN` calls for PoC.

## 6. Visualization

### 6.1 `chop.dot` (Graphviz)

Static artefact suitable for PR/Markdown embedding.

- `rankdir=TB`; methods as `subgraph cluster_M_owner`; statement-clusters nested inside.
- Colour/style per `EdgeLayer`: DDG=blue solid, CFG=gray solid, CDG=purple dashed, CG=black bold, ARG_PASS/RETURN_BIND=green.
- `ResolutionKind`: CHA=dashed, EXACT=solid, UNKNOWN=dotted red.
- `isTarget` nodes: `fillcolor=gold`; `isEntryPoint`: `fillcolor=lightblue`.
- If graph > 300 nodes, render only default layers; mention this in metadata.

### 6.2 `chop.html` (Cytoscape.js)

The interactive view — interpretability stands or falls here.

- Standalone HTML, all data + Cytoscape.js inlined; no server.
- **Layout**: `dagre`, `rankDir: TB`. Compound nodes (methods) cluster around their statement/expression children automatically.
- **Left panel — controls**:
  - List of target statements with checkboxes for filter.
  - Layer toggles (CG / DDG / CDG / CFG / AST / ARG_PASS / RETURN_BIND / Overrides).
  - Resolution toggles (EXACT / CHA / UNKNOWN).
  - Reset view; Export PNG.
- **Primary UX — per-statement highlight**: click a statement → all nodes/edges with `S ∈ touchedBy` stay full-colour, everything else fades to `opacity 0.15`. Multi-select unions. Reset clears. This is the "individual sub-graph per statement" from the original requirement — one model, filter-based.
- **Default layer preset**: ON = CG, DDG, CDG, ARG_PASS, RETURN_BIND; OFF = CFG, AST, Overrides.
- **Tooltips**: `displayText`, full `SourceRange` (clickable for IDE-future), `touchedBy` summary.

### 6.3 `chop.graphml`

JGraphT's `GraphMLExporter` with all custom attributes (`layer`, `resolution`, `dataKind`, joined `touchedBy`). Lets users feed the graph into Gephi/yEd/graph-tool for metrics, community detection, custom layouts. Effectively free.

### 6.4 Interpretability objectives

1. **Compound nodes** make methods visible as boxes; eye scans by method boundaries, not symbol soup.
2. **Per-statement filter** answers the precise question "how do tests influence S?" rather than dumping everything at once.
3. **Layer toggles** remove noise on demand; AST is hidden by default.
4. **Resolution styling** shows where analysis degrades.
5. **Source ranges everywhere** make every node IDE-clickable (future bridge).

### 6.5 Out of scope for PoC

- Method-box collapse/expand.
- Diff between two chops (before/after refactor).
- Animations / force-directed layouts (counterproductive for hierarchical code).

## 7. CLI

```
graph-tipper chop \
  --project <dir>             # absolute path to target repo
  --target <FQN>#<method>     # e.g. com.example.Foo#bar or Foo#bar(int,String)
  --out <dir>                 # default: ./out/chop/
  --max-depth <n>             # default unlimited
  --max-methods <n>           # default 500 (guardrail; exit 3 if exceeded)
  --layers <comma-list>       # default: CG,DDG,CDG,ARG_PASS,RETURN_BIND
                              # PdgBuilder builds ALL layers; this controls DEFAULT render
  --joern-home <dir>          # as in existing slice command
  --no-cache                  # as in existing

Exit codes:
  0 — success; three artefacts in --out
  1 — generic error
  2 — target not found (with fuzzy candidates)
  3 — --max-methods exceeded
  4 — cannot start Joern
```

CLI is a picocli subcommand alongside existing `slice` etc. No new global flags.

## 8. Error Handling

| Condition | Behaviour |
|---|---|
| Joern fails to start / crash | Exit 4 with hint about `--joern-home` / env vars |
| Target FQN not found | Exit 2 with fuzzy-match candidates (reuse `slice` code) |
| SymbolSolver cannot resolve type | Do not fail; emit edge with `ResolutionKind.UNKNOWN`. Visible UNKNOWN is more useful than a hard error |
| `targetStatements` empty (abstract/empty body) | Exit 2 with "target has empty body, nothing to chop" |
| `--max-methods` exceeded during reachability scan | Exit 3 with current count and hint to raise the limit |
| `entryPoints` empty (no test reaches target) | Do not fail; render artefacts with empty `entryPoints` and a logged warning. Empty-coverage is itself a meaningful signal |
| Ambiguous target match | Exit 1 with list of matching candidates printed to stderr |

No retry logic. One Joern run; on failure, exit and let the user decide.

## 9. Testing

### Unit tests (`src/test/java/com/graphtipper/chop/`)

- `CallGraphLoaderTest` — small Joern JSON → JGraphT call graph.
- `EntryPointFinderTest` — JUnit annotation, src/test path, name suffix.
- `PdgBuilderTest` — fixture per Statement Kind; verify nodes and edges (def-use, CDG immediate post-dominator).
- `ChopComposerTest` — two-method fixture (A calls B, B returns); verify ARG_PASS and RETURN_BIND splice.
- `ChopAnnotatorTest` — small hand-built graph; verify `touchedBy` populated correctly per statement.
- `DotRendererTest` — golden file.
- `GraphMLRendererTest` — XML schema validation.
- `CytoscapeRendererTest` — embedded Cytoscape JSON present, calls correct API; HTML opens (parseable).

### Integration / acceptance

`JGraphTBuilderChopIntegrationTest` runs the full pipeline against JGraphT-Builder:

```
graph-tipper chop \
  --project /Users/sckwoky/Projects/JGraphT-Builder \
  --target com.github.sckwoky.typegraph.flow.BackwardSlicer#slicePerReturn \
  --out <tmp>
```

Asserts:

- Three artefacts exist and are non-empty.
- Graph contains a `MethodNode` for `slicePerReturn`, `MethodNode`s for all reachable callers, ≥1 `MethodNode` with `isEntryPoint=true`.
- All ~12 statements of `slicePerReturn` present as `StatementNode`.
- HTML contains an embedded Cytoscape JSON with the expected structure.

### What we do not test

- Accuracy of JavaParser SymbolSolver's def-use chains (treated as a black box).
- Joern call-graph correctness (treated as a black box).
- Visual fidelity of HTML (manual check at acceptance).

## 10. PoC Acceptance

PoC is "done" when:

1. `graph-tipper chop --project ... --target com.github.sckwoky.typegraph.flow.BackwardSlicer#slicePerReturn --out out/` succeeds.
2. `out/chop.dot`, `out/chop.graphml`, `out/chop.html` exist.
3. Opening `chop.html` in a browser shows: clustered method boxes; statements visible inside; ≥1 method labelled `[TEST]`; the target method labelled `[TARGET]`; clicking a statement of `slicePerReturn` correctly fades non-related nodes/edges.
4. Manual sanity check: at least one test method in `MethodFlowBuilderTest` is reachable through `BackwardSlicer#slicePerReturn` in the rendered graph.
5. Integration test described in §9 passes in CI.

## 11. Motivation for LLM-coding-agent IDEs (context)

### 11.1 Today's pain

Current coding agents (Claude Code, Cursor, Aider, Cline) build context via text-level mechanisms: keyword search, embedding search, file globs, at best LSP references. On non-trivial tasks ("change behaviour of `putValue`") they end up packaging:

- whole files (most of which is irrelevant),
- symbol-expansion blobs (everything that mentions the name, half of which is not on the execution path),
- no information about *why* a branch fires ("this `if` is true because the test passed N").

Outcomes: context overflow, hallucinated execution paths, fixes in the wrong place.

### 11.2 What a chop graph gives

A semantically-precise "context retrieval" for one point in code:

- **Chop nodes = the minimal code required to reason about `S`.** Not all references — exactly the statements that deliver control/data into `S` plus those it can affect.
- **`touchedBy` cardinality = relevance ranking.** A node in 8 of 12 statement chops is critical to understanding the whole method; a node in one chop is niche.
- **Layer filters = adjustable level of detail.** "Give me only data flow" or "only control conditions" instead of always-all.
- **`ResolutionKind` = honest uncertainty.** Agent sees where analysis degrades to CHA or UNKNOWN, reasons about it instead of hallucinating.
- **Entry-point → S patterns = test context.** Agent immediately sees which tests reach `S` and through which paths — direct input to risk-aware refactoring.

### 11.3 Concrete IDE use cases

| Use case | What chop provides |
|---|---|
| Inline "what affects this line" | Single-statement chop; 10-50× less context than "open all relevant files" |
| Pre-edit "what tests cover this" | List of entry-points reaching changed statements; coverage gaps surfaced |
| Refactor planning | Inter-method data dependencies the refactor must preserve |
| Bug localization from failing assert | Forward chop from assert traces back through RETURN_BIND/DDG to candidate root causes |
| Diff explanation | For a method change, predict affected tests + paths before running |

### 11.4 What is needed to make this a production IDE feature

PoC delivers CLI + static artefacts. Production needs:

1. **Incremental chop** — cache per-file PDGs, invalidate on AST diff. Known-hard but proven (Joern, Sourcegraph SCIP).
2. **MCP / LSP server** — thin wrapper exposing `chop(target_file, target_line) → ChopGraph (JSON)` as an agent tool. Direct extension of current JSON output.
3. **Sub-graph extraction API** — `chop.summary(maxTokens=N)` returning top-K nodes by `touchedBy`-weight or a specific layer slice.
4. **Cross-language** — Joern supports Python/JS/Go; each needs its own `PdgBuilder`; architecture (Joern CG + per-language PDG + JGraphT compose) is language-agnostic.
5. **Persistent IPC** — gRPC or LSP-style stdio for sub-second IDE responsiveness.

### 11.5 Honest limitations

- **Java reflection, interfaces, lambdas** without heap analysis leave many `UNKNOWN` edges. Either accept and surface them, or invest in proper points-to (large effort).
- **Cost of building** — Joern on picocli takes seconds. Intra-edit responsiveness requires incremental mode.
- **Centrally-used utilities** — chop of `String#equals` is "almost everything". Cut-offs (`--max-methods`, top-K weight) required.
- **Vs simpler retrieval** — embedding search is cheap and sometimes good enough. Chop graph earns its cost where understanding must not hallucinate: refactoring, test-coupled bug fixing, behavioural-change planning. Not for "tell me about this class".
- **Agent prompt design** — graph structures are not natively understood by every LLM. Prompt templates explaining how to read the chop will likely be needed.

### 11.6 Where PoC sits on the roadmap

PoC is step 0. It proves:

1. Construction and visualization are technically feasible.
2. The graph is interpretable to a human reader.
3. Joern + JavaParser + JGraphT composition is the right architecture.

Subsequent steps in priority order: scale to picocli `putValue` → MCP/JSON tool for agents → incremental rebuild → cross-language `PdgBuilder`s.

## 12. Future Work (out of PoC scope)

- Context-sensitivity via Reps-Horwitz-Sagiv summary edges.
- Heap / alias analysis for field-mediated data flow.
- Lambda and method-reference resolution beyond `UNKNOWN`.
- Method-box collapse, chop diff view, anchor selection by source line.
- MCP server exposing chop as agent tool.
- Incremental rebuild and persistent caching.
- Cross-language `PdgBuilder` implementations.
