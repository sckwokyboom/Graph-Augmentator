import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import defaultdict
from harness.impact.fqn import method_fqn_from_pitest, test_fqn


def build_mutation(mutations_xml: Path) -> dict:
    root = ET.parse(mutations_xml).getroot()
    killers: dict = defaultdict(set)
    line_kills: dict = defaultdict(lambda: defaultdict(int))   # fqn -> line -> kill count
    for mut in root.iter("mutation"):
        cls = mut.findtext("mutatedClass", "")
        meth = mut.findtext("mutatedMethod", "")
        fqn = method_fqn_from_pitest(cls, meth)
        line = int(mut.findtext("lineNumber", "0"))
        line_kills[fqn].setdefault(line, 0)
        kt = mut.findtext("killingTest", "") or ""
        if mut.get("detected") == "true" and kt:
            # killingTest form: "p.T.method(p.T)" — strip the trailing "(...)"
            killers[fqn].add(test_fqn(*kt.split("(", 1)[0].rsplit(".", 1)))
            line_kills[fqn][line] += 1
    out: dict = {}
    for fqn in set(killers) | set(line_kills):
        regions = [{"label": f"line:{ln}", "lines": [ln, ln], "killers": cnt}
                   for ln, cnt in sorted(line_kills[fqn].items())]
        out[fqn] = {"killers": sorted(killers[fqn]), "regions": regions}
    return out


def main():
    mutations_xml, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    mut = build_mutation(mutations_xml)
    Path(out_path).write_text(json.dumps(mut, indent=0))
    print(f"mutation.json: {len(mut)} methods")


if __name__ == "__main__":
    main()
