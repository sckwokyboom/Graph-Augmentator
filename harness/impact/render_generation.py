"""Render the generation augmentation: denoise the slice .budget.md (drop clusters /
long-tail / unresolved slices via demo_format) and append a dynamic 'Observed behaviour'
section. The examples are labelled NON-oracle (they reflect the baseline implementation;
the oracle is the tests / reference, not these)."""
from harness.demo_format import strip_demo


def _simple_method(fqn: str) -> str:
    return fqn.rsplit(".", 1)[-1] if "." in fqn else fqn


def format_examples(target_fqn: str, rows: list) -> str:
    name = _simple_method(target_fqn)
    out = ["## Observed behaviour (baseline runtime examples — NOT an oracle)\n",
           "_Captured from the existing implementation; use as behavioural hints, "
           "verify intent against the tests._\n"]
    for r in rows:
        call = f"{name}({', '.join(r['args'])})"
        out.append(f"- `{call} => {r['result']}`")
    out.append("")
    return "\n".join(out)


def render_generation(budget_md: str, examples: dict, target_fqn: str) -> str:
    base = strip_demo(budget_md, keep_chains="none").rstrip() + "\n"
    rows = examples.get(target_fqn, [])
    if not rows:
        return base
    return base + "\n" + format_examples(target_fqn, rows)


def main():
    import json
    import sys
    from pathlib import Path
    budget_md_path, examples_json, target_fqn, out_path = (
        Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], Path(sys.argv[4]))
    md = budget_md_path.read_text()
    examples = json.loads(examples_json.read_text())
    Path(out_path).write_text(render_generation(md, examples, target_fqn))
    print(f"wrote {out_path}")


if __name__ == "__main__":
    main()
