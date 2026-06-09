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
