"""Deterministic assembly of augment.prompt.md from a kgpool pool. No model call, no
Date/random. The pool is STRICT (stub-only), so the bundle cannot contain the reference
target body by construction; we still assert the stub is shown. All embedded files are
sanitized (own headings stripped) and every section has a fallback so the renderer never
crashes on a partial pool."""
import json
from pathlib import Path

DEFAULT_CAPS = {"values": 12, "snippets": 8, "snippet_lines": 14, "co_covered": 8,
                "kg": 8, "goldens": 4}

SKELETON = """You are producing a debugging-methodology augmentation for a Java code task.
Write ONE Markdown file that forces this workflow: **instrument the existing tests ->
observe the real data flow -> implement against what you saw**. Use ONLY the facts below
(all captured from a run where the target body is stubbed). Required sections:
1. How to use this (read first) — the instrument-then-implement workflow.
2. Direct tests (the contract) — the oracles that pin behaviour (see the Direct-tests block).
3. Which tests to instrument — the universe + the focus set below.
4. Consumer contract — the chokepoint that reads the return value (see Consumer/chokepoint).
5. Call chains / chain snippets — where to place `//[probe]` diagnostics.
6. Chokepoint — the single method most calls pass through.
7. Reminders — remove every `//[probe]` before finishing."""


def _embed(pool, rel, fallback="_(none)_"):
    """Embed a pool markdown file, dropping its own leading heading (we supply an H3)."""
    f = pool / rel
    if not f.exists():
        return fallback
    lines = f.read_text().strip().splitlines()
    if lines and lines[0].lstrip().startswith("#"):
        lines = lines[1:]
    return "\n".join(lines).strip() or fallback


def _universe(pool):
    f = pool / "03-tests/covering-tests.txt"
    if not f.exists():
        return "_(no covering tests)_"
    tests = [t for t in f.read_text().splitlines() if t.strip()]
    by_cls = {}
    for t in tests:
        by_cls.setdefault(t.rpartition(".")[0], []).append(t)
    rows = [f"- `{cls}` — {len(v)} test(s)" for cls, v in sorted(by_cls.items(), key=lambda kv: (-len(kv[1]), kv[0]))]
    return (f"{len(tests)} tests reach the target across {len(by_cls)} classes. NOTE: most reach "
            "it only incidentally (rendering some unrelated feature's help) and assert the whole "
            "output — they will not reveal its contract. The focus set below is what constrains it.\n"
            + "\n".join(rows))


def _values(pool, fqn, cap):
    f = pool / "04-runtime/value-capture/red.json"
    if not f.exists() or not fqn:
        return "_(no value capture)_", 0
    recs = json.loads(f.read_text()).get(fqn, [])
    rows = []
    for r in recs[:cap]:
        args = ", ".join(map(str, r.get("args", [])))
        rows.append(f"- `({args})` → `{r.get('result', '')}`" + (" _[throws]_" if r.get("throws") else ""))
    return ("\n".join(rows) or "_(none captured)_"), max(0, len(recs) - cap)


def _chokepoint_fqn(pool):
    f = pool / "02-static/chokepoint.txt"
    return f.read_text().strip() if f.exists() else None


def _snippets(pool, cap, line_cap):
    d = pool / "02-static/snippets"
    files = sorted(d.glob("*.java")) if d.is_dir() else []
    out = []
    for p in files[:cap]:
        lines = p.read_text().splitlines()
        i = 0                                   # skip leading javadoc/comment -> show the CODE
        while i < len(lines) and (not lines[i].strip()
                                  or lines[i].lstrip().startswith(("/**", "*", "*/", "//"))):
            i += 1
        code = lines[i:] or lines
        clip = "\n".join(code[:line_cap]) + ("\n// ..." if len(code) > line_cap else "")
        out.append(f"**{p.stem}**\n```java\n{clip}\n```")
    return ("\n\n".join(out) or "_(no snippets)_"), max(0, len(files) - cap)


