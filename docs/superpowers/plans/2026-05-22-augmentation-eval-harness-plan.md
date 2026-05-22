# Augmentation Eval Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an LLM-in-the-loop evaluation harness that A/B-tests 6 artifact variants (with/without JaCoCo pruning, with/without Katz ranking) against JavaBench (pass@1) and a standalone cycles-to-green runner (picocli `putValue` + 2–3 hand-picked targets). Verify or falsify the two hypotheses in [the spec](../specs/2026-05-22-augmentation-eval-harness-design.md).

**Architecture:** Java side extends graph-tipper with three new pieces — JaCoCo report parser+pruner, Katz scorer over the chop graph, and renderer modes (`--bare`, `--prune-by-coverage`, `--katz-rank`). Python side (new `harness/`) orchestrates artifact build per (target, arm), invokes the LLM, runs gradle tests, aggregates metrics, and writes one Markdown report with plots. JavaBench-side reuses their existing `inference.py` + `evaluation.py`; standalone runner is fully owned by us.

**Tech Stack:** Java 21, JGraphT 1.5.2 (existing dep, has `AlphaCentrality`), JavaParser, Jackson, JUnit 5, AssertJ; Python 3.11+, Anthropic SDK, pytest, matplotlib (via JavaBench's existing `paper_plot/`), Gradle invoked via subprocess.

**Spec reference:** `docs/superpowers/specs/2026-05-22-augmentation-eval-harness-design.md`.

---

## File Structure

**New Java files (`src/main/java/`):**
- `com/graphtipper/slice/JacocoExecReport.java` — parses JaCoCo XML aggregate report into `(file, line) → executed` lookup.
- `com/graphtipper/slice/SnippetCoveragePruner.java` — wraps a `JacocoExecReport` and answers `boolean isExecuted(filePath, line)`, with target-range exclusion baked in.
- `com/graphtipper/chop/score/KatzScorer.java` — derives method-vertex graph from `ChopGraph`, runs `org.jgrapht.alg.scoring.AlphaCentrality`, exposes `score(MethodRef)`.
- `com/graphtipper/render/RenderOptions.java` — record bundling `bare`, `pruner` (nullable), `scorer` (nullable).

**Modified Java files:**
- `com/graphtipper/render/MarkdownRenderer.java` — accept `RenderOptions`, emit `// … unexecuted by tests` and `[hub: ...]` markers, gate sections in `--bare` mode.
- `com/graphtipper/render/BudgetPlanner.java` — when scorer present, sort clusters by Katz desc before eviction.
- `com/graphtipper/slice/AstSnippetExtractor.java` — annotate `SnippetAt.renderedBody` with per-line execution-status markers when pruner is provided.
- `com/graphtipper/cli/SliceCommand.java` — wire three new flags `--prune-by-coverage <path>`, `--katz-rank`, `--bare`.

**New Java tests:** mirror layout, plus fixtures under `src/test/resources/coverage-fixtures/` and `src/test/resources/chop-fixtures/`.

**New Python files (`harness/`, sibling to `src/`):**
- `harness/pyproject.toml`, `harness/requirements.txt`, `harness/README.md`.
- `harness/orchestrator.py` — CLI entry point.
- `harness/arms.py` — defines the 6 arms and which apply per bench source.
- `harness/artifact_builder.py` — shells out to `graph-tipper slice` once per (target, arm).
- `harness/llm_provider.py` — wraps Anthropic SDK with retry, prompt-cache.
- `harness/javabench_runner.py` — drops artifacts into `fixtures/JavaBench/datasets/gt-augment/<arm>/`, calls their `inference.py` + `evaluation.py`, collects per-method pass/fail.
- `harness/standalone_runner.py` — cycles-to-green loop for picocli + hand-picked targets.
- `harness/metrics.py` — bootstrap CI, McNemar, Wilcoxon; pure functions.
- `harness/report.py` — emits `report.md` + two plots.
- `harness/targets.json` — data file with standalone target list.
- `harness/tests/` — `test_arms.py`, `test_metrics.py`, `test_artifact_builder.py`, `test_standalone_runner.py`.

---

## Phase 1 — Java extensions (Tasks 1–10)

### Task 1: JacocoExecReport — parse aggregate XML

**Files:**
- Create: `src/main/java/com/graphtipper/slice/JacocoExecReport.java`
- Test: `src/test/java/com/graphtipper/slice/JacocoExecReportTest.java`
- Fixture: `src/test/resources/coverage-fixtures/sample-exec.xml`

- [ ] **Step 1: Add a small fixture XML**

Create `src/test/resources/coverage-fixtures/sample-exec.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="sample">
  <sessioninfo id="s1" start="0" dump="0"/>
  <package name="com/example">
    <class name="com/example/Foo" sourcefilename="Foo.java">
      <method name="bar" desc="()V" line="10">
        <counter type="INSTRUCTION" missed="0" covered="6"/>
      </method>
      <sourcefile name="Foo.java">
        <line nr="10" mi="0" ci="3" mb="0" cb="0"/>
        <line nr="11" mi="0" ci="2" mb="0" cb="0"/>
        <line nr="12" mi="4" ci="0" mb="0" cb="0"/>
        <line nr="13" mi="0" ci="1" mb="0" cb="0"/>
      </sourcefile>
    </class>
  </package>
</report>
```

- [ ] **Step 2: Write failing test**

Create `src/test/java/com/graphtipper/slice/JacocoExecReportTest.java`:

```java
package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.assertj.core.api.Assertions.assertThat;

class JacocoExecReportTest {
    private static final Path FIXTURE =
        Paths.get("src/test/resources/coverage-fixtures/sample-exec.xml");

    @Test void parsesAndReportsExecutedLine() {
        JacocoExecReport r = JacocoExecReport.fromXml(FIXTURE);
        assertThat(r.isExecuted("com/example/Foo.java", 10)).isTrue();
        assertThat(r.isExecuted("com/example/Foo.java", 11)).isTrue();
        assertThat(r.isExecuted("com/example/Foo.java", 13)).isTrue();
    }

    @Test void reportsUnexecutedLine() {
        JacocoExecReport r = JacocoExecReport.fromXml(FIXTURE);
        assertThat(r.isExecuted("com/example/Foo.java", 12)).isFalse();
    }

    @Test void unknownFileOrLineIsNotExecuted() {
        JacocoExecReport r = JacocoExecReport.fromXml(FIXTURE);
        assertThat(r.isExecuted("com/example/Other.java", 10)).isFalse();
        assertThat(r.isExecuted("com/example/Foo.java", 999)).isFalse();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.JacocoExecReportTest`
Expected: FAIL with `JacocoExecReport` symbol not found.

- [ ] **Step 4: Implement JacocoExecReport**

Create `src/main/java/com/graphtipper/slice/JacocoExecReport.java`:

```java
package com.graphtipper.slice;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class JacocoExecReport {

    private final Map<String, Set<Integer>> executedLinesByFile;

    private JacocoExecReport(Map<String, Set<Integer>> executedLinesByFile) {
        this.executedLinesByFile = executedLinesByFile;
    }

    /** Path key format: "<package-with-slashes>/<SourceFile.java>", matching JaCoCo's hierarchy. */
    public boolean isExecuted(String packageQualifiedSourcePath, int line) {
        Set<Integer> s = executedLinesByFile.get(packageQualifiedSourcePath);
        return s != null && s.contains(line);
    }

    public static JacocoExecReport fromXml(Path xml) {
        Map<String, Set<Integer>> acc = new HashMap<>();
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (InputStream in = Files.newInputStream(xml)) {
            XMLStreamReader r = f.createXMLStreamReader(in);
            String currentPackage = null;
            String currentSourceFile = null;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if ("package".equals(name)) {
                        currentPackage = r.getAttributeValue(null, "name");
                    } else if ("sourcefile".equals(name)) {
                        currentSourceFile = r.getAttributeValue(null, "name");
                    } else if ("line".equals(name) && currentPackage != null && currentSourceFile != null) {
                        int nr = Integer.parseInt(r.getAttributeValue(null, "nr"));
                        int ci = parseIntSafe(r.getAttributeValue(null, "ci"));
                        if (ci > 0) {
                            String key = currentPackage + "/" + currentSourceFile;
                            acc.computeIfAbsent(key, k -> new HashSet<>()).add(nr);
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if ("sourcefile".equals(r.getLocalName())) currentSourceFile = null;
                    if ("package".equals(r.getLocalName())) currentPackage = null;
                }
            }
            return new JacocoExecReport(acc);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse JaCoCo XML " + xml, e);
        }
    }

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.JacocoExecReportTest`
Expected: 3 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/JacocoExecReport.java \
        src/test/java/com/graphtipper/slice/JacocoExecReportTest.java \
        src/test/resources/coverage-fixtures/sample-exec.xml
git commit -m "feat(slice): JacocoExecReport — parse JaCoCo XML into (file,line) executed lookup"
```

---

### Task 2: SnippetCoveragePruner — filter with target-range exclusion

**Files:**
- Create: `src/main/java/com/graphtipper/slice/SnippetCoveragePruner.java`
- Test: `src/test/java/com/graphtipper/slice/SnippetCoveragePrunerTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/graphtipper/slice/SnippetCoveragePrunerTest.java`:

```java
package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import static org.assertj.core.api.Assertions.assertThat;

class SnippetCoveragePrunerTest {

    private static final java.nio.file.Path FIXTURE =
        Paths.get("src/test/resources/coverage-fixtures/sample-exec.xml");

    @Test void executedCallerLineIsExecuted() {
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(FIXTURE),
            "com/example/Target.java", 5, 9);
        assertThat(pruner.isExecuted("com/example/Foo.java", 10)).isTrue();
    }

    @Test void unexecutedCallerLineIsNotExecuted() {
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(FIXTURE),
            "com/example/Target.java", 5, 9);
        assertThat(pruner.isExecuted("com/example/Foo.java", 12)).isFalse();
    }

    @Test void linesInsideTargetRangeAreNeverReportedExecuted() {
        // Even if JaCoCo says the line was covered, lines inside the target's
        // source range must be excluded to prevent leakage.
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(FIXTURE),
            "com/example/Foo.java", 10, 13);
        assertThat(pruner.isExecuted("com/example/Foo.java", 10)).isFalse();
        assertThat(pruner.isExecuted("com/example/Foo.java", 11)).isFalse();
        assertThat(pruner.isExecuted("com/example/Foo.java", 13)).isFalse();
    }

    @Test void linesOutsideTargetRangeAreUnaffected() {
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(FIXTURE),
            "com/example/Foo.java", 11, 12);
        assertThat(pruner.isExecuted("com/example/Foo.java", 10)).isTrue(); // before range
        assertThat(pruner.isExecuted("com/example/Foo.java", 13)).isTrue(); // after range
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.SnippetCoveragePrunerTest`
Expected: FAIL with `SnippetCoveragePruner` symbol not found.

- [ ] **Step 3: Implement SnippetCoveragePruner**

Create `src/main/java/com/graphtipper/slice/SnippetCoveragePruner.java`:

```java
package com.graphtipper.slice;

