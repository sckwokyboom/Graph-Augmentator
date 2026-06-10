"""Indexes over the graph-tipper CPG export (export.json) for crash-slice walks.

Statement-level vertices (CALL/LITERAL/CONTROL_STRUCTURE/RETURN) carry
PARENT_METHOD_ID, LINE_NUMBER, CODE; METHOD vertices carry FULL_NAME, FILENAME,
line range, IS_TEST. Edges: {label, outV, inV}. Produced by the slice cache's
prepare-and-export.sc.
"""
import json
from collections import defaultdict
from pathlib import Path


class CpgIndex:
    def __init__(self, data):
        self.vid = {v["id"]: v for v in data["vertices"]}
        self.children = defaultdict(list)   # method id -> statement vertices
        self.methods = []
        for v in data["vertices"]:
            p = v.get("properties", {})
            if v.get("label") == "METHOD":
                self.methods.append(v)
            elif "PARENT_METHOD_ID" in p:
                self.children[p["PARENT_METHOD_ID"]].append(v)
        self.rev_cdg = defaultdict(list)    # use(inV) -> [controlling guards (outV)]
        self.rev_rd = defaultdict(list)     # use(inV) -> [defs (outV)]
        for e in data["edges"]:
            if e["label"] == "CDG":
                self.rev_cdg[e["inV"]].append(e["outV"])
            elif e["label"] == "REACHING_DEF":
                self.rev_rd[e["inV"]].append(e["outV"])
        self._by_name = defaultdict(list)   # "cls.method" -> [METHOD vertices]
        for m in self.methods:
            name = m["properties"].get("FULL_NAME", "").split(":", 1)[0]
            self._by_name[name].append(m)

    def resolve_method(self, cls, method, line=None):
        """METHOD vertex for cls.method; overloads disambiguated by line-in-range."""
        cands = self._by_name.get(f"{cls}.{method}", [])
        if line is not None:
            in_range = [m for m in cands
                        if int(m["properties"].get("LINE_NUMBER", -1)) <= line
                        <= int(m["properties"].get("LINE_NUMBER_END", -1))]
            if in_range:
                return in_range[0]
        return cands[0] if cands else None

    def statements_at(self, method_vertex, line):
        return [v for v in self.children.get(method_vertex["id"], [])
                if int(v.get("properties", {}).get("LINE_NUMBER", -1)) == line]


def load_index(export_json) -> CpgIndex:
    return CpgIndex(json.loads(Path(export_json).read_text()))
