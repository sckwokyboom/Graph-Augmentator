from pathlib import Path
from harness.artifact_builder import build_arm_command


def test_build_arm_command_for_bare_arm():
    cmd = build_arm_command(
        graph_tipper_bin="/usr/local/bin/graph-tipper",
        project_dir="/tmp/picocli",
        target_spec="X#y",
        out_dir=Path("/tmp/out"),
        arm="no-context",
        exec_xml_path=None,
    )
    assert "--bare" in cmd
    assert "--prune-by-coverage" not in cmd


def test_build_arm_command_for_gt_jacoco():
    cmd = build_arm_command(
        graph_tipper_bin="/usr/local/bin/graph-tipper",
        project_dir="/tmp/picocli",
        target_spec="X#y",
        out_dir=Path("/tmp/out"),
        arm="gt+jacoco",
        exec_xml_path=Path("/tmp/jacoco.xml"),
    )
    assert "--prune-by-coverage" in cmd
    assert "/tmp/jacoco.xml" in cmd
    assert "--katz-rank" not in cmd


def test_build_arm_command_for_gt_katz():
    cmd = build_arm_command(
        graph_tipper_bin="/usr/local/bin/graph-tipper",
        project_dir="/tmp/picocli",
        target_spec="X#y",
        out_dir=Path("/tmp/out"),
        arm="gt+katz",
        exec_xml_path=None,
    )
    assert "--katz-rank" in cmd
    assert "--prune-by-coverage" not in cmd


def test_build_arm_command_for_gt_both():
    cmd = build_arm_command(
        graph_tipper_bin="/usr/local/bin/graph-tipper",
        project_dir="/tmp/picocli",
        target_spec="X#y",
        out_dir=Path("/tmp/out"),
        arm="gt+jacoco+katz",
        exec_xml_path=Path("/tmp/jacoco.xml"),
    )
    assert "--prune-by-coverage" in cmd
    assert "--katz-rank" in cmd


def test_gt_current_has_no_special_flags():
    cmd = build_arm_command(
        graph_tipper_bin="/usr/local/bin/graph-tipper",
        project_dir="/tmp/picocli",
        target_spec="X#y",
        out_dir=Path("/tmp/out"),
        arm="gt-current",
        exec_xml_path=None,
    )
    assert "--bare" not in cmd
    assert "--prune-by-coverage" not in cmd
    assert "--katz-rank" not in cmd


def test_gt_jacoco_arm_requires_exec_xml():
    import pytest
    with pytest.raises(ValueError, match="exec_xml_path"):
        build_arm_command(
            graph_tipper_bin="/usr/local/bin/graph-tipper",
            project_dir="/tmp/picocli",
            target_spec="X#y",
            out_dir=Path("/tmp/out"),
            arm="gt+jacoco",
            exec_xml_path=None,
        )


def test_unknown_arm_raises():
    import pytest
    with pytest.raises(ValueError, match="unknown arm"):
        build_arm_command(
            graph_tipper_bin="/usr/local/bin/graph-tipper",
            project_dir="/tmp/picocli",
            target_spec="X#y",
            out_dir=Path("/tmp/out"),
            arm="nope",
            exec_xml_path=None,
        )
