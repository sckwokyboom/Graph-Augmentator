import { tool } from "@opencode-ai/plugin"

/**
 * OpenCode custom tool: crash-slice for a red (exception-failing) test.
 *
 * Install: copy to `<project>/.opencode/tools/crash_slice.ts` and extend
 * `<project>/.opencode/impact.json` with:
 *   "cpg_export": "/abs/path/to/export.json",   // Joern export (slice cache)
 *   "package":    "picocli."                    // project package prefix
 * (`harness_path` is shared with the impact tool's config.)
 *
 * Shells to `harness.impact.crash_slice`: parses the gradle failure XMLs,
 * picks the root cause, and renders the stack spine annotated with a static
 * dependency corridor (possible guards + resolvable defs, file:line anchors,
 * FULL/FALLBACK confidence per frame). Exception failures only (assertion
 * failures are reported as not applicable).
 */
export default tool({
  description:
    "Explain a RED test that failed with an EXCEPTION. Returns a crash-slice: " +
    "the failure's stack spine with file:line anchors, the seed statement per " +
    "frame, possible controlling guards and data definitions around each hop, " +
    "and per-frame confidence (FULL = backed by the code graph, FALLBACK = " +
    "source quote only). Call right after a test run fails with an exception, " +
    "BEFORE grepping the codebase — it replaces manual stack-chasing. Not for " +
    "assertion failures (expected-vs-actual): those report as not applicable.",
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
    let cfg: { harness_path: string; cpg_export?: string; package?: string }
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
    const proc = await Bun.$`python3 -m harness.impact.crash_slice --export ${cfg.cpg_export} --trace ${results} --package ${cfg.package} --project ${context.worktree} --out ${out}`
      .cwd(cfg.harness_path)
      .env({ ...process.env, PYTHONPATH: cfg.harness_path })
      .nothrow()
    const stdout = proc.stdout.toString()
    if (proc.exitCode !== 0) {
      // Includes the honest "no applicable exception failure (assertion failures
      // are v2)" path — surface it verbatim along with the applicability line.
      return `crash_slice not applicable:\n${stdout}${proc.stderr.toString()}`
    }
    const slice = await Bun.file(out).text()
    return `${stdout}\n${slice}`
  },
})
