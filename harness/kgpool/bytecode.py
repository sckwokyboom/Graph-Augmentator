"""javap dump for configured classes with the target member section redacted."""
import re
import subprocess

MEMBER_RE = re.compile(r"^  (public|protected|private|static|final|\w).*[;{)]\s*$")


def redact_member(javap_text: str, method_name: str) -> str:
    out, skip = [], False
    for ln in javap_text.splitlines():
        if MEMBER_RE.match(ln) and f".{method_name}" not in ln and f" {method_name}(" in ln:
            skip = True
            out.append(f"  // [REDACTED: {method_name} member omitted — leak rule]")
            continue
        if skip and MEMBER_RE.match(ln):
            skip = False
        if not skip:
            out.append(ln)
    return "\n".join(out) + "\n"


def dump_bytecode(cfg, classpath="build/classes/java/main"):
    outdir = cfg.pool / "02-static/bytecode"
    outdir.mkdir(parents=True, exist_ok=True)
    target_method = cfg.target_fqn.rpartition(".")[2]
    target_cls = cfg.target_fqn.rpartition(".")[0]
    ok, skipped = 0, []
    for c in cfg.bytecode_classes:
        r = subprocess.run(["javap", "-p", "-c", "-l", "-cp", classpath, c],
                           cwd=cfg.project, capture_output=True, text=True)
        out = outdir / (c.replace("$", "_") + ".txt")
        if r.returncode != 0 or "Error:" in r.stdout or not r.stdout.strip():
            # javap could not resolve this class (config_synth's source scan does not
            # resolve nested/external type binary names) — degrade, don't crash.
            skipped.append(c)
            out.write_text(f"// [unavailable: javap could not resolve {c}]\n")
            continue
        raw = redact_member(r.stdout, target_method) if c == target_cls else r.stdout
        out.write_text(raw)
        ok += 1
    note = (f"{ok} classes dumped; {target_method} member redacted in {target_cls}. "
            "NB: compiled from the STUBBED source (strict policy).")
    if skipped:
        note += f" Skipped (unresolved): {', '.join(skipped)}."
    cfg.provenance("02-static/bytecode/", "kgpool.bytecode.dump_bytecode", note)
