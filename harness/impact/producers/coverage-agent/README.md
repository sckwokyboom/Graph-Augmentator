# In-JVM coverage agent (gtcov)

Generates `coverage.json` = `{method_fqn: [test_fqn, ...]}` for a gradle+JUnit project by
instrumenting target-package method entries with a ByteBuddy java-agent and attributing
each call to the driving test via a stack walk. **In-JVM ⇒ no race** — this is the robust
replacement for the rejected JaCoCo-tcpserver runner (`../run_coverage.sh`), which lost a
cross-process dump race on fast tests.

## Build
    bash build_agent.sh
Produces `gtcov-agent.jar` (`-javaagent`, fat with ByteBuddy) and `gtcov-boot.jar`
(`gtcov.Recorder` only; premain appends it to the bootstrap loader so the inlined advice
resolves it). Core picocli tests use no ByteBuddy, so bundling it is conflict-free.

## Run
    PROJECT=~/gt-eval/picocli INCLUDES='picocli.CommandLine$Help$TextTable' \
      bash ../run_coverage_agent.sh /tmp/gtcov-out :test

`INCLUDES` is a `;`-separated list of type-name prefixes to instrument. Widen it (e.g.
`picocli.`) for whole-package coverage; scope it tight for fast validation. The driving
test is the outermost frame in the project package (`pkg` agent arg, default `picocli.`).

## Validation (picocli, 2026-06-09)
Target `picocli.CommandLine$Help$TextTable.putValue`. Measured against
`~/gt-eval/F_dynamic.txt` (406 tests that fail when putValue throws):

| metric | value |
|---|---|
| putValue covering tests | **412** |
| recall vs F_dynamic (406) | **100%** (gap 0 — every throw-failing test is covered) |
| covering-but-not-failing (C − F) | 6 (execute putValue but don't assert on its output) |
| suite | 2233 tests, single-fork, ~17 s + compile |

`412 = 406 + 6`. This reproduces the prior session's stack-probe truth exactly, with no
race and a single suite run. Oracle is `F_dynamic.txt` — **not** `C_putvalue.txt`, which
the old source-probe shutdown hook clobbers to 4 lines.

## Design notes
- Call-time work is in-memory only (survives picocli's Java-18-23 SecurityManager); IO is
  only in the shutdown hook, to a PID-keyed `matrix.<pid>.tsv`.
- Attribution: the outermost project-package stack frame is the test entry point — source
  frames are always deeper than the test that calls them, and JUnit/gradle frames are not
  in the package. No class-name heuristic (an earlier `contains("Test")` rule dropped
  picocli tests named `Issue<N>*`, an 11-test recall gap).
- FQNs match `harness/impact/fqn.py`: method = `package.Outer$Nested.method` (no
  signature, from ByteBuddy `#t`/`#m`); test = `package.Class.method` (no `[param]` —
  bytecode stack names have none).
- The parser (`../coverage_agent_parse.py`) can intersect with `executed_tests.txt`
  (gradle-collected, diagnostic) via `attribution=outer executed=<file>` if a project's
  stacks ever surface non-`@Test` outermost frames (`@Before`/setup). Not needed for picocli.
```
