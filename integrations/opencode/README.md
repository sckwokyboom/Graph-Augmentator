# OpenCode integration — `impact` + `crash_slice` tools

A custom [OpenCode](https://opencode.ai/docs/custom-tools/) tool that gives the model a
**per-diff test-impact report** for the current change: which tests *verify* it (Tier 1),
which only *cover* it (Tier 2), and which changed lines are **mutation blind spots** the
suite can't catch. The model calls it after editing and before running tests.

## How it works
```
OpenCode (model calls `impact`)
   └─ .opencode/tools/impact.ts        (thin wrapper, returns markdown)
        └─ python -m harness.impact.from_git --config .opencode/impact.json --repo <worktree>
             ├─ git diff (in the project)            → changed lines
             └─ harness.impact.cli (the engine)      → Tier1/Tier2 + blind spots
                  consumes methods.json / coverage.json / mutation.json
```
The engine is build-agnostic; only the **producers** of the three artifacts are
project-specific (validated on Gradle + JUnit 4 — see the repo's producers).

## Install (per project you develop with OpenCode)
1. Copy the tool:
   ```bash
   mkdir -p <project>/.opencode/tools
   cp integrations/opencode/tools/impact.ts <project>/.opencode/tools/impact.ts
   ```
   (or `~/.config/opencode/tools/impact.ts` to enable it for every project.)
2. Create the config from the example and fill in the paths:
   ```bash
   cp integrations/opencode/impact.json.example <project>/.opencode/impact.json
   # edit harness_path + artifact paths + total_tests
   ```
3. Produce the artifacts once (cache them under `<project>/.impact/`):
   ```bash
   # coverage.json  (in-JVM agent)
   PROJECT=<project> INCLUDES='<pkg.Class>' \
     bash harness/impact/producers/run_coverage_agent.sh <project>/.impact/cov :test
   cp <project>/.impact/cov/coverage.json <project>/.impact/coverage.json
   # mutation.json  (PITest)
   PROJECT=<project> TARGET_TESTS='<pkg>.*' \
     bash harness/impact/producers/run_mutation.sh '<pkg.Class>' <project>/.impact/mut
   cp <project>/.impact/mut/mutation.json <project>/.impact/mutation.json
   # methods.json   (Joern export → index), regenerated on the CURRENT checkout
   PYTHONPATH=<Graph-Tipper> python3 -m harness.impact.producers.method_index \
     <joern-export>/export.json <project>/.impact/methods.json
   ```

## Use
In an OpenCode session, after the model edits code it can call the tool:
- no args → analyzes uncommitted changes (`git diff HEAD`)
- `base: "main"` → analyzes the working tree vs `main`

The tool returns markdown like:
```
## Changed
- picocli.CommandLine$Help$TextTable.putValue
## ⛔ Blind spots — changes here are NOT verified by the suite
- line:17415: putValue: region killed by 0 mutants — green suite is not evidence
## Affected tests (412 of 2233; the other 1821 → skip)
### Tier 1 — VERIFIERS (8): ./gradlew test --tests picocli.HelpTest …
### Tier 2 — COVERERS (404)
```

## Second tool: `crash_slice` (red exception tests)

`tools/crash_slice.ts` — call after a test run fails with an **exception**. Returns the
crash-slice: stack spine with `file:line`, seed statement per frame, possible controlling
guards + resolvable defs, per-frame `FULL`/`FALLBACK` confidence. Assertion failures
report as "not applicable" (v2). Config needs two extra keys in `.opencode/impact.json`:
`cpg_export` (the Joern export.json from the slice cache) and `package` (project package
prefix). The agent flow: run tests → red with exception → `crash_slice` → read the
corridor instead of grepping the stack by hand.

## Quick test without OpenCode
The wrapper just shells to the core, so you can run the exact same thing by hand:
```bash
PYTHONPATH=<Graph-Tipper> python3 -m harness.impact.from_git \
  --config <project>/.opencode/impact.json --repo <project> [--base main]
```

## Caveats (read before trusting line-level output)
- **Artifact freshness.** `methods.json` must be regenerated on the *current* checkout.
  Stale ranges cause false maps: e.g. with a SHA-7555 index on an a8999 checkout, a
  `putValue` diff also mapped a phantom `TextTable$Count.<init>` (Count is really at line
  17469, but the stale index placed it at 17425, inside the hunk). Coverage/mutation are
  method-keyed so they tolerate line shifts; `methods.json` is the line-sensitive one.
- **Validated on Gradle + JUnit 4** (picocli). Other build tools / test frameworks need
  the producers adapted (the engine + this tool are unchanged).
- **Hunk granularity.** Mapping uses the diff hunk range (includes a few context lines),
  so a change adjacent to another method can map both. Method granularity is the unit.
