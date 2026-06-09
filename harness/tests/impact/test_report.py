from harness.impact.tiering import ImpactResult, RegionStrength, BlindSpot
from harness.impact.report import render_report


def _result():
    return ImpactResult(
        changed_methods={"p.TextTable.putValue"},
        affected={"p.HelpTest.testWrap", "p.TextTableTest.addRowValues", "p.ExecuteTest.tolerant"},
        tier1={"p.HelpTest.testWrap", "p.TextTableTest.addRowValues"},
        tier2={"p.ExecuteTest.tolerant"},
        regions=[
            RegionStrength("p.TextTable.putValue", "empty-check", 0, "UNVERIFIED"),
            RegionStrength("p.TextTable.putValue", "bounds-check", 1, "weak"),
            RegionStrength("p.TextTable.putValue", "layout", 284, "strong"),
        ],
        blind_spots=[BlindSpot("empty-check", "p.TextTable.putValue: region 'empty-check' killed by 0 mutants — green suite is not evidence")],
    )


def test_report_has_all_sections_and_tiering():
    md = render_report(_result(), total_tests=2369)
    assert "# Diff Impact" in md
    assert "## Changed" in md
    assert "p.TextTable.putValue" in md
    # verification strength
    assert "UNVERIFIED" in md and "empty-check" in md
    assert "layout" in md and "284" in md
    # affected + tiers + economy
    assert "Tier 1" in md and "Tier 2" in md
    assert "3 of 2369" in md  # affected vs total
    # scoped command lists the affected test CLASSES
    assert "--tests p.HelpTest" in md
    assert "--tests p.TextTableTest" in md


def test_report_surfaces_blind_spot_prominently():
    md = render_report(_result(), total_tests=2369)
    assert "green suite is not evidence" in md


def test_report_tier1_run_command_excludes_tier2_classes():
    md = render_report(_result(), total_tests=2369)
    # Tier 1 command must not pull ExecuteTest (tier2-only class)
    cmd_line = [l for l in md.splitlines() if l.strip().startswith("./gradlew")][0]
    assert "p.ExecuteTest" not in cmd_line
