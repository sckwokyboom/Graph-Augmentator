"""Semantic extraction from the CPG for the augmentation bundle — the pieces a mechanical
digest misses but the hand-authored slice leads with:

- direct-caller TESTS of the target (+ their source, which carries the oracles),
- the return-value CONSUMER / single CHOKEPOINT (production caller, ranked by co-coverage),
- a semantic FOCUS SET (direct callers + name-matched tests), not a lexicographic sample.

Pure functions over a CpgIndex (harness.impact.cpg_index) + the red coverage matrix
(`{method_fqn: [test_fqn, ...]}`). Reachability of the return value is not tracked at the
identifier level (only statement-level REACHING_DEF exists), so the consumer is derived from
the production callers ranked by co-coverage — reliable, matches the hand slice's pick."""
from collections import defaultdict
from pathlib import Path


def reverse_call_map(idx):
    rev = defaultdict(set)
    for caller, callees in idx.call_map.items():
        for callee in callees:
            rev[callee].add(caller)
    return rev


def _is_test(idx, fqn):
    ms = idx.methods_named(fqn)
    return bool(ms) and idx.is_test_code(ms[0])


def direct_callers(idx, target_fqn):
    """Method FQNs whose body directly CALLs target_fqn (from the static call map)."""
    return sorted(reverse_call_map(idx).get(target_fqn, set()))


def direct_test_callers(idx, target_fqn):
    return [c for c in direct_callers(idx, target_fqn) if _is_test(idx, c)]


def production_callers(idx, target_fqn):
    return [c for c in direct_callers(idx, target_fqn)
            if idx.methods_named(c) and not _is_test(idx, c)]


def chokepoint(idx, target_fqn, coverage):
    """The production caller with the highest test-set overlap with the target — the single
    method the most chains pass through. Returns {fqn, jaccard, shared, callers} or None."""
    prod = production_callers(idx, target_fqn)
    if not prod:
        return None
    tgt = set(coverage.get(target_fqn, []))
    best = None
    for c in prod:
        ct = set(coverage.get(c, []))
        union = tgt | ct
        j = len(tgt & ct) / len(union) if union else 0.0
        if best is None or j > best["jaccard"]:
            best = {"fqn": c, "jaccard": round(j, 3), "shared": len(tgt & ct)}
    best["callers"] = prod
    return best


def focus_tests(idx, target_fqn, covering, k_namematch=10):
    """Tests whose oracle actually constrains the target: direct callers first, then tests
    name-matched to the target class/method simple names, capped. `covering` = the red
    matrix's test list for the target (only tests that actually executed it)."""
    cov = set(covering)
    direct = [t for t in direct_test_callers(idx, target_fqn) if t in cov]
    cls_simple = target_fqn.rpartition(".")[0].rpartition("$")[2].lower()
    m_simple = target_fqn.rpartition(".")[2].lower()
    seen = set(direct)

    def matches(t):
        low = t.lower()
        return cls_simple in low or m_simple in low
    named = [t for t in sorted(cov) if matches(t) and t not in seen]
    return {"direct": direct, "named": named[:k_namematch]}


def method_source(idx, fqn, project, *, max_lines=45, strip_doc=False):
    """Extract a method's source body from disk via its CPG file+line range.
    strip_doc drops a leading javadoc/line-comment block (for consumer/chain code where the
    logic, not the doc, is what matters)."""
    ms = idx.methods_named(fqn)
    if not ms:
        return None
    p = ms[0]["properties"]
    rel = idx.map_filename(p.get("FILENAME") or "")
    start, end = p.get("LINE_NUMBER"), p.get("LINE_NUMBER_END")
    if not rel or rel == "<empty>" or not str(start).lstrip("-").isdigit():
        return None
    try:
        lines = (Path(project) / rel).read_text(encoding="utf-8", errors="ignore").splitlines()
    except OSError:
        return None
    s, e = int(start), int(end or start)
    body = lines[s - 1:e]
    if strip_doc:
        j = 0
        while j < len(body) and (not body[j].strip() or body[j].lstrip().startswith(("/**", "*", "*/", "//"))):
            j += 1
        body = body[j:] or body
    return {"fqn": fqn, "file": rel, "line": s, "source": "\n".join(body[:max_lines])}


def write_semantics(cfg, idx, coverage, covering):
    """Produce the semantic pool artifacts the mechanical digest misses:
    03-tests/direct-tests.md, 03-tests/focus.md, 02-static/consumer.md +
    02-static/chokepoint.txt. Returns the chokepoint dict (or None)."""
    target = cfg.target_fqn
    m_simple = target.rpartition(".")[2]

    # direct-caller tests + their source (the oracles live in the asserts around the call)
    srcs = [s for s in (method_source(idx, t, cfg.project, max_lines=40)
                        for t in direct_test_callers(idx, target)[:6]) if s]
    lines = [f"# Direct tests — call `{m_simple}` directly (oracles are the asserts here)", ""]
    if not srcs:
        lines.append("_(no direct-caller tests found in the CPG)_")
    for s in srcs:
        lines += [f"## `{s['fqn']}`  ({s['file']}:{s['line']})", "```java", s["source"], "```", ""]
    (cfg.pool / "03-tests/direct-tests.md").write_text("\n".join(lines))
    cfg.provenance("03-tests/direct-tests.md", "kgpool.semantics.write_semantics",
                   f"{len(srcs)} direct-caller test bodies (test-owned oracles, not a leak)")

    # semantic focus set (direct callers + name-matched), not lexicographic
    f = focus_tests(idx, target, covering)
    fl = ["# Focus set — tests whose oracle constrains the target", "",
          "## Direct (call the target directly)"] + \
         ([f"- `{t}`" for t in f["direct"]] or ["_(none)_"]) + \
         ["", "## Name-matched (target class / method)"] + \
         ([f"- `{t}`" for t in f["named"]] or ["_(none)_"])
    (cfg.pool / "03-tests/focus.md").write_text("\n".join(fl))
    cfg.provenance("03-tests/focus.md", "kgpool.semantics.write_semantics",
                   f"{len(f['direct'])} direct + {len(f['named'])} name-matched")

    # consumer / chokepoint (production caller with the highest co-coverage)
    cp = chokepoint(idx, target, coverage)
    if cp:
        src = method_source(idx, cp["fqn"], cfg.project, max_lines=50)
        cl = [f"# Consumer / chokepoint: `{cp['fqn']}`", "",
              f"Production caller of the target with the highest test-set overlap "
              f"(jaccard={cp['jaccard']}, {cp['shared']} shared tests) — the single method the "
              f"most chains pass through; it consumes the target's return value.", ""]
        if len(cp["callers"]) > 1:
            cl += ["Other production callers: " + ", ".join(f"`{c}`" for c in cp["callers"] if c != cp["fqn"]), ""]
        if src:
            cl += ["```java", src["source"], "```"]
        (cfg.pool / "02-static/consumer.md").write_text("\n".join(cl))
        (cfg.pool / "02-static/chokepoint.txt").write_text(cp["fqn"] + "\n")
        cfg.provenance("02-static/consumer.md", "kgpool.semantics.write_semantics",
                       f"chokepoint {cp['fqn']} (jaccard {cp['jaccard']}, {len(cp['callers'])} prod callers)")
    return cp
