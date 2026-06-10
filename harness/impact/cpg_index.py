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
        # _test_classes: classes with at least one method in a __t__ source file.
        # Joern synthesizes some helpers with FILENAME='<empty>' that belong to
        # test classes (measured on picocli: HelpTest.assertEquals, .usageString).
        # Class membership is the reliable fallback when FILENAME is absent.
        self._test_classes: set = set()
        for m in self.methods:
            p = m.get("properties", {})
            if "/__t__/" in (p.get("FILENAME") or ""):
                cls = p.get("FULL_NAME", "").split(":", 1)[0].rsplit(".", 1)[0]
                if cls:
                    self._test_classes.add(cls)
        self._call_map = None               # lazy: method name -> {callee names}

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

    @staticmethod
    def is_test(method_vertex):
        """IS_TEST is a JSON boolean in the real export, a string elsewhere."""
        return str(method_vertex.get("properties", {}).get("IS_TEST")).lower() == "true"

    def is_test_code(self, method_vertex):
        """Test code = @Test-flagged (IS_TEST) OR declared in a test source file
        (the export rewrites test dirs to src/__t__/...) OR the declaring class
        has any sibling method in a __t__ file (handles Joern-synthesized helpers
        with FILENAME='<empty>' — measured on picocli: HelpTest.usageString and
        HelpTest.assertEquals carry IS_TEST=false and FILENAME='<empty>')."""
        p = method_vertex.get("properties", {})
        if str(p.get("IS_TEST")).lower() == "true":
            return True
        fn = p.get("FILENAME") or ""
        if "/__t__/" in fn:
            return True
        # Class-level fallback: if this class has any __t__-resident sibling.
        cls = p.get("FULL_NAME", "").split(":", 1)[0].rsplit(".", 1)[0]
        return cls in self._test_classes

    @staticmethod
    def map_filename(rel):
        """The export rewrites test source dirs to src/__t__/...; map back for disk reads."""
        return rel.replace("/__t__/", "/test/") if rel else rel

    def methods_named(self, name):
        """All METHOD vertices whose FULL_NAME name-part (before ':') equals name."""
        return self._by_name.get(name, [])

    @property
    def call_map(self):
        """Forward static call map: method FQN-name -> set of callee FQN-names
        (from child CALL vertices' METHOD_FULL_NAME; <operator>.* excluded)."""
        if self._call_map is None:
            m = defaultdict(set)
            for mv in self.methods:
                name = mv["properties"].get("FULL_NAME", "").split(":", 1)[0]
                for s in self.children.get(mv["id"], []):
                    tgt = s.get("properties", {}).get("METHOD_FULL_NAME", "").split(":", 1)[0]
                    if tgt and not tgt.startswith("<operator>"):
                        m[name].add(tgt)
            self._call_map = m
        return self._call_map


def load_index(export_json) -> CpgIndex:
    return CpgIndex(json.loads(Path(export_json).read_text()))
