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
  - paths+snippets: like `paths`, plus under each cluster, the call-site code
            fragment for the last few hops to the target (where caller invokes the
            next method, with the backward-sliced statements). Read from the sibling
            `.json` sidecar's per-step `snippet` fields. Identical call-edges (the
            convergent tail shared across clusters) are shown once and referenced
            afterwards, to avoid 10x repetition.
  - full:  keep clusters verbatim.

Input: path to a .budget.md (produced by `graph-tipper slice --no-current-body`,
optionally with `--katz-rank` so clusters are Katz-ordered). For paths+snippets the
sibling `<stem>.json` must exist next to it (graph-tipper always emits both).
Output: writes `<input-stem>.demo.md` (or `.demo-<keep_chains>.md`) next to the input.

Usage:
    python -m harness.demo_format /tmp/gt-out/.../abc.budget.md --keep-chains paths+snippets
"""
import argparse
import json
import re
import sys
from pathlib import Path

DROP_SECTIONS = {"Long tail", "Negative Memory"}
HEADER_META = ("> Budget:", "> Consumers:", "> Direct tests:", "> Generated for:")
# In `paths` mode we keep a whitelist of per-cluster lines and drop everything else
# (Static slice args, Differential matrix tables, Behavior signals, "+N more"). The
# Primary representative line is kept so the LLM still has a "which test to grep" pointer.
PATHS_KEEP_PREFIXES = ("[hub:", "**Entry-point:", "**Path:", "**Depth:",
                       "**Primary representative:")


VALID_KEEP_CHAINS = ("none", "paths", "paths+snippets", "full")


def strip_demo(md: str, keep_chains: str = "none", chains: list | None = None,
               chain_hops: int = 3) -> str:
    if keep_chains not in VALID_KEEP_CHAINS:
        raise ValueError(f"keep_chains must be one of {VALID_KEEP_CHAINS}, got {keep_chains!r}")
    # paths+snippets is paths plus a post-pass that injects call-site fragments.
    base = "paths" if keep_chains == "paths+snippets" else keep_chains
    result = _strip_demo_base(md, base)
    if keep_chains == "paths+snippets":
        result = _inject_callsite_snippets(result, chains or [], chain_hops)
    return result


def _strip_demo_base(md: str, keep_chains: str) -> str:
    lines = md.splitlines()
    out: list[str] = []

    drop_section = False        # inside a ## section we drop entirely
    cluster_state = None        # None | "keep" | "drop" | "paths"

    for line in lines:
        # 1. Chatty header metadata.
        if any(line.startswith(p) for p in HEADER_META):
            continue

        # 2. ## section headers reset all nested state.
        if line.startswith("## "):
            section = line[3:].strip()
            drop_section = section in DROP_SECTIONS
            cluster_state = None
            if not drop_section:
                out.append(line)
            continue

        if drop_section:
            continue

        # 3. ### subsection ends any cluster.
        if line.startswith("### "):
            cluster_state = None
            out.append(line)
            continue

        # 4. #### cluster header.
        if line.startswith("#### "):
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
            # Whitelist: keep the path skeleton + hub + representative test pointer; drop
            # the Static-slice args, Differential-matrix table, Behavior signals, "+N more".
            if stripped == "" or any(stripped.startswith(p) for p in PATHS_KEEP_PREFIXES):
                out.append(line)
            continue
        # cluster_state in (None, "keep"): fall through to normal handling.

        out.append(line)

    out = _strip_joern_type_sigs(out)
    return _collapse_blanks(out)


_BACKTICK_RE = re.compile(r"`([^`]+)`")


def _simple(fqn: str) -> str:
    """ClassName.method from a fully-qualified name (mirrors the Java renderer roughly)."""
    if not fqn:
        return "?"
    last_dot = fqn.rfind(".")
    if last_dot < 0:
        return fqn
    method = fqn[last_dot + 1:]
    rest = fqn[:last_dot]
    sep = max(rest.rfind("."), rest.rfind("$"))
    cls = rest if sep < 0 else rest[sep + 1:]
    return f"{cls}.{method}"


def _find_representative_chain(chains: list, test_fqn: str, entry_fqn: str):
    """Pick the chain whose test == representative and whose first hop enters entry_fqn."""
    candidates = []
    for ch in chains:
        t = ch.get("test")
        tf = t.get("fqn") if isinstance(t, dict) else t
        if tf != test_fqn:
            continue
        steps = ch.get("steps") or []
        if steps and steps[0].get("calleeFqn") == entry_fqn:
            return ch
        candidates.append(ch)
    return candidates[0] if candidates else None


def _inject_callsite_snippets(md: str, chains: list, hops: int) -> str:
    """After each cluster's Primary representative line, inject the call-site snippets for
    the last `hops` steps of that cluster's representative chain. Identical call-edges
    (caller→callee) are emitted once; later occurrences get a one-line back-reference."""
    if not chains:
        return md
    lines = md.splitlines()
    out: list[str] = []
    cur_entry = None
    cur_anchor = None
    seen_edges: dict[str, str] = {}  # "Caller.m → Callee.m" -> anchor where first shown

    for line in lines:
        out.append(line)
        stripped = line.strip()

        if stripped.startswith("#### "):
            # e.g. "#### 4.4.1.a Cluster: ..."
            parts = stripped.split()
            cur_anchor = parts[1] if len(parts) > 1 else "?"
            cur_entry = None
            continue

        if stripped.startswith("**Entry-point:**"):
            m = _BACKTICK_RE.search(stripped)
            cur_entry = m.group(1) if m else None
            continue

        if stripped.startswith("**Primary representative:**"):
            m = _BACKTICK_RE.search(stripped)
            repr_test = m.group(1) if m else None
            if not (repr_test and cur_entry):
                continue
            ch = _find_representative_chain(chains, repr_test, cur_entry)
            if ch is None:
                continue
            steps = (ch.get("steps") or [])[-hops:]
            if not steps:
                continue
            block: list[str] = ["", f"**Call-sites (last {len(steps)} hops to target):**"]
            for s in steps:
                edge = f"{_simple(s.get('callerFqn',''))} → {_simple(s.get('calleeFqn',''))}"
                if edge in seen_edges:
                    block.append(f"- `{edge}` — same call-site as Cluster {seen_edges[edge]}")
                    continue
                seen_edges[edge] = cur_anchor
                snippet = (s.get("snippet") or "").rstrip()
                if not snippet:
                    block.append(f"- `{edge}` — (call site not located)")
                    continue
                block.append(f"- `{edge}`:")
                block.append("```java")
                block.extend(snippet.splitlines())
                block.append("```")
            out.extend(block)
            continue

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


def _load_sibling_chains(budget_md: Path) -> list:
    """graph-tipper emits <stem>.json next to <stem>.budget.md; it holds the chains."""
    json_path = budget_md.with_name(budget_md.name.replace(".budget.md", ".json"))
    if not json_path.exists():
        print(f"error: paths+snippets needs the sidecar {json_path} (not found)", file=sys.stderr)
        sys.exit(1)
    return json.loads(json_path.read_text()).get("chains", [])


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("budget_md", type=Path, help="Path to .budget.md from graph-tipper slice")
    p.add_argument("--keep-chains", choices=list(VALID_KEEP_CHAINS), default="none",
                   help="How much of the path-cluster blocks to keep (default: none)")
    p.add_argument("--chain-hops", type=int, default=3,
                   help="For paths+snippets: how many trailing call-site hops to show (default: 3)")
    p.add_argument("--out", type=Path, default=None,
                   help="Output path (default: <stem>.demo.md, or .demo-<mode>.md if not 'none')")
    args = p.parse_args()

    if not args.budget_md.exists():
        print(f"error: input file not found: {args.budget_md}", file=sys.stderr)
        sys.exit(1)

    chains = _load_sibling_chains(args.budget_md) if args.keep_chains == "paths+snippets" else None
    md = args.budget_md.read_text()
    cleaned = strip_demo(md, keep_chains=args.keep_chains, chains=chains, chain_hops=args.chain_hops)

    out_path = args.out
    if out_path is None:
        stem = args.budget_md.name.replace(".budget.md", "")
        mode_tag = args.keep_chains.replace("+", "-")  # paths+snippets -> paths-snippets
        suffix = ".demo.md" if args.keep_chains == "none" else f".demo-{mode_tag}.md"
        out_path = args.budget_md.parent / f"{stem}{suffix}"

    out_path.write_text(cleaned)
    in_tokens = len(md) // 4
    out_tokens = len(cleaned) // 4
    print(f"wrote {out_path} ({out_tokens} tokens, keep-chains={args.keep_chains}, "
          f"{in_tokens - out_tokens} fewer than input)")


if __name__ == "__main__":
    main()
