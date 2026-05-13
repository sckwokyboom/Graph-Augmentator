# Graph-Tipper V1 — Design

**Date:** 2026-05-13
**Status:** Approved (brainstorming complete; awaiting written-spec review before plan)
**Owner:** michaelreadov@gmail.com

## 1. Problem Statement

We are building an agentic IDE for Java. One of its core tasks is generating the
body of a Java method from its signature, using project context, surrounding
members, and existing test cases. The agent currently spends a lot of cycles
running tests and rebuilding the project just to collect enough context to
answer the question "what does this method need to do?"

**Graph-Tipper** is a stand-alone CLI tool that, given a target Java method and
a Java project, produces a single text artifact ("augmentation") containing:

1. Reverse call-graph chains from the target method to the test cases that
   transitively exercise it, with code snippets and argument-origin slices at
   each call site.
2. Local context around the target: surrounding members of its class, public
   API of used types (with enum constants in full), and production call sites.

The artifact is designed to be dropped directly into the context of an external
agent (the IDE's planner / code-writer), so that the agent can produce a body
that passes the project's test suite with significantly fewer test/build
cycles.

The motivating example is `picocli.CommandLine$TextTable#putValue(int, int,
Text)` in the [picocli](https://github.com/remkop/picocli) project.

## 2. Scope of V1

**In:**

- Full Code Property Graph (AST + CFG + PDG + Call Graph) of a flat Java
  project, produced by **Joern** (`javasrc2cpg` frontend) as a subprocess and
  exported in a neutral format.
- A neutral in-memory `ProjectGraph` schema, independent of Joern's
  representation.
- Reverse call-chain extraction from a target method up to any reachable test
  method, including overrides ("virtual" steps).
- Per-call-site enrichment: source snippet + argument back-slice along data
  dependencies down to literals, parameters, fields, or opaque factory calls.
- Local-context extraction: sibling members used by the target, used types'
  public API (enum constants in full), production call sites of the target.
- Output: Markdown (for the agent), JSON sidecar (stable schema, reserved
  slots for negative memory), optional DOT (debug), `meta` JSON (run
  metadata).
- Token budget for the Markdown artifact (default 20 000 tokens, 4-chars/token
  approximation), with a deterministic eviction strategy and a protected
  minimum.
- CLI with target spec in two forms (path+sig and FQN-only), source-tree hash
  caching of the CPG.

**Out (V1):**

- Negative memory as a feature (only reserved schema slot and empty Markdown
  section).
- `jimple2cpg` (bytecode-based) frontend or any non-Joern producer.
- Incremental CPG updates on file changes (V1 rebuilds when the source-tree
  hash changes).
- Multi-module Maven/Gradle projects (V1 supports flat layout, as in picocli).
- Web/IDE integration.

## 3. Architecture

Three strictly separated layers:

```
┌────────────────────────────────────────────────────────────┐
│  Layer 1: CPG Producer (external, replaceable)             │
│  Joern  ──javasrc2cpg──▶  *.cpg.bin ──joern-export──▶ JSON │
└────────────────────────────────────────────────────────────┘
                              │  CpgImporter
                              ▼  (only module that knows Joern)
┌────────────────────────────────────────────────────────────┐
│  Layer 2: ProjectGraph (our neutral in-memory model)       │
│  Sealed Node / sealed Edge types                           │
└────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────┐
│  Layer 3: Slicers + Renderers                              │
│  TestDetector · MethodLocator · ReverseCallChain           │
│  CallSiteSlicer · LocalContextExtractor                    │
│  → Markdown (agent) · JSON (machine) · DOT (debug)         │
└────────────────────────────────────────────────────────────┘
```

Key invariants:

- **Joern is a build-step, not a runtime library.** Its output is cached by
  the SHA-256 of the project source tree; re-runs on unchanged code are cheap.
- **`CpgImporter` is the only module aware of Joern's format.** Replacing
  Joern with SootUp or a JavaParser-based producer requires changing only this
  module; Layer 2 and Layer 3 are unaffected.
- **Slicers are pure functions of `ProjectGraph`.** They never touch the
  user's project. They are testable on synthetic, hand-built `ProjectGraph`
  instances.
- **No CPGQL or Scala code on our side.** All graph traversal lives in our
  Java code, on our model.

## 4. `ProjectGraph` Schema (Layer 2)

Typed model, no property bags. `sealed interface Node`, `sealed interface
Edge`.

### 4.1 Node types

| Type | Fields |
|---|---|
| `MethodNode` | `id`, `fqn` (e.g. `picocli.CommandLine$TextTable.putValue`), `signature`, `paramTypes[]`, `returnType`, `file`, `lineStart`, `lineEnd`, `javadoc?`, `isTest`, `isAbstract`, `modifiers[]` |
| `TypeNode` | `id`, `fqn`, `kind` (CLASS/INTERFACE/ENUM/ANNOTATION), `file?`, `lineStart?`, `lineEnd?`, `enumConstants[]?` |
| `FieldNode` | `id`, `ownerType`, `name`, `type`, `modifiers[]`, `lineStart`, `lineEnd` |
| `ParameterNode` | `id`, `ownerMethod`, `name`, `type`, `index` |
| `CallSiteNode` | `id`, `inMethod`, `callee` (resolved `MethodNode` or unresolved FQN), `argCount`, `line`, `col`, `codeSnippet` (1 line) |
| `StmtNode` | `id`, `inMethod`, `line`, `kind` (IF/LOOP/RETURN/EXPR/...), `codeSnippet` |
| `LiteralNode` | `id`, `inMethod`, `kind` (INT/STR/BOOL/NULL), `value`, `line` |

### 4.2 Edge types

Directed.

| Edge | From | To | Semantics |
|---|---|---|---|
| `Calls` | `MethodNode` or `CallSiteNode` | `MethodNode` | Interprocedural call graph. `CallSite → Method` gives precise call-site location; `Method → Method` is aggregated. |
| `AstContains` | `MethodNode` / `TypeNode` | any child | Structural nesting. |
| `Ddg` | `Node` (def) | `Node` (use) | Data dependency. Used to back-slice argument origins. |
| `Cdg` | `StmtNode` | `StmtNode` | Control dependency for conditional intra-method slices. |
| `RefType` | `MethodNode` / `FieldNode` / `ParameterNode` | `TypeNode` | Used types, for local context. |
| `Overrides` | `MethodNode` | `MethodNode` | For virtual-dispatch handling in reverse call-chain extraction. |
| `Reads` | `MethodNode` | `FieldNode` | For local context (which fields target reads). |
| `Writes` | `MethodNode` | `FieldNode` | For local context (which fields target writes). |

### 4.3 What we deliberately drop on import

- Full expression-level AST (Joern is verbose; we keep only what slicers need).
- Generic-signature internals (we keep FQNs as strings).
- File BLOBs (snippets are read on demand by `SourceFragmentReader`, with an
  in-memory file cache).

### 4.4 IDs

Stable, string-valued, deterministic across runs (used as cache keys):

- `m:picocli.CommandLine$TextTable.putValue(int,int,picocli.CommandLine$Help$Ansi$Text)`
- `t:picocli.CommandLine$Cell`
- `cs:<methodId>@<line>:<col>`

### 4.5 Storage and indexes

In-memory `Map<String, Node>` plus indexes: `byFqn`, `byFile`, `incomingCalls`,
`outgoingCalls`, `testMethods`. Picocli-scale graphs (tens of thousands of
nodes) fit comfortably in heap; no database.

## 5. Slicers (Layer 3)

Four independent components. Each is a pure function of `ProjectGraph` + its
inputs.

### 5.1 `TestDetector`

Marks `MethodNode.isTest = true`. Rules, in decreasing reliability:

1. Method-level annotation: `@Test`, `@ParameterizedTest`, `@RepeatedTest`,
   `@TestFactory` (JUnit 5), `org.junit.Test` (JUnit 4).
2. `public void` method in a class extending `junit.framework.TestCase`, name
   starts with `test`.
3. (Opt-in via `--treat-test-dirs-as-tests`.) Methods under `src/test/java/**`.
   Heuristic, off by default.

Annotations are read from the CPG, with a regex fallback against the source
line (`@Test`) to guard against `javasrc2cpg` quirks.

### 5.2 `MethodLocator`

Input: target spec in either form (see §7.1). Algorithm:

1. Filter `MethodNode` by `byFile == relPath` (path-form only).
2. Filter by simple class name (last `$`-segment of FQN).
3. Filter by method name.
4. Match `paramTypes`: exact by `paramTypes[]`; if unset or ambiguous, fuzzy
   match by simple names (`Text`, `Cell`, `int`).

Outcomes: exactly one match → return; zero → fail with top-5 nearest
sigantures by Damerau–Levenshtein on FQN; >1 → fail with the list of
candidates.

### 5.3 `ReverseCallChainExtractor`

Inputs: `MethodNode target`, `int maxChains`. **No depth cap** — the only
limits are (a) the `visited` set on `(callerId, calleeId)` pairs, which kills
cycles, and (b) a frontier guard of `maxChains × 8` live branches that
prevents runaway BFS.

Algorithm: BFS upward along `Calls` edges. Two nuances:

1. **Virtual calls.** When a chain step would reach a `MethodNode` overridden
   by `target` (or one of `target`'s callees), we add a "virtual" step using
   `Overrides`: any caller of the interface/parent method is treated as a
   caller of the implementing method, with `viaVirtual = true` on the step.
2. **Truncation.** If the frontier exceeds `maxChains × 8`, BFS halts; the
   chains discovered so far are returned and `truncated = true` is set on the
   result.

**Chain ranking** (best first):

1. Shorter chains first.
2. Tie-broken by smaller test method size (number of unique methods the test
   touches before reaching the target). Focused tests are more useful to the
   agent.

Default `maxChains = 16` (CLI-tunable).

Output: `List<Chain>`, where `Chain = List<CallStep>` and
`CallStep = { callerMethod, callSite, calleeMethod, viaVirtual }`.

### 5.4 `CallSiteSlicer`

For each `CallStep`:

1. **Call-site snippet:** 3 lines before + the call line + 2 lines after, via
   `SourceFragmentReader` (per-file cache).
2. **Argument back-slice.** For each call argument, traverse `Ddg` backwards
   inside `callerMethod` until reaching one of:
   - `LiteralNode` → record value.
   - `ParameterNode` of caller → record `name:type`. Back-slicing continues
     naturally at the next chain step (the caller's caller), yielding
     end-to-end argument flow from test to target.
   - `FieldNode` → record `T.field`.
   - `CallSiteNode` → record `factory.foo(...)` as `FACTORY_CALL` with FQN.
   - Cycle or depth > 6 → `<unknown>`.

Result: `enrichedStep = step + { snippet, argOrigins: List<ArgOrigin> }`.

### 5.5 `LocalContextExtractor`

Produces a `LocalContext` block, independent of the chains.

1. **Sibling members** of the target's class actually used by the target:
   - Methods of the same `TypeNode` reached by `Calls` from target.
   - Fields read/written by target (via `Reads` / `Writes`).
   - Rendered as signature + javadoc + full body if ≤ 30 lines; otherwise
     signature + first 10 lines + `// ...`.
2. **Used types** (via `RefType` from target):
   - FQN, kind.
   - Enum: full constant list.
   - Class/interface: public methods (signature + javadoc).
   - Inner classes of the same source file are rendered in fuller form (bodies
     up to 30 lines); external types only their public API.
3. **Production call sites** of target (non-test callers): up to 5 examples
   with `callerFqn`, `file:line`, snippet.

## 6. Token Budget and Eviction

Total budget is on the Markdown artifact, not on any single section. Default
**20 000 tokens**, approximated as 4 chars/token (≈ 76 000 characters). CLI:
`--budget-tokens`.

### 6.1 Eviction order (top dropped first)

1. Production call sites of target.
2. Bodies of used types — keep only signatures + javadoc; **enum constants
   remain in full**.
3. Sibling-member bodies — truncate to signature + first 5 lines + `// ...`.
4. Argument back-slice details on the farthest steps of each chain (keep only
   signatures).
5. Lowest-ranked chains, one by one, until the artifact fits.

### 6.2 Protected minimum (never evicted)

- Target: javadoc + current body (if any) + signature.
- Top-1 chain in full (with snippets and argument origins).
- Signatures of every sibling member directly touched by the target.
- Full enum-constant lists for every enum type used by the target.

If the protected minimum itself does not fit, the run fails with exit code 3
("budget exceeded on minimum") and a stderr breakdown of what did not fit.

### 6.3 Truncation signalling

The Markdown header always shows `Budget: <used> / <max> tokens` and
`Truncated: <true|false>`. The JSON sidecar records `budget.tokensUsed`,
`budget.tokensMax`, and `budget.evicted: [<section names>]`.

## 7. CLI

### 7.1 Surface

```
graph-tipper \
  --project <dir>            # required
  --target  <spec>           # required
  --out     <dir>            # required
  [--budget-tokens 20000]
  [--max-chains 16]
  [--treat-test-dirs-as-tests]
  [--no-cache]
  [--joern-home <dir>]
  [--debug-dot]
  [-v|-vv]
```

Target spec, two accepted forms:

1. Path + signature:
   `src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)`
2. FQN only:
   `picocli.CommandLine$TextTable#putValue(int,int,picocli.CommandLine$Help$Ansi$Text)`

### 7.2 Exit codes

- `0` — success.
- `1` — generic runtime error.
- `2` — target not found or ambiguous.
- `3` — budget exceeded even on protected minimum.
- `4` — Joern not found / unusable.

### 7.3 Output files

```
<out>/
  <hash>.md      ← agent-facing artifact
  <hash>.json    ← machine-readable sidecar (stable schema)
  <hash>.dot     ← optional, with --debug-dot
  <hash>.meta    ← run metadata
```

`<hash>` is derived from the target spec plus the project source-tree hash.

Files are written via temp-file + atomic rename so an external reader never
sees a partial Markdown.

## 8. Output Format

### 8.1 Markdown structure

Order is optimized for an agent reading top-down:

```
# Graph-Tipper Augmentation
> Generated for: <project> @ <git-sha-or-srchash>
> Target: <fqn(sig)>
> Budget: <used> / <max> tokens · Chains: <n> · Truncated: <bool>

## Target
- File and line range
- Javadoc (block-quoted)
- Signature (java code block)
- Current body (java code block)

## Test Chains
### Chain k (depth=…, virtual=…)
- Test fqn + file:line
- Test source code block
- "Arg origins at target call:" bullet list

## Local Context
### Sibling members of <class> used by target
### Used types
### Production call-sites of target (non-test, up to 5)

## Negative Memory
_(reserved — not populated in V1)_
```

All code blocks are tagged `java`. Every snippet is prefixed by a one-line
`// <file>:<line>` comment.

### 8.2 JSON sidecar schema (v1.0)

```json
{
  "schemaVersion": "1.0",
  "target": {
    "fqn": "...", "paramTypes": [...], "file": "...",
    "lineStart": 0, "lineEnd": 0, "javadoc": "...", "currentBody": "..."
  },
  "chains": [
    {
      "rank": 1, "depth": 2, "virtualSteps": 0, "truncated": false,
      "test": { "fqn": "...", "file": "...", "line": 0 },
      "steps": [
        {
          "callerFqn": "...", "calleeFqn": "...",
          "callSite": { "file": "...", "line": 0, "col": 0 },
          "snippet": "...",
          "argOrigins": [
            { "arg": 0, "kind": "LITERAL", "value": "0", "file": "...", "line": 0 },
            { "arg": 2, "kind": "FACTORY_CALL",
              "factoryFqn": "...", "file": "...", "line": 0 }
          ],
          "viaVirtual": false
        }
      ],
      "failures": []
    }
  ],
  "localContext": {
    "siblingMembers": [...],
    "usedTypes": [...],
    "productionCallSites": [...]
  },
  "budget": { "tokensUsed": 0, "tokensMax": 0, "evicted": [...] },
  "negativeMemory": []
}
```

`failures` (on each chain) and `negativeMemory` (top-level) are reserved for
V2 and intentionally start empty. Backward-incompatible schema changes bump
`schemaVersion`.

### 8.3 `meta` JSON

```json
{
  "graphTipperVersion": "0.1.0",
  "joernVersion": "...",
  "joernFrontend": "javasrc2cpg",
  "projectPath": "...",
  "projectSrcHash": "...",
  "cpgCacheKey": "...",
  "targetSpec": "...",
  "generatedAtUtc": "..."
}
```

`projectSrcHash` is the cache key for the Joern output.

### 8.4 DOT (debug)

Optional, via `--debug-dot`. Contains only the subgraph that made it into the
Markdown: target + the chains that were rendered + used types as a fan-out
from target. For human inspection (`dot -Tsvg`), not for the agent.

## 9. Error Handling and Diagnostics

- All errors go to stderr; artifacts only to `--out`.
- Writes are atomic (temp + rename) so consumers never read partial files.

Concrete cases:

- `joern not found` → install hint and `--joern-home` reminder; exit 4.
- `javasrc2cpg failed` → Joern stderr is echoed; hint about `--no-cache` and a
  future `jimple2cpg` fallback (not in V1).
- `target not found` → top-5 nearest signatures by Damerau–Levenshtein; exit 2.
- `no chains found` → **the artifact is still written** with an empty `## Test
  Chains` section and an explicit `> No tests transitively reach this target`
  notice. This is a useful signal to the agent, not an error.
- `budget exceeded on minimum` → exit 3, with stderr breakdown of what did
  not fit.

Logging is structured (timestamp, level, component) on stderr; `-vv` adds
`ProjectGraph` size dumps and phase timings.

## 10. Testing Strategy

Three levels.

### 10.1 Unit (the bulk)

Each component is tested against synthetic minimal `ProjectGraph` instances
built via a test-only builder. Coverage targets:

- `ReverseCallChainExtractor`: cycles, virtual overrides, frontier guard,
  ranking.
- `CallSiteSlicer`: back-slice termination cases (literal / parameter / field
  / factory / cycle).
- `LocalContextExtractor`: eviction order, enum-constant inclusion, protected
  minimum.
- `TestDetector`: JUnit 3 / 4 / 5 paths, false-positive guards.
- `MethodLocator`: exact match, fuzzy by simple names, ambiguous case.
- `Renderer` (Markdown + JSON): snapshot tests on fixed mini-graphs.

### 10.2 Integration

One tiny fixture project in `fixtures/tiny-project/` (5–6 Java files, 2
tests, 1 target). Runs the real Joern subprocess. Fast (seconds). Catches
`CpgImporter` regressions.

### 10.3 End-to-end smoke

Opt-in via `GRAPHTIPPER_PICOCLI_HOME=<dir> ./gradlew test -PsmokeTests`. Runs
the full pipeline on picocli for `TextTable#putValue` and asserts artifact
invariants (target present, ≥ 1 chain, JSON parses against schema, budget not
exceeded). It does **not** snapshot the Markdown text (too brittle for a
real-world project).

Development discipline: TDD per component — failing test, minimal impl,
refactor.

## 11. Project Layout

```
Graph-Tipper/
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── docs/superpowers/specs/2026-05-13-graph-tipper-v1-design.md
├── src/
│   ├── main/java/com/graphtipper/
│   │   ├── cli/Main.java
│   │   ├── cpg/
│   │   │   ├── JoernRunner.java
│   │   │   └── CpgImporter.java
│   │   ├── model/
│   │   │   ├── ProjectGraph.java
│   │   │   ├── Node.java               (sealed)
│   │   │   └── Edge.java               (sealed)
│   │   ├── detect/
│   │   │   ├── TestDetector.java
│   │   │   └── MethodLocator.java
│   │   ├── slice/
│   │   │   ├── ReverseCallChainExtractor.java
│   │   │   ├── CallSiteSlicer.java
│   │   │   └── LocalContextExtractor.java
│   │   ├── render/
│   │   │   ├── MarkdownRenderer.java
│   │   │   ├── JsonRenderer.java
│   │   │   └── DotRenderer.java
│   │   └── util/
│   │       ├── SourceFragmentReader.java
│   │       ├── TokenBudget.java
│   │       └── Cache.java
│   └── test/java/com/graphtipper/...
├── fixtures/tiny-project/
└── tools/install-joern.sh
```

Dependencies:

- `info.picocli:picocli` — our CLI parser.
- `com.fasterxml.jackson:jackson-databind` — JSON.
- `org.slf4j:slf4j-simple` — logging.
- `org.junit.jupiter:*` + `org.assertj:assertj-core` — tests.
- `com.approvaltests:approvaltests` — snapshot tests for renderers.

No Joern Scala libraries on the classpath. Joern is always a subprocess.

Java toolchain: 21+.

## 12. Roadmap Notes (V2 and Beyond)

Intentionally out of scope for V1 but acknowledged here so V1 design doesn't
foreclose them:

- **Negative memory.** A separate runner that captures `gradle test` /
  `gradle compileJava` failures, attributes them to the chain step or source
  region they correlate with, and writes them into the JSON sidecar's
  `failures` / `negativeMemory` slots. The Markdown `## Negative Memory`
  section becomes populated.
- **`jimple2cpg` frontend.** Bytecode-based CPG for projects where
  `javasrc2cpg` is too noisy on generics/lambdas/method references. Drop-in
  replacement at the `CpgImporter` layer.
- **Incremental updates.** Re-run only on touched files.
- **Multi-module projects.** Maven/Gradle module-aware project discovery.
- **IDE integration.** Embedding Graph-Tipper as a library inside the
  Java-focused agentic IDE.

## 13. Open Risks

- **`javasrc2cpg` quality on real-world Java.** Generics, lambdas, method
  references can be under-resolved. Mitigations: regex fallback for
  annotations; `Overrides` edges used during reverse traversal; planned
  `jimple2cpg` fallback in V2.
- **Token-budget accuracy.** 4-chars/token is a coarse approximation. For
  picocli it is conservative enough; if a future agent uses a different
  tokenizer, we can swap in a real tokenizer at the `TokenBudget` boundary
  without touching slicers.
- **Snapshot tests for renderers.** Approval tests are brittle if Joern's
  output drifts. Mitigated by running renderer tests on hand-built
  `ProjectGraph` instances, not on real Joern output.
