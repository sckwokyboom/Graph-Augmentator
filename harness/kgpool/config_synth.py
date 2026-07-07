"""Derive a complete kgpool.json from source + the target FQN (no CPG needed).
Resolves the chicken/egg where the stubbed export requires the signature first: we read
signature/types straight from source. All structural scanning is skip-aware — it ignores
braces/parens/keywords inside string/char literals and comments, so it holds up on real
19k-line files (picocli's CommandLine.java has `{@link ...}` javadoc and `"{"` literals
everywhere). Type resolution is still a heuristic source scan (nested-type binary names
and generics not fully resolved); CPG-assisted resolution is a future enhancement (see
docs/superpowers/specs/2026-07-07-kgpool-any-project-augment-design.md).

synth_config() returns a dict that carries an extra `slice_target` (for the export stage)
and NO `export_json`; make.py removes slice_target and fills export_json before the single
config.load_config() (KgPoolConfig has neither field and load_config rejects extras)."""
import re
from pathlib import Path

PRIMITIVES = {"int", "long", "short", "byte", "char", "boolean", "float", "double", "void"}
# tokens that, when they precede a `name(`, mark it as a call/expression, not a decl
_KW_BEFORE = {"return", "new", "throw", "throws", "else", "instanceof", "assert",
              "yield", "case", "do", "while", "if", "for", "switch", "synchronized"}


def _is_ident(c: str) -> bool:
    return c.isalnum() or c in "_$"


def _java_files(project: Path):
    return [p for p in sorted(project.rglob("*.java"))
            if "/build/" not in str(p).replace("\\", "/")]


def _skip_quote(src: str, i: int, q: str) -> int:
    n = len(src)
    i += 1
    while i < n:
        if src[i] == "\\":
            i += 2
            continue
        if src[i] == q:
            return i + 1
        i += 1
    return n


def _skip_noncode(src: str, i: int) -> int:
    """If src[i] starts a string/char literal or comment, return the index just past it;
    else return i unchanged. Handles "...", '...', triple-quoted text blocks, // and /*."""
    n = len(src)
    c = src[i]
    if c == '"':
        if src[i:i + 3] == '"""':
            j = src.find('"""', i + 3)
            return n if j < 0 else j + 3
        return _skip_quote(src, i, '"')
    if c == "'":
        return _skip_quote(src, i, "'")
    if c == "/" and i + 1 < n:
        if src[i + 1] == "/":
            j = src.find("\n", i)
            return n if j < 0 else j + 1
        if src[i + 1] == "*":
            j = src.find("*/", i + 2)
            return n if j < 0 else j + 2
    return i


def _find_struct(src: str, start: int, ch: str) -> int:
    """Index of the first structural (not in a string/char/comment) `ch` at/after start."""
    i, n = start, len(src)
    while i < n:
        s = _skip_noncode(src, i)
        if s != i:
            i = s
            continue
        if src[i] == ch:
            return i
        i += 1
    return -1


def _find_class_kw(block: str, simple: str) -> int:
    """Index of a `class|interface|enum|record <simple>` declaration at a code position
    (skips matches inside strings/comments), or -1."""
    pat = re.compile(rf"(?:class|interface|enum|record)\s+{re.escape(simple)}\b")
    n, i = len(block), 0
    while i < n:
        s = _skip_noncode(block, i)
        if s != i:
            i = s
            continue
        if (i == 0 or not _is_ident(block[i - 1])) and pat.match(block, i):
            return i
        i += 1
    return -1


def _brace_block(src: str, kw_idx: int):
    """(block_text, open_idx, close_idx) for the {...} opened at/after kw_idx, ignoring
    braces inside strings/char-literals/comments."""
    o = _find_struct(src, kw_idx, "{")
    if o < 0:
        raise ValueError("no opening brace")
    depth, i, n = 0, o, len(src)
    while i < n:
        s = _skip_noncode(src, i)
        if s != i:
            i = s
            continue
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[kw_idx:i + 1], o, i
        i += 1
    raise ValueError("unbalanced braces")


def _decl_header(text: str, kw_idx: int) -> str:
    """The decl header from its line start through the opening '{' (inclusive), normalised."""
    line_start = text.rfind("\n", 0, kw_idx) + 1
    brace = _find_struct(text, kw_idx, "{")
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
        idx = _find_class_kw(block, simple)
        if idx < 0:
            raise ValueError(f"class {simple!r} not found while descending {chain}")
        header = _decl_header(block, idx)
        block, _o, _c = _brace_block(block, idx)
    return block, header


def _looks_like_decl(block: str, name_idx: int) -> bool:
    """True if the method name at name_idx is a declaration (a return type precedes it),
    not a call/expression (preceded by '.', '=', '(', an operator, or a control keyword)."""
    j = name_idx - 1
    while j >= 0 and block[j] in " \t\r\n":
        j -= 1
    if j < 0:
        return False
    pc = block[j]
    if pc in ">]":                       # generic/array return type: `List<X> m(` / `int[] m(`
        return True
    if not _is_ident(pc):                # '.', '=', '(', '!', '&', etc. -> a call
        return False
    end = j + 1
    while j >= 0 and _is_ident(block[j]):
        j -= 1
    return block[j + 1:end] not in _KW_BEFORE


def _find_signature(class_block: str, method: str) -> str:
    """The method's source decl (through the opening body '{', normalised), found with a
    skip-aware scan so `method(` inside javadoc/strings and same-class calls are ignored."""
    n, i = len(class_block), 0
    while i < n:
        s = _skip_noncode(class_block, i)
        if s != i:
            i = s
            continue
        if (class_block.startswith(method, i)
                and (i == 0 or not _is_ident(class_block[i - 1]))):
            j = i + len(method)
            while j < n and class_block[j] in " \t\r\n":
                j += 1
            if j < n and class_block[j] == "(" and _looks_like_decl(class_block, i):
                depth, k = 0, j                       # skip-aware paren match
                while k < n:
                    sk = _skip_noncode(class_block, k)
                    if sk != k:
                        k = sk
                        continue
                    if class_block[k] == "(":
                        depth += 1
                    elif class_block[k] == ")":
                        depth -= 1
                        if depth == 0:
                            k += 1
                            break
                    k += 1
                brace = _find_struct(class_block, k, "{")
                semi = _find_struct(class_block, k, ";")
                if brace >= 0 and not (0 <= semi < brace):
                    ls = class_block.rfind("\n", 0, i) + 1
                    return " ".join(class_block[ls:brace + 1].split())
        i += 1
    raise ValueError(f"method decl {method!r} with a body not found in the target class")


def _find_type_decl(project: Path, simple: str):
    for p in _java_files(project):
        text = p.read_text(encoding="utf-8", errors="ignore")
        idx = _find_class_kw(text, simple)
        if idx < 0:
            continue
        pm = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        pkg = (pm.group(1) + ".") if pm else ""
        return pkg + simple, _decl_header(text, idx)
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