public final class SnippetCoveragePruner {

    private final JacocoExecReport report;
    private final String targetPackageQualifiedFile;
    private final int targetStartLine;
    private final int targetEndLine;

    private SnippetCoveragePruner(JacocoExecReport report, String targetPackageQualifiedFile,
                                   int targetStartLine, int targetEndLine) {
        this.report = report;
        this.targetPackageQualifiedFile = targetPackageQualifiedFile;
        this.targetStartLine = targetStartLine;
        this.targetEndLine = targetEndLine;
    }

    public static SnippetCoveragePruner of(JacocoExecReport report,
                                            String targetPackageQualifiedFile,
                                            int targetStartLine, int targetEndLine) {
        return new SnippetCoveragePruner(report, targetPackageQualifiedFile,
                targetStartLine, targetEndLine);
    }

    public boolean isExecuted(String packageQualifiedSourcePath, int line) {
        if (targetPackageQualifiedFile.equals(packageQualifiedSourcePath)
                && line >= targetStartLine && line <= targetEndLine) {
            return false;
        }
        return report.isExecuted(packageQualifiedSourcePath, line);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.SnippetCoveragePrunerTest`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/SnippetCoveragePruner.java \
        src/test/java/com/graphtipper/slice/SnippetCoveragePrunerTest.java
git commit -m "feat(slice): SnippetCoveragePruner — JaCoCo filter with target-range exclusion"
```

---

### Task 3: AstSnippetExtractor — line-level execution annotation

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java` (extend `SnippetAt` record, accept optional pruner)
- Test: `src/test/java/com/graphtipper/slice/AstSnippetExtractorPruneTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/graphtipper/slice/AstSnippetExtractorPruneTest.java`:

```java
package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AstSnippetExtractorPruneTest {

    @Test void renderedBodyCarriesExecutedFlagPerLine() {
        // Reuse the fixture from JacocoExecReport tests; we don't need a real Java file
        // — just feed the extractor's annotation hook directly with synthetic content.
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(
                Paths.get("src/test/resources/coverage-fixtures/sample-exec.xml")),
            "com/example/Target.java", 1, 1);
        // annotateLines is the pure helper we will add to AstSnippetExtractor for testability.
        List<String> annotated = AstSnippetExtractor.annotateLines(
            List.of("a();", "b();", "c();", "d();"),
            "com/example/Foo.java", 10, pruner);
        assertThat(annotated).containsExactly(
            "a();",
            "b();",
            "// … unexecuted by tests",
            "d();");
    }

    @Test void annotateLinesPassthroughWhenPrunerNull() {
        List<String> raw = List.of("x();", "y();");
        List<String> out = AstSnippetExtractor.annotateLines(raw,
            "com/example/Foo.java", 10, null);
        assertThat(out).isEqualTo(raw);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorPruneTest`
Expected: FAIL — `annotateLines` not defined.

- [ ] **Step 3: Add annotateLines to AstSnippetExtractor**

Add this static helper at the end of `AstSnippetExtractor.java` (before the closing `}` of the class):

```java
    /**
     * Replaces lines not covered by the pruner with a single placeholder comment.
     * Consecutive unexecuted lines collapse to one placeholder. Pruner=null is a no-op.
     *
     * @param rawLines  body lines as they appear in source order
     * @param fileKey   JaCoCo package-qualified source path, e.g. "com/example/Foo.java"
     * @param startLine line number of rawLines[0] in the source
     * @param pruner    optional; null means return rawLines unchanged
     */
    public static java.util.List<String> annotateLines(
            java.util.List<String> rawLines, String fileKey, int startLine,
            SnippetCoveragePruner pruner) {
        if (pruner == null) return rawLines;
        java.util.List<String> out = new java.util.ArrayList<>(rawLines.size());
        boolean inGap = false;
        for (int i = 0; i < rawLines.size(); i++) {
            int line = startLine + i;
            if (pruner.isExecuted(fileKey, line)) {
                out.add(rawLines.get(i));
                inGap = false;
            } else if (!inGap) {
                out.add("// … unexecuted by tests");
                inGap = true;
            }
        }
        return out;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorPruneTest`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/AstSnippetExtractor.java \
        src/test/java/com/graphtipper/slice/AstSnippetExtractorPruneTest.java
git commit -m "feat(slice): AstSnippetExtractor.annotateLines — collapse non-executed lines via pruner"
```

---

### Task 4: RenderOptions + MarkdownRenderer plumbing

**Files:**
- Create: `src/main/java/com/graphtipper/render/RenderOptions.java`
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java` (constructor with options, snippet-line transform hook)
- Test: `src/test/java/com/graphtipper/render/MarkdownRendererPruneTest.java`

- [ ] **Step 1: Create RenderOptions record**

Create `src/main/java/com/graphtipper/render/RenderOptions.java`:

```java
package com.graphtipper.render;

import com.graphtipper.chop.score.KatzScorer;
import com.graphtipper.slice.SnippetCoveragePruner;

public record RenderOptions(boolean bare, SnippetCoveragePruner pruner, KatzScorer scorer) {

    public static RenderOptions defaults() {
        return new RenderOptions(false, null, null);
    }

    public RenderOptions withBare(boolean b) { return new RenderOptions(b, pruner, scorer); }
    public RenderOptions withPruner(SnippetCoveragePruner p) { return new RenderOptions(bare, p, scorer); }
    public RenderOptions withScorer(KatzScorer s) { return new RenderOptions(bare, pruner, s); }
}
```

Note: `KatzScorer` does not exist yet (Task 5 adds it). Add it as a placeholder import; the file won't compile until Task 5 lands. To keep the build green between tasks, this step lands together with a compile-only stub. **Inline the stub now**:

Create `src/main/java/com/graphtipper/chop/score/KatzScorer.java`:

```java
package com.graphtipper.chop.score;

import com.graphtipper.chop.model.MethodRef;

/** Stub; real implementation in Task 5. */
public final class KatzScorer {
    public double score(MethodRef m) { return 0.0; }
}
```

- [ ] **Step 2: Write failing test**

Create `src/test/java/com/graphtipper/render/MarkdownRendererPruneTest.java`:

```java
package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererPruneTest {

    @Test void rendererAcceptsOptionsAndProducesNonEmptyMarkdown() {
        var target = new Node.Method(
                /* id */ "m:com.example.Foo#bar",
                /* fqn */ "com.example.Foo#bar",
                /* signature */ "bar(int)",
                /* paramTypes */ List.of("int"),
                /* returnType */ "void",
                /* file */ "src/main/java/com/example/Foo.java",
                /* lineStart */ 5, /* lineEnd */ 10,
                /* javadoc */ "", /* isTest */ false, /* isAbstract */ false,
                /* modifiers */ List.of("public"));
        var artifact = new Artifact(target, "void bar(int x){}", List.of(), false,
                new LocalContext(List.of(), List.of()));
        var md = new MarkdownRenderer(RenderOptions.defaults())
                .render(artifact, new TokenBudget(20_000), "src-sha", "demo");
        assertThat(md).contains("# Graph-Tipper Augmentation");
    }
}
```

(Constructor matches `Node.Method` record at `model/Node.java:15-27` and `LocalContext` at `slice/LocalContext.java:7-12` as of plan-writing time. Engineer should re-verify before running if `Node.java` has changed.)

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererPruneTest`
Expected: FAIL — `MarkdownRenderer` has no constructor taking `RenderOptions`.

- [ ] **Step 4: Add RenderOptions-aware constructor to MarkdownRenderer**

In `src/main/java/com/graphtipper/render/MarkdownRenderer.java`:

- Add a field `private final RenderOptions options;` and store it in a new constructor.
- Keep the existing zero-arg constructor and make it delegate: `public MarkdownRenderer() { this(RenderOptions.defaults()); }`.
- Add `public MarkdownRenderer(RenderOptions options) { this.options = options; }`.

Diff sketch (apply at top of class, after the `final class MarkdownRenderer {` line):

```java
    private final RenderOptions options;

    public MarkdownRenderer() { this(RenderOptions.defaults()); }

    public MarkdownRenderer(RenderOptions options) { this.options = options; }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererPruneTest`
Expected: PASS. Run also `./gradlew test --tests com.graphtipper.render.*` to ensure no regression.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/render/RenderOptions.java \
        src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/main/java/com/graphtipper/chop/score/KatzScorer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererPruneTest.java
git commit -m "feat(render): RenderOptions + MarkdownRenderer options constructor (Katz stub)"
```

---

### Task 5: KatzScorer — real AlphaCentrality on chop method graph

**Files:**
- Replace stub: `src/main/java/com/graphtipper/chop/score/KatzScorer.java`
- Test: `src/test/java/com/graphtipper/chop/score/KatzScorerTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/graphtipper/chop/score/KatzScorerTest.java`:

```java
package com.graphtipper.chop.score;

import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class KatzScorerTest {

    private static MethodRef m(String fqn) {
        return new MethodRef(fqn, "()");
    }

    @Test void hubMethodReachedByManyCallersScoresHigher() {
        MethodRef target = m("com.example.Target");
        MethodRef hub = m("com.example.Hub");
        MethodRef leafA = m("com.example.LeafA");
        MethodRef leafB = m("com.example.LeafB");
        MethodRef leafC = m("com.example.LeafC");

        ChopGraph g = new ChopGraph(target, List.of(), Set.of(leafA, leafB, leafC));
        addMethodEdge(g, leafA, hub);
        addMethodEdge(g, leafB, hub);
        addMethodEdge(g, leafC, hub);
        addMethodEdge(g, hub, target);

        var scorer = new KatzScorer(g);
        assertThat(scorer.score(hub)).isGreaterThan(scorer.score(leafA));
        assertThat(scorer.score(hub)).isGreaterThan(scorer.score(leafB));
    }

    @Test void disconnectedMethodScoresZero() {
        MethodRef target = m("T");
        MethodRef isolated = m("Isolated");
        ChopGraph g = new ChopGraph(target, List.of(), Set.of(target));
        addMethodEdge(g, target, target);
        var scorer = new KatzScorer(g);
        assertThat(scorer.score(isolated)).isEqualTo(0.0);
    }

    private static void addMethodEdge(ChopGraph g, MethodRef src, MethodRef dst) {
        MethodNode srcNode = new MethodNode(src, /* isTest */ false, /* isTarget */ false, Set.of());
        MethodNode dstNode = new MethodNode(dst, /* isTest */ false, /* isTarget */ false, Set.of());
        g.addNode(srcNode);
        g.addNode(dstNode);
        g.addEdge(new ChopEdge(
                srcNode, dstNode,
                EdgeLayer.CG, ResolutionKind.EXACT, DataKind.DEF_USE,
                /* label */ "call", /* touchedBy */ Set.of()));
    }
}
```

(Constructors verified against `MethodRef.java`, `MethodNode.java`, `ChopEdge.java`. `EdgeLayer.CG` = call-graph layer; `ResolutionKind.EXACT` is the strongest available; `DataKind` is required by `ChopEdge` even for non-data edges — `DEF_USE` is a benign default.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.chop.score.KatzScorerTest`
Expected: FAIL — `KatzScorer` has no graph-taking constructor (only the stub).

- [ ] **Step 3: Implement KatzScorer**

Replace `src/main/java/com/graphtipper/chop/score/KatzScorer.java`:

```java
package com.graphtipper.chop.score;

import com.graphtipper.chop.model.ChopGraph;
import com.graphtipper.chop.model.ChopNode;
import com.graphtipper.chop.model.ChopEdge;
import com.graphtipper.chop.model.MethodRef;
import org.jgrapht.alg.scoring.AlphaCentrality;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;

import java.util.HashMap;
import java.util.Map;

public final class KatzScorer {

    private static final double ALPHA = 0.01;

    private final Map<MethodRef, Double> scores;

    public KatzScorer(ChopGraph chop) {
        SimpleDirectedGraph<MethodRef, DefaultEdge> mg = new SimpleDirectedGraph<>(DefaultEdge.class);
        for (MethodRef m : chop.involvedMethods()) mg.addVertex(m);
        for (ChopEdge e : chop.jgraph().edgeSet()) {
            MethodRef s = ((ChopNode) e.src()).owner();
            MethodRef d = ((ChopNode) e.dst()).owner();
            if (s.equals(d)) continue;
            if (!mg.containsVertex(s)) mg.addVertex(s);
            if (!mg.containsVertex(d)) mg.addVertex(d);
            mg.addEdge(s, d);
        }
        if (mg.vertexSet().isEmpty()) {
            this.scores = Map.of();
            return;
        }
        var alpha = new AlphaCentrality<>(mg, ALPHA);
        this.scores = new HashMap<>(alpha.getScores());
    }

    public double score(MethodRef m) {
        return scores.getOrDefault(m, 0.0);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.chop.score.KatzScorerTest`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/score/KatzScorer.java \
        src/test/java/com/graphtipper/chop/score/KatzScorerTest.java
git commit -m "feat(chop): KatzScorer — AlphaCentrality over method-vertex chop subgraph"
```

---

### Task 6: BudgetPlanner — Katz-aware cluster ordering

**Files:**
- Modify: `src/main/java/com/graphtipper/render/BudgetPlanner.java`
- Test: `src/test/java/com/graphtipper/render/BudgetPlannerKatzTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/graphtipper/render/BudgetPlannerKatzTest.java`:

```java
package com.graphtipper.render;

import com.graphtipper.chop.score.KatzScorer;
import com.graphtipper.slice.PathCluster;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BudgetPlannerKatzTest {

    @Test void clustersAreSortedByKatzDescendingWhenScorerPresent() {
        // Use a fake KatzScorer-shaped test double via a subclass that returns
        // fixed scores by FQN substring. The real KatzScorer takes a ChopGraph
        // and we don't want to construct one here.
        KatzScorer fake = new FixedKatzScorer(java.util.Map.of(
                "com.example.Hub", 5.0,
                "com.example.Leaf", 0.5));
        var c1 = clusterWithImmediateConsumer("com.example.Leaf");
        var c2 = clusterWithImmediateConsumer("com.example.Hub");
        var sorted = BudgetPlanner.sortByKatz(List.of(c1, c2), fake);
        assertThat(sorted.get(0).immediateConsumer()).isEqualTo("com.example.Hub");
    }

    private static PathCluster clusterWithImmediateConsumer(String fqn) {
        // Build a minimal PathCluster; the only field consulted by sortByKatz is the
        // method FQNs touched by the cluster. For this test, immediateConsumer suffices.
        return new PathCluster(
            new com.graphtipper.slice.PathSignature(List.of(fqn)),
            fqn, fqn, 1, List.of(), List.of());
    }

    private static final class FixedKatzScorer extends KatzScorer {
        private final java.util.Map<String, Double> byFqn;
        FixedKatzScorer(java.util.Map<String, Double> byFqn) {
            super(new com.graphtipper.chop.model.ChopGraph(
                    new com.graphtipper.chop.model.MethodRef("__sentinel__", "()"),
                    List.of(), java.util.Set.of()));
            this.byFqn = byFqn;
        }
        @Override public double score(com.graphtipper.chop.model.MethodRef m) {
            return byFqn.getOrDefault(m.fqn(), 0.0);
        }
    }
}
```

If `KatzScorer` is `final` (Task 5 wrote it as `final`), drop the `final` modifier in `KatzScorer.java` to allow this test double. This is a deliberate testability concession.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.BudgetPlannerKatzTest`
Expected: FAIL — `BudgetPlanner.sortByKatz` not defined.

- [ ] **Step 3: Add Katz-aware sort + remove `final` from KatzScorer**

In `src/main/java/com/graphtipper/chop/score/KatzScorer.java`, change `public final class KatzScorer` → `public class KatzScorer`.

In `src/main/java/com/graphtipper/render/BudgetPlanner.java`, add this static helper before the closing class brace:

```java
    /**
     * Returns clusters sorted by max-Katz over the methods they touch, descending.
     * Used by {@link #fit(Artifact, com.graphtipper.util.TokenBudget)} when a scorer is in scope.
     * Right now we approximate "methods touched" by the path signature FQNs of the cluster.
     */
    public static java.util.List<com.graphtipper.slice.PathCluster> sortByKatz(
            java.util.List<com.graphtipper.slice.PathCluster> clusters,
            com.graphtipper.chop.score.KatzScorer scorer) {
        var copy = new java.util.ArrayList<>(clusters);
        copy.sort((a, b) -> Double.compare(maxKatz(b, scorer), maxKatz(a, scorer)));
        return copy;
    }

    private static double maxKatz(com.graphtipper.slice.PathCluster c,
                                   com.graphtipper.chop.score.KatzScorer scorer) {
        double best = 0.0;
        for (String fqn : c.signature().fqns()) {
            // MethodRef stub: scorer only consults fqn() in tests, so a thin record works.
            var ref = new com.graphtipper.chop.model.MethodRef(fqn, "");
            double s = scorer.score(ref);
            if (s > best) best = s;
        }
        return best;
    }
```

(`PathSignature` exposes `fqns()` per `slice/PathSignature.java`. Code above already uses it.)

- [ ] **Step 4: Wire the sort into `fit(...)` when a scorer is in `RenderOptions`**

Add a setter or constructor variant so `BudgetPlanner.fit` can know about a scorer. Smallest change: extend the eviction entry point.

In `BudgetPlanner`, add:

```java
    private com.graphtipper.chop.score.KatzScorer katzScorer;

    public BudgetPlanner withScorer(com.graphtipper.chop.score.KatzScorer s) {
        this.katzScorer = s; return this;
    }
```

And inside `evictLowRankAndSingletonClusters` (or whichever helper iterates clusters first), call `sortByKatz(...)` before evicting when `katzScorer != null`. Locate the existing cluster-list reference in `evictLowRankAndSingletonClusters` and wrap it:

```java
        java.util.List<com.graphtipper.slice.PathCluster> orderedClusters =
            (katzScorer != null) ? sortByKatz(clusters, katzScorer) : clusters;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.BudgetPlannerKatzTest`
Expected: PASS.

- [ ] **Step 6: Run full test suite to check no regression**

Run: `./gradlew test`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphtipper/render/BudgetPlanner.java \
        src/main/java/com/graphtipper/chop/score/KatzScorer.java \
        src/test/java/com/graphtipper/render/BudgetPlannerKatzTest.java
git commit -m "feat(render): BudgetPlanner.sortByKatz + scorer hook"
```

---

### Task 7: MarkdownRenderer — render `[hub: ...]` markers per cluster

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Test: `src/test/java/com/graphtipper/render/MarkdownRendererHubMarkerTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.graphtipper.render;

import com.graphtipper.chop.model.MethodRef;
import com.graphtipper.chop.score.KatzScorer;
import com.graphtipper.slice.PathCluster;
import com.graphtipper.slice.PathSignature;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererHubMarkerTest {

    @Test void renderClusterPrependsHubMarkersForTop2KatzMethods() {
        KatzScorer fake = new KatzScorer(new com.graphtipper.chop.model.ChopGraph(
                new MethodRef("__sentinel__", "()"),
                List.of(), java.util.Set.of())) {
            @Override public double score(MethodRef m) {
                return switch (m.fqn()) {
                    case "com.example.HubA" -> 9.0;
                    case "com.example.HubB" -> 5.0;
                    case "com.example.Leaf" -> 0.1;
                    default -> 0.0;
                };
            }
        };
        var cluster = new PathCluster(
                new PathSignature(List.of("com.example.HubA", "com.example.Leaf", "com.example.HubB")),
                "com.example.HubA", "com.example.HubB", 3, List.of(), List.of());
        String marker = MarkdownRenderer.renderHubMarker(cluster, fake);
        assertThat(marker).isEqualTo("[hub: com.example.HubA, com.example.HubB]");
    }

    @Test void renderHubMarkerEmptyWhenNoScorer() {
        var cluster = new PathCluster(
                new PathSignature(List.of("com.example.X")),
                "com.example.X", "com.example.X", 1, List.of(), List.of());
        assertThat(MarkdownRenderer.renderHubMarker(cluster, null)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererHubMarkerTest`
Expected: FAIL — `renderHubMarker` not defined.

- [ ] **Step 3: Implement renderHubMarker**

In `MarkdownRenderer.java`, add a public static helper:

```java
    public static String renderHubMarker(com.graphtipper.slice.PathCluster cluster,
                                          com.graphtipper.chop.score.KatzScorer scorer) {
        if (scorer == null) return "";
        var fqns = cluster.signature().fqns();
        var scored = new java.util.ArrayList<java.util.Map.Entry<String, Double>>();
        for (String fqn : fqns) {
            scored.add(java.util.Map.entry(fqn,
                scorer.score(new com.graphtipper.chop.model.MethodRef(fqn, ""))));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        var top = new java.util.ArrayList<String>();
        for (var e : scored) {
            if (top.size() >= 2) break;
            if (e.getValue() <= 0.0) continue;
            top.add(e.getKey());
        }
        if (top.isEmpty()) return "";
        return "[hub: " + String.join(", ", top) + "]";
    }
```

Then, in the per-cluster section of the existing `render(...)` (locate the loop that emits cluster headings — likely string-builds something like `### Cluster N`), prepend the marker when `options.scorer() != null`. Sketch:

```java
String hub = renderHubMarker(cluster, options.scorer());
if (!hub.isEmpty()) sb.append(hub).append("\n");
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererHubMarkerTest`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererHubMarkerTest.java
git commit -m "feat(render): renderHubMarker for cluster top-2 Katz methods"
```

---

### Task 8: `--bare` mode

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Test: `src/test/java/com/graphtipper/render/MarkdownRendererBareTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.LocalContext;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererBareTest {

    @Test void bareModeProducesSignaturePlusJavadocOnly() {
        var target = new Node.Method(
                "m:com.example.Foo#bar(int)", "com.example.Foo#bar(int)",
                "bar(int)", List.of("int"), "void",
                "src/main/java/com/example/Foo.java", 5, 10,
                "/** Sets cell. */",
                false, false, List.of("public"));
        var artifact = new Artifact(target, "void bar(int x){}", List.of(), false,
                new LocalContext(List.of(), List.of()));
        String md = new MarkdownRenderer(RenderOptions.defaults().withBare(true))
            .render(artifact, new TokenBudget(20_000), "src-sha", "demo");
        assertThat(md)
            .contains("bar(int)")
            .contains("Sets cell.")
            .doesNotContain("## Test Chains")
            .doesNotContain("## Local Context");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererBareTest`
Expected: FAIL — bare mode not honored.

- [ ] **Step 3: Gate sections in render(...)**

In `MarkdownRenderer.render(...)`, after constructing the header section, return early when `options.bare()` is true, having appended only `# Graph-Tipper Augmentation`, the target line (signature + javadoc), and `## Target` section. Skip Test Chains, Local Context, Negative Memory.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererBareTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererBareTest.java
git commit -m "feat(render): --bare mode emits target signature+javadoc only"
```

---

### Task 9: SliceCommand — wire `--prune-by-coverage`, `--katz-rank`, `--bare` flags

**Files:**
- Modify: `src/main/java/com/graphtipper/cli/SliceCommand.java`
- Test: `src/test/java/com/graphtipper/cli/SliceCommandFlagsTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/graphtipper/cli/SliceCommandFlagsTest.java`:

```java
package com.graphtipper.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import static org.assertj.core.api.Assertions.assertThat;

class SliceCommandFlagsTest {

    @Test void newFlagsAreRecognizedByPicocli() {
        SliceCommand cmd = new SliceCommand();
        new CommandLine(cmd).parseArgs(
                "--project", "/tmp/p",
                "--target", "Foo#bar",
                "--out", "/tmp/o",
                "--prune-by-coverage", "/tmp/exec.xml",
                "--katz-rank",
                "--bare");
        assertThat(cmd.pruneByCoverage).isNotNull();
        assertThat(cmd.katzRank).isTrue();
        assertThat(cmd.bare).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.cli.SliceCommandFlagsTest`
Expected: FAIL — flag fields don't exist.

- [ ] **Step 3: Add the flags to SliceCommand**

In `src/main/java/com/graphtipper/cli/SliceCommand.java`, add (alongside existing `@Option` fields):

```java
    @Option(names = "--prune-by-coverage",
            description = "Path to JaCoCo XML report. Snippet lines not covered by reaching tests "
                    + "are collapsed to `// … unexecuted by tests`. Target method's own range "
                    + "is excluded from the coverage signal to prevent leakage.")
    Path pruneByCoverage;

    @Option(names = "--katz-rank",
            description = "Rank path clusters by max Katz centrality on the chop method graph. "
                    + "High-centrality clusters get priority under the token budget.")
    boolean katzRank;

    @Option(names = "--bare",
            description = "Emit only the target signature + javadoc (no chains, no local context). "
                    + "Used by the no-context arm of the eval harness.")
    boolean bare;
```

Then, inside `call()`, after `Artifact budgetArtifact = ...`, wire the options to the renderer:

```java
            RenderOptions opts = RenderOptions.defaults()
                    .withBare(bare);
            if (pruneByCoverage != null) {
                var report = com.graphtipper.slice.JacocoExecReport.fromXml(pruneByCoverage);
                String tgtPkgFile = packageQualifiedSourcePath(targetMethod);
                var pruner = com.graphtipper.slice.SnippetCoveragePruner.of(
                        report, tgtPkgFile,
                        targetMethod.lineStart(), targetMethod.lineEnd());
                opts = opts.withPruner(pruner);
            }
            if (katzRank) {
                // ChopGraph build: out of scope here; assume `chop.cli.ChopPipeline` exposes a builder.
                var chopGraph = new com.graphtipper.chop.cli.ChopPipeline(project, joernHome)
                        .buildForTarget(target);
                opts = opts.withScorer(new com.graphtipper.chop.score.KatzScorer(chopGraph));
            }

            String budgetMd = new MarkdownRenderer(opts).render(
                    budgetArtifact, budget, projectSrcHash, projectName);
```

Add a private helper `packageQualifiedSourcePath(Node.Method)` that derives `"com/example/Foo.java"` from `targetMethod.fqn()` and `targetMethod.file()`. The simplest version: take `targetMethod.file()` (already a project-relative path like `src/main/java/com/example/Foo.java`), strip `src/main/java/` prefix if present.

```java
    private static String packageQualifiedSourcePath(Node.Method m) {
        String f = m.file();
        if (f == null) return "";
        int idx = f.indexOf("src/main/java/");
        if (idx >= 0) return f.substring(idx + "src/main/java/".length());
        idx = f.indexOf("src/test/java/");
        if (idx >= 0) return f.substring(idx + "src/test/java/".length());
        return f; // last resort
    }
```

**Note:** `ChopPipeline.buildForTarget` may not exist with this exact name. Audit `com.graphtipper.chop.cli.*` and adapt. If no programmatic builder exists yet, defer this line behind a TODO with a clear failure mode (throw `UnsupportedOperationException("--katz-rank requires programmatic ChopPipeline access; see Task 9 followup")`). Better: add the minimal builder as part of this task — see §Followups.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.cli.SliceCommandFlagsTest`
Expected: PASS.

- [ ] **Step 5: Run full Java suite**

Run: `./gradlew test`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/cli/SliceCommand.java \
        src/test/java/com/graphtipper/cli/SliceCommandFlagsTest.java
git commit -m "feat(cli): SliceCommand — wire --prune-by-coverage / --katz-rank / --bare"
```

---

### Task 10: End-to-end Java integration on tiny-project fixture

**Files:**
- Create: `src/test/java/com/graphtipper/cli/SliceCommandEndToEndPruneTest.java`
- Reuse fixture: `fixtures/tiny-project/`

- [ ] **Step 1: Write failing test**

```java
package com.graphtipper.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class SliceCommandEndToEndPruneTest {

    @Test void bareModeProducesShortArtifact(@TempDir Path out) {
        int code = new CommandLine(new com.graphtipper.cli.Main()).execute(
                "slice",
                "--project", "fixtures/tiny-project",
                "--target", "src/main/java/tiny/Adder.java#Adder.add(int,int)",
                "--out", out.toString(),
                "--bare");
        assertThat(code).isEqualTo(0);
        var mds = Files.list(out).filter(p -> p.toString().endsWith(".budget.md")).toList();
        assertThat(mds).hasSize(1);
        String content = Files.readString(mds.get(0));
        assertThat(content).doesNotContain("## Test Chains");
    }
}
```

(`tiny/Adder.add(int,int)` is the only non-trivial production method in `fixtures/tiny-project/`.)

- [ ] **Step 2: Run, iterate to PASS**

Run: `./gradlew test --tests com.graphtipper.cli.SliceCommandEndToEndPruneTest`
Expected after fix: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/graphtipper/cli/SliceCommandEndToEndPruneTest.java
git commit -m "test(cli): end-to-end bare mode on tiny-project"
```

---

## Phase 2 — Python harness (Tasks 11–18)

### Task 11: harness skeleton + tooling

**Files:**
- Create: `harness/pyproject.toml`, `harness/requirements.txt`, `harness/README.md`
- Create: `harness/__init__.py`, `harness/tests/__init__.py`
- Create: `harness/.gitignore`

- [ ] **Step 1: Write requirements.txt**

```text
anthropic>=0.39.0
pytest>=8.0.0
matplotlib>=3.8.0
scipy>=1.13.0
numpy>=1.26.0
```

- [ ] **Step 2: Write pyproject.toml**

```toml
[project]
name = "graph-tipper-harness"
version = "0.1.0"
requires-python = ">=3.11"

[tool.pytest.ini_options]
testpaths = ["tests"]
```

- [ ] **Step 3: README with single command to run pilot**

```markdown
# Harness

Python orchestrator for the eval harness. Reads bench config, builds artifacts via
graph-tipper, invokes the LLM, runs tests, writes report.

## Run pilot

```bash
cd harness
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
ANTHROPIC_API_KEY=... python orchestrator.py --bench javabench-pa21 --arms all --samples 3
```
```

- [ ] **Step 4: .gitignore**

```text
.venv/
__pycache__/
*.pyc
output/
.pytest_cache/
```

- [ ] **Step 5: Commit**

```bash
git add harness/
git commit -m "chore(harness): scaffold Python package (pyproject, requirements, README)"
```

---

### Task 12: targets.json + artifact_builder

**Files:**
- Create: `harness/targets.json`
- Create: `harness/artifact_builder.py`
- Create: `harness/tests/test_artifact_builder.py`

- [ ] **Step 1: Define standalone targets in targets.json**

```json
{
  "standalone": [
    {
      "id": "picocli-putvalue",
      "project_dir": "/tmp/picocli",
      "target_spec": "src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)",
      "test_filter": "picocli.TextTableTest"
    }
  ],
  "_comments": "Pilot-time additions (1-3 hand-picked from JavaBench PA-Solution projects) appended once Pilot-2 narrows them down."
}
```

- [ ] **Step 2: Write failing test**

Create `harness/tests/test_artifact_builder.py`:

```python
import json
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
```

- [ ] **Step 3: Run, expect fail**

Run: `cd harness && python -m pytest tests/test_artifact_builder.py -v`
Expected: ImportError — `artifact_builder` not found.

- [ ] **Step 4: Implement artifact_builder**

Create `harness/artifact_builder.py`:

```python
from pathlib import Path
from typing import Optional

VALID_ARMS = {"no-context", "javabench-selective", "gt-current",
              "gt+jacoco", "gt+katz", "gt+jacoco+katz"}

def build_arm_command(*, graph_tipper_bin: str, project_dir: str, target_spec: str,
                      out_dir: Path, arm: str,
                      exec_xml_path: Optional[Path]) -> list[str]:
    if arm not in VALID_ARMS:
        raise ValueError(f"unknown arm: {arm}")
    cmd = [graph_tipper_bin, "slice",
           "--project", str(project_dir),
           "--target", target_spec,
           "--out", str(out_dir)]
    if arm == "no-context":
        cmd.append("--bare")
    if arm in {"gt+jacoco", "gt+jacoco+katz"}:
        if exec_xml_path is None:
            raise ValueError(f"arm {arm} requires exec_xml_path")
        cmd.extend(["--prune-by-coverage", str(exec_xml_path)])
    if arm in {"gt+katz", "gt+jacoco+katz"}:
        cmd.append("--katz-rank")
    return cmd
```

- [ ] **Step 5: Run, expect pass**

Run: `cd harness && python -m pytest tests/test_artifact_builder.py -v`
Expected: 4 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add harness/targets.json harness/artifact_builder.py harness/tests/test_artifact_builder.py
git commit -m "feat(harness): artifact_builder — graph-tipper CLI command builder per arm"
```

---

### Task 13: llm_provider — Anthropic client wrapper

**Files:**
- Create: `harness/llm_provider.py`
- Create: `harness/tests/test_llm_provider.py` (mocked)

- [ ] **Step 1: Write failing test**

Create `harness/tests/test_llm_provider.py`:

```python
from unittest.mock import MagicMock
from harness.llm_provider import LLMProvider, build_prompt

def test_build_prompt_includes_system_artifact_signature():
    prompt = build_prompt(
        system="You generate Java method bodies.",
        artifact="# Augmentation\n...",
        signature="void putValue(int row, int col, Text v)",
        history=[],
    )
    assert "You generate Java method bodies." in prompt["system"]
    assert "# Augmentation" in prompt["user"]
    assert "void putValue(int row, int col, Text v)" in prompt["user"]

def test_build_prompt_includes_history_for_cycle_2plus():
    prompt = build_prompt(
        system="sys",
        artifact="art",
        signature="sig",
        history=[("attempt1 body", "failure feedback")],
    )
    assert "attempt1 body" in prompt["user"]
    assert "failure feedback" in prompt["user"]

def test_provider_passes_through_to_client():
    client = MagicMock()
    client.messages.create.return_value.content = [MagicMock(text="generated body")]
    provider = LLMProvider(client=client, model="claude-sonnet-4-6")
    body = provider.complete(system="sys", user="usr", max_tokens=2000)
    assert body == "generated body"
    client.messages.create.assert_called_once()
```

- [ ] **Step 2: Run, expect fail**

Run: `cd harness && python -m pytest tests/test_llm_provider.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement llm_provider**

Create `harness/llm_provider.py`:

```python
from typing import Optional

def build_prompt(*, system: str, artifact: str, signature: str,
                  history: list[tuple[str, str]]) -> dict:
    history_block = ""
    for i, (attempt, feedback) in enumerate(history, 1):
        history_block += f"\n\n### Previous attempt {i} (rejected)\n```java\n{attempt}\n```\n\n"
        history_block += f"### Feedback {i}\n```\n{feedback}\n```\n"
    user = (
        f"## Augmentation\n{artifact}\n\n"
        f"## Method signature to implement\n```java\n{signature}\n```\n"
        f"{history_block}"
        "Return only the method body (everything inside the braces) — no signature, no markdown fences."
    )
    return {"system": system, "user": user}

class LLMProvider:
    def __init__(self, *, client, model: str):
        self.client = client
        self.model = model

    def complete(self, *, system: str, user: str, max_tokens: int = 2000) -> str:
        resp = self.client.messages.create(
            model=self.model,
            max_tokens=max_tokens,
            system=system,
            messages=[{"role": "user", "content": user}],
        )
        return resp.content[0].text
```

- [ ] **Step 4: Run, expect pass**

Run: `cd harness && python -m pytest tests/test_llm_provider.py -v`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/llm_provider.py harness/tests/test_llm_provider.py
git commit -m "feat(harness): llm_provider — Anthropic wrapper with prompt builder"
```

---

### Task 14: standalone_runner — cycles-to-green loop

**Files:**
- Create: `harness/standalone_runner.py`
- Create: `harness/tests/test_standalone_runner.py`

- [ ] **Step 1: Write failing test (loop logic only, no real LLM/gradle)**

```python
from unittest.mock import MagicMock
from harness.standalone_runner import run_cycles_to_green, CycleResult

def test_green_on_first_attempt_returns_one_cycle():
    llm = MagicMock()
    llm.complete.return_value = "// passes immediately"
    test_runner = MagicMock(side_effect=[("green", "")])
    result = run_cycles_to_green(
        llm=llm, system="sys", artifact="art", signature="sig",
        write_body=lambda body: None,
        compile_and_test=test_runner,
        cap=5,
    )
    assert result == CycleResult(status="green", cycles=1)

def test_red_then_green_returns_two_cycles():
    llm = MagicMock()
    llm.complete.side_effect = ["bad", "good"]
    test_runner = MagicMock(side_effect=[("red", "AssertionError"), ("green", "")])
    result = run_cycles_to_green(
        llm=llm, system="sys", artifact="art", signature="sig",
        write_body=lambda body: None,
        compile_and_test=test_runner,
        cap=5,
    )
    assert result == CycleResult(status="green", cycles=2)

def test_cap_reached_returns_not_converged():
    llm = MagicMock()
    llm.complete.return_value = "always bad"
    test_runner = MagicMock(return_value=("red", "AssertionError"))
    result = run_cycles_to_green(
        llm=llm, system="sys", artifact="art", signature="sig",
        write_body=lambda body: None,
        compile_and_test=test_runner,
        cap=3,
    )
    assert result == CycleResult(status="not_converged", cycles=3)

def test_compile_failure_counts_as_cycle():
    llm = MagicMock()
    llm.complete.side_effect = ["syntax err", "ok"]
    test_runner = MagicMock(side_effect=[
        ("compile_error", "expected ';'"),
        ("green", "")])
    result = run_cycles_to_green(
        llm=llm, system="sys", artifact="art", signature="sig",
        write_body=lambda body: None,
        compile_and_test=test_runner,
        cap=5,
    )
    assert result.cycles == 2
```

- [ ] **Step 2: Run, expect fail**

Run: `cd harness && python -m pytest tests/test_standalone_runner.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement standalone_runner**

Create `harness/standalone_runner.py`:

```python
from dataclasses import dataclass
from typing import Callable, Literal
from harness.llm_provider import build_prompt

@dataclass(frozen=True)
class CycleResult:
    status: Literal["green", "not_converged"]
    cycles: int

def run_cycles_to_green(*, llm, system: str, artifact: str, signature: str,
                         write_body: Callable[[str], None],
                         compile_and_test: Callable[[], tuple[str, str]],
                         cap: int) -> CycleResult:
    history: list[tuple[str, str]] = []
    for cycle in range(1, cap + 1):
        prompt = build_prompt(system=system, artifact=artifact,
                              signature=signature, history=history)
        body = llm.complete(system=prompt["system"], user=prompt["user"])
        write_body(body)
        status, feedback = compile_and_test()
        if status == "green":
            return CycleResult(status="green", cycles=cycle)
        history.append((body, f"[{status}] {feedback}"))
    return CycleResult(status="not_converged", cycles=cap)
```

- [ ] **Step 4: Run, expect pass**

Run: `cd harness && python -m pytest tests/test_standalone_runner.py -v`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/standalone_runner.py harness/tests/test_standalone_runner.py
git commit -m "feat(harness): standalone_runner — cycles-to-green loop with cap"
```

---

### Task 15: javabench_runner — drop artifacts into JavaBench dataset + invoke their pipeline

**Files:**
- Create: `harness/javabench_runner.py`
- Create: `harness/tests/test_javabench_runner.py`

- [ ] **Step 1: Write failing test**

```python
import json
from pathlib import Path
from unittest.mock import MagicMock, patch
from harness.javabench_runner import place_artifact, parse_pass_at_one

def test_place_artifact_writes_to_arm_specific_dir(tmp_path):
    place_artifact(
        javabench_root=tmp_path,
        arm="gt+jacoco",
        target_key="PA21-Method-foo",
        artifact_md="# Augmentation\n...",
    )
    expected = tmp_path / "datasets" / "gt-augment" / "gt+jacoco" / "PA21-Method-foo.txt"
    assert expected.exists()
    assert expected.read_text().startswith("# Augmentation")

def test_parse_pass_at_one_counts_passing():
    eval_out = {"PA21-A": {"pass": True}, "PA21-B": {"pass": False}, "PA21-C": {"pass": True}}
    assert parse_pass_at_one(eval_out) == 2 / 3
```

- [ ] **Step 2: Run, expect fail**

Run: `cd harness && python -m pytest tests/test_javabench_runner.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement javabench_runner**

Create `harness/javabench_runner.py`:

```python
import json
import subprocess
from pathlib import Path

def place_artifact(*, javabench_root: Path, arm: str, target_key: str,
                    artifact_md: str) -> None:
    arm_dir = Path(javabench_root) / "datasets" / "gt-augment" / arm
    arm_dir.mkdir(parents=True, exist_ok=True)
    (arm_dir / f"{target_key}.txt").write_text(artifact_md)

def run_javabench_inference(*, javabench_root: Path, dataset: str,
                              model: str) -> Path:
    """Returns the path to the inference output JSON."""
    cmd = ["python", "inference.py", "--dataset", dataset, "--model", model]
    subprocess.run(cmd, cwd=javabench_root, check=True)
    out = javabench_root / "output" / f"{dataset}-{model}.json"
    return out

def run_javabench_evaluation(*, javabench_root: Path, inference_output: Path) -> dict:
    """Returns parsed evaluation output: {target_key: {pass: bool, ...}}."""
    cmd = ["python", "evaluation.py", "--input", str(inference_output)]
    result = subprocess.run(cmd, cwd=javabench_root, check=True,
                            capture_output=True, text=True)
    return json.loads(result.stdout)

def parse_pass_at_one(eval_out: dict) -> float:
    if not eval_out:
        return 0.0
    passes = sum(1 for v in eval_out.values() if v.get("pass"))
    return passes / len(eval_out)
```

**Caveat:** `inference.py` and `evaluation.py` argument schemas are assumed. Before this task is final, audit `fixtures/JavaBench/inference.py` and `fixtures/JavaBench/evaluation.py` and adjust `cmd` lists to match. If their schemas don't permit a new dataset directory like `datasets/gt-augment/<arm>/`, add a thin patch in JavaBench (separate commit, separate concern). Mark the audit as a sub-bullet:

- [ ] **Step 3b: Audit JavaBench scripts**

Run: `head -50 fixtures/JavaBench/inference.py && head -50 fixtures/JavaBench/evaluation.py`

Adjust `run_javabench_inference` and `run_javabench_evaluation` argument strings to match. If schemas conflict (e.g., requires a registered dataset name in a manifest), patch JavaBench in a separate commit with a clear message.

- [ ] **Step 4: Run, expect pass**

Run: `cd harness && python -m pytest tests/test_javabench_runner.py -v`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/javabench_runner.py harness/tests/test_javabench_runner.py
git commit -m "feat(harness): javabench_runner — placement + inference/eval invocation"
```

---

### Task 16: metrics — bootstrap CI, McNemar, Wilcoxon

**Files:**
- Create: `harness/metrics.py`
- Create: `harness/tests/test_metrics.py`

- [ ] **Step 1: Write failing test**

```python
from harness.metrics import (
    bootstrap_ci_pass_at_one,
    mcnemar_test,
    wilcoxon_cycles,
)

def test_bootstrap_ci_pass_at_one_returns_tuple():
    successes = [True, True, False, True, True, False, True, True, True, True]
    lo, hi = bootstrap_ci_pass_at_one(successes, n_resamples=200, seed=42)
    assert 0.4 < lo < 0.9
    assert lo < hi
    assert hi <= 1.0

def test_mcnemar_returns_pvalue_and_effect():
    # Arm A passes where B fails 8 times; opposite 2 times → A clearly better.
    arm_a = [True] * 10 + [False] * 0
    arm_b = [False] * 8 + [True] * 2
    p, effect = mcnemar_test(arm_a, arm_b)
    assert p < 0.05
    assert effect > 0

def test_wilcoxon_cycles_detects_arm_better():
    a_cycles = [1, 1, 2, 1, 1, 2, 1]
    b_cycles = [5, 5, 5, 4, 5, 5, 4]
    p = wilcoxon_cycles(a_cycles, b_cycles)
    assert p < 0.05
```

- [ ] **Step 2: Run, expect fail**

Run: `cd harness && python -m pytest tests/test_metrics.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement metrics**

Create `harness/metrics.py`:

```python
import numpy as np
from scipy.stats import wilcoxon

def bootstrap_ci_pass_at_one(successes: list[bool], *, n_resamples: int = 1000,
                              ci: float = 0.95, seed: int = 0) -> tuple[float, float]:
    rng = np.random.default_rng(seed)
    arr = np.array([1 if s else 0 for s in successes], dtype=int)
    n = len(arr)
    if n == 0:
        return (0.0, 0.0)
    means = np.empty(n_resamples, dtype=float)
    for i in range(n_resamples):
        idx = rng.integers(0, n, size=n)
        means[i] = arr[idx].mean()
    alpha = (1 - ci) / 2
    return float(np.quantile(means, alpha)), float(np.quantile(means, 1 - alpha))

def mcnemar_test(arm_a: list[bool], arm_b: list[bool]) -> tuple[float, float]:
    """Paired binary outcomes per item. Returns (p_value, effect_size_diff_means)."""
    if len(arm_a) != len(arm_b):
        raise ValueError("paired arms must have same length")
    b = sum(1 for a, c in zip(arm_a, arm_b) if a and not c)
    c = sum(1 for a, d in zip(arm_a, arm_b) if not a and d)
    if b + c == 0:
        return (1.0, 0.0)
    # Exact binomial McNemar
    from scipy.stats import binomtest
    p = binomtest(min(b, c), b + c, p=0.5, alternative="two-sided").pvalue
    effect = (sum(arm_a) - sum(arm_b)) / len(arm_a)
    return (float(p), float(effect))

def wilcoxon_cycles(arm_a: list[int], arm_b: list[int]) -> float:
    if len(arm_a) != len(arm_b):
        raise ValueError("paired arms must have same length")
    diffs = [a - b for a, b in zip(arm_a, arm_b)]
    if all(d == 0 for d in diffs):
        return 1.0
    stat, p = wilcoxon(arm_a, arm_b, zero_method="zsplit")
    return float(p)
```

- [ ] **Step 4: Run, expect pass**

Run: `cd harness && python -m pytest tests/test_metrics.py -v`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/metrics.py harness/tests/test_metrics.py
git commit -m "feat(harness): metrics — bootstrap CI, McNemar, Wilcoxon"
```

---

### Task 17: report — Markdown + plots

**Files:**
- Create: `harness/report.py`
- Create: `harness/tests/test_report.py`

- [ ] **Step 1: Write failing test**

```python
from harness.report import render_report

def test_report_includes_all_arms_and_verdicts(tmp_path):
    results = {
        "no-context":        {"pass_at_one": 0.20, "pass_ci": (0.10, 0.30), "cycles_median": None, "convergence": None},
        "gt-current":        {"pass_at_one": 0.50, "pass_ci": (0.40, 0.60), "cycles_median": 3.0, "convergence": 0.85},
        "gt+jacoco":         {"pass_at_one": 0.62, "pass_ci": (0.52, 0.72), "cycles_median": 2.5, "convergence": 0.90},
        "gt+katz":           {"pass_at_one": 0.55, "pass_ci": (0.45, 0.65), "cycles_median": 2.8, "convergence": 0.88},
        "gt+jacoco+katz":    {"pass_at_one": 0.68, "pass_ci": (0.58, 0.78), "cycles_median": 2.0, "convergence": 0.95},
    }
    out = tmp_path / "report.md"
    render_report(results, out)
    text = out.read_text()
    for arm in results:
        assert arm in text
    assert "Hypothesis (a) JaCoCo" in text
    assert "Hypothesis (b) Katz" in text
```

- [ ] **Step 2: Run, expect fail**

Run: `cd harness && python -m pytest tests/test_report.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement report**

Create `harness/report.py`:

```python
from pathlib import Path

def render_report(results: dict, out: Path) -> None:
    rows = []
    for arm in ["no-context", "javabench-selective", "gt-current",
                "gt+jacoco", "gt+katz", "gt+jacoco+katz"]:
        if arm not in results:
            continue
        r = results[arm]
        rows.append(
            f"| {arm} | {r['pass_at_one']:.3f} | "
            f"[{r['pass_ci'][0]:.3f}, {r['pass_ci'][1]:.3f}] | "
            f"{r['cycles_median'] if r['cycles_median'] is not None else '-'} | "
            f"{r['convergence']:.2f}" if r.get('convergence') is not None else
            f"| {arm} | {r['pass_at_one']:.3f} | "
            f"[{r['pass_ci'][0]:.3f}, {r['pass_ci'][1]:.3f}] | - | -"
        )
    verdicts = _compute_verdicts(results)
    md = (
        "# Augmentation Eval Harness — Report\n\n"
        "## Verdicts\n"
        f"- Hypothesis (a) JaCoCo helps → {verdicts['jacoco']}\n"
        f"- Hypothesis (b) Katz helps → {verdicts['katz']}\n"
        f"- Additivity (gt+both ≥ max(gt+jacoco, gt+katz)) → {verdicts['additive']}\n"
        f"- Artifact validity (gt-current ≫ no-context) → {verdicts['validity']}\n\n"
        "## Table\n\n"
        "| arm | pass@1 | CI95 | cycles (median) | convergence |\n"
        "|---|---|---|---|---|\n"
        + "\n".join(rows) + "\n"
    )
    out.write_text(md)

def _compute_verdicts(results: dict) -> dict:
    def lift(a: str, baseline: str = "gt-current") -> tuple[float, bool]:
        if a not in results or baseline not in results:
            return (0.0, False)
        diff = results[a]["pass_at_one"] - results[baseline]["pass_at_one"]
        ci_a_lo = results[a]["pass_ci"][0]
        ci_b_hi = results[baseline]["pass_ci"][1]
        return (diff, diff >= 0.05 and ci_a_lo > ci_b_hi)

    _, jacoco_ok = lift("gt+jacoco")
    _, katz_ok = lift("gt+katz")
    _, both_ok = lift("gt+jacoco+katz")
    _, validity_ok = lift("gt-current", baseline="no-context")
    additive_ok = (
        "gt+jacoco+katz" in results
        and "gt+jacoco" in results
        and "gt+katz" in results
        and results["gt+jacoco+katz"]["pass_at_one"]
            >= max(results["gt+jacoco"]["pass_at_one"], results["gt+katz"]["pass_at_one"])
    )
    return {
        "jacoco": "confirmed" if jacoco_ok else "not confirmed",
        "katz": "confirmed" if katz_ok else "not confirmed",
        "additive": "yes" if additive_ok else "no",
        "validity": "yes" if validity_ok else "no",
    }
```

- [ ] **Step 4: Run, expect pass**

Run: `cd harness && python -m pytest tests/test_report.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/report.py harness/tests/test_report.py
git commit -m "feat(harness): report — Markdown table + verdicts"
```

---

### Task 18: orchestrator — top-level wiring

**Files:**
- Create: `harness/orchestrator.py`
- Create: `harness/arms.py`
- Create: `harness/tests/test_orchestrator.py`

- [ ] **Step 1: Write failing test (integration-shaped, no real LLM)**

```python
from unittest.mock import MagicMock, patch
from harness.orchestrator import collect_results_for_arms

def test_collect_results_returns_dict_keyed_by_arm():
    arm_outcomes = {
        "gt-current": {"pass_at_one": 0.5, "pass_ci": (0.4, 0.6),
                       "cycles_median": 3.0, "convergence": 0.85},
        "gt+jacoco": {"pass_at_one": 0.6, "pass_ci": (0.5, 0.7),
                      "cycles_median": 2.0, "convergence": 0.90},
    }
    with patch("harness.orchestrator.run_one_arm") as run:
        run.side_effect = lambda arm, **_: arm_outcomes[arm]
        out = collect_results_for_arms(
            arms=["gt-current", "gt+jacoco"],
            bench_cfg={"javabench_root": "fixtures/JavaBench", "standalone_targets": []},
        )
    assert set(out.keys()) == {"gt-current", "gt+jacoco"}
```

- [ ] **Step 2: Run, expect fail**

Run: `cd harness && python -m pytest tests/test_orchestrator.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement arms + orchestrator skeleton**

Create `harness/arms.py`:

```python
ALL_ARMS = ["no-context", "javabench-selective", "gt-current",
            "gt+jacoco", "gt+katz", "gt+jacoco+katz"]

STANDALONE_ARMS = [a for a in ALL_ARMS if a != "javabench-selective"]
```

Create `harness/orchestrator.py`:

```python
import argparse
from pathlib import Path
from harness.arms import ALL_ARMS
from harness.report import render_report

def run_one_arm(arm: str, **kwargs) -> dict:
    """Run a full pass for one arm: build artifacts, invoke LLM, run tests, aggregate.
       Real implementation calls javabench_runner + standalone_runner; here we expose
       the seam for tests to mock."""
    raise NotImplementedError("wired in Task 19")

def collect_results_for_arms(*, arms: list[str], bench_cfg: dict) -> dict:
    results = {}
    for arm in arms:
        results[arm] = run_one_arm(arm, bench_cfg=bench_cfg)
    return results

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--bench", default="all")
    p.add_argument("--arms", default="all")
    p.add_argument("--samples", type=int, default=5)
    p.add_argument("--out", type=Path, default=Path("harness/output"))
    args = p.parse_args()
    arms = ALL_ARMS if args.arms == "all" else args.arms.split(",")
    bench_cfg = {"javabench_root": "fixtures/JavaBench", "standalone_targets": []}
    results = collect_results_for_arms(arms=arms, bench_cfg=bench_cfg)
    args.out.mkdir(parents=True, exist_ok=True)
    render_report(results, args.out / "report.md")
    print(f"Report: {args.out / 'report.md'}")

if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run, expect pass**

Run: `cd harness && python -m pytest tests/test_orchestrator.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/arms.py harness/orchestrator.py harness/tests/test_orchestrator.py
git commit -m "feat(harness): orchestrator + arms — top-level wiring (run_one_arm stubbed)"
```

---

## Phase 3 — Pilot smoke run (Task 19)

### Task 19: Wire run_one_arm + end-to-end pilot smoke on one target

**Files:**
- Modify: `harness/orchestrator.py` — flesh out `run_one_arm`
- Create: `harness/tests/test_pilot_smoke.py` — runs the full pipeline on 1 method, 1 sample (uses real graph-tipper + real gradle + a stubbed LLM that returns a known-good body)

- [ ] **Step 1: Pick a single target + write the stub LLM body**

In `harness/tests/test_pilot_smoke.py`:

```python
import os
import pytest
from pathlib import Path
from unittest.mock import MagicMock
from harness.orchestrator import collect_results_for_arms
from harness import orchestrator

PICOCLI_ROOT = os.environ.get("PICOCLI_ROOT")  # set by CI / dev

@pytest.mark.skipif(not PICOCLI_ROOT, reason="set PICOCLI_ROOT to run smoke")
def test_pilot_smoke_one_target_all_arms(tmp_path, monkeypatch):
    # Stub LLM with the *actual* putValue body so we test pipeline plumbing,
    # not generation quality.
    real_body = Path(PICOCLI_ROOT, "src/main/java/picocli/CommandLine.java") \
        .read_text().split("void putValue(int row, int col, Text value)")[1] \
        .split("}", 1)[0] + "}"
    fake_llm = MagicMock()
    fake_llm.complete.return_value = real_body
    monkeypatch.setattr(orchestrator, "make_llm_provider", lambda: fake_llm)
    results = collect_results_for_arms(
        arms=["no-context", "gt-current", "gt+jacoco", "gt+katz", "gt+jacoco+katz"],
        bench_cfg={"javabench_root": None, "standalone_targets": [{
            "id": "picocli-putvalue",
            "project_dir": PICOCLI_ROOT,
            "target_spec": "src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)",
            "test_filter": "picocli.TextTableTest",
        }]},
    )
    for arm, r in results.items():
        assert r["convergence"] >= 0.0  # smoke: pipeline ran
```

- [ ] **Step 2: Implement `run_one_arm` for the standalone path**

In `harness/orchestrator.py`, replace `run_one_arm`:

```python
import subprocess
from harness.artifact_builder import build_arm_command
from harness.standalone_runner import run_cycles_to_green
from harness.llm_provider import LLMProvider
from harness.metrics import bootstrap_ci_pass_at_one
import anthropic

def make_llm_provider() -> LLMProvider:
    return LLMProvider(client=anthropic.Anthropic(), model="claude-sonnet-4-6")

def run_one_arm(arm: str, *, bench_cfg: dict) -> dict:
    llm = make_llm_provider()
    cycles = []
    successes = []
    for target in bench_cfg.get("standalone_targets", []):
        out_dir = Path("/tmp/gt-eval") / arm / target["id"]
        out_dir.mkdir(parents=True, exist_ok=True)
        exec_xml = bench_cfg.get("exec_xml")  # collected once by smoke task; None on no-context
        cmd = build_arm_command(
            graph_tipper_bin="build/install/graph-tipper/bin/graph-tipper",
            project_dir=target["project_dir"],
            target_spec=target["target_spec"],
            out_dir=out_dir, arm=arm, exec_xml_path=exec_xml,
        )
        subprocess.run(cmd, check=True)
        artifact_md = next(out_dir.glob("*.budget.md")).read_text()
        signature = _read_signature(target)
        def write_body(b): _write_body(target, b)
        def compile_and_test(): return _gradle_test(target)
        res = run_cycles_to_green(
            llm=llm, system="You write Java method bodies.",
            artifact=artifact_md, signature=signature,
            write_body=write_body, compile_and_test=compile_and_test, cap=5,
        )
        cycles.append(res.cycles)
        successes.append(res.status == "green")
    pass_at_one = sum(successes) / max(1, len(successes))
    pass_ci = bootstrap_ci_pass_at_one(successes) if successes else (0.0, 0.0)
    cycles_median = sorted(cycles)[len(cycles)//2] if cycles else None
    convergence = sum(1 for c in cycles if c < 5) / max(1, len(cycles))
    return {"pass_at_one": pass_at_one, "pass_ci": pass_ci,
            "cycles_median": cycles_median, "convergence": convergence}

def _read_signature(target: dict) -> str:
    # Extract signature line from target file by parsing target_spec.
    raise NotImplementedError("helper — extract signature line from target_spec")

def _write_body(target: dict, body: str) -> None:
    raise NotImplementedError("helper — splice body into target's source file")

def _gradle_test(target: dict) -> tuple[str, str]:
    raise NotImplementedError("helper — run gradle test with target['test_filter'] and parse outcome")
```

- [ ] **Step 3: Implement the three helpers**

Add to `harness/orchestrator.py`:

```python
import re
import shutil

def _parse_target_spec(spec: str) -> tuple[str, str, str]:
    """Returns (file_path, simple_class, method_with_params).
    Accepts 'path/Foo.java#Foo.method(int,Text)' form used by graph-tipper."""
    file_part, frag = spec.split("#", 1)
    cls, method = frag.split(".", 1)  # 'Foo', 'method(int,Text)'
    return file_part, cls, method

def _read_signature(target: dict) -> str:
    file_part, _, method = _parse_target_spec(target["target_spec"])
    method_name = method.split("(", 1)[0]
    full = Path(target["project_dir"]) / file_part
    text = full.read_text()
    # First line containing the method name followed by '(' that is not inside a comment.
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        if re.search(rf"\b{re.escape(method_name)}\s*\(", stripped):
            return stripped.rstrip("{").strip()
    raise RuntimeError(f"signature not found for {target['target_spec']}")

def _backup_path(target: dict) -> Path:
    file_part, _, _ = _parse_target_spec(target["target_spec"])
    return Path(target["project_dir"]) / (file_part + ".orig")

def _write_body(target: dict, body: str) -> None:
    file_part, _, method = _parse_target_spec(target["target_spec"])
    method_name = method.split("(", 1)[0]
    src = Path(target["project_dir"]) / file_part
    backup = _backup_path(target)
    if not backup.exists():
        shutil.copy2(src, backup)
    text = backup.read_text()  # always splice into the original, not the previous attempt
    # Find the signature line, then balance braces from there.
    sig_match = re.search(
        rf"^[^\n]*\b{re.escape(method_name)}\s*\([^)]*\)[^{{]*{{", text, re.MULTILINE)
    if not sig_match:
        raise RuntimeError(f"method body open-brace not found for {method_name}")
    brace_open = sig_match.end() - 1  # index of '{'
    depth = 0
    i = brace_open
    while i < len(text):
        c = text[i]
        if c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                brace_close = i
                break
        i += 1
    else:
        raise RuntimeError("unbalanced braces")
    body_stripped = body.strip()
    if body_stripped.startswith("{") and body_stripped.endswith("}"):
        body_stripped = body_stripped[1:-1].strip()
    new_text = text[: brace_open + 1] + "\n" + body_stripped + "\n" + text[brace_close:]
    src.write_text(new_text)

def _restore_source(target: dict) -> None:
    backup = _backup_path(target)
    if backup.exists():
        file_part, _, _ = _parse_target_spec(target["target_spec"])
        shutil.copy2(backup, Path(target["project_dir"]) / file_part)

def _gradle_test(target: dict) -> tuple[str, str]:
    proc = subprocess.run(
        ["./gradlew", "test", "--tests", target["test_filter"]],
        cwd=target["project_dir"], capture_output=True, text=True)
    if proc.returncode == 0:
        return ("green", "")
    combined = (proc.stdout or "") + "\n" + (proc.stderr or "")
    if "error: " in combined and "compileJava" in combined:
        return ("compile_error", _tail(combined, 30))
    return ("red", _tail(combined, 60))

def _tail(s: str, n: int) -> str:
    lines = s.splitlines()
    return "\n".join(lines[-n:])
```

Then update `run_one_arm` to call `_restore_source(target)` in a `finally` after each target's loop, so we always leave the picocli checkout untouched.

- [ ] **Step 4: Run smoke test**

Run: `PICOCLI_ROOT=/tmp/picocli cd harness && python -m pytest tests/test_pilot_smoke.py -v`
Expected (first run): probably FAIL on first try. Iterate fixes — the smoke test's purpose is to surface plumbing bugs before the real pilot.

- [ ] **Step 5: Restore picocli source after smoke**

Add a `pytest` fixture that backs up `CommandLine.java` before the run and restores it after.

- [ ] **Step 6: Commit**

```bash
git add harness/orchestrator.py harness/tests/test_pilot_smoke.py
git commit -m "feat(harness): orchestrator.run_one_arm + pilot smoke test"
```

---

## Followups (not blocking; out of plan)

- **ChopPipeline programmatic API** — Task 9 assumes `com.graphtipper.chop.cli.ChopPipeline` has a `buildForTarget` method. If the current chop CLI only exposes a `Callable<Integer>` shape, add a thin programmatic builder before running the gt+katz arm against real projects. Tracked as a separate small task.
- **JavaBench inference.py schema compat** — Task 15 step 3b. Audit + adjust before running Pilot-1.
- **Per-method JavaBench evaluation** — if `evaluation.py` doesn't expose per-method pass/fail, extend it. Tracked separately.

---

## Self-review

(performed after writing; fixes inlined above)

Spec coverage:
- 6 arms — covered by Tasks 4, 7, 8, 9 (`--bare`), 9 (`--prune-by-coverage`, `--katz-rank`).
- JaCoCo target-range leakage handling — Task 2.
- Katz on chop graph with α=0.01 — Task 5.
- Cycles-to-green cap=5 with full feedback — Task 14.
- JavaBench plug-in — Task 15.
- Bootstrap CI + McNemar + Wilcoxon — Task 16.
- Report with verdict block — Task 17.
- Pilot smoke — Task 19.

Type consistency: `RenderOptions` ↔ `MarkdownRenderer` ↔ `SnippetCoveragePruner` ↔ `KatzScorer`. `CycleResult` ↔ `run_cycles_to_green`. `build_arm_command` arm names match `ALL_ARMS` in `arms.py`. Verified.

Constructor signatures cross-checked against current source:
- `Node.Method` — 12-arg record at `model/Node.java:15-27`. Tests in Tasks 4 & 8 use the full 12-arg form.
- `LocalContext` — 2-arg record at `slice/LocalContext.java:7-12`. Tests in Tasks 4 & 8 use 2 args.
- `MethodRef` — 2-arg record at `chop/model/MethodRef.java`. All uses corrected.
- `MethodNode` — 4-arg record (`owner`, `isTest`, `isTarget`, `touchedBy`). Task 5 uses full form.
- `ChopEdge` — 7-arg record including `DataKind` and `label`. Task 5 uses full form with `DataKind.DEF_USE` placeholder for control edges.
- `EdgeLayer` values: `AST, CFG, CDG, DDG, CG, OVERRIDES, ARG_PASS, RETURN_BIND`. Task 5 uses `CG`.
- `ResolutionKind` values: `EXACT, CHA, UNKNOWN`. Task 5 uses `EXACT`.
- `PathSignature.fqns()` accessor (not `calleeFqns()`).

Followups (not blocking):
- Task 9 makes assumption about `ChopPipeline.buildForTarget`; engineer must confirm or add the programmatic builder.
- Task 15 assumes JavaBench `inference.py` / `evaluation.py` accept a new dataset directory; audit step 3b is in the plan.
- Task 6 step 3 asks engineer to locate the existing cluster-list reference inside `evictLowRankAndSingletonClusters` — exact line number depends on current BudgetPlanner state, not pinned in the plan.
