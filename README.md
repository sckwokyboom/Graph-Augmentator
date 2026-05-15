# Graph-Tipper

CLI that produces a Markdown context-augmentation artifact for a Java target
method using a Code Property Graph produced by Joern. The artifact is designed
to be dropped directly into the context of an agentic IDE so the agent can
generate a method body with fewer test/build cycles.

The artifact contains:

- Reverse call-graph chains from the target up to the test methods that
  transitively exercise it, with source snippets and argument back-slices.
- Local context: sibling members the target uses, public API of used types
  (with enum constants in full), production call sites of the target.

A companion JSON sidecar carries the same data in a stable schema (with
reserved slots for V2 negative-memory).

---

## Prerequisites

1. **Java 21**. Verify:
   ```
   java -version
   ```
   If you don't have it: `brew install openjdk@21` (macOS) or
   `sdk install java 21.0.4-tem` (SDKMAN).

2. **Joern** on PATH (binaries `javasrc2cpg` and `joern`).

   **macOS / Linux** — install via the bundled helper:
   ```
   bash tools/install-joern.sh
   ```

   **Windows** — Joern's official installer is bash-only. Either:
   - Download the latest `joern-cli.zip` from
     https://github.com/joernio/joern/releases/latest, extract it, and add the
     resulting `joern-cli\` folder (the one containing `javasrc2cpg.bat`) to
     your `PATH`; or
   - Install via [Scoop](https://scoop.sh):
     `scoop bucket add joernio https://github.com/joernio/joern.git && scoop install joern`.

   Alternatively, skip `PATH` setup and point graph-tipper at the install
   directory with `--joern-home C:\path\to\joern-cli`.

   Verify:
   ```
   javasrc2cpg --version          # Unix
   javasrc2cpg.bat --version      # Windows
   ```

---

## Build

From the repository root:

```
./gradlew installDist
```

This produces a launcher at `build/install/graph-tipper/bin/graph-tipper`
(plus `graph-tipper.bat` on Windows). Optional: add it to PATH for convenience.

```
# macOS / Linux
export PATH="$PWD/build/install/graph-tipper/bin:$PATH"
graph-tipper --help
```

```powershell
# Windows (PowerShell)
$env:Path = "$PWD\build\install\graph-tipper\bin;$env:Path"
graph-tipper --help
```

---

## End-to-end example: picocli `TextTable.putValue`

