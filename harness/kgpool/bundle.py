"""Deterministic assembly of augment.prompt.md from a kgpool pool. No model call, no
Date/random. The pool is STRICT (stub-only), so the bundle cannot contain the reference
body by construction; we still assert the stub is shown."""
import json
from pathlib import Path

DEFAULT_CAPS = {"values": 12, "snippets": 8, "snippet_lines": 12, "co_covered": 8, "kg": 8}

SKELETON = """You are producing a debugging-methodology augmentation for a Java code task.
Write ONE Markdown file that forces this workflow: **instrument the existing tests ->
observe the real data flow -> implement against what you saw**. Use ONLY the facts below
(all captured from a run where the target body is stubbed). Required sections:
1. How to use this (read first) — the instrument-then-implement workflow.
2. Direct tests (the contract) — the oracles that pin behaviour.
3. Which tests to instrument — the universe + the focus set below.
4. Consumer contract — who consumes the return value.
5. Call chains / chain snippets — where to place `//[probe]` diagnostics.
6. Chokepoint — the single method most calls pass through.
7. Reminders — remove every `//[probe]` before finishing."""


def _universe(pool):
    f = pool / "03-tests/covering-tests.txt"
    if not f.exists():
        return "_(no covering tests)_"
    tests = [t for t in f.read_text().splitlines() if t.strip()]
    by_cls = {}
    for t in tests:
        by_cls.setdefault(t.rpartition(".")[0], []).append(t)
    rows = [f"- `{cls}` — {len(v)} test(s)" for cls, v in sorted(by_cls.items())]
    return f"Total {len(tests)} covering tests across {len(by_cls)} classes:\n" + "\n".join(rows)


def _focus(pool):
    f = pool / "03-tests/exemplars.txt"
    ex = [t for t in f.read_text().splitlines() if t.strip()] if f.exists() else []
    return "\n".join(f"- `{t}`" for t in ex) or "_(none)_"


def _values(pool, target_fqn, cap):
    f = pool / "04-runtime/value-capture/red.json"
    if not f.exists():
        return "_(no value capture)_", 0
    recs = json.loads(f.read_text()).get(target_fqn, [])
    rows = []
    for r in recs[:cap]:
        args = ", ".join(map(str, r.get("args", [])))
        rows.append(f"- `({args})` → `{r.get('result', '')}`" + (" _[throws]_" if r.get("throws") else ""))
    return ("\n".join(rows) or "_(none)_"), max(0, len(recs) - cap)


def _contracts(pool):
    f = pool / "02-static/method-contracts.md"
    return f.read_text() if f.exists() else "_(no contracts)_"


def _snippets(pool, cap, line_cap):
    d = pool / "02-static/snippets"
    files = sorted(d.glob("*.java")) if d.is_dir() else []
    out = []
    for p in files[:cap]:
        body = p.read_text().splitlines()
        clip = "\n".join(body[:line_cap]) + ("\n// ..." if len(body) > line_cap else "")
        out.append(f"**{p.stem}**\n```java\n{clip}\n```")
    return ("\n\n".join(out) or "_(no snippets)_"), max(0, len(files) - cap)


def _failures(pool):
    f = pool / "05-failure/red-run/failures-summary.md"
    return f.read_text() if f.exists() else "_(no failures summary)_"


def _kg(pool, cap):
    f = pool / "knowledge-graph.json"
    if not f.exists():
        return "_(no KG)_"
    kg = json.loads(f.read_text())
    nodes = kg.get("nodes", [])
    pick = lambda t: [n for n in nodes if n.get("type") == t]
    lines = []
    bc = pick("BehaviorClass")
    if bc:
        lines.append("Behavior classes: " + ", ".join(
            f"{n['label']} (×{n.get('props', {}).get('count', '?')})" for n in bc[:cap]))
    fm = pick("FailureMode")
    if fm:
        lines.append("Failure modes: " + ", ".join(
            f"{n['label']} (×{n.get('props', {}).get('count', '?')})" for n in fm))
    co = sorted(((e.get("props", {}).get("jaccard", 0), e["from"]) for e in kg.get("edges", [])
                 if e.get("rel") == "CO_COVERED_WITH"), reverse=True)
    if co:
        lines.append("Top co-covered: " + ", ".join(f"{fid.split(':')[-1]} (J={j})" for j, fid in co[:cap]))
    ip = pick("InputProfile")
    if ip:
        lines.append(f"Input profile: {ip[0].get('props', {})}")
    return "\n".join(f"- {ln}" for ln in lines) or "_(empty KG)_"


def render(cfg, *, caps=None):
    caps = {**DEFAULT_CAPS, **(caps or {})}
    pool = Path(cfg.pool)
    values, v_drop = _values(pool, cfg.target_fqn, caps["values"])
    snippets, s_drop = _snippets(pool, caps["snippets"], caps["snippet_lines"])
    capped = []
    if v_drop:
        capped.append(f"{v_drop} runtime value row(s)")
    if s_drop:
        capped.append(f"{s_drop} chain snippet(s)")
    caps_note = ("<!-- capped: " + "; ".join(capped) + " -->") if capped else "<!-- capped: nothing -->"

    doc = f"""# Synthesis task

{SKELETON}

## Leak rules

- NEVER reproduce the target method body. Show only the stub.
- Use ONLY the data in this file (captured with the target stubbed).

## Target

- FQN: `{cfg.target_fqn}`
- File: `{cfg.source_file}`
- Signature: `{cfg.target_signature}`
- Stub: `{cfg.stub_body}`

## Pool digest

pool: {pool}
{caps_note}

### Universe (tests that reach the target)

{_universe(pool)}

### Focus set (exemplars to instrument first)

{_focus(pool)}

### Runtime values (observed with the stub in place)

{values}

### Method contracts (corridor)

{_contracts(pool)}

### Chain snippets (place `//[probe]` here)

{snippets}

### Failures (red run)

{_failures(pool)}

### Knowledge-graph summary

{_kg(pool, caps["kg"])}
"""
    marker = cfg.stub_body.split("(")[0]
    if marker not in doc:
        raise RuntimeError("bundle does not show the stub marker — refusing to emit")
    out = pool / "augment.prompt.md"
    out.write_text(doc, encoding="utf-8")
    return out


def main():
    import argparse
    from harness.kgpool.config import load_config
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    args = ap.parse_args()
    print(render(load_config(args.config)))


if __name__ == "__main__":
    main()
