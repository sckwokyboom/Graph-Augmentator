import json
import sys
from pathlib import Path
from harness.impact.fqn import method_fqn_from_joern


def build_method_index(export_json: Path) -> dict:
    data = json.loads(Path(export_json).read_text())
    out: dict = {}
    for v in data.get("vertices", []):
        if v.get("label") != "METHOD":
            continue
        p = v.get("properties", {})
        fn = p.get("FILENAME")
        if not fn or fn == "<empty>":
            continue
        start = int(p.get("LINE_NUMBER", -1))
        end = int(p.get("LINE_NUMBER_END", -1))
        if start < 0:
            continue
        fqn = method_fqn_from_joern(p.get("FULL_NAME", ""))
        if not fqn:
            continue
        out[fqn] = {"file": fn, "start": start, "end": end if end >= start else start}
    return out


def main():
    export_json, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    idx = build_method_index(export_json)
    Path(out_path).write_text(json.dumps(idx, indent=0))
    print(f"methods.json: {len(idx)} methods")


if __name__ == "__main__":
    main()
