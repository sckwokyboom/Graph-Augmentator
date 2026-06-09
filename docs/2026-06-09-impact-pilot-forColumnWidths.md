# Impact-tool pilot — `forColumnWidths` (real diff, A/B, 2026-06-09)

**Goal:** Run the per-diff impact tool end-to-end on a **real, non-putValue** diff with verification *headroom*, using only **produced** artifacts (no hand-built fixtures), and measure whether the tool's guidance beats the run-everything baseline.

## Why not putValue
putValue is verified strongly (PITest ~93% kill across its lines) — a green suite already implies correctness, so the baseline "converges in one cycle" and there's no headroom to demonstrate. We needed a target the suite *covers but does not verify*.

## Target selection (by mutation gap)
Full-suite PITest over `picocli.CommandLine$Help$TextTable` (all picocli tests; `run_mutation.sh` with `TARGET_TESTS=picocli.*`) ranked methods by surviving mutants:

| method | mutants | killed | survived | kill% |
|---|---|---|---|---|
| **forColumnWidths** | 6 | 4 | 2 | **66%** ← chosen |
| copy | 20 | 18 | 2 | 90% |
| putValue | 32 | 30 | 2 | 93% |
| addRowValues / toString | 23 / 17 | all | 0 | 100% |

`forColumnWidths` has the lowest kill rate and a concrete blind spot at **line 17280** — the `i == columnWidths.length - 1 ? WRAP : SPAN` ternary, where Math + NegateConditionals mutants survive (the suite never pins down last-column wrap behaviour).

## Artifacts (all produced, consistent on picocli `a8999631`)
- `methods.json` — Joern export (forColumnWidths range 17277–17283, verified to match the current checkout).
- `coverage.json` — the in-JVM coverage agent (`coverage-agent/`): forColumnWidths covered by **392** tests.
- `mutation.json` — PITest full-suite run via `run_mutation.sh`.

## The diff (real behavioural change on the under-tested line)
```diff
--- a/src/main/java/picocli/CommandLine.java
+++ b/src/main/java/picocli/CommandLine.java
@@ -17277,7 +17277,7 @@
-                    columns[i] = new Column(columnWidths[i], 0, i == columnWidths.length - 1 ? WRAP : SPAN);
+                    columns[i] = new Column(columnWidths[i], 0, i == columnWidths.length - 1 ? SPAN : WRAP);
```

## Tool report (`harness/impact/cli.py`)
- **Changed:** `forColumnWidths`.
- **Verification strength:** `line:17280` → **⛔ 0 killers — UNVERIFIED**; other lines weak (1–2 killers).
- **Blind spot:** explicitly flags `line:17280` — *"killed by 0 mutants — green suite is not evidence."*
- **Affected tests:** 392 of 2233 (1841 skip — coverage-sound).
- **Tier 1 — VERIFIERS: 1** (`picocli.HelpTest`) — the only covering test that is also a PITest killer.
- **Tier 2 — COVERERS: 391** — execute the method but kill no mutant.

## Measured A/B
- **B (baseline, run everything):** applied the WRAP↔SPAN change and ran the full 2233-test suite → **BUILD SUCCESSFUL, 0 failures.** The regression ships green → *false confidence*.
- **A (tool-guided):** the tool predicted this from mutation data alone — `line:17280` UNVERIFIED — i.e. it told you up front that the suite cannot catch a change there, and that of 392 "covering" tests only 1 actually verifies the method. Closing the gap requires a **new** test asserting last-column wrap behaviour; the tool points at exactly the line.

**Conclusion:** headroom confirmed. On a target the suite merely *covers*, the run-everything baseline is misleading (green ≠ verified), while the impact tool (a) shrinks the per-iteration loop from 392→1 verifier and (b) names the unverified changed line so a missing test can be written. This is the value putValue couldn't show.

## Reproduce
```bash
# 1. coverage.json (in-JVM agent)
PROJECT=~/gt-eval/picocli INCLUDES='picocli.CommandLine$Help$TextTable' \
  bash harness/impact/producers/run_coverage_agent.sh /tmp/gtcov-out :test
# 2. mutation.json (PITest, full suite)
PROJECT=~/gt-eval/picocli TARGET_TESTS='picocli.*' \
  bash harness/impact/producers/run_mutation.sh 'picocli.CommandLine$Help$TextTable' /tmp/gtcov-mut-full
# 3. methods.json (Joern export → index)
PYTHONPATH=. python3 -m harness.impact.producers.method_index \
  ~/gt-eval/slice/.cache/<sha>/export/export.json /tmp/methods.json
# 4. impact report on the diff
PYTHONPATH=. python3 -m harness.impact.cli --methods /tmp/methods.json \
  --coverage /tmp/gtcov-out/coverage.json --mutation /tmp/gtcov-mut-full/mutation.json \
  --diff <forColumnWidths.diff> --total-tests 2233
```

## Caveat / follow-up
`methods.json` (Joern export, SHA `7555…`) is line-aligned with the current checkout (`a8999…`) for `forColumnWidths` (17277–17283 in both), but **not** for all methods (e.g. Joern reports `copy` 17452–17457 while PITest mutates copy at 17485 — drift). For a target whose range has drifted, regenerate `methods.json` on the target SHA (Joern, or a bytecode `LineNumberTable`/`javap` extractor) before trusting the diff→method mapping.
