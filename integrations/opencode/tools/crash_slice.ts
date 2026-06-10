import { tool } from "@opencode-ai/plugin"

/**
 * OpenCode custom tool: crash-slice for a red (exception-failing) test.
 *
 * Install: copy to `<project>/.opencode/tools/crash_slice.ts` and extend
 * `<project>/.opencode/impact.json` with:
 *   "cpg_export": "/abs/path/to/export.json",   // Joern export (slice cache)
 *   "package":    "picocli."                    // project package prefix
 *   "coverage":   "../.impact/coverage.json"   // optional; defaults to <project>/.impact/coverage.json
 * (`harness_path` is shared with the impact tool's config.)
 *
 * Shells to `harness.impact.crash_slice`: parses the gradle failure XMLs,
 * picks the root cause, and renders the stack spine annotated with a static
 * dependency corridor (possible guards + resolvable defs, file:line anchors,
 * FULL/FALLBACK confidence per frame). Handles both exception failures (v1 corridor) and assertion failures (v2 aggregated localization; reads .impact/coverage.json when available).
 */
export default tool({
  description:
    "Explain RED tests after a failing run. Exception failures get a crash-slice " +
    "(stack spine + static dependency corridor, FULL/FALLBACK confidence). " +
    "Assertion failures (expected-vs-actual) get an aggregated localization " +
    "hypothesis: test→production boundary, coverage×reachability-ranked suspect " +
    "methods with confidence mode (CONTRAST/FREQUENCY/BOUNDARY-ONLY), and an " +
    "exemplar corridor. Call right after a red test run, BEFORE grepping the " +
    "codebase — it replaces manual stack-chasing and suspect hunting.",
  args: {
    test_results_dir: tool.schema
      .string()
      .optional()
      .describe(
        "Gradle test-results dir with TEST-*.xml. " +
          "Defaults to build/test-results/test under the project root.",
      ),
  },
  async execute(args, context) {
    const cfgPath = `${context.worktree}/.opencode/impact.json`
    let cfg: { harness_path: string; cpg_export?: string; package?: string; coverage?: string }
    try {
      cfg = await Bun.file(cfgPath).json()
    } catch {
      return `crash_slice: no config at ${cfgPath} (see integrations/opencode/impact.json.example).`
    }
    if (!cfg.cpg_export || !cfg.package) {
      return (
        `crash_slice: config ${cfgPath} must define "cpg_export" (Joern export.json) ` +
        `and "package" (project package prefix, e.g. "picocli.").`
      )
    }
    const results =
      args.test_results_dir && args.test_results_dir.length > 0
        ? args.test_results_dir
        : `${context.worktree}/build/test-results/test`
    const out = `${context.worktree}/.opencode/crash-slice.md`
    const coverage = cfg.coverage
      ? cfg.coverage.startsWith("/")
        ? cfg.coverage
        : `${context.worktree}/.opencode/${cfg.coverage}`
      : `${context.worktree}/.impact/coverage.json`
    const proc = await Bun.$`python3 -m harness.impact.crash_slice --export ${cfg.cpg_export} --trace ${results} --package ${cfg.package} --project ${context.worktree} --coverage ${coverage} --out ${out}`
      .cwd(cfg.harness_path)
      .env({ ...process.env, PYTHONPATH: cfg.harness_path })
      .nothrow()
    const stdout = proc.stdout.toString()
    if (proc.exitCode !== 0) {
      // Honest not-applicable path (no project frames / unresolvable assertion
      // set without a matrix) — surface stdout+stderr verbatim.
      return `crash_slice not applicable:\n${stdout}${proc.stderr.toString()}`
    }
    const slice = await Bun.file(out).text()
    return `${stdout}\n${slice}`
  },
})
