from harness.impact.artifacts import MethodIndex
from harness.impact.diff_parser import changed_methods

DIFF = """\
diff --git a/src/main/java/p/CommandLine.java b/src/main/java/p/CommandLine.java
index 111..222 100644
--- a/src/main/java/p/CommandLine.java
+++ b/src/main/java/p/CommandLine.java
@@ -17418,7 +17418,7 @@ class TextTable {
-                int indent = column.indent;
+                int indent = 0;
@@ -100,3 +100,3 @@ class Other {
-    foo();
+    bar();
"""

IDX = MethodIndex({
    "p.TextTable.putValue": {"file": "src/main/java/p/CommandLine.java", "start": 17414, "end": 17460},
    "p.Other.m":            {"file": "src/main/java/p/CommandLine.java", "start": 95,    "end": 110},
    "p.TextTable.unrelated":{"file": "src/main/java/p/CommandLine.java", "start": 200,   "end": 250},
})


def test_changed_methods_maps_hunks_to_enclosing_methods():
    got = changed_methods(DIFF, IDX)
    assert got == {"p.TextTable.putValue", "p.Other.m"}


def test_changed_methods_ignores_files_not_in_index():
    diff = DIFF.replace("CommandLine.java", "Unknown.java")
    assert changed_methods(diff, IDX) == set()


def test_changed_method_outside_any_range_is_dropped():
    diff = """\
--- a/src/main/java/p/CommandLine.java
+++ b/src/main/java/p/CommandLine.java
@@ -500,1 +500,1 @@
-x
+y
"""
    assert changed_methods(diff, IDX) == set()


def test_attributes_by_deleted_side_not_grown_new_side():
    """The picocli putValue bug: implementing a stubbed method grows the NEW side
    across the FOLLOWING methods' (seed-coords) spans. Attributing by the NEW side
    false-flags those neighbours; attributing by the DELETED side (the stub the
    edit removed) flags only the method actually changed."""
    idx = MethodIndex({
        "p.T.putValue": {"file": "src/X.java", "start": 10, "end": 12},  # 3-line stub
        "p.T.length":   {"file": "src/X.java", "start": 13, "end": 16},
    })
    diff = (
        "--- a/src/X.java\n"
        "+++ b/src/X.java\n"
        "@@ -10,3 +10,6 @@\n"
        " public Cell putValue(...) {\n"                       # context: old 10
        "-    throw new UnsupportedOperationException();\n"     # deleted: old 11
        "-}\n"                                                  # deleted: old 12
        "+    impl1\n+    impl2\n+    impl3\n+    impl4\n+}\n"   # additions (new side grows)
        " public int length() {\n"                             # context: old 13
    )
    assert changed_methods(diff, idx) == {"p.T.putValue"}


def test_pure_insertion_attributed_to_enclosing_method_via_anchor():
    """An insertion with no deletions still attributes to the method containing the
    base anchor line (so a body-only addition isn't silently dropped)."""
    idx = MethodIndex({"p.T.m": {"file": "src/X.java", "start": 10, "end": 30}})
    diff = ("--- a/src/X.java\n+++ b/src/X.java\n"
            "@@ -15,0 +16,2 @@\n+added1\n+added2\n")
    assert changed_methods(diff, idx) == {"p.T.m"}
