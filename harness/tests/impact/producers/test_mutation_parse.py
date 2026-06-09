from pathlib import Path
from harness.impact.producers.mutation_parse import build_mutation

_XML = """<?xml version="1.0"?><mutations>
<mutation detected="true" status="KILLED"><mutatedClass>p.C$N</mutatedClass>
  <mutatedMethod>putValue</mutatedMethod><lineNumber>12</lineNumber>
  <killingTest>p.T.tA(p.T)</killingTest></mutation>
<mutation detected="true" status="KILLED"><mutatedClass>p.C$N</mutatedClass>
  <mutatedMethod>putValue</mutatedMethod><lineNumber>12</lineNumber>
  <killingTest>p.T.tB(p.T)</killingTest></mutation>
<mutation detected="false" status="SURVIVED"><mutatedClass>p.C$N</mutatedClass>
  <mutatedMethod>putValue</mutatedMethod><lineNumber>6</lineNumber>
  <killingTest/></mutation>
</mutations>"""


def test_killers_union_and_per_line_regions(tmp_path):
    p = tmp_path / "mutations.xml"; p.write_text(_XML)
    mut = build_mutation(p)
    entry = mut["p.C$N.putValue"]
    assert set(entry["killers"]) == {"p.T.tA", "p.T.tB"}   # killingTest param suffix stripped
    by_label = {r["label"]: r["killers"] for r in entry["regions"]}
    assert by_label["line:12"] == 2     # two mutants killed at line 12
    assert by_label["line:6"] == 0      # survived -> 0 killers (blind spot)
