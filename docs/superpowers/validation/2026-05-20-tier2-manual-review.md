# Tier 2 Static Slice — Validation Notes

**Date:** 2026-05-20
**Branch:** `feat/static-slice-tier2`
**Plan:** `docs/superpowers/plans/2026-05-18-static-slice-tier2.md`
**Spec:** `docs/superpowers/specs/2026-05-18-static-slice-tier2-design.md`

## Status

All 31 tasks of the plan have been implemented and committed. Test suite:
**196 tests passing, 0 failing.**

## Methodology

Each task followed strict TDD: write failing test → run RED → implement →
run GREEN → commit. A few tasks were combined into single commits when the
changes were tightly coupled (e.g., Tasks 20+21, Tasks 23+24).

## Per-task deviations from the plan

- **Task 14 (loop variable detection):** placement of the for-loop detector
  was moved from "before the parameter loop" (per plan §Task 14 Step 3) to
  the **start of `intraProcBackwardSlice`**, before the `VariableDeclarator`
  walk. Reason: the for-init's `VariableDeclarator` for `int i = 0` would
  otherwise be picked up first and resolve `i` to `0`. Plan's placement
  would never be reached. Intent of the plan is preserved.
- **Task 19 (ClusterEnricher integration):** the plan explicitly tagged this
  as "implementer fills in fixture wiring". Concrete shape:
  - new constructor `ClusterEnricher(OracleExtractor, int maxSliceDepth, int maxSliceBranches)`
  - new functional interface `ConsumerFileResolver`
  - new overload `enrich(clusters, testResolver, consumerResolver, targetFqn, targetParamNames, targetParamTypes, chainArgsMap)`
  - legacy 3-arg `enrich(...)` delegates to the new overload with empty target params (no slicing)
  - new minimal fixture `src/test/resources/slice-fixtures/SimpleChain.java`
- **Tasks 20 + 21:** committed together because the tautology-drop guard
  (Task 21) requires the slice-derived signal helper (Task 20) to be
  meaningful. Single commit `feat(slice): DifferentialAnalyzer slice-derived
  signals + drop tautological invariants`.
- **Tasks 23 + 24:** committed together (basic block + collapse policy).
  Insertion point for `renderStaticSlice` is **before** the
  `cluster.members().isEmpty()` early return so the slice still renders
  even when members are empty (per Task 23 test expectations).
- **Task 26 (JSON schema v2.2):** plan's test snippet asserted without
  space (`"schemaVersion":"2.2"`) but Jackson's default pretty-printer
  emits `"schemaVersion" : "2.2"`. Test assertions adjusted to match
  actual output format.
- **Task 28 (CLI wiring):** target parameter **names** are not available in
  `Node.Method` (only `paramTypes`). The slicer falls back to `argN` names,
  which is fine for rendering. Pass `targetMethod.paramTypes()` as types
  and empty list as names.

## Phase 1 manual review (per spec §8.1)

**Not performed in this session.** `GRAPHTIPPER_PICOCLI_HOME` is unset and
no local picocli checkout is available, so the end-to-end smoke against
picocli (plan §Task 31 Step 2) and the 5-method comparison (Step 3) must
be run separately. The plumbing is fully in place:

- `./gradlew installDist` succeeds
- `./build/install/graph-tipper/bin/graph-tipper --help` lists
  `--slice-depth`, `--slice-branches`, `--no-slice`
- `PicocliSmokeTest.v2_artifact_for_putValue_is_well_compressed` will
  assert `"**Static slice (Tier 2):**"` presence when run with the env
  var set

**Recommended next step:** clone picocli locally, set the env var, run
`./gradlew test --tests com.graphtipper.PicocliSmokeTest`, and complete
the 5-method Phase 1 table in this file.

## Self-review checklist (from plan §Self-review)

- [x] All spec sections §4–§8 have at least one task implementing them.
- [x] All §5 components: `StaticSlicer`, `SliceResult`, `UnresolvedReason`,
      `ArgSlice`, `ClusterSlice`, `SliceMemoCache`.
- [x] `ClusterMember.argSlices` populated through `ClusterEnricher`.
- [x] `PathCluster.clusterSlice` populated.
- [x] JSON sidecar: `schemaVersion: "2.2"`, emits `structuralSlice` +
      `argSlices`.
- [x] `BudgetPlanner.fit` has 3 new slice-eviction tiers (3a/3b/3c)
      between `truncateSignalEvidence` and `dropLowRankConsumers`.
- [x] `MarkdownRenderer` emits `**Static slice (Tier 2):**`; matrix column
      reads `Sliced args`.
- [x] Render policy collapses to one-line summary when all args share the
      same `UNRESOLVED` reason.
- [x] `--no-slice`, `--slice-depth N`, `--slice-branches N` CLI flags
      work and skip slicer when `noSlice=true`.
- [x] `PicocliSmokeTest` asserts slice section presence.
- [x] No new `TODO` / `FIXME` markers introduced in production code.

## Decision

Implementation is complete and green. **Promote** to the integration
review stage. Picocli end-to-end smoke + Phase 1 manual table to be run
against a working picocli checkout before merging to `main`.
