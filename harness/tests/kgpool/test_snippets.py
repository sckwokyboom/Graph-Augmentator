from harness.kgpool.snippets import _javadoc_summary


def test_first_sentence_clean():
    doc = [
        "/**",
        " * Writes the specified value into the cell and returns the last row and column.",
        " * Depending on the {@link Column#overflow Overflow} policy, the value may span rows.",
        " * @param row the target row",
        " */",
    ]
    s = _javadoc_summary(doc)
    assert s == "Writes the specified value into the cell and returns the last row and column."
    assert "@param" not in s and "{@link" not in s


def test_no_stray_comment_close():
    # the old heuristic turned the closing `*/` line into `> /`
    assert _javadoc_summary(["/** short doc */"]) == "short doc"
    assert "/" not in _javadoc_summary([" * one line", " */"])


def test_empty():
    assert _javadoc_summary([]) == ""
    assert _javadoc_summary(["/**", " */"]) == ""
