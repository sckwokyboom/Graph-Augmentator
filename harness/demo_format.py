"""Strip a graph-tipper .budget.md to a compact, demo-ready format.

Removes sections that look impressive but carry little signal for an LLM
generating the target body:

  - All path-cluster blocks (`#### 4.4.1.a..j Cluster: ...`) — they repeat
    the same UNRESOLVED static slices 10 times for picocli/putValue.
    Keeps the consumer header + body slice + implied requirements (the parts
    that *do* help).
  - `## Long tail` section.
  - `## Negative Memory` (reserved placeholder).
  - Joern-style type-signature lines that appear above each sibling
    in Local Context (e.g. `int(picocli.CommandLine$Help$Ansi$Text)`).
    They're for grep, not for humans.
  - Metadata lines in the header (`> Budget: ...`, `> Consumers: ...`).

Input: path to a .budget.md (produced by `graph-tipper slice --no-current-body`)
Output: writes `<input-stem>.demo.md` next to the input.

Usage:
    python -m harness.demo_format /tmp/gt-out/picocli-putvalue/abc.budget.md
"""
import argparse
import re
import sys
from pathlib import Path


def strip_demo(md: str) -> str:
    lines = md.splitlines()
    out: list[str] = []

    # Pass 1: drop full sections (## Long tail, ## Negative Memory) and the
    # cluster blocks (#### 4.4.1.a..j). Track section depth via headers.
    drop_until_next_h2 = False
    in_consumer_block = False
    drop_until_next_h3_or_h2 = False

    i = 0
    while i < len(lines):
        line = lines[i]

        # Drop chatty metadata in the header (between `# Graph-Tipper` and `## Target`).
        if line.startswith("> Budget:") or line.startswith("> Consumers:") \
                or line.startswith("> Direct tests:") or line.startswith("> Generated for:"):
            i += 1
            continue

        # Section gating: ## headers.
        if line.startswith("## "):
            heading = line[3:].strip()
            drop_until_next_h2 = heading in ("Long tail", "Negative Memory")
            in_consumer_block = heading == "Consumer contracts"
            drop_until_next_h3_or_h2 = False
            if not drop_until_next_h2:
                out.append(line)
            i += 1
            continue

        if drop_until_next_h2:
            i += 1
            continue

        # Inside Consumer contracts: drop the per-cluster `#### 4.4.1.x ...` blocks
        # but keep the first consumer's body slice and implications.
        if in_consumer_block and line.startswith("#### "):
            drop_until_next_h3_or_h2 = True
            i += 1
            continue

        if drop_until_next_h3_or_h2:
            # Re-enable rendering at the next h3 or h2.
            if line.startswith("### ") or line.startswith("## "):
                drop_until_next_h3_or_h2 = False
                # fall through to process this line normally
            else:
                i += 1
                continue

        out.append(line)
        i += 1

    # Pass 2: in Local Context → Sibling members, strip Joern-style type signatures.
    # Pattern: a line that LOOKS like `<return-type>(<param-types>)` with no body, no
    # Java keywords. We detect these as lines whose stripped form matches /^[\w$.\[\]]+\([^)]*\)$/
    # AND that don't look like a real Java declaration (no `public/private/protected/static`,
    # no trailing `{`).
    type_sig_re = re.compile(r"^[\w$.\[\]]+\([^)]*\)$")
    java_keywords = ("public ", "private ", "protected ", "static ", "@", "void ",
                     "final ", "abstract ", "synchronized ", "default ")

    cleaned: list[str] = []
    in_sibling_section = False
    in_code_fence = False

    for line in out:
        # Track ### Sibling members
        if line.startswith("### Sibling members used by target"):
            in_sibling_section = True
            cleaned.append(line)
            continue
        if in_sibling_section and (line.startswith("### ") or line.startswith("## ")):
            in_sibling_section = False
            cleaned.append(line)
            continue

        if in_sibling_section:
            # Track code-fence depth so we strip type-sigs only inside the ```java block.
            if line.startswith("```"):
                in_code_fence = not in_code_fence
                cleaned.append(line)
                continue
            if in_code_fence:
                stripped = line.strip()
                if (type_sig_re.match(stripped)
                        and not any(stripped.startswith(kw) for kw in java_keywords)
                        and not stripped.endswith("{")
                        and not stripped.endswith(";")):
                    # Type-signature header line — drop.
                    continue
        cleaned.append(line)

    # Pass 3: collapse runs of blank lines.
    final: list[str] = []
    prev_blank = False
    for line in cleaned:
        is_blank = (line.strip() == "")
        if is_blank and prev_blank:
            continue
        final.append(line)
        prev_blank = is_blank

    return "\n".join(final).rstrip() + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("budget_md", type=Path, help="Path to .budget.md from graph-tipper slice")
    p.add_argument("--out", type=Path, default=None,
                   help="Output path (default: <stem>.demo.md next to input)")
    args = p.parse_args()

    if not args.budget_md.exists():
        print(f"error: input file not found: {args.budget_md}", file=sys.stderr)
        sys.exit(1)

    md = args.budget_md.read_text()
    cleaned = strip_demo(md)

    out_path = args.out
    if out_path is None:
        stem = args.budget_md.name.replace(".budget.md", "")
        out_path = args.budget_md.parent / f"{stem}.demo.md"

    out_path.write_text(cleaned)
    in_tokens = len(md) // 4
    out_tokens = len(cleaned) // 4
    print(f"wrote {out_path} ({out_tokens} tokens ≈ {in_tokens - out_tokens} fewer than input)")


if __name__ == "__main__":
    main()
