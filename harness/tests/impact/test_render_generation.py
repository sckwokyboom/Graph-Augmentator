from harness.impact.render_generation import render_generation, format_examples


def test_format_examples_renders_args_and_results():
    rows = [
        {"args": ["0", "0", "abc"], "result": "Cell{column=0, row=0}", "throws": False},
        {"args": ["1", "0", "abc"], "result": "throws IllegalArgumentException: rowCount=0", "throws": True},
    ]
    out = format_examples("p.C.putValue", rows)
    assert "Observed behaviour" in out
    assert "not an oracle" in out.lower()
    assert "putValue(0, 0, abc) => Cell{column=0, row=0}" in out
    assert "putValue(1, 0, abc) => throws IllegalArgumentException: rowCount=0" in out


def test_render_generation_denoises_and_appends():
    budget_md = (
        "# Graph-Tipper Augmentation\n\n## Target\n**Signature:** sig\n\n"
        "## Consumer contracts\n\n### Consumer 1: foo\n"
        "#### 4.4.1.a Cluster: NOISE\n**Static slice (Tier 2):**\narg0:\n  <UNRESOLVED>\n"
        "**Behavior signals:**\n- junk\n\n## Long tail\n76 singletons\n"
    )
    examples = {"p.C.putValue": [{"args": ["0", "0", "abc"], "result": "Cell{column=0, row=0}", "throws": False}]}
    out = render_generation(budget_md, examples, target_fqn="p.C.putValue")
    assert "NOISE" not in out and "UNRESOLVED" not in out and "Long tail" not in out
    assert "## Target" in out                      # backbone kept
    assert "Observed behaviour" in out             # examples appended
    assert "putValue(0, 0, abc)" in out
