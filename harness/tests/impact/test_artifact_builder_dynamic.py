from pathlib import Path

from harness.artifact_builder import build_arm_command


def test_dynamic_compact_arm_builds_compact_slice_command():
    cmd = build_arm_command(
        graph_tipper_bin="gt", project_dir="/p", target_spec="F.java#C.m(int)",
        out_dir=Path("/o"), arm="gt+dynamic-compact", exec_xml_path=Path("/e.xml"))
    assert "slice" in cmd
    assert "--prune-by-coverage" in cmd and "/e.xml" in cmd   # compact = coverage-pruned
    assert "--katz-rank" not in cmd                            # compact ≠ katz cluster dump
