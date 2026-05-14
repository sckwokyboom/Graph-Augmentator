# Graph-Tipper: AST-aware snippets + graph output

> Status: Design approved. Implementation plan to follow via `superpowers:writing-plans`.
> Scope: Augmentation quality (snippet extraction) + additional output formats. Builds on the V1 pipeline; does not touch CPG construction.
> Out of scope (this spec): Reflection-aware call resolution (`@MethodSource`, `Class.forName(literal)`, etc.). Acknowledged as the next gap; tracked separately.

## 1. Motivation

The V1 pipeline (Joern → ProjectGraph → ReverseCallChainExtractor → MarkdownRenderer) is correct end-to-end but produces low-quality snippets and only one output file. Two observed problems:

1. **Snippets are extracted by raw line range** (`SourceFragmentReader.readAround(line, before, after)`). The window doesn't respect AST boundaries, so the rendered code routinely starts with leading lines that are tail-fragments of preceding statements (dangling `}`, `};`, etc.) and ends with unrelated trailing code. This noise actively misleads an LLM that consumes the file.

2. **Only one rendered artifact (`<hash>.md`) under a fixed 20k-token budget**, so users either see a budget-shaped excerpt or have to opt into `--no-budget` and get a different file shape. Agents can't pick between "compact" and "complete" based on their context size; there's no graph-shaped representation for agents that reason better over explicit node/edge structures.

The user's primary use case: feed the resulting file(s) to an LLM agent that must produce a correct implementation of a target method **one-shot**, without iterating over test runs. Snippet correctness is the highest-leverage axis; multiple output shapes are secondary convenience.

## 2. Goals

- **G1.** Snippets reflect dataflow: for each call site in a chain, emit the enclosing method signature plus only the statements that actually contribute to the call's arguments (intra-method backward slice).
- **G2.** Three artifacts emitted per run by default — `<hash>.budget.md`, `<hash>.full.md`, `<hash>.graph.json` — covering compact-for-window, complete, and structured-for-agents use cases.
- **G3.** Backward-compatible CLI: existing flags continue to work; their semantics narrow rather than break.
- **G4.** Degradations remain explicit: the graph output carries a `degradations` array that captures every quality-loss event (orphan METHOD_REF, slice truncations, parse failures, etc.).

## 3. Non-goals

- Reflection-aware call graph (intentionally deferred).
- Cross-method (interprocedural) program slicing — intra-method backward slice only.
- A new CPG schema or any change to `prepare-and-export.sc`.
- Replacing JavaParser with tree-sitter or another parser.
- An XML variant of the graph output (JSON only; XML can be added later if a model needs it).

## 4. Architecture

```
                  javasrc2cpg → CPG → prepare-and-export.sc → export.json
                                                                  │
                         ┌────────────────────────────────────────┘
                         ▼
                  CpgImporter → ProjectGraph
                         │
                         ▼
                  MethodLocator → targetMethod
                         │
                         ▼
                  ReverseCallChainExtractor (unlimited) → List<Chain>
                         │
                         ▼
                  CallSiteSlicer ──▶ AstSnippetExtractor  ← NEW
                         │             ↑   (JavaParser, intra-method
                         │             │    backward slice on arg
                         │             ▼    identifiers)
                         │           Slice { sigLine, body[], callLine, truncated }
                         ▼
                  enrichedChains: List<Chain> (slice attached per CallStep)
                         │
                  ┌──────┼─────────────────┬─────────────────────────────┐
                  ▼      ▼                 ▼                             ▼
           BudgetPlanner Markdown        Markdown                   GraphJsonRenderer  ← NEW
           (20k)         (full chains)   (top-N chains)             (vertices+edges+chains)
                  │      │                 │                             │
                  ▼      ▼                 ▼                             ▼
            <hash>.json  <hash>.full.md  <hash>.budget.md          <hash>.graph.json
            (legacy)
```

### New components

**`AstSnippetExtractor`** (in `com.graphtipper.slice` or `.util`).
Encapsulates JavaParser, holds a `Map<Path, CompilationUnit>` cache, and exposes a single method:

```java
Slice sliceAt(Path file,
              int callLine,
              int callColumn,
              String calleeSimpleName,
              List<String> argExpressionHints,
              int maxSliceStmts);

record Slice(String methodSignatureLine,
             List<String> body,
             int callLine,
             boolean truncated,
             List<String> warnings);
```

**`GraphJsonRenderer`** (in `com.graphtipper.render`).
Pure stateless rendering: `String render(Artifact a, GraphRenderContext ctx)`.

### Changed components

