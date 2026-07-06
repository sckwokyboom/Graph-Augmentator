"""Mechanical KG from red/candidate-run data ONLY (strict leak policy).
build_kg() is pure: pass parsed inputs, get {meta, nodes, edges}."""
import re
from collections import Counter, defaultdict

CELL_RE = re.compile(r"Cell\{column=(\d+), row=(\d+)\}")


def build_kg(target_fqn, values, coverage, failures, covering, exemplars):
    nodes, edges, ids = [], [], set()

    def add_node(n):
        if n["id"] not in ids:
            nodes.append(n)
            ids.add(n["id"])

    def add_edge(f, rel, t, props=None):
        e = {"from": f, "rel": rel, "to": t}
        if props:
            e["props"] = props
        edges.append(e)

    add_node({"id": "m:target", "type": "Method", "label": target_fqn,
              "ev": "config.target_fqn"})

    red_by_test = {t: (typ, msg[:140]) for t, typ, msg in failures}
    for t in covering:
        props = {"class": t.rpartition(".")[0], "exemplar": t in exemplars}
        if t in red_by_test:
            props["red_type"], props["red_msg"] = red_by_test[t]
        add_node({"id": f"t:{t}", "type": "Test", "label": t.rpartition(".")[2],
                  "props": props, "ev": "red run"})
        add_edge(f"t:{t}", "COVERS", "m:target")

    for mode, cnt in Counter(v[0] for v in red_by_test.values()).items():
        mid = f"f:mode:{mode.rpartition('.')[2]}"
        add_node({"id": mid, "type": "FailureMode", "label": mode,
                  "props": {"count": cnt}, "ev": "red run failures"})
    for t, (mode, _m) in red_by_test.items():
        if f"t:{t}" in ids:
            add_edge(f"t:{t}", "FAILS_WITH", f"f:mode:{mode.rpartition('.')[2]}")

    classes = defaultdict(list)
    tgt_recs = values.get(target_fqn, [])
    for r in tgt_recs:
        a = r["args"]
        try:
            row_in, col_in = int(a[0]), int(a[1])
        except (ValueError, IndexError):
            continue
        value = " | ".join(a[2:])
        if r["throws"]:
            key = ("throws", r["result"].split(":")[0].replace("throws ", ""))
        else:
            m = CELL_RE.match(r["result"])
            key = (int(m.group(1)) - col_in, int(m.group(2)) - row_in) if m else ("other", r["result"][:30])
        classes[key].append({"row": row_in, "col": col_in, "vlen": len(value),
                             "value": value[:70], "result": r["result"][:70], "test": r["test"]})
    for key, exs in sorted(classes.items(), key=lambda kv: -len(kv[1])):
        bid = (f"bc:dcol{key[0]:+d}_drow{key[1]:+d}" if isinstance(key[0], int)
               else f"bc:{key[0]}:{key[1]}")
        add_node({"id": bid, "type": "BehaviorClass",
                  "label": f"target I/O class {key}",
                  "props": {"count": len(exs),
                            "value_len_range": [min(e["vlen"] for e in exs), max(e["vlen"] for e in exs)],
                            "representatives": exs[:5]},
                  "ev": "red/candidate value capture"})
        add_edge(bid, "OBSERVED_AT", "m:target")
        for e in exs[:5]:
            if e["test"] and f"t:{e['test']}" in ids:
                add_edge(f"t:{e['test']}", "EXHIBITS", bid)

    ok = [r for r in tgt_recs if not r["throws"]]
    allr = [r for r in tgt_recs if r["args"] and r["args"][0].isdigit()]
    if allr:
        rows = Counter(int(r["args"][0]) for r in allr)
        cols = Counter(int(r["args"][1]) for r in allr if len(r["args"]) > 1 and r["args"][1].isdigit())
        vlens = sorted(len(" | ".join(r["args"][2:])) for r in allr)
        add_node({"id": "profile:target-inputs", "type": "InputProfile",
                  "label": "observed input domain (red/candidate run)",
                  "props": {"n": len(allr), "non_throwing": len(ok),
                            "row_hist": dict(rows), "col_hist": dict(cols),
                            "value_len_min_med_max": [vlens[0], vlens[len(vlens) // 2], vlens[-1]]},
                  "ev": "red/candidate value capture"})
        add_edge("profile:target-inputs", "OBSERVED_AT", "m:target")

    tgt_tests = set(coverage.get(target_fqn, []))
    for meth, tests in coverage.items():
        if meth == target_fqn:
            continue
        short = meth.rpartition(".")[2]
        add_node({"id": f"m:co:{short}", "type": "Method", "label": meth,
                  "props": {"covered_by": len(tests)}, "ev": "red coverage matrix"})
        shared = len(tgt_tests & set(tests))
        add_edge(f"m:co:{short}", "CO_COVERED_WITH", "m:target",
                 {"shared_tests": shared,
                  "jaccard": round(shared / len(tgt_tests | set(tests)), 3)})

    goldens = []
    for t, typ, msg in failures:
        if "ComparisonFailure" not in typ:
            continue
        m = re.search(r"expected:<(.{40,300}?)> but was", msg) or re.search(r"expected:<(.{40,300})$", msg)
        if m and "\\n " in m.group(1):
            goldens.append((t, m.group(1)))
    for i, (t, exc) in enumerate(sorted(goldens, key=lambda g: len(g[1]))[:4]):
        add_node({"id": f"gn:{i}", "type": "GoldenOutput",
                  "label": f"golden expected output ({t.rpartition('.')[2]})",
                  "props": {"excerpt": exc, "test": t}, "ev": "failures.tsv expected side (test-owned)"})
        if f"t:{t}" in ids:
            add_edge(f"gn:{i}", "ASSERTED_BY", f"t:{t}")

    return {"meta": {"policy": "STRICT — no reference-derived data",
                     "target": target_fqn},
            "nodes": nodes, "edges": edges}