def _goldens(pool, cap):
    f = pool / "knowledge-graph.json"
    if not f.exists():
        return "_(no golden outputs)_"
    gs = [n for n in json.loads(f.read_text()).get("nodes", []) if n.get("type") == "GoldenOutput"][:cap]
    if not gs:
        return "_(no multi-line ComparisonFailure expected sides captured)_"
    out = []
    for n in gs:
        pr = n.get("props", {})
        out.append(f"- expected by `{pr.get('test', '?').rpartition('.')[2]}`:\n```\n{pr.get('excerpt', '')}\n```")
    return "\n".join(out)


def _kg(pool, cap):
    f = pool / "knowledge-graph.json"
    if not f.exists():
        return "_(no KG)_"
    kg = json.loads(f.read_text())
    nodes = kg.get("nodes", [])
    pick = lambda t: [n for n in nodes if n.get("type") == t]
    lines = []
    fm = pick("FailureMode")
    if fm:
        lines.append("Failure modes: " + ", ".join(
            f"{n['label']} (×{n.get('props', {}).get('count', '?')})" for n in fm))
    co = sorted(((e.get("props", {}).get("jaccard", 0), e["from"]) for e in kg.get("edges", [])
                 if e.get("rel") == "CO_COVERED_WITH"), reverse=True)
    if co:
        lines.append("Co-covered methods (jaccard — co-execution, NOT necessarily a caller; the "
                     "actual consumer is in the Consumer/chokepoint section): "
                     + ", ".join(f"{fid.split(':')[-1]} (J={j})" for j, fid in co[:cap]))
    ip = pick("InputProfile")
    if ip:
        lines.append(f"Observed target input domain: {ip[0].get('props', {})}")
    return "\n".join(f"- {ln}" for ln in lines) or "_(empty KG)_"


def render(cfg, *, caps=None):
    caps = {**DEFAULT_CAPS, **(caps or {})}
    pool = Path(cfg.pool)
    m_simple = cfg.target_fqn.rpartition(".")[2]
    tgt_vals, tv_drop = _values(pool, cfg.target_fqn, caps["values"])
    cp_fqn = _chokepoint_fqn(pool)
    cp_vals, cv_drop = _values(pool, cp_fqn, caps["values"]) if cp_fqn else ("_(no chokepoint)_", 0)
    snippets, s_drop = _snippets(pool, caps["snippets"], caps["snippet_lines"])
    capped = []
    for n, what in ((tv_drop, "target value row(s)"), (cv_drop, "consumer value row(s)"),
                    (s_drop, "chain snippet(s)")):
        if n:
            capped.append(f"{n} {what}")
    caps_note = ("<!-- capped: " + "; ".join(capped) + " -->") if capped else "<!-- capped: nothing -->"

    consumer_vals = (f"**Consumer `{cp_fqn.rpartition('.')[2]}`** — real inputs flowing toward the "
                     f"target (this method is NOT stubbed, so these are genuine values):\n\n{cp_vals}\n"
                     if cp_fqn else "")

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

### Direct tests (the contract)

Tests that call `{m_simple}` directly — the asserts here ARE the oracle. Reproduce these
as the "Direct tests" section:

{_embed(pool, "03-tests/direct-tests.md", "_(no direct-caller tests found)_")}

### Universe (tests that reach the target)

{_universe(pool)}

### Focus set (instrument these first)

{_embed(pool, "03-tests/focus.md", _embed(pool, "03-tests/exemplars.txt"))}

### Consumer / chokepoint (who reads the return)

{_embed(pool, "02-static/consumer.md", "_(no production caller identified)_")}

### Runtime values (observed with the target stubbed)

**Target `{m_simple}`** — the stub throws on the first call in every test, so these rows show
only the ARGUMENTS it was handed (the result is always the stub throw), not its behaviour:

{tgt_vals}

{consumer_vals}
### Method contracts (corridor)

{_embed(pool, "02-static/method-contracts.md", "_(no contracts)_")}

### Chain snippets (place `//[probe]` here)

{snippets}

### Failures & golden outputs

Failure modes and the multi-line expected outputs asserted by the ComparisonFailure tests
(these are the golden strings the real implementation must reproduce):

{_goldens(pool, caps["goldens"])}

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