- **`CallSiteSlicer`**: stops calling `SourceFragmentReader.readAround`; calls `AstSnippetExtractor.sliceAt` and embeds the result in `CallStep.snippet` plus structured `ArgOrigin` entries.
- **`ReverseCallChainExtractor`**: `maxChains` parameter is removed from the call site in `Main`. The extractor always runs unbounded; downstream renderers slice the list as needed. `frontierGuard` becomes a fixed safety (`100_000`) instead of `maxChains * 8`.
- **`Main`**: orchestrates three renders from a single enriched artifact. `--max-chains` becomes a budget-md-only knob; default stays 16. `--budget-tokens` likewise applies only to budget.md. `--no-budget` is retained as a deprecated no-op (always-emit-all is the new default for full.md and graph.json).

## 5. Component design: AstSnippetExtractor

### 5.1 Algorithm

1. **Parse + cache.** `JavaParser.parse(file)` once per file; store the resulting `CompilationUnit` in an instance-level `LinkedHashMap` with capacity 256 (LRU). On parse failure, return a `Slice` flagged with `warning = "parse_failed"` and a `readAround(callLine, 3, 3)` fallback body. JavaParser is configured with `ParserConfiguration.LanguageLevel.JAVA_21` and `setStoreTokens(true)` for accurate line ranges.

2. **Locate the call site.** Visit the AST collecting `MethodCallExpr` and `ObjectCreationExpr` nodes whose `Range` contains `callLine`. Filter by `getName().asString().equals(calleeSimpleName)` (for `MethodCallExpr`) or `getType().getName().asString().equals(calleeSimpleName)` (for `ObjectCreationExpr`). If multiple candidates remain on the same line, prefer the one whose column matches `callColumn` (with tolerance ±1); ties break by source order. If zero candidates, fall back to `readAround` with a `warning = "call_not_found"`.

3. **Find the enclosing method.** Walk `getParentNode()` until a `MethodDeclaration`, `ConstructorDeclaration`, or `InitializerDeclaration` is found. If none (e.g., the call lives in a field initializer at class scope), use the nearest `BodyDeclaration` and emit `warning = "no_enclosing_method"`.

4. **Compute seed identifiers from the call's arguments.** For each `Expression` in `callNode.getArguments()`:
   - `LiteralExpr` → record as `{origin: "literal", value: <text>}`, no seed.
   - `NameExpr` → seed = `{name}`.
   - `FieldAccessExpr` (`foo.bar` or `this.bar`) → seed = `{the leftmost NameExpr}` plus record `{origin: "field_access", expr}`.
   - `MethodCallExpr` (`getX()`) → seed = leftmost identifier (the receiver), record `{origin: "method_call", expr}`.
   - `ArrayAccessExpr` (`arr[i]`) → seeds = `{arr, i}`, record `{origin: "indexed_access", expr}`.
   - `BinaryExpr` / unary / cast → recurse on operands.
   - `ObjectCreationExpr` (`new Foo(a, b)`) → seeds = all identifiers in arguments, record `{origin: "constructor", expr}`.

5. **Backward slice within the enclosing method body.** Flatten the method body into a list of statements in source order (preserving the AST so we can read for-headers, if-conditions, etc. when needed). Walk this list **from the call statement backwards toward the method head**. Maintain `needed: Set<String>` initialized with seeds and `selected: LinkedHashSet<Statement>` initialized with the call statement.
   - For each `Statement` `S` visited in reverse order:
     - If `S` is an `ExpressionStmt` wrapping an `AssignExpr` whose LHS resolves to a `NameExpr` whose name is in `needed`: add `S` to `selected`, add identifiers in `S.RHS` to `needed`.
     - If `S` declares variables (`VariableDeclarationExpr` inside `ExpressionStmt`, or `ForStmt`/`ForEachStmt` init): for any declared name in `needed`, add the declaration (or enclosing for-header) to `selected` and union the initializer's identifiers into `needed`.
     - If the call statement is **inside** an `IfStmt`/`WhileStmt`/`TryStmt` body, walk up through `getParentNode()` and add each enclosing control statement's header (condition / resource) to `selected` once. We render just the header line, not the whole block.
   - Nested blocks (e.g. `try`/`if`) are handled transparently: the flat ordered list already contains their statements; `getParentNode()` is the only mechanism we need for control-structure headers.
   - Stop when `needed` is empty or when `selected.size() >= maxSliceStmts`. In the latter case set `truncated = true`.

6. **Method parameters.** If any name in `needed` matches a method parameter, no further work is needed for that name — it's covered by the signature.

