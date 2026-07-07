"""Derive a complete kgpool.json from source + the target FQN (no CPG needed).
Resolves the chicken/egg where the stubbed export requires the signature first: we read
signature/types straight from source. Type resolution is a heuristic source scan
(collisions/generics not fully handled); CPG-assisted resolution is a future enhancement
(see docs/superpowers/specs/2026-07-07-kgpool-any-project-augment-design.md).

synth_config() returns a dict that carries an extra `slice_target` (for the export stage)
and NO `export_json`; make.py removes slice_target and fills export_json before the single
config.load_config() (KgPoolConfig has neither field and load_config rejects extras)."""
import re
from pathlib import Path

PRIMITIVES = {"int", "long", "short", "byte", "char", "boolean", "float", "double", "void"}
_TYPE_DECL = r"\b(?:class|interface|enum|record)\s+{name}\b"


def _java_files(project: Path):
    return [p for p in sorted(project.rglob("*.java"))
            if "/build/" not in str(p).replace("\\", "/")]


def _brace_block(src: str, kw_idx: int):
    """(block_text, open_idx, close_idx) for the {...} opened at/after kw_idx."""
    o = src.index("{", kw_idx)
    depth = 0
    for j in range(o, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[kw_idx:j + 1], o, j
    raise ValueError("unbalanced braces")


def _decl_header(text: str, kw_idx: int) -> str:
    """The type/method decl header from its line start through the opening '{' (inclusive),
    whitespace-normalised to a single line."""
    line_start = text.rfind("\n", 0, kw_idx) + 1
    brace = text.index("{", kw_idx)
    return " ".join(text[line_start:brace + 1].split())


def _base_simple(t: str) -> str:
    t = t.strip().split("<")[0].replace("[]", "").strip()
    return t.rpartition(".")[2] or t


def _split_params(s: str):
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return out


def _param_type(part: str) -> str:
    part = re.sub(r"@\w+(\([^)]*\))?\s*", "", part).strip()
    if part.startswith("final "):
        part = part[len("final "):]
    type_str = part.rsplit(None, 1)[0] if len(part.split()) >= 2 else part
    return _base_simple(type_str)


def _locate_source_file(project: Path, top_binary: str) -> str:
    pkg, _, simple = top_binary.rpartition(".")
    want = (pkg.replace(".", "/") + "/" if pkg else "") + simple + ".java"
    cands = [p for p in _java_files(project)
             if str(p.relative_to(project)).replace("\\", "/").endswith(want)]
    if not cands:
        raise ValueError(f"source file for top-level class {top_binary!r} not found "
                         f"(looked for **/{want})")
    cands.sort(key=lambda p: (0 if "/src/main/" in str(p).replace("\\", "/") else 1, len(str(p))))
    return str(cands[0].relative_to(project)).replace("\\", "/")


def _descend(src: str, chain):
    """Return (innermost_class_block, innermost_header). chain = simple names outer->inner."""
    block, header = src, None
    for simple in chain:
        m = re.search(_TYPE_DECL.format(name=re.escape(simple)), block)
        if not m:
            raise ValueError(f"class {simple!r} not found while descending {chain}")
        header = _decl_header(block, m.start())
        block, _o, _c = _brace_block(block, m.start())
    return block, header


def _find_signature(class_block: str, method: str) -> str:
    for m in re.finditer(rf"\b{re.escape(method)}\s*\(", class_block):
        after = class_block[m.end():]
        depth, k = 1, 0
        while k < len(after) and depth:
            if after[k] == "(":
                depth += 1
            elif after[k] == ")":
                depth -= 1
            k += 1
        rest = after[k:]
        semi, brace = rest.find(";"), rest.find("{")
        if brace < 0 or (0 <= semi < brace):
            continue  # not a body decl (abstract / call)
        line_start = class_block.rfind("\n", 0, m.start()) + 1
        end = m.end() + k + brace  # index of the body '{'
        return " ".join(class_block[line_start:end + 1].split())
    raise ValueError(f"method decl {method!r} with a body not found in the target class")


def _find_type_decl(project: Path, simple: str):
    pat = re.compile(_TYPE_DECL.format(name=re.escape(simple)))
    for p in _java_files(project):
        text = p.read_text(encoding="utf-8", errors="ignore")
        m = pat.search(text)
        if not m:
            continue
        pm = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        pkg = (pm.group(1) + ".") if pm else ""
        return pkg + simple, _decl_header(text, m.start())
    return None


def _sig_types(signature: str):
    head, _, rest = signature.partition("(")
    params = rest.rpartition(")")[0]
    toks = head.split()
    types = []
    if len(toks) >= 2 and toks[-2] != "void":
        types.append(_base_simple(toks[-2]))          # return type
    for part in _split_params(params):
        if part.strip():
            types.append(_param_type(part))
    return types


def synth_config(project, target_fqn, *, tests=None, spec_tests=None,
                 stub_body=None, reference_file=None, pool):
    project = Path(project)
    outer, _, method = target_fqn.rpartition(".")
    top_binary = outer.split("$")[0]
    decl_simple = outer.split("$")[-1]
    package = (top_binary.rpartition(".")[0] + ".") if "." in top_binary else ""
    source_file = _locate_source_file(project, top_binary)
    src = (project / source_file).read_text(encoding="utf-8")

    chain = [top_binary.rpartition(".")[2]] + outer.split("$")[1:]
    class_block, target_class_header = _descend(src, chain)
    signature = _find_signature(class_block, method)

    if stub_body is None:
        stub_body = f'throw new UnsupportedOperationException("TODO: implement {decl_simple}.{method}");'

    type_decls = {"__target_class__": target_class_header}
    bytecode = [outer]
    seen = set()
    for simple in _sig_types(signature):
        if simple in PRIMITIVES or simple in seen:
            continue
        seen.add(simple)
        found = _find_type_decl(project, simple)
        if found:
            binary, header = found
            type_decls[simple] = header
            if binary not in bytecode:
                bytecode.append(binary)

    param_types = [_param_type(p) for p in _split_params(signature.split("(", 1)[1].rsplit(")", 1)[0])]
    slice_target = f"{source_file}#{decl_simple}.{method}({','.join(param_types)})"

    ladder = list(tests) if tests else [{"name": "full", "tests": []}]
    if spec_tests:
        ladder = [{"name": "spec", "tests": list(spec_tests)}] + ladder

    return {
        "target_fqn": target_fqn,
        "target_signature": signature,
        "stub_body": stub_body,
        "project": str(project),
        "package": package,
        "pool": str(pool),
        "includes": outer,
        "source_file": source_file,
        "bytecode_classes": bytecode,
        "type_decls": type_decls,
        "ladder": ladder,
        "reference_file": str(reference_file) if reference_file else None,
        "slice_target": slice_target,   # extra: consumed by make.py, not by KgPoolConfig
    }


def main():
    import argparse
    import json
    ap = argparse.ArgumentParser(description="Emit a kgpool synth-config (pre-export).")
    ap.add_argument("--project", required=True)
    ap.add_argument("--target", required=True)
    ap.add_argument("--pool", required=True)
    ap.add_argument("--stub", default=None)
    ap.add_argument("--reference", default=None)
    ap.add_argument("--out", default=None, help="write JSON here (default: <pool>/kgpool.synth.json)")
    args = ap.parse_args()
    cfg = synth_config(args.project, args.target, pool=args.pool,
                       stub_body=args.stub, reference_file=args.reference)
    out = Path(args.out) if args.out else Path(args.pool) / "kgpool.synth.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(cfg, indent=1))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
