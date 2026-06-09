package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.JacocoExecReport;
import com.graphtipper.slice.LocalContext;
import com.graphtipper.slice.SnippetCoveragePruner;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererLocalContextPruneTest {

    @Test void siblingsWithZeroExecutedLinesAreOmittedEntirely(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // JaCoCo XML: only line 10 in p/Foo.java is covered (the "hot" sibling).
        // The "cold" sibling lives at lines 30-35 — never executed.
        Path xml = tmp.resolve("exec.xml");
        Files.writeString(xml,
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
              + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">\n"
              + "<report name=\"x\">\n"
              + "  <sessioninfo id=\"s1\" start=\"0\" dump=\"0\"/>\n"
              + "  <package name=\"p\">\n"
              + "    <class name=\"p/Foo\" sourcefilename=\"Foo.java\">\n"
              + "      <sourcefile name=\"Foo.java\">\n"
              + "        <line nr=\"10\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/>\n"
              + "        <line nr=\"30\" mi=\"5\" ci=\"0\" mb=\"0\" cb=\"0\"/>\n"
              + "      </sourcefile>\n"
              + "    </class>\n"
              + "  </package>\n"
              + "</report>\n");
        var pruner = SnippetCoveragePruner.of(JacocoExecReport.fromXml(xml),
                "p/SomeTarget.java", 1, 1);  // target excludes itself; siblings live in Foo.java

        var hot = new LocalContext.SiblingMember(
                "void hot()", null, "void hot() { call(); }", false,
                "src/main/java/p/Foo.java", 10, 10);
        var cold = new LocalContext.SiblingMember(
                "void cold()", null, "void cold() { unused(); doNothing(); }", false,
                "src/main/java/p/Foo.java", 30, 35);

        var target = new Node.Method(
                "m:t#t()", "t.t", "t()", List.of(), "void",
                "src/main/java/p/SomeTarget.java", 1, 1, "", false, false, List.of("public"));
        var artifact = new Artifact(target, "void t(){}", List.<com.graphtipper.slice.Chain>of(), false,
                new LocalContext(List.of(hot, cold), List.of()));

        var opts = RenderOptions.defaults().withPruner(pruner);
        String md = new MarkdownRenderer(opts).render(artifact, new TokenBudget(50_000), "x", "x");

        assertThat(md).contains("void hot()");  // hot sibling kept
        assertThat(md).doesNotContain("void cold()");  // cold sibling fully dropped
        assertThat(md).doesNotContain("doNothing()");
    }

    @Test void siblingsWithMissingLineEndAreKeptNotDropped(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // Joern frequently omits LINE_NUMBER_END for short methods → lineEnd=-1. The pruner
        // must not drop such siblings (otherwise we lose real, tested helpers like
        // picocli.length(Text) — surfaced during the picocli/putValue dry-run).
        Path xml = tmp.resolve("exec.xml");
        Files.writeString(xml,
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
              + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">\n"
              + "<report name=\"x\"><sessioninfo id=\"s1\" start=\"0\" dump=\"0\"/>\n"
              + "  <package name=\"p\"><class name=\"p/Foo\" sourcefilename=\"Foo.java\">\n"
              + "    <sourcefile name=\"Foo.java\">\n"
              + "      <line nr=\"100\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/>\n"
              + "    </sourcefile></class></package></report>\n");
        var pruner = SnippetCoveragePruner.of(JacocoExecReport.fromXml(xml),
                "p/SomeTarget.java", 1, 1);

        var noLineEnd = new LocalContext.SiblingMember(
                "void helper()", null, "void helper() { return 42; }", false,
                "src/main/java/p/Foo.java", 100, -1);  // lineEnd missing

        var target = new Node.Method(
                "m:t#t()", "t.t", "t()", List.of(), "void",
                "src/main/java/p/SomeTarget.java", 1, 1, "", false, false, List.of("public"));
        var artifact = new Artifact(target, "void t(){}", List.<com.graphtipper.slice.Chain>of(), false,
                new LocalContext(List.of(noLineEnd), List.of()));

        String md = new MarkdownRenderer(RenderOptions.defaults().withPruner(pruner))
                .render(artifact, new TokenBudget(50_000), "x", "x");
        assertThat(md).contains("void helper()");  // not dropped despite missing lineEnd
        assertThat(md).contains("return 42;");
    }

    @Test void siblingsWithSomeExecutedLinesArePartiallyPruned(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // Three-line sibling: line 20 covered, 21 missed, 22 covered.
        Path xml = tmp.resolve("exec.xml");
        Files.writeString(xml,
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
              + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">\n"
              + "<report name=\"x\">\n"
              + "  <sessioninfo id=\"s1\" start=\"0\" dump=\"0\"/>\n"
              + "  <package name=\"p\">\n"
              + "    <class name=\"p/Foo\" sourcefilename=\"Foo.java\">\n"
              + "      <sourcefile name=\"Foo.java\">\n"
              + "        <line nr=\"20\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/>\n"
              + "        <line nr=\"21\" mi=\"5\" ci=\"0\" mb=\"0\" cb=\"0\"/>\n"
              + "        <line nr=\"22\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/>\n"
              + "      </sourcefile>\n"
              + "    </class>\n"
              + "  </package>\n"
              + "</report>\n");
        var pruner = SnippetCoveragePruner.of(JacocoExecReport.fromXml(xml),
                "p/SomeTarget.java", 1, 1);

        var sibling = new LocalContext.SiblingMember(
                "void mixed()", null, "void mixed() {\n  unused();\n  used();\n}", false,
                "src/main/java/p/Foo.java", 20, 22);

        var target = new Node.Method(
                "m:t#t()", "t.t", "t()", List.of(), "void",
                "src/main/java/p/SomeTarget.java", 1, 1, "", false, false, List.of("public"));
        var artifact = new Artifact(target, "void t(){}", List.<com.graphtipper.slice.Chain>of(), false,
                new LocalContext(List.of(sibling), List.of()));

        String md = new MarkdownRenderer(RenderOptions.defaults().withPruner(pruner))
                .render(artifact, new TokenBudget(50_000), "x", "x");

        // The middle line (21 = "unused()") is dropped via placeholder.
        assertThat(md).contains("// … unexecuted by tests");
        assertThat(md).doesNotContain("unused();");
    }
}