7. **Emit body.** Sort `selected` by source order. Walk statements; between any two non-adjacent items insert a single `// ...` separator line. Emit the method signature on the first line, the selected statements next, and a closing `}` on the last line. Preserve original indentation (read from source by line range; do not re-pretty-print, JavaParser's formatter can be lossy).

### 5.2 Default limits

- `maxSliceStmts = 12` (configurable via constructor; CLI exposure deferred).
- Per-snippet hard line cap = 60 (just-in-case guard against runaway).
- Cache size = 256 CompilationUnits (LRU).

### 5.3 Error handling

All failures degrade to `readAround(callLine, 3, 3)` and append a `warning` string to the `Slice`. The renderers propagate warnings into the rendered output (markdown footnote / graph degradation entry). No exception escapes `sliceAt`.

## 6. Component design: GraphJsonRenderer

### 6.1 Schema

```jsonc
{
  "schema_version": "1",
  "generated_for": {
    "project": "<project-name>",
    "commit_hash_proxy": "<projectSrcHash>",
    "timestamp": "<ISO-8601>"
  },
  "target": {
    "id": "target",
    "fqn": "<FQN>",
    "signature": "<rendered signature>",
    "file": "<relative path>",
    "line_start": <int>, "line_end": <int>,
    "current_body": "<full body, not sliced>"
  },
  "vertices": [
    { "id": "v_test_<sanitized_fqn>",
      "kind": "test_method" | "intermediate_method",
      "fqn": "<FQN>", "file": "<path>", "line": <int>,
      "snippet": "<sliced body>",
      "snippet_truncated": <bool>,
      "warnings": ["..."] }
  ],
  "edges": [
    { "id": "e_<n>",
      "from": "<vertex_id>", "to": "<vertex_id|target>",
      "kind": "calls",
      "call_site": { "file": "<path>", "line": <int>, "code": "<one-line>" },
      "args": [
        { "index": <int>, "origin": "literal", "value": "<text>" },
        { "index": <int>, "origin": "local_var" | "loop_var" | "parameter",
          "name": "<id>",
          "defined_at": { "line": <int>, "snippet": "<one statement>" } },
        { "index": <int>, "origin": "field_access" | "method_call" | "indexed_access" | "constructor",
          "expr": "<text>" }
      ],
      "virtual": <bool> }
  ],
  "chains": [
    { "id": "chain_<n>",
      "depth": <int>, "virtual_steps": <int>,
      "path": ["<vertex_id>", ..., "target"],
      "edges": ["<edge_id>", ...] }
  ],
  "stats": {
    "total_chains": <int>, "distinct_tests": <int>,
    "vertices": <int>, "edges": <int>,
    "truncated": <bool>
  },
  "degradations": [
    { "kind": "<string>", "details": "<free-form>",
      "file": "<path>", "line": <int>, "effect": "<string>" }
  ]
}
```

### 6.2 ID scheme

- `target` (literal) — always one vertex.
- `v_test_<class>_<method>` — test methods. Class and method are dot-and-`$`-stripped to underscores. Disambiguating suffix `_<linenum>` appended on collision.
- `v_method_<class>_<method>` — intermediate methods. Same scheme.
- `e_<n>` / `chain_<n>` — sequential indices in emission order.

The scheme is **stable across runs** for the same input project so an LLM's references survive re-generation; it's **not stable across Joern versions** (Joern's internal node IDs change, but our IDs derive from FQN+line which we control).

### 6.3 Vertex deduplication

A vertex is emitted exactly once even if many chains traverse it. Chain `path` arrays reference the shared vertex id. Edges are keyed by the tuple `(fromVertexId, toVertexId, callSiteFile, callSiteLine, callSiteColumn)`; duplicates with the same tuple collapse to one edge. Distinct call sites between the same two methods produce distinct edges. Edge ids themselves remain sequential `e_<n>` — the tuple is the deduplication key, not the id.

### 6.4 Output ordering

Vertices: target first, then test methods (sorted by FQN), then intermediates (sorted by FQN). Edges: by `chains[0]` order in which they first appear. Chains: by extractor's existing rank (depth ASC, virtualSteps ASC). This makes diffs across runs deterministic and easy to inspect.

## 7. CLI behaviour

### 7.1 Files emitted

Always, per run:

| Path | Content | Budget |
|---|---|---|
| `<out>/<hash>.budget.md` | top-`maxChains` chains, with full slice context | `--budget-tokens` (default 20000) |
| `<out>/<hash>.full.md` | every chain, with full slice context | unlimited |
| `<out>/<hash>.graph.json` | structured graph (schema §6) | unlimited |
| `<out>/<hash>.json` | existing artifact JSON | matches budget.md |

`stdout` prints the path to `<hash>.budget.md` (unchanged from V1).

### 7.2 Flag semantics

| Flag | New meaning |
|---|---|
| `--budget-tokens N` | Applies to `<hash>.budget.md` only. Defaults to 20000. Does not affect full.md or graph.json. |
| `--max-chains N` | Applies to `<hash>.budget.md` only (and the legacy `<hash>.json`). Defaults to 16. Extractor always runs unbounded. |
| `--no-budget` | No-op. Same three files are produced regardless of this flag's presence (full.md is always full, graph.json is always full). A one-line deprecation notice is printed to stderr when the flag is supplied; the flag is removed in the release after next. |
| All other flags | Unchanged. |

### 7.3 Performance

The extractor on picocli yielded 1513 chains; AST slicing runs on ~3000 call sites (each chain has ~2 intermediate edges). JavaParser parses each unique file once. Expected wall-clock cost: <2 s for the slice phase on picocli's main+test tree. Render phase is dominated by I/O (writing ~10 MB of markdown + ~4 MB of JSON), expected <500 ms.

## 8. Testing

### 8.1 Unit tests (always run)

- `AstSnippetExtractorTest` with fixtures under `src/test/resources/snippet-fixtures/`:
  - `SimpleVarChain.java` — two locals as args → slice includes both declarations.
  - `ParameterArg.java` — arg from a method parameter → no false "missing definition" warning.
  - `LiteralOnly.java` — all literals → slice = signature + call only.
  - `LoopVar.java` — for-each loop variable → slice includes loop header.
  - `NestedBlocks.java` — `if (cond) { use(x); }` → condition included.
  - `UnparseableFile.java` — broken syntax → fallback path produces a non-empty body and a `parse_failed` warning.
  - `InnerClassMethod.java` — method inside inner class → enclosing-method resolution picks the right body.
  - `TruncationLimit.java` — slice with `maxSliceStmts=3` against 10 contributing statements → `truncated=true`.
- `GraphJsonRendererTest`: deterministic ProjectGraph with two pre-built chains. Assertions:
  - Document validates against JSON Schema (schema lives in `src/test/resources/graph-schema.json`).
  - ID stability: same input → identical ids.
  - Shared intermediate vertex appears once.
  - `degradations` empty by default; populated when mock orphans are seeded.
  - `stats` match actual counts.
- `CallSiteSlicerTest`: extend existing test. Mocks `AstSnippetExtractor`; verifies args (file/line/calleeName) are forwarded and the returned slice is embedded verbatim.
- `MainSmokeTest`: after a stubbed run, assert all three files exist with non-empty content; `<hash>.graph.json` parses; budget.md path is on stdout.

### 8.2 Regression e2e (opt-in)

`PicocliSmokeTest` is extended (still gated behind the existing opt-in env var) with:

- ≥ 1000 distinct tests reach `TextTable.putValue`.
- `<hash>.graph.json` validates against the schema.
- Sliced snippet for `HelpTest.testDefaultLayout_addsEachRowToTable` contains both `final Text[][] values` and `Help.Layout layout` declarations and does not contain a dangling `};`.

### 8.3 Not tested

- JavaParser internals.
- Joern.
- BudgetPlanner (already covered by existing tests).

## 9. Migration / compatibility

- The existing `<hash>.md` path is **renamed to** `<hash>.budget.md`. This is a deliberate breaking change to the output filename. graph-tipper has no external consumers in production yet, so no compatibility shim is provided; the change is called out in CHANGELOG and the README's invocation example is updated.
- The existing `<hash>.json` (legacy artifact JSON) keeps its path and shape, mirroring the budget.md contents. New consumers should prefer `<hash>.graph.json`; `<hash>.json` remains for one release as a known-shape escape hatch.
- The stdout line (path printed at the end of a run) continues to point at the budget.md file (now `<hash>.budget.md`), so callers that pipe stdout to a file path get a working artifact.
- The `cpg-sample/export.json` fixture stays valid: CpgImporter is unchanged.

## 10. Future work (explicitly out of scope)

- Reflection-aware pass (`@MethodSource`, `Class.forName(literal)`, picocli `@Command(subcommands={...})`) in `prepare-and-export.sc`. Tracked as a follow-up.
- XML variant of `graph.json`.
- Cross-method (interprocedural) backward slicing.
- Slice-aware token budget (currently the BudgetPlanner doesn't understand that a slice is more compact than the full body; it estimates tokens after slicing as-is, which already helps but could be exploited further).
