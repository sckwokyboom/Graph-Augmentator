"""Final leak sweep (eval-side): distinctive reference-body-only lines must not appear
in the pool. Needs cfg.reference_file (the ORIGINAL source file with the real body);
skipped with a provenance note when absent. Markers = non-trivial lines of the real
target body that do NOT appear anywhere else in the file (so shared idioms and
assert-side expected strings stay legal)."""
import subprocess


def sweep(cfg) -> int:
    if not cfg.reference_file or not cfg.reference_file.exists():
        cfg.provenance("(leak-sweep)", "kgpool.leak_sweep.sweep",
                       "SKIPPED: no reference_file configured/present")
        return 0
    src = cfg.reference_file.read_text()
    sig = cfg.target_signature
    i = src.find(sig)
    if i < 0:
        raise RuntimeError(f"target signature not found in reference file: {sig}")
    o = i + len(sig) - 1
    depth = 0
    j = o
    for j in range(o, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                break
    body = src[o + 1:j]
    rest = src[:i] + src[j:]
    body_lines = [l.strip() for l in body.splitlines()
                  if len(l.strip()) > 25 and not l.strip().startswith(("//", "*", "/*"))]
    markers = [l for l in body_lines if l not in rest]
    bad = 0
    for marker in markers:
        r = subprocess.run(["grep", "-rlF", marker, str(cfg.pool)],
                           capture_output=True, text=True)
        hits = [h for h in r.stdout.split()
                if "_baseline" not in h and "_reference" not in h and "leak_sweep" not in h]
        if hits:
            print(f"LEAK: {marker!r} in {hits}")
            bad += 1
    cfg.provenance("(leak-sweep)", "kgpool.leak_sweep.sweep",
                   f"{len(markers)} target-unique markers checked, {bad} leaks")
    return bad
