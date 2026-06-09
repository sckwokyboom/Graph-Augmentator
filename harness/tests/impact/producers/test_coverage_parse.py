from pathlib import Path
from harness.impact.producers.coverage_parse import build_coverage

_XML = """<?xml version="1.0"?><report name="r"><package name="p">
<class name="p/C" sourcefilename="C.java"><sourcefile name="C.java">
<line nr="{covered}" mi="0" ci="3"/><line nr="99" mi="5" ci="0"/>
</sourcefile></class></package></report>"""

IDX = {"p.C.m": {"file": "src/main/java/p/C.java", "start": 10, "end": 15}}


def _write(d: Path, test, covered):
    (d / f"{test}.xml").write_text(_XML.format(covered=covered))


def test_method_covered_when_a_line_in_range_has_ci(tmp_path):
    _write(tmp_path, "p.T.tCovers", 12)     # line 12 in [10,15], ci=3 -> covers
    _write(tmp_path, "p.T.tMisses", 50)     # line 50 outside range -> no
    cov = build_coverage(tmp_path, IDX)
    assert cov == {"p.C.m": ["p.T.tCovers"]}


def test_unexecuted_line_in_range_does_not_count(tmp_path):
    # line 99 has ci=0 (only missed); even though present it must not count
    (tmp_path / "p.T.tZero.xml").write_text(_XML.format(covered=99))
    cov = build_coverage(tmp_path, IDX)
    assert cov == {}
