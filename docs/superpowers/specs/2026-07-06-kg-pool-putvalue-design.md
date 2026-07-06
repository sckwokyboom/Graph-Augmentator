# KG Context Pool for putValue — Design (iteration 1: collection only)

Date: 2026-07-06
Status: approved (collection scope only)

## Goal

Collect a complete, mechanically-cut, human-reviewable pool of static and dynamic
context around the stubbed `picocli.CommandLine$Help$TextTable.putValue` into
`~/gt-eval/kg-pool/putValue/`. The pool is the input for a later experiment where an
external LLM (e.g. DeepSeek-v4-flash) designs its OWN knowledge-graph format and
content — we deliberately do NOT rank, compress, or interpret. The only cut is
reachability: material connected to putValue through code, tests, or runtime.

Out of scope for this iteration: the KG prompt, calling the external LLM, the agent
loop, and invasive-debug as an agent tool (println here is only a data source and a
mechanics probe).

## Deliverable: bundle layout

```
~/gt-eval/kg-pool/putValue/
├── 00-MANIFEST.md            # index: every file, producing command, size, token estimate; failed steps recorded as MISSING + reason
├── 01-task/                  # problem statement: stubbed putValue + surrounding TextTable source
├── 02-static/
│   ├── corridor-slice.{json,md}   # CPG subgraph around putValue: AST/CFG/DFG/CALL edges (2 CALL-hops out from putValue in both directions)
│   ├── method-contracts.md        # signatures, javadoc, callers/callees
│   ├── snippets/                  # corridor source: callers (addRowValues, ...), Text ops, callees
│   └── bytecode/                  # javap -c -l for TextTable and Text — putValue EXCLUDED (leak rule)
├── 03-tests/
│   ├── covering-tests.txt         # the 412 from the gtcov coverage agent
│   ├── chains/                    # test→putValue call chains from the call graph
│   └── assert-snippets/           # full test-method bodies with asserts
├── 04-runtime/
│   ├── jacoco-TextTable.md        # per-line coverage
│   ├── value-capture/             # multi-point capture: args/return/exception per corridor method, tied to test
│   └── println-prototype/         # println experiment: insertion diff + parsed output (1–2 points)
└── 05-failure/
    ├── red-run/                   # stubbed-putValue red run: failure digest, expected vs actual
    └── assertion-slice.md         # assertion-slice v2 artifact
```

## What is new vs reused

Reused as-is: Joern export / method index, gtcov coverage agent, JaCoCo, assertion
slice v2, red-run tooling.

New:
1. **Multi-point value capture** — extend the ByteBuddy agent `capture=` to a list of
   corridor-method FQNs instead of a single one.
2. **println prototype** — insert 1–2 logging lines into picocli sources, run a small
   covering-test subset, parse output, revert. Save both the diff and the parsed
   output (doubles as a probe of the future agent tool).
3. **The packer** — writes the bundle + MANIFEST.

## Sampling without interpretation

- The full 412-test list always ships whole.
- Detailed per-test artifacts (chains, assert snippets, value-capture digests) follow a
  mechanical rule: group covering tests by test class, take the first K=2 exemplars per
  class in lexicographic order (the coverage matrix is a deduped set; suite order is not
  recoverable). No relevance ranking on our side.
- Value capture runs once over the full suite; any filtering happens at render time,
  never at collection time.

## Leak rule (hard)

The real putValue body must not appear anywhere in the pool:
- source: stub version only;
- bytecode: javap output for putValue excluded;
- CPG: from the cache built on the stubbed body — verify SHA drift (cache 7555… vs
  picocli HEAD a8999…) and regenerate if drifted;
- oracles (`~/gt-eval/F_dynamic.txt` etc.) never enter the pool — they are for our
  later verification only.

Expected values inside test asserts are a legitimate part of the spec and stay.

## Failure handling

Collector steps are independent; a failed step is recorded in the MANIFEST as
MISSING with a reason and does not abort the rest.

## Process decision

Hybrid B→A: this iteration is an ad-hoc collection pass so the user can review real
artifacts first; codification into `harness/kgpool/` (collector CLI + pytest units)
happens afterwards as a separate plan, once the pool composition is frozen.

## Done criteria

All categories on disk, MANIFEST accurate, user has reviewed the pool and the
composition is frozen for codification.
