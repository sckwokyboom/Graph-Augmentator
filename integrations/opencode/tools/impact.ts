import { tool } from "@opencode-ai/plugin"

/**
 * OpenCode custom tool: per-diff test impact.
 *
 * Install: copy this file to `<project>/.opencode/tools/impact.ts` (or
 * `~/.config/opencode/tools/impact.ts` for all projects) and create
 * `<project>/.opencode/impact.json` from impact.json.example.
 *
 * It shells out to the harness-agnostic Python core (`harness.impact.from_git`),
 * which runs `git diff` in the project and feeds it + the produced artifacts
 * (methods/coverage/mutation) to the impact engine. The returned markdown is
 * injected into the model's context as the tool result.
 */
export default tool({
  description:
    "Per-diff test impact for the CURRENT change. Returns: the methods touched; " +
    "Tier-1 VERIFIER tests (cover AND kill mutants — run these every edit iteration); " +
    "Tier-2 COVERER tests (run only at final validation); and mutation BLIND SPOTS " +
    "(changed lines the suite does NOT verify — a green run there is not evidence, write a test). " +
    "Call right after editing code and before running tests, to pick the minimal verifying " +
    "test set and to catch changes the suite cannot detect.",
  args: {
    base: tool.schema
      .string()
      .optional()
      .describe(
        "Git ref to diff against, e.g. 'main' or a commit SHA. " +
          "Omit to analyze uncommitted working-tree changes (git diff HEAD).",
      ),
  },
  async execute(args, context) {
    const cfgPath = `${context.worktree}/.opencode/impact.json`
    let cfg: { harness_path: string }
    try {
      cfg = await Bun.file(cfgPath).json()
    } catch {
      return (
        `impact: no config at ${cfgPath}. Create it from ` +
        `integrations/opencode/impact.json.example: ` +
        `{ "harness_path", "methods", "coverage", "mutation", "total_tests" }.`
      )
    }
    const base = args.base && args.base.length > 0 ? args.base : "WORKTREE"
    const proc = await Bun.$`python3 -m harness.impact.from_git --config ${cfgPath} --repo ${context.worktree} --base ${base}`
      .cwd(cfg.harness_path)
      .env({ ...process.env, PYTHONPATH: cfg.harness_path })
      .nothrow()
    if (proc.exitCode !== 0) {
      return `impact failed (exit ${proc.exitCode}):\n${proc.stderr.toString()}\n${proc.stdout.toString()}`
    }
    return proc.stdout.toString()
  },
})
