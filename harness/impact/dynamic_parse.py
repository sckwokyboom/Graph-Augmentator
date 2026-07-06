"""Parse the agent's values.<pid>.tsv dump into per-method example records.

Row formats (autodetected per line):
  legacy: "<method_fqn>\t<arg0> | <arg1> | ...\t=> <result>"
  4-col:  "<method_fqn>\t<test_fqn|->\t<arg0> | <arg1> | ...\t=> <result>"
where <result> is a value or "throws <Type>[: msg]". Examples are deduped (set
semantics from the agent) and capped. Records carry "test" (None for legacy/"-").
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
            parts = head.split("\t")
            if len(parts) == 3:                      # 4-col: method \t test \t args
                method, test, argstr = parts
                test = None if test == "-" else test
            elif len(parts) == 2:                    # legacy: method \t args
                (method, argstr), test = parts, None
            else:
                continue
            args = [a.strip() for a in argstr.split(" | ")] if argstr else []
            rec = {"args": args, "result": result, "throws": result.startswith("throws "),
                   "test": test}
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
