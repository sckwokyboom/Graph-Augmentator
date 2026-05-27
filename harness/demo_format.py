"""Strip a graph-tipper .budget.md to a compact, demo-ready format.

Always removes:
  - `## Long tail` and `## Negative Memory` sections
  - Joern-style type-signature lines above each sibling in Local Context
    (e.g. `int(picocli.CommandLine$Help$Ansi$Text)`)
  - chatty header metadata (`> Budget: ...`, `> Consumers: ...`)

Path-cluster handling is controlled by --keep-chains:
  - none  (default): drop all `#### 4.4.1.x Cluster` blocks entirely. Keeps the
            consumer header + body slice + implied requirements only.
  - paths: keep each cluster's header + [hub] marker + Entry-point + Path + Depth,
            but drop the Static slice / Differential matrix / Behavior signals
            (the UNRESOLVED noise). Shows "GT mapped all call paths, ranked them"
            without the visual clutter.
  - full:  keep clusters verbatim.

Input: path to a .budget.md (produced by `graph-tipper slice --no-current-body`,
optionally with `--katz-rank` so clusters are Katz-ordered).
Output: writes `<input-stem>.demo.md` (or `.demo-<keep_chains>.md`) next to the input.

Usage:
    python -m harness.demo_format /tmp/gt-out/.../abc.budget.md --keep-chains paths
"""
import argparse
import re
import sys
from pathlib import Path

DROP_SECTIONS = {"Long tail", "Negative Memory"}
HEADER_META = ("> Budget:", "> Consumers:", "> Direct tests:", "> Generated for:")
# Markers that begin the noisy part of a cluster block (dropped in `paths` mode).
CLUSTER_NOISE_MARKERS = ("**Static slice", "**Primary representative",
                         "**Differential matrix", "**Behavior signals",
                         "**+ ")


def strip_demo(md: str, keep_chains: str = "none") -> str:
    if keep_chains not in ("none", "paths", "full"):
        raise ValueError(f"keep_chains must be none|paths|full, got {keep_chains!r}")
    lines = md.splitlines()
    out: list[str] = []

    drop_section = False        # inside a ## section we drop entirely
    cluster_state = None        # None | "keep" | "drop" | "paths"
    cluster_suppress = False    # paths mode: past the **Static slice marker

    for line in lines:
        # 1. Chatty header metadata.
        if any(line.startswith(p) for p in HEADER_META):
            continue

        # 2. ## section headers reset all nested state.
        if line.startswith("## "):
            section = line[3:].strip()
            drop_section = section in DROP_SECTIONS
            cluster_state = None
            cluster_suppress = False
            if not drop_section:
                out.append(line)
            continue

        if drop_section:
            continue

        # 3. ### subsection ends any cluster.
        if line.startswith("### "):
            cluster_state = None
            cluster_suppress = False
            out.append(line)
            continue

        # 4. #### cluster header.
        if line.startswith("#### "):
            cluster_suppress = False
            if keep_chains == "none":
                cluster_state = "drop"
                continue
            elif keep_chains == "paths":
                cluster_state = "paths"
                out.append(line)
                continue
            else:  # full
                cluster_state = "keep"
                out.append(line)
                continue

        # 5. Cluster body.
        if cluster_state == "drop":
            continue
        if cluster_state == "paths":
            stripped = line.strip()
            if any(stripped.startswith(m) for m in CLUSTER_NOISE_MARKERS):
                cluster_suppress = True
            if cluster_suppress:
                continue
            out.append(line)
            continue
        # cluster_state in (None, "keep"): fall through to normal handling.

        out.append(line)

    out = _strip_joern_type_sigs(out)
    return _collapse_blanks(out)


def _strip_joern_type_sigs(lines: list[str]) -> list[str]:
    """In Local Context → Sibling members, drop Joern-style type-signature header lines."""
    type_sig_re = re.compile(r"^[\w$.\[\]]+\([^)]*\)$")
    java_keywords = ("public ", "private ", "protected ", "static ", "@", "void ",
                     "final ", "abstract ", "synchronized ", "default ")
    out: list[str] = []
    in_siblings = False
    in_code_fence = False
    for line in lines:
        if line.startswith("### Sibling members used by target"):
            in_siblings = True
            out.append(line)
            continue
        if in_siblings and (line.startswith("### ") or line.startswith("## ")):
            in_siblings = False
            out.append(line)
            continue
        if in_siblings:
            if line.startswith("```"):
                in_code_fence = not in_code_fence
                out.append(line)
                continue
            if in_code_fence:
                stripped = line.strip()
                if (type_sig_re.match(stripped)
                        and not any(stripped.startswith(kw) for kw in java_keywords)
                        and not stripped.endswith("{")
                        and not stripped.endswith(";")):
                    continue
        out.append(line)
    return out


def _collapse_blanks(lines: list[str]) -> str:
    final: list[str] = []
    prev_blank = False
    for line in lines:
        is_blank = (line.strip() == "")
        if is_blank and prev_blank:
            continue
        final.append(line)
        prev_blank = is_blank
    return "\n".join(final).rstrip() + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("budget_md", type=Path, help="Path to .budget.md from graph-tipper slice")
    p.add_argument("--keep-chains", choices=["none", "paths", "full"], default="none",
                   help="How much of the path-cluster blocks to keep (default: none)")
    p.add_argument("--out", type=Path, default=None,
                   help="Output path (default: <stem>.demo.md, or .demo-<mode>.md if not 'none')")
    args = p.parse_args()

    if not args.budget_md.exists():
        print(f"error: input file not found: {args.budget_md}", file=sys.stderr)
        sys.exit(1)

    md = args.budget_md.read_text()
    cleaned = strip_demo(md, keep_chains=args.keep_chains)

    out_path = args.out
    if out_path is None:
        stem = args.budget_md.name.replace(".budget.md", "")
        suffix = ".demo.md" if args.keep_chains == "none" else f".demo-{args.keep_chains}.md"
        out_path = args.budget_md.parent / f"{stem}{suffix}"

    out_path.write_text(cleaned)
    in_tokens = len(md) // 4
    out_tokens = len(cleaned) // 4
    print(f"wrote {out_path} ({out_tokens} tokens, keep-chains={args.keep_chains}, "
          f"{in_tokens - out_tokens} fewer than input)")


if __name__ == "__main__":
    main()
