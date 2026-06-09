"""Parse the agent's values.<pid>.tsv dump into per-method example records.

Row format: "<method_fqn>\t<arg0> | <arg1> | ...\t=> <result>"  where <result> is a value
or "throws <Type>[: msg]". Examples are deduped (set semantics from the agent) and capped.
"""
import glob
from pathlib import Path


def parse_values(paths, limit=5):
    out: dict = {}
    seen: dict = {}
    for fp in paths:
        for line in Path(fp).read_text().splitlines():
            if not line or "\t=> " not in line:
                continue
            head, result = line.split("\t=> ", 1)
            method, _, argstr = head.partition("\t")
            args = [a.strip() for a in argstr.split(" | ")] if argstr else []
            rec = {"args": args, "result": result, "throws": result.startswith("throws ")}
            key = (method, tuple(args), result)
            if key in seen.setdefault(method, set()):
                continue
            seen[method].add(key)
            out.setdefault(method, [])
            if len(out[method]) < limit:
                out[method].append(rec)
    return out


def main():
    import json
    import sys
    values_dir, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    ex = parse_values(sorted(glob.glob(str(values_dir / "values*.tsv"))))
    out_path.write_text(json.dumps(ex, indent=0))
    print(f"examples: {sum(len(v) for v in ex.values())} across {len(ex)} method(s)")


if __name__ == "__main__":
    main()
