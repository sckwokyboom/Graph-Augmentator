"""Generate 00-MANIFEST.md from provenance.jsonl + a walk of the pool."""
import json


SKIP = ("_tools", "_raw", "_baseline", "_examples", "_iterations", "_reference",
        "_export", "00-MANIFEST", "kgpool.json", "kgpool.synth.json",
        "kgpool.provenance.json", "augment.prompt.md")


def write_manifest(cfg):
    pool = cfg.pool
    prov = {}
    prov_file = cfg.pool_tools / "provenance.jsonl"
    if prov_file.exists():
        for line in prov_file.read_text().splitlines():
            if line.strip():
                r = json.loads(line)
                prov[r["file"].rstrip("/")] = r

    def lookup(rel):
        if rel in prov:
            return prov[rel]
        parts = rel.split("/")
        for k in range(len(parts) - 1, 0, -1):
            d = "/".join(parts[:k])
            if d in prov:
                return prov[d]
        return {}

    rows = [f"# KG context pool: {cfg.target_fqn}", "",
            "Policy: STRICT — no reference-implementation data anywhere in this pool;",
            "all dynamics from the red (stubbed) run. Raw run outputs live in _raw/.", "",
            "| file | size | ~tokens | produced by | note |", "|---|---|---|---|---|"]
    total = 0
    for p in sorted(pool.rglob("*")):
        if p.is_dir() or p.name == ".DS_Store":
            continue
        rel = str(p.relative_to(pool))
        if rel.startswith(SKIP):
            continue
        sz = p.stat().st_size
        tok = sz // 4
        total += tok
        pr = lookup(rel)
        rows.append(f"| {rel} | {sz} | {tok} | {pr.get('cmd', '?')} | {pr.get('note', '')} |")
    rows += ["", f"Total ≈ {total} tokens (chars/4) across the LLM-facing files."]
    (pool / "00-MANIFEST.md").write_text("\n".join(rows))
    return total
