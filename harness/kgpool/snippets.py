"""Per-corridor-method source snippets (javadoc + body) + signature-type class regions
+ whole-target-class extract for 01-task. MUST run while the stub is applied so the
target snippets are the stub. Port of the putValue pool's _tools/snippets.py."""
import json
import re


def _javadoc_summary(doc_lines):
    """First-sentence javadoc summary from the lines above a method decl: strip comment
    delimiters and {@link}/{@code} markup, stop at the first @tag, cut at the first
    sentence (<=220 chars). Replaces the old heuristic that emitted `> /` and mid-sentence
    fragments."""
    parts = []
    for line in doc_lines:
        t = line.strip()
        if t.endswith("*/"):
            t = t[:-2]
        t = t.lstrip("/*").strip().lstrip("*").strip()
        if t.startswith("@"):
            break
        if t:
            parts.append(t)
    text = re.sub(r"\{@\w+\s*", "", " ".join(parts)).replace("}", "").strip()
    dot = text.find(". ")
    return (text[:dot + 1] if 0 < dot <= 220 else text[:220]).strip()


def _brace_extract(src, decl):
    i = src.find(decl)
    if i < 0:
        return None, -1
    o = src.index("{", i)
    depth, close = 0, -1
    for j in range(o, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                close = j
                break
    line = src[:i].count("\n") + 1
    return src[i:close + 1], line


def write_snippets(cfg):
    pool, proj = cfg.pool, cfg.project
    methods = json.loads((pool / "02-static/corridor-methods.json").read_text())
    (pool / "02-static/snippets").mkdir(parents=True, exist_ok=True)

    src_cache = {}

    def lines_of(rel):
        if rel not in src_cache:
            src_cache[rel] = (proj / rel).read_text().splitlines()
        return src_cache[rel]

    contracts = ["# Method contracts (corridor)", ""]
    written = 0
    for m in methods:
        if not m["file"] or m["file"] == "<empty>" or m["line_start"] in (None, -1):
            continue
        ls = lines_of(m["file"])
        s, e = int(m["line_start"]) - 1, int(m["line_end"])
        j = s
        while j > 0 and (ls[j - 1].strip().startswith(("*", "/*", "//", "@")) or not ls[j - 1].strip()):
            j -= 1
            if ls[j].strip().startswith("/*"):
                break
        body = "\n".join(ls[j:e])
        safe = re.sub(r"[^A-Za-z0-9_.$]", "_", f"{m['fqn']}_L{m['line_start']}")
        (pool / f"02-static/snippets/{safe}.java").write_text(body + "\n")
        written += 1
        sig_line = ls[s].strip()
        summary = _javadoc_summary(ls[j:s])
        contracts += [f"## {m['fqn']}",
                      f"`{sig_line}`  ({m['file']}:{m['line_start']}-{m['line_end']})"]
        if summary:
            contracts.append(f"> {summary}")
        contracts.append("")

    src = (proj / cfg.source_file).read_text()
    for name, decl in cfg.type_decls.items():
        if name == "__target_class__":
            continue
        extract, line = _brace_extract(src, decl)
        out = pool / f"02-static/snippets/type_{name}.java"
        if extract is None:
            out.write_text(f"// MISSING: decl not found: {decl}\n")
        else:
            out.write_text(f"// signature-type class {name} ({cfg.source_file}:{line})\n" + extract + "\n")
            written += 1

    (pool / "02-static/method-contracts.md").write_text("\n".join(contracts))
    cfg.provenance("02-static/snippets/", "kgpool.snippets.write_snippets (stub applied)",
                   f"{written} snippets (corridor methods with STUB target + signature-type classes)")
    cfg.provenance("02-static/method-contracts.md", "kgpool.snippets.write_snippets (stub applied)",
                   "signature + first javadoc lines per corridor method")
    return written


def write_target_class(cfg):
    decl = cfg.type_decls.get("__target_class__")
    if not decl:
        return
    src = (cfg.project / cfg.source_file).read_text()
    extract, line = _brace_extract(src, decl)
    if extract is None:
        raise RuntimeError(f"target class decl not found: {decl}")
    cls_name = cfg.target_fqn.rpartition(".")[0].rpartition("$")[2]
    (cfg.pool / "01-task").mkdir(parents=True, exist_ok=True)
    out = cfg.pool / f"01-task/{cls_name}-stubbed.java"
    out.write_text(f"// {cfg.target_fqn.rpartition('.')[0]} ({cfg.source_file}:{line}), "
                   f"target body STUBBED\n" + extract + "\n")
    if cfg.stub_body.split("(")[0] not in extract:
        raise RuntimeError("target class extract does not contain the stub — "
                           "write_target_class must run while the stub is applied")
    cfg.provenance(f"01-task/{cls_name}-stubbed.java",
                   "kgpool.snippets.write_target_class (stub applied)",
                   "whole target class, brace-matched; stub presence verified")