This walks through generating an artifact for the motivating example —
`putValue(int, int, Text)` in
[picocli](https://github.com/remkop/picocli).

### Step 1 — clone the target project

```
git clone https://github.com/remkop/picocli /tmp/picocli
```

### Step 2 — run Graph-Tipper

```
graph-tipper \
  --project /tmp/picocli \
  --target 'src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)' \
  --out /tmp/gt-out
```

First run will spend ~30–60 seconds invoking Joern to build the CPG. The CPG
is cached under `/tmp/gt-out/.cache/<sha256-of-source-tree>/`, so subsequent
runs against the same source state finish in under a second.

On success the launcher prints the absolute path of the Markdown artifact, for
example:

```
/tmp/gt-out/3f7a91b0c14d8e22.md
```

### Step 3 — inspect the artifact

```
ls /tmp/gt-out
# 3f7a91b0c14d8e22.md      ← drop this into the agent's context
# 3f7a91b0c14d8e22.json    ← machine-readable sidecar

less /tmp/gt-out/3f7a91b0c14d8e22.md
```

The Markdown starts with a budget header and is organized as:

```
# Graph-Tipper Augmentation
> Generated for: picocli @ <hash>
> Target: picocli.CommandLine$TextTable.putValue
> Budget: 18420 / 20000 tokens · Chains: 9 · Truncated: false

## Target           ← signature + javadoc + current body
## Test Chains      ← chain test → ... → target, with snippets + arg origins
## Local Context    ← sibling members, used types (enum constants in full)
## Negative Memory  ← reserved for V2
```

### Step 4 — pass it to the agent

Concatenate the artifact into the agent's prompt:

```
cat /tmp/gt-out/3f7a91b0c14d8e22.md \
  | pbcopy   # macOS — paste into your agent UI
```

Or programmatically (any agent framework):

```bash
ARTIFACT=$(cat /tmp/gt-out/3f7a91b0c14d8e22.md)
your-agent-cli --context "$ARTIFACT" --task "Implement putValue per the artifact"
```

---

## CLI reference

```
graph-tipper [OPTIONS] --project <dir> --target <spec> --out <dir>
```

| Option | Default | Purpose |
|---|---|---|
| `--project <dir>` | required | Java project root (containing `src/`). |
| `--target <spec>` | required | Target method spec; two forms (see below). |
| `--out <dir>` | required | Output directory for the artifact and cache. |
| `--budget-tokens <int>` | `20000` | Markdown token budget. Approximation: 4 chars/token. |
| `--max-chains <int>` | `16` | Maximum number of chains to keep. |
| `--treat-test-dirs-as-tests` | off | Also treat methods under `src/test/java/**` as tests even without `@Test`. |
| `--no-cache` | off | Force a fresh Joern build, ignore cache. |
| `--joern-home <dir>` | unset | Use `<dir>/javasrc2cpg` etc. instead of relying on PATH. |
| `--debug-dot` | off | Parsed, but DOT rendering is deferred to V2. |
| `-h`, `--help` | — | Print usage. |

### Target spec — two forms

**Path + signature** (matches your javadoc-style references):
```
src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)
```

**FQN-only** (compact):
```
picocli.CommandLine$TextTable#putValue(int,int,picocli.CommandLine$Help$Ansi$Text)
```

The parameter list is optional in both forms. If omitted or ambiguous, the
tool prints the candidates and exits with code 2.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Artifact written successfully. |
| 1 | Generic error (Joern failure, I/O, etc.). |
| 2 | Target method not found or ambiguous. |
| 3 | Token budget too small to fit the protected minimum. |

---

## Output layout

```
<--out>/
├── <hash>.md       ← agent-facing artifact
├── <hash>.json     ← machine sidecar (stable schema v1.0)
└── .cache/
    └── <src-sha>/  ← Joern CPG cache, keyed by source-tree SHA-256
        ├── cpg.bin
        └── export/export.json
```

`<hash>` is derived from the target spec + source-tree SHA-256, so identical
inputs produce identical filenames (good for `diff` between iterations).

---

## Troubleshooting

**"No tests transitively reach this target"** — the artifact is still
written, with an empty Test Chains section and an explicit note. This is a
valid signal: the target has no test coverage. The agent can still use the
local-context block.

**"No method matches X" (exit 2)** — the tool prints the five nearest
signatures. Either fix the spec or omit the parameter list to see all
candidates with that name in that class.

**"Multiple matches" (exit 2)** — disambiguate by providing parameter types.
The error message lists them.

**"budget exceeded on minimum" (exit 3)** — the target's javadoc + current
body + top-1 chain alone exceed the budget. Raise `--budget-tokens`.

**Joern fails on the project** — `javasrc2cpg` is source-only and stumbles on
some unusual generic/lambda shapes. As a workaround, try `--no-cache` to
force a rebuild. The jimple-based (bytecode) fallback is on the V2 roadmap.

**`CreateProcess error=2, The system cannot find the file specified`
(Windows)** — graph-tipper could not find `javasrc2cpg.bat` or `joern.bat`.
Confirm `javasrc2cpg.bat --version` runs in the same shell. If Joern is
installed but not on `PATH`, pass `--joern-home C:\path\to\joern-cli`
(the directory that contains the `.bat` launchers).

---

## Other commands

### Run the test suite

```
./gradlew test
```

### Optional smoke test against a local picocli checkout

```
GRAPHTIPPER_PICOCLI_HOME=/tmp/picocli \
  ./gradlew test --tests com.graphtipper.PicocliSmokeTest
```

Skipped when the env var is unset.

### Re-generate after editing the project

The cache key is the SHA-256 of all `.java` files. Editing any source file
invalidates the cache automatically — just re-run the same command. Use
`--no-cache` only when you suspect a stale cache entry.

---

## Design

See [`docs/superpowers/specs/2026-05-13-graph-tipper-v1-design.md`](docs/superpowers/specs/2026-05-13-graph-tipper-v1-design.md)
for the full V1 design (architecture, schema, slicer algorithms, eviction
order) and known V1 limitations (§14).

The implementation plan is at
[`docs/superpowers/plans/2026-05-13-graph-tipper-v1.md`](docs/superpowers/plans/2026-05-13-graph-tipper-v1.md).
