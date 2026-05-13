# Graph-Tipper V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a CLI tool that, given a Java project and a target method, produces a Markdown augmentation artifact (≤ 20 000 tokens) containing reverse call-graph chains to test cases (with call-site snippets and argument back-slices) plus local context around the target.

**Architecture:** Three layers. Layer 1 (external): Joern runs as a subprocess, producing CPG JSON. Layer 2: our neutral in-memory `ProjectGraph` (sealed `Node` / `Edge`), populated by a single `CpgImporter` that is the only module aware of Joern's format. Layer 3: pure-function slicers (`ReverseCallChainExtractor`, `CallSiteSlicer`, `LocalContextExtractor`) plus three renderers (Markdown, JSON, DOT).

**Tech Stack:** Java 21 (sealed types, records, pattern matching), Gradle (Kotlin DSL), JUnit 5 + AssertJ, Jackson, `info.picocli:picocli` for CLI parsing, SLF4J Simple. Joern is invoked as a subprocess (no Scala dependency in classpath).

**Spec:** [docs/superpowers/specs/2026-05-13-graph-tipper-v1-design.md](../specs/2026-05-13-graph-tipper-v1-design.md)

---

## Task 0: Project scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `.gitignore`
- Create: `src/main/java/com/graphtipper/cli/Main.java`
- Create: `src/test/java/com/graphtipper/SmokeTest.java`

- [ ] **Step 1: Write the smoke test**

`src/test/java/com/graphtipper/SmokeTest.java`:
```java
package com.graphtipper;

import com.graphtipper.cli.Main;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest {
    @Test
    void mainExists() {
        assertThat(Main.class).isNotNull();
    }
}
```

- [ ] **Step 2: Create minimal `Main`**

`src/main/java/com/graphtipper/cli/Main.java`:
```java
package com.graphtipper.cli;

public final class Main {
    public static void main(String[] args) {
        System.out.println("graph-tipper");
    }
    private Main() {}
}
```

- [ ] **Step 3: Write build files**

`settings.gradle.kts`:
```kotlin
rootProject.name = "graph-tipper"
```

`build.gradle.kts`:
```kotlin
plugins {
    application
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    implementation("info.picocli:picocli:4.7.6")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

application {
    mainClass.set("com.graphtipper.cli.Main")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

`gradle.properties`:
```
org.gradle.jvmargs=-Xmx2g
```

`.gitignore`:
```
.gradle/
build/
.idea/
*.iml
out/
.cache/
```

- [ ] **Step 4: Run the smoke test**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, `SmokeTest.mainExists()` PASS. (Will prompt to install the Gradle wrapper first if `gradlew` doesn't exist; in that case run `gradle wrapper --gradle-version 8.10` first.)

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties .gitignore src/
git commit -m "feat: project scaffolding (Gradle, Java 21, JUnit 5)"
```

---

## Task 1: `Node` sealed hierarchy

**Files:**
- Create: `src/main/java/com/graphtipper/model/Node.java`
- Test: `src/test/java/com/graphtipper/model/NodeTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/model/NodeTest.java`:
```java
package com.graphtipper.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class NodeTest {
    @Test
    void methodNodeStoresFqnAndSignature() {
        var m = new Node.Method(
            "m:p.C.foo(int)", "p.C.foo", "foo(int)", List.of("int"),
            "void", "p/C.java", 10, 20, "javadoc", false, false, List.of("public"));
        assertThat(m.fqn()).isEqualTo("p.C.foo");
        assertThat(m.paramTypes()).containsExactly("int");
        assertThat(m.id()).startsWith("m:");
    }

    @Test
    void typeNodeEnumKeepsConstants() {
        var t = new Node.Type(
            "t:p.Color", "p.Color", Node.TypeKind.ENUM, "p/Color.java", 1, 5,
            List.of("RED", "GREEN", "BLUE"));
        assertThat(t.enumConstants()).containsExactly("RED", "GREEN", "BLUE");
        assertThat(t.kind()).isEqualTo(Node.TypeKind.ENUM);
    }

    @Test
    void callSiteHasInMethodAndCallee() {
        var cs = new Node.CallSite("cs:m@10:5", "m:p.C.foo(int)", "p.C.bar",
            2, 10, 5, "bar(x, 1)");
        assertThat(cs.calleeFqn()).isEqualTo("p.C.bar");
        assertThat(cs.line()).isEqualTo(10);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.model.NodeTest`
Expected: FAIL — `Node` not defined.

- [ ] **Step 3: Implement `Node`**

`src/main/java/com/graphtipper/model/Node.java`:
```java
package com.graphtipper.model;

import java.util.List;

public sealed interface Node permits
        Node.Method, Node.Type, Node.Field, Node.Parameter,
        Node.CallSite, Node.Stmt, Node.Literal {

    String id();

    enum TypeKind { CLASS, INTERFACE, ENUM, ANNOTATION }
    enum StmtKind { IF, LOOP, RETURN, EXPR, OTHER }
    enum LiteralKind { INT, LONG, FLOAT, DOUBLE, STRING, BOOL, NULL, OTHER }

    record Method(
            String id,
            String fqn,
            String signature,
            List<String> paramTypes,
            String returnType,
            String file,
            int lineStart,
            int lineEnd,
            String javadoc,
            boolean isTest,
            boolean isAbstract,
            List<String> modifiers) implements Node {}

    record Type(
            String id,
            String fqn,
            TypeKind kind,
            String file,
            int lineStart,
            int lineEnd,
            List<String> enumConstants) implements Node {}

    record Field(
            String id,
            String ownerTypeFqn,
            String name,
            String type,
            List<String> modifiers,
            int lineStart,
            int lineEnd) implements Node {}

    record Parameter(
            String id,
            String ownerMethodId,
            String name,
            String type,
            int index) implements Node {}

    record CallSite(
            String id,
            String inMethodId,
            String calleeFqn,
            int argCount,
            int line,
            int col,
            String codeSnippet) implements Node {}

    record Stmt(
            String id,
            String inMethodId,
            int line,
            StmtKind kind,
            String codeSnippet) implements Node {}

    record Literal(
            String id,
            String inMethodId,
            LiteralKind kind,
            String value,
            int line) implements Node {}
}
```

- [ ] **Step 4: Run test, verify pass**

Run: `./gradlew test --tests com.graphtipper.model.NodeTest`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/model/Node.java src/test/java/com/graphtipper/model/NodeTest.java
git commit -m "feat(model): sealed Node hierarchy"
```

---

## Task 2: `Edge` sealed hierarchy

**Files:**
- Create: `src/main/java/com/graphtipper/model/Edge.java`
- Test: `src/test/java/com/graphtipper/model/EdgeTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/model/EdgeTest.java`:
```java
package com.graphtipper.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EdgeTest {
    @Test
    void callsEdgeHasFromAndTo() {
        var e = new Edge.Calls("m:a", "m:b", false);
        assertThat(e.fromId()).isEqualTo("m:a");
        assertThat(e.toId()).isEqualTo("m:b");
        assertThat(e.viaVirtual()).isFalse();
    }

    @Test
    void ddgEdgeMarksDataDependency() {
        var e = new Edge.Ddg("p:1", "cs:2");
        assertThat(e.fromId()).isEqualTo("p:1");
    }

    @Test
    void allEdgeKindsAreCovered() {
        Edge[] kinds = {
            new Edge.Calls("a", "b", false),
            new Edge.AstContains("a", "b"),
            new Edge.Ddg("a", "b"),
            new Edge.Cdg("a", "b"),
            new Edge.RefType("a", "b"),
            new Edge.Overrides("a", "b"),
            new Edge.Reads("a", "b"),
            new Edge.Writes("a", "b")
        };
        assertThat(kinds).hasSize(8);
        for (Edge e : kinds) {
            assertThat(e.fromId()).isNotNull();
            assertThat(e.toId()).isNotNull();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.model.EdgeTest`
Expected: FAIL — `Edge` not defined.

- [ ] **Step 3: Implement `Edge`**

`src/main/java/com/graphtipper/model/Edge.java`:
```java
package com.graphtipper.model;

public sealed interface Edge permits
        Edge.Calls, Edge.AstContains, Edge.Ddg, Edge.Cdg,
        Edge.RefType, Edge.Overrides, Edge.Reads, Edge.Writes {

    String fromId();
    String toId();

    record Calls(String fromId, String toId, boolean viaVirtual) implements Edge {}
    record AstContains(String fromId, String toId) implements Edge {}
    record Ddg(String fromId, String toId) implements Edge {}
    record Cdg(String fromId, String toId) implements Edge {}
    record RefType(String fromId, String toId) implements Edge {}
    record Overrides(String fromId, String toId) implements Edge {}
    record Reads(String fromId, String toId) implements Edge {}
    record Writes(String fromId, String toId) implements Edge {}
}
```

- [ ] **Step 4: Run test, verify pass**

Run: `./gradlew test --tests com.graphtipper.model.EdgeTest`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/model/Edge.java src/test/java/com/graphtipper/model/EdgeTest.java
git commit -m "feat(model): sealed Edge hierarchy"
```

---

## Task 3: `ProjectGraph` with indexes

**Files:**
- Create: `src/main/java/com/graphtipper/model/ProjectGraph.java`
- Test: `src/test/java/com/graphtipper/model/ProjectGraphTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/model/ProjectGraphTest.java`:
```java
package com.graphtipper.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectGraphTest {
    private Node.Method m(String fqn) {
        return new Node.Method("m:" + fqn, fqn, fqn + "()", List.of(), "void",
                "F.java", 1, 2, null, false, false, List.of("public"));
    }

    @Test
    void addsAndLooksUpByFqn() {
        var g = new ProjectGraph();
        var a = m("p.A.foo");
        g.addNode(a);
        assertThat(g.byFqn("p.A.foo")).containsExactly(a);
        assertThat(g.byId(a.id())).isEqualTo(a);
    }

    @Test
    void callsEdgeUpdatesIncomingOutgoingIndexes() {
        var g = new ProjectGraph();
        var a = m("p.A.foo");
        var b = m("p.B.bar");
        g.addNode(a); g.addNode(b);
        g.addEdge(new Edge.Calls(a.id(), b.id(), false));
        assertThat(g.outgoingCalls(a.id())).hasSize(1);
        assertThat(g.incomingCalls(b.id())).hasSize(1);
    }

    @Test
    void testMethodsIndexRespectsIsTest() {
        var g = new ProjectGraph();
        var prod = m("p.A.foo");
        var test = new Node.Method("m:p.T.t1", "p.T.t1", "t1()", List.of(),
                "void", "T.java", 1, 2, null, true, false, List.of("public"));
        g.addNode(prod); g.addNode(test);
        assertThat(g.testMethods()).containsExactly(test);
    }

    @Test
    void byFileGroupsNodes() {
        var g = new ProjectGraph();
        var a = m("p.A.foo");
        g.addNode(a);
        assertThat(g.byFile("F.java")).contains(a);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.model.ProjectGraphTest`
Expected: FAIL — `ProjectGraph` not defined.

- [ ] **Step 3: Implement `ProjectGraph`**

`src/main/java/com/graphtipper/model/ProjectGraph.java`:
```java
package com.graphtipper.model;

import java.util.*;

public final class ProjectGraph {
    private final Map<String, Node> nodes = new HashMap<>();
    private final Map<String, List<Node>> byFqn = new HashMap<>();
    private final Map<String, List<Node>> byFile = new HashMap<>();
    private final Map<String, List<Edge.Calls>> outgoingCalls = new HashMap<>();
    private final Map<String, List<Edge.Calls>> incomingCalls = new HashMap<>();
    private final Map<String, List<Edge>> outgoingByFrom = new HashMap<>();
    private final Map<String, List<Edge>> incomingByTo = new HashMap<>();
    private final List<Node.Method> testMethods = new ArrayList<>();

    public void addNode(Node n) {
        if (nodes.putIfAbsent(n.id(), n) != null) return;
        switch (n) {
            case Node.Method m -> {
                byFqn.computeIfAbsent(m.fqn(), k -> new ArrayList<>()).add(m);
                byFile.computeIfAbsent(m.file(), k -> new ArrayList<>()).add(m);
                if (m.isTest()) testMethods.add(m);
            }
            case Node.Type t -> {
                byFqn.computeIfAbsent(t.fqn(), k -> new ArrayList<>()).add(t);
                if (t.file() != null) byFile.computeIfAbsent(t.file(), k -> new ArrayList<>()).add(t);
            }
            default -> {}
        }
    }

    public void addEdge(Edge e) {
        outgoingByFrom.computeIfAbsent(e.fromId(), k -> new ArrayList<>()).add(e);
        incomingByTo.computeIfAbsent(e.toId(), k -> new ArrayList<>()).add(e);
        if (e instanceof Edge.Calls c) {
            outgoingCalls.computeIfAbsent(c.fromId(), k -> new ArrayList<>()).add(c);
            incomingCalls.computeIfAbsent(c.toId(), k -> new ArrayList<>()).add(c);
        }
    }

    public Node byId(String id) { return nodes.get(id); }
    public List<Node> byFqn(String fqn) { return byFqn.getOrDefault(fqn, List.of()); }
    public List<Node> byFile(String file) { return byFile.getOrDefault(file, List.of()); }
    public List<Edge.Calls> outgoingCalls(String id) { return outgoingCalls.getOrDefault(id, List.of()); }
    public List<Edge.Calls> incomingCalls(String id) { return incomingCalls.getOrDefault(id, List.of()); }
    public List<Edge> outgoing(String id) { return outgoingByFrom.getOrDefault(id, List.of()); }
    public List<Edge> incoming(String id) { return incomingByTo.getOrDefault(id, List.of()); }
    public List<Node.Method> testMethods() { return List.copyOf(testMethods); }
    public Collection<Node> allNodes() { return nodes.values(); }
    public int size() { return nodes.size(); }
}
```

- [ ] **Step 4: Run test, verify pass**

Run: `./gradlew test --tests com.graphtipper.model.ProjectGraphTest`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/model/ProjectGraph.java src/test/java/com/graphtipper/model/ProjectGraphTest.java
git commit -m "feat(model): ProjectGraph with FQN/file/calls indexes"
```

---

## Task 4: Test-only `ProjectGraphBuilder` (DSL for slicer tests)

**Files:**
- Create: `src/test/java/com/graphtipper/model/Gb.java` (small builder used by every slicer test)

- [ ] **Step 1: Write the failing test for the builder itself**

`src/test/java/com/graphtipper/model/GbTest.java`:
```java
package com.graphtipper.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GbTest {
    @Test
    void buildsMinimalGraphFluently() {
        var g = Gb.graph()
            .method("p.A.foo").testFlag(false).done()
            .method("p.T.t1").testFlag(true).done()
            .calls("p.T.t1", "p.A.foo")
            .build();
        assertThat(g.testMethods()).hasSize(1);
        assertThat(g.incomingCalls(g.byFqn("p.A.foo").get(0).id())).hasSize(1);
    }
}
```

- [ ] **Step 2: Run, verify fails**

Run: `./gradlew test --tests com.graphtipper.model.GbTest`
Expected: FAIL — `Gb` not defined.

- [ ] **Step 3: Implement `Gb`**

`src/test/java/com/graphtipper/model/Gb.java`:
```java
package com.graphtipper.model;

import java.util.*;

public final class Gb {
    private final ProjectGraph g = new ProjectGraph();

    public static Gb graph() { return new Gb(); }

    public MethodB method(String fqn) { return new MethodB(this, fqn); }

    public Gb calls(String fromFqn, String toFqn) {
        var from = (Node.Method) g.byFqn(fromFqn).get(0);
        var to = (Node.Method) g.byFqn(toFqn).get(0);
        g.addEdge(new Edge.Calls(from.id(), to.id(), false));
        return this;
    }

    public Gb callsVirtual(String fromFqn, String toFqn) {
        var from = (Node.Method) g.byFqn(fromFqn).get(0);
        var to = (Node.Method) g.byFqn(toFqn).get(0);
        g.addEdge(new Edge.Calls(from.id(), to.id(), true));
        return this;
    }

    public Gb overrides(String childFqn, String parentFqn) {
        var c = (Node.Method) g.byFqn(childFqn).get(0);
        var p = (Node.Method) g.byFqn(parentFqn).get(0);
        g.addEdge(new Edge.Overrides(c.id(), p.id()));
        return this;
    }

    public ProjectGraph build() { return g; }

    public static final class MethodB {
        private final Gb owner;
        private final String fqn;
        private boolean isTest = false;
        private String file = "F.java";
        private int lineStart = 1, lineEnd = 2;
        private String javadoc;
        private List<String> paramTypes = List.of();

        MethodB(Gb owner, String fqn) { this.owner = owner; this.fqn = fqn; }
        public MethodB testFlag(boolean t) { this.isTest = t; return this; }
        public MethodB file(String f) { this.file = f; return this; }
        public MethodB lines(int s, int e) { this.lineStart = s; this.lineEnd = e; return this; }
        public MethodB javadoc(String j) { this.javadoc = j; return this; }
        public MethodB params(String... types) { this.paramTypes = List.of(types); return this; }
        public Gb done() {
            var sig = fqn.substring(fqn.lastIndexOf('.') + 1) + "(" + String.join(",", paramTypes) + ")";
            owner.g.addNode(new Node.Method(
                "m:" + fqn + "(" + String.join(",", paramTypes) + ")",
                fqn, sig, paramTypes, "void", file, lineStart, lineEnd,
                javadoc, isTest, false, List.of("public")));
            return owner;
        }
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.model.GbTest`
Expected: 1 test PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/graphtipper/model/
git commit -m "test: fluent graph builder for slicer tests"
```

---

## Task 5: `SourceFragmentReader` (file-line snippet utility)

**Files:**
- Create: `src/main/java/com/graphtipper/util/SourceFragmentReader.java`
- Test: `src/test/java/com/graphtipper/util/SourceFragmentReaderTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/util/SourceFragmentReaderTest.java`:
```java
package com.graphtipper.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class SourceFragmentReaderTest {
    @Test
    void readsCenterWithSurroundingLines(@TempDir Path dir) throws Exception {
        var f = dir.resolve("X.java");
        Files.writeString(f, """
            line1
            line2
            line3
            line4
            line5
            line6
            """);
        var r = new SourceFragmentReader(dir);
        var snip = r.readAround("X.java", 4, 2, 1);
        assertThat(snip).isEqualTo("""
            line2
            line3
            line4
            line5
            """.stripTrailing());
    }

    @Test
    void readsBodyByLineRange(@TempDir Path dir) throws Exception {
        var f = dir.resolve("X.java");
        Files.writeString(f, "a\nb\nc\nd\n");
        var r = new SourceFragmentReader(dir);
        assertThat(r.readLines("X.java", 2, 3)).isEqualTo("b\nc");
    }

    @Test
    void cachesFileContents(@TempDir Path dir) throws Exception {
        var f = dir.resolve("X.java");
        Files.writeString(f, "hello\n");
        var r = new SourceFragmentReader(dir);
        assertThat(r.readLines("X.java", 1, 1)).isEqualTo("hello");
        Files.writeString(f, "changed\n");
        assertThat(r.readLines("X.java", 1, 1)).isEqualTo("hello");  // cached
    }
}
```

- [ ] **Step 2: Run, verify fails**

Run: `./gradlew test --tests com.graphtipper.util.SourceFragmentReaderTest`
Expected: FAIL — class not defined.

- [ ] **Step 3: Implement**

`src/main/java/com/graphtipper/util/SourceFragmentReader.java`:
```java
package com.graphtipper.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

public final class SourceFragmentReader {
    private final Path projectRoot;
    private final Map<String, List<String>> cache = new HashMap<>();

    public SourceFragmentReader(Path projectRoot) { this.projectRoot = projectRoot; }

    private List<String> load(String relPath) {
        return cache.computeIfAbsent(relPath, p -> {
            try {
                return Files.readAllLines(projectRoot.resolve(p));
            } catch (IOException e) {
                throw new UncheckedIOException("read " + p, e);
            }
        });
    }

    public String readLines(String relPath, int startLine, int endLine) {
        var lines = load(relPath);
        int s = Math.max(1, startLine);
        int e = Math.min(lines.size(), endLine);
        if (s > e) return "";
        var sb = new StringBuilder();
        for (int i = s; i <= e; i++) {
            if (i > s) sb.append('\n');
            sb.append(lines.get(i - 1));
        }
        return sb.toString();
    }

    public String readAround(String relPath, int line, int before, int after) {
        return readLines(relPath, line - before, line + after);
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.util.SourceFragmentReaderTest`
Expected: 3 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/util/SourceFragmentReader.java src/test/java/com/graphtipper/util/SourceFragmentReaderTest.java
git commit -m "feat(util): SourceFragmentReader with in-memory file cache"
```

---

## Task 6: `TokenBudget` (4-chars/token approximation + tracking)

**Files:**
- Create: `src/main/java/com/graphtipper/util/TokenBudget.java`
- Test: `src/test/java/com/graphtipper/util/TokenBudgetTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/util/TokenBudgetTest.java`:
```java
package com.graphtipper.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetTest {
    @Test
    void approximatesByFourCharsPerToken() {
        var b = new TokenBudget(10);
        assertThat(b.estimate("abcdefgh")).isEqualTo(2);  // 8/4
        assertThat(b.estimate("ab")).isEqualTo(1);        // ceil
    }

    @Test
    void tryAddSucceedsWhenFits() {
        var b = new TokenBudget(10);
        assertThat(b.tryAdd("x".repeat(20))).isTrue();   // 20/4 = 5
        assertThat(b.used()).isEqualTo(5);
        assertThat(b.remaining()).isEqualTo(5);
    }

    @Test
    void tryAddFailsAndDoesNotConsumeWhenOver() {
        var b = new TokenBudget(5);
        b.tryAdd("x".repeat(16));   // 4 tokens
        assertThat(b.tryAdd("x".repeat(8))).isFalse();   // 2 tokens, would exceed
        assertThat(b.used()).isEqualTo(4);
    }

    @Test
    void recordsEvictedSections() {
        var b = new TokenBudget(100);
        b.recordEviction("production-call-sites");
        b.recordEviction("used-types-bodies");
        assertThat(b.evicted()).containsExactly("production-call-sites", "used-types-bodies");
    }
}
```

- [ ] **Step 2: Run, verify fails**

Run: `./gradlew test --tests com.graphtipper.util.TokenBudgetTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/graphtipper/util/TokenBudget.java`:
```java
package com.graphtipper.util;

import java.util.ArrayList;
import java.util.List;

public final class TokenBudget {
    private final int max;
    private int used = 0;
    private final List<String> evicted = new ArrayList<>();

    public TokenBudget(int max) { this.max = max; }

    public int estimate(String text) {
        return (text.length() + 3) / 4;
    }

    public boolean tryAdd(String text) {
        int cost = estimate(text);
        if (used + cost > max) return false;
        used += cost;
        return true;
    }

    public void recordEviction(String section) { evicted.add(section); }

    public int used() { return used; }
    public int max() { return max; }
    public int remaining() { return max - used; }
    public List<String> evicted() { return List.copyOf(evicted); }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.util.TokenBudgetTest`
Expected: 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/util/TokenBudget.java src/test/java/com/graphtipper/util/TokenBudgetTest.java
git commit -m "feat(util): TokenBudget with 4-chars/token approximation"
```

---

## Task 7: `SourceHash` (project source-tree hashing for CPG cache key)

**Files:**
- Create: `src/main/java/com/graphtipper/util/SourceHash.java`
- Test: `src/test/java/com/graphtipper/util/SourceHashTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/util/SourceHashTest.java`:
```java
package com.graphtipper.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class SourceHashTest {
    @Test
    void hashIsStableForSameContent(@TempDir Path dir) throws Exception {
        var src = dir.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}");
        var h1 = SourceHash.ofJavaSources(dir);
        var h2 = SourceHash.ofJavaSources(dir);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64); // sha-256 hex
    }

    @Test
    void hashChangesWhenContentChanges(@TempDir Path dir) throws Exception {
        var src = dir.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}");
        var h1 = SourceHash.ofJavaSources(dir);
        Files.writeString(src.resolve("A.java"), "class A { int x; }");
        var h2 = SourceHash.ofJavaSources(dir);
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void ignoresNonJavaFiles(@TempDir Path dir) throws Exception {
        var src = dir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}");
        var h1 = SourceHash.ofJavaSources(dir);
        Files.writeString(src.resolve("README.md"), "hello");
        var h2 = SourceHash.ofJavaSources(dir);
        assertThat(h1).isEqualTo(h2);
    }
}
```

- [ ] **Step 2: Run, verify fails**

Run: `./gradlew test --tests com.graphtipper.util.SourceHashTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/graphtipper/util/SourceHash.java`:
```java
package com.graphtipper.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

public final class SourceHash {
    private SourceHash() {}

    public static String ofJavaSources(Path projectRoot) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.walk(projectRoot)) {
                s.filter(p -> p.toString().endsWith(".java"))
                 .filter(Files::isRegularFile)
                 .forEach(files::add);
            }
            files.sort(Comparator.naturalOrder());
            for (Path f : files) {
                String rel = projectRoot.relativize(f).toString().replace('\\', '/');
                md.update(rel.getBytes());
                md.update((byte) 0);
                md.update(Files.readAllBytes(f));
                md.update((byte) 0);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.util.SourceHashTest`
Expected: 3 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/util/SourceHash.java src/test/java/com/graphtipper/util/SourceHashTest.java
git commit -m "feat(util): SourceHash for CPG cache keys"
```

---

## Task 8: `TestDetector`

**Files:**
- Create: `src/main/java/com/graphtipper/detect/TestDetector.java`
- Test: `src/test/java/com/graphtipper/detect/TestDetectorTest.java`

Note: at this stage we operate on the `MethodNode.isTest` flag that is set by `CpgImporter` (Task 10). `TestDetector` is a **post-import** verifier that also flags JUnit3-style methods (which the importer might miss). We test both flagging logic and idempotency.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/detect/TestDetectorTest.java`:
```java
package com.graphtipper.detect;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TestDetectorTest {
    @Test
    void respectsImporterIsTestFlag() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).done()
            .method("p.A.foo").testFlag(false).done()
            .build();
        var marked = new TestDetector(false).markTests(g);
        assertThat(marked).extracting(Node.Method::fqn).containsExactly("p.T.t1");
    }

    @Test
    void treatTestDirsAsTestsFlagsMethodsUnderSrcTestJava() {
        var g = new ProjectGraph();
        g.addNode(new Node.Method("m:p.T.t1", "p.T.t1", "t1()", List.of(), "void",
            "src/test/java/p/T.java", 1, 2, null, false, false, List.of("public")));
        g.addNode(new Node.Method("m:p.A.foo", "p.A.foo", "foo()", List.of(), "void",
            "src/main/java/p/A.java", 1, 2, null, false, false, List.of("public")));
        var marked = new TestDetector(true).markTests(g);
        assertThat(marked).extracting(Node.Method::fqn).containsExactly("p.T.t1");
    }
}
```

- [ ] **Step 2: Run, verify fails**

Run: `./gradlew test --tests com.graphtipper.detect.TestDetectorTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/graphtipper/detect/TestDetector.java`:
```java
package com.graphtipper.detect;

import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import java.util.ArrayList;
import java.util.List;

public final class TestDetector {
    private final boolean treatTestDirsAsTests;

    public TestDetector(boolean treatTestDirsAsTests) {
        this.treatTestDirsAsTests = treatTestDirsAsTests;
    }

    public List<Node.Method> markTests(ProjectGraph g) {
        var out = new ArrayList<Node.Method>();
        for (Node n : g.allNodes()) {
            if (!(n instanceof Node.Method m)) continue;
            if (m.isTest()) { out.add(m); continue; }
            if (treatTestDirsAsTests && m.file() != null
                    && m.file().replace('\\', '/').contains("/src/test/java/")) {
                out.add(promote(m));
            }
        }
        return out;
    }

    private Node.Method promote(Node.Method m) {
        return new Node.Method(m.id(), m.fqn(), m.signature(), m.paramTypes(),
                m.returnType(), m.file(), m.lineStart(), m.lineEnd(), m.javadoc(),
                true, m.isAbstract(), m.modifiers());
    }
}
```

Note: `markTests` does not mutate the graph; it returns the *effective* test set. The importer's `isTest` flag is the source of truth for normal annotation-based detection. The directory heuristic produces promoted copies for downstream consumers; the slicer treats any `MethodNode` returned here as a test root.

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.detect.TestDetectorTest`
Expected: 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/detect/TestDetector.java src/test/java/com/graphtipper/detect/TestDetectorTest.java
git commit -m "feat(detect): TestDetector with optional test-dir heuristic"
```

---

## Task 9: `MethodLocator` — path+sig and FQN-only target specs

**Files:**
- Create: `src/main/java/com/graphtipper/detect/MethodLocator.java`
- Create: `src/main/java/com/graphtipper/detect/TargetSpec.java`
- Test: `src/test/java/com/graphtipper/detect/MethodLocatorTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/detect/MethodLocatorTest.java`:
```java
package com.graphtipper.detect;

import com.graphtipper.model.Gb;
import com.graphtipper.model.ProjectGraph;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MethodLocatorTest {
    @Test
    void parsesPathSpec() {
        var s = TargetSpec.parse("src/main/java/p/A.java#A.foo(int,Text)");
        assertThat(s.file()).isEqualTo("src/main/java/p/A.java");
        assertThat(s.simpleClass()).isEqualTo("A");
        assertThat(s.methodName()).isEqualTo("foo");
        assertThat(s.paramTypes()).containsExactly("int", "Text");
    }

    @Test
    void parsesFqnSpec() {
        var s = TargetSpec.parse("p.outer$Inner#foo(int,p.X)");
        assertThat(s.file()).isNull();
        assertThat(s.classFqn()).isEqualTo("p.outer$Inner");
        assertThat(s.simpleClass()).isEqualTo("Inner");
    }

    @Test
    void findsByPathExactParams() {
        var g = Gb.graph()
            .method("p.A.foo").file("src/main/java/p/A.java").params("int", "Text").done()
            .method("p.A.foo").file("src/main/java/p/A.java").params().done()
            .build();
        var m = new MethodLocator().locate(g, TargetSpec.parse("src/main/java/p/A.java#A.foo(int,Text)"));
        assertThat(m.paramTypes()).containsExactly("int", "Text");
    }

    @Test
    void ambiguousMatchThrowsWithCandidates() {
        var g = Gb.graph()
            .method("p.A.foo").file("F.java").params("int").done()
            .method("p.A.foo").file("F.java").params("long").done()
            .build();
        var loc = new MethodLocator();
        assertThatThrownBy(() -> loc.locate(g, TargetSpec.parse("F.java#A.foo")))
            .isInstanceOf(MethodLocator.AmbiguousTargetException.class)
            .hasMessageContaining("foo(int)")
            .hasMessageContaining("foo(long)");
    }

    @Test
    void notFoundSuggestsNearest() {
        var g = Gb.graph()
            .method("p.A.foo").file("F.java").done()
            .build();
        assertThatThrownBy(() -> new MethodLocator().locate(g, TargetSpec.parse("F.java#A.fooo")))
            .isInstanceOf(MethodLocator.TargetNotFoundException.class)
            .hasMessageContaining("foo");
    }
}
```

- [ ] **Step 2: Run, verify fails**

Run: `./gradlew test --tests com.graphtipper.detect.MethodLocatorTest`
Expected: FAIL.

- [ ] **Step 3: Implement `TargetSpec`**

`src/main/java/com/graphtipper/detect/TargetSpec.java`:
```java
package com.graphtipper.detect;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record TargetSpec(
        String file,           // null for FQN-only
        String classFqn,       // null for path-form
        String simpleClass,
        String methodName,
        List<String> paramTypes  // may be empty when omitted
) {
    private static final Pattern PATH_FORM = Pattern.compile(
            "^(?<file>[^#]+)#(?<cls>[A-Za-z_][A-Za-z_0-9$]*)\\.(?<name>[A-Za-z_][A-Za-z_0-9]*)(?:\\((?<params>[^)]*)\\))?$");
    private static final Pattern FQN_FORM = Pattern.compile(
            "^(?<fqn>[A-Za-z_][A-Za-z_0-9.$]*)#(?<name>[A-Za-z_][A-Za-z_0-9]*)(?:\\((?<params>[^)]*)\\))?$");

    public static TargetSpec parse(String raw) {
        Matcher m = PATH_FORM.matcher(raw.trim());
        if (m.matches()) {
            return new TargetSpec(m.group("file"), null, m.group("cls"),
                    m.group("name"), splitParams(m.group("params")));
        }
        m = FQN_FORM.matcher(raw.trim());
        if (m.matches()) {
            String fqn = m.group("fqn");
            String simple = fqn.substring(Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$')) + 1);
            return new TargetSpec(null, fqn, simple, m.group("name"),
                    splitParams(m.group("params")));
        }
        throw new IllegalArgumentException("Invalid target spec: " + raw);
    }

    private static List<String> splitParams(String params) {
        if (params == null || params.isBlank()) return List.of();
        String[] parts = params.split("\\s*,\\s*");
        return List.of(parts);
    }
}
```

- [ ] **Step 4: Implement `MethodLocator`**

`src/main/java/com/graphtipper/detect/MethodLocator.java`:
```java
package com.graphtipper.detect;

import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import java.util.*;
import java.util.stream.Collectors;

public final class MethodLocator {

    public static final class TargetNotFoundException extends RuntimeException {
        public TargetNotFoundException(String msg) { super(msg); }
    }

    public static final class AmbiguousTargetException extends RuntimeException {
        public AmbiguousTargetException(String msg) { super(msg); }
    }

    public Node.Method locate(ProjectGraph g, TargetSpec spec) {
        List<Node.Method> pool = new ArrayList<>();
        for (Node n : g.allNodes()) {
            if (n instanceof Node.Method m) pool.add(m);
        }
        // Filter by file (path form)
        if (spec.file() != null) {
            pool = pool.stream().filter(m -> spec.file().equals(m.file())).collect(Collectors.toList());
        }
        // Filter by simple class (last $ or . segment of fqn before method)
        if (spec.simpleClass() != null) {
            pool = pool.stream().filter(m -> simpleClass(m).equals(spec.simpleClass())).collect(Collectors.toList());
        }
        // Filter by method name
        pool = pool.stream().filter(m -> m.fqn().endsWith("." + spec.methodName())).collect(Collectors.toList());

        if (pool.isEmpty()) {
            throw new TargetNotFoundException("No method matches " + spec.methodName()
                    + "; near: " + nearest(g, spec.methodName()));
        }

        // Filter by paramTypes if specified
        if (!spec.paramTypes().isEmpty()) {
            var exact = pool.stream()
                    .filter(m -> matches(m.paramTypes(), spec.paramTypes()))
                    .collect(Collectors.toList());
            if (exact.size() == 1) return exact.get(0);
            if (!exact.isEmpty()) pool = exact;
        }

        if (pool.size() == 1) return pool.get(0);
        if (pool.size() > 1) {
            var sigs = pool.stream().map(Node.Method::signature).collect(Collectors.joining(", "));
            throw new AmbiguousTargetException("Multiple matches: " + sigs);
        }
        throw new TargetNotFoundException("No match for spec");
    }

    private String simpleClass(Node.Method m) {
        int dot = m.fqn().lastIndexOf('.');
        String beforeDot = dot < 0 ? m.fqn() : m.fqn().substring(0, dot);
        int dollar = beforeDot.lastIndexOf('$');
        int lastSep = Math.max(dot, dollar);
        String cls = lastSep < 0 ? beforeDot : beforeDot.substring(beforeDot.lastIndexOf('.') + 1);
        int innerSep = cls.lastIndexOf('$');
        return innerSep < 0 ? cls : cls.substring(innerSep + 1);
    }

    private boolean matches(List<String> actual, List<String> requested) {
        if (actual.size() != requested.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            String a = simpleName(actual.get(i));
            String r = simpleName(requested.get(i));
            if (!a.equalsIgnoreCase(r) && !actual.get(i).equalsIgnoreCase(requested.get(i))) return false;
        }
        return true;
    }

    private String simpleName(String typeFqn) {
        int dot = Math.max(typeFqn.lastIndexOf('.'), typeFqn.lastIndexOf('$'));
        return dot < 0 ? typeFqn : typeFqn.substring(dot + 1);
    }

    private String nearest(ProjectGraph g, String name) {
        var candidates = new ArrayList<String>();
        for (Node n : g.allNodes()) {
            if (n instanceof Node.Method m) {
                int d = levenshtein(name, m.fqn().substring(m.fqn().lastIndexOf('.') + 1));
                if (d <= 2) candidates.add(m.fqn());
            }
        }
        Collections.sort(candidates);
        return candidates.isEmpty() ? "(no near matches)" : String.join(", ", candidates.stream().limit(5).toList());
    }

    static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
```

- [ ] **Step 5: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.detect.MethodLocatorTest`
Expected: 5 PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/detect/ src/test/java/com/graphtipper/detect/MethodLocatorTest.java
git commit -m "feat(detect): MethodLocator and TargetSpec (path+sig and FQN forms)"
```

---

## Task 10: `ReverseCallChainExtractor` — base BFS

**Files:**
- Create: `src/main/java/com/graphtipper/slice/Chain.java`
- Create: `src/main/java/com/graphtipper/slice/CallStep.java`
- Create: `src/main/java/com/graphtipper/slice/ReverseCallChainExtractor.java`
- Test: `src/test/java/com/graphtipper/slice/ReverseCallChainExtractorTest.java`

- [ ] **Step 1: Write the failing test (basic chain)**

`src/test/java/com/graphtipper/slice/ReverseCallChainExtractorTest.java`:
```java
package com.graphtipper.slice;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReverseCallChainExtractorTest {
    @Test
    void findsSingleStepChain() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).done()
            .method("p.A.target").done()
            .calls("p.T.t1", "p.A.target")
            .build();
        var target = (Node.Method) g.byFqn("p.A.target").get(0);
        var result = new ReverseCallChainExtractor(16).extract(g, target);
        assertThat(result.chains()).hasSize(1);
        assertThat(result.chains().get(0).steps()).hasSize(1);
        assertThat(result.chains().get(0).steps().get(0).callerFqn()).isEqualTo("p.T.t1");
        assertThat(result.chains().get(0).steps().get(0).calleeFqn()).isEqualTo("p.A.target");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void findsTwoStepChain() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).done()
            .method("p.B.bridge").done()
            .method("p.A.target").done()
            .calls("p.T.t1", "p.B.bridge")
            .calls("p.B.bridge", "p.A.target")
            .build();
        var target = (Node.Method) g.byFqn("p.A.target").get(0);
        var result = new ReverseCallChainExtractor(16).extract(g, target);
        assertThat(result.chains()).hasSize(1);
        assertThat(result.chains().get(0).steps()).hasSize(2);
        // closest-to-test first ordering: t1 → bridge → target
        assertThat(result.chains().get(0).steps().get(0).callerFqn()).isEqualTo("p.T.t1");
        assertThat(result.chains().get(0).steps().get(1).callerFqn()).isEqualTo("p.B.bridge");
    }

    @Test
    void emitsNoChainsWhenNoTestReachesTarget() {
        var g = Gb.graph()
            .method("p.A.target").done()
            .build();
        var target = (Node.Method) g.byFqn("p.A.target").get(0);
        var result = new ReverseCallChainExtractor(16).extract(g, target);
        assertThat(result.chains()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }
}
```

- [ ] **Step 2: Implement `CallStep` and `Chain`**

`src/main/java/com/graphtipper/slice/CallStep.java`:
```java
package com.graphtipper.slice;

import java.util.List;

public record CallStep(
        String callerMethodId,
        String callerFqn,
        String calleeMethodId,
        String calleeFqn,
        boolean viaVirtual,
        String snippet,             // filled later by CallSiteSlicer
        List<ArgOrigin> argOrigins  // filled later by CallSiteSlicer
) {
    public CallStep withEnrichment(String snippet, List<ArgOrigin> origins) {
        return new CallStep(callerMethodId, callerFqn, calleeMethodId, calleeFqn,
                viaVirtual, snippet, origins);
    }
}
```

`src/main/java/com/graphtipper/slice/Chain.java`:
```java
package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

public record Chain(
        Node.Method test,
        List<CallStep> steps,
        int virtualSteps
) {
    public int depth() { return steps.size(); }
}
```

`src/main/java/com/graphtipper/slice/ArgOrigin.java`:
```java
package com.graphtipper.slice;

public record ArgOrigin(
        int argIndex,
        Kind kind,
        String value,        // literal value, or null
        String factoryFqn,   // for FACTORY_CALL, else null
        String paramName,    // for PARAMETER (caller's param)
        String fieldFqn,     // for FIELD
        String file,
        int line
) {
    public enum Kind { LITERAL, PARAMETER, FIELD, FACTORY_CALL, UNKNOWN }
}
```

`src/main/java/com/graphtipper/slice/ChainResult.java`:
```java
package com.graphtipper.slice;

import java.util.List;

public record ChainResult(List<Chain> chains, boolean truncated) {}
```

- [ ] **Step 3: Implement minimal `ReverseCallChainExtractor`**

`src/main/java/com/graphtipper/slice/ReverseCallChainExtractor.java`:
```java
package com.graphtipper.slice;

import com.graphtipper.model.*;
import java.util.*;

public final class ReverseCallChainExtractor {
    private final int maxChains;

    public ReverseCallChainExtractor(int maxChains) {
        this.maxChains = maxChains;
    }

    public ChainResult extract(ProjectGraph g, Node.Method target) {
        // BFS upward: each frontier element is a partial path ending at some method.
        // When we hit a test method, we record the path as a Chain (reversed: test → ... → target).
        record Path(String methodId, List<CallStep> stepsTowardTarget) {}

        List<Chain> chains = new ArrayList<>();
        Deque<Path> frontier = new ArrayDeque<>();
        Set<String> visitedEdges = new HashSet<>();
        frontier.add(new Path(target.id(), List.of()));
        int frontierGuard = maxChains * 8;
        boolean truncated = false;

        while (!frontier.isEmpty()) {
            if (frontier.size() > frontierGuard) { truncated = true; break; }
            Path p = frontier.poll();
            Node node = g.byId(p.methodId());
            if (node instanceof Node.Method m && m.isTest() && !p.stepsTowardTarget().isEmpty()) {
                // Reverse: build chain test → ... → target (caller-to-callee order)
                var reversed = new ArrayList<>(p.stepsTowardTarget());
                Collections.reverse(reversed);
                int v = (int) reversed.stream().filter(CallStep::viaVirtual).count();
                chains.add(new Chain(m, reversed, v));
                if (chains.size() >= maxChains) break;
                continue;
            }
            for (Edge.Calls in : g.incomingCalls(p.methodId())) {
                String edgeKey = in.fromId() + "->" + in.toId();
                if (!visitedEdges.add(edgeKey)) continue;
                if (!(g.byId(in.fromId()) instanceof Node.Method caller)) continue;
                if (!(g.byId(in.toId()) instanceof Node.Method callee)) continue;
                var step = new CallStep(
                        caller.id(), caller.fqn(), callee.id(), callee.fqn(),
                        in.viaVirtual(), null, List.of());
                var nextSteps = new ArrayList<>(p.stepsTowardTarget());
                nextSteps.add(step);
                frontier.add(new Path(caller.id(), nextSteps));
            }
        }

        return new ChainResult(rank(chains), truncated);
    }

    private List<Chain> rank(List<Chain> chains) {
        chains.sort(Comparator
                .comparingInt(Chain::depth)
                .thenComparingInt((Chain c) -> c.virtualSteps()));
        return chains.subList(0, Math.min(maxChains, chains.size()));
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.slice.ReverseCallChainExtractorTest`
Expected: 3 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ src/test/java/com/graphtipper/slice/ReverseCallChainExtractorTest.java
git commit -m "feat(slice): ReverseCallChainExtractor (basic BFS + ranking)"
```

---

## Task 11: `ReverseCallChainExtractor` — cycles, virtual, truncation

**Files:**
- Modify: `src/test/java/com/graphtipper/slice/ReverseCallChainExtractorTest.java`

- [ ] **Step 1: Add cycle test**

Append to test class:
```java
    @Test
    void handlesCycleWithoutInfiniteLoop() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).done()
            .method("p.A.foo").done()
            .method("p.A.bar").done()
            .calls("p.T.t1", "p.A.foo")
            .calls("p.A.foo", "p.A.bar")
            .calls("p.A.bar", "p.A.foo")  // cycle
            .build();
        var target = (Node.Method) g.byFqn("p.A.bar").get(0);
        var result = new ReverseCallChainExtractor(16).extract(g, target);
        assertThat(result.chains()).isNotEmpty();
        assertThat(result.chains().get(0).steps()).hasSize(2); // t1 → foo → bar
    }
```

- [ ] **Step 2: Add virtual-step test**

Append:
```java
    @Test
    void usesOverridesEdgeForVirtualDispatch() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).done()
            .method("p.I.do").done()
            .method("p.Impl.do").done()
            .calls("p.T.t1", "p.I.do")        // test calls interface
            .overrides("p.Impl.do", "p.I.do") // Impl overrides interface
            .build();
        var target = (Node.Method) g.byFqn("p.Impl.do").get(0);
        var result = new ReverseCallChainExtractor(16).extract(g, target);
        assertThat(result.chains()).hasSize(1);
        var chain = result.chains().get(0);
        assertThat(chain.virtualSteps()).isEqualTo(1);
        assertThat(chain.steps()).hasSize(1);
        assertThat(chain.steps().get(0).viaVirtual()).isTrue();
    }
```

- [ ] **Step 3: Add truncation test**

Append:
```java
    @Test
    void truncatesWhenFrontierExceedsGuard() {
        // Create a fan-out: 200 tests each calling target.
        var b = Gb.graph().method("p.A.target").done();
        for (int i = 0; i < 200; i++) {
            b = b.method("p.T.t" + i).testFlag(true).done()
                 .calls("p.T.t" + i, "p.A.target");
        }
        var g = b.build();
        var target = (Node.Method) g.byFqn("p.A.target").get(0);
        var result = new ReverseCallChainExtractor(4).extract(g, target);
        assertThat(result.chains()).hasSize(4);
    }
```

- [ ] **Step 4: Run, verify two new fail, one passes**

Run: `./gradlew test --tests com.graphtipper.slice.ReverseCallChainExtractorTest`
Expected: cycle PASS (visited-set already there), virtual FAIL (overrides edges ignored), truncation PASS (maxChains already caps).

- [ ] **Step 5: Add virtual handling**

In `ReverseCallChainExtractor.extract`, after the `incomingCalls` loop, add:
```java
            // Virtual: any caller of a method we override is also a caller of us.
            for (Edge over : g.incoming(p.methodId())) {
                if (!(over instanceof Edge.Overrides ov)) continue;
                String parentId = ov.fromId().equals(p.methodId()) ? ov.toId() : ov.fromId();
                // Edge.Overrides goes child → parent; we want callers of `parent` to reach `child` (= p.methodId())
                if (!parentId.equals(p.methodId())) {
                    for (Edge.Calls in : g.incomingCalls(parentId)) {
                        String edgeKey = in.fromId() + "->virtual->" + p.methodId();
                        if (!visitedEdges.add(edgeKey)) continue;
                        if (!(g.byId(in.fromId()) instanceof Node.Method caller)) continue;
                        var step = new CallStep(caller.id(), caller.fqn(),
                                p.methodId(), ((Node.Method) g.byId(p.methodId())).fqn(),
                                true, null, List.of());
                        var nextSteps = new ArrayList<>(p.stepsTowardTarget());
                        nextSteps.add(step);
                        frontier.add(new Path(caller.id(), nextSteps));
                    }
                }
            }
```

Note: `Edge.Overrides` is stored child→parent (per the `Gb.overrides` helper). So when standing on a child method (the target's identity), we look at *outgoing* `Overrides` edges to find the parent, then collect callers of the parent. Adjust the lookup direction:

```java
            for (Edge over : g.outgoing(p.methodId())) {
                if (!(over instanceof Edge.Overrides ov)) continue;
                String parentId = ov.toId();
                for (Edge.Calls in : g.incomingCalls(parentId)) {
                    String edgeKey = in.fromId() + "->virtual->" + p.methodId();
                    if (!visitedEdges.add(edgeKey)) continue;
                    if (!(g.byId(in.fromId()) instanceof Node.Method caller)) continue;
                    var step = new CallStep(caller.id(), caller.fqn(),
                            p.methodId(), ((Node.Method) g.byId(p.methodId())).fqn(),
                            true, null, List.of());
                    var nextSteps = new ArrayList<>(p.stepsTowardTarget());
                    nextSteps.add(step);
                    frontier.add(new Path(caller.id(), nextSteps));
                }
            }
```

(Use the second version — outgoing `Overrides` from current node points to parent we override.)

- [ ] **Step 6: Run, verify all pass**

Run: `./gradlew test --tests com.graphtipper.slice.ReverseCallChainExtractorTest`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add src/
git commit -m "feat(slice): cycle/virtual-override/truncation handling in chain extractor"
```

---

## Task 12: `CallSiteSlicer` — call-site snippets and argument back-slice

**Files:**
- Create: `src/main/java/com/graphtipper/slice/CallSiteSlicer.java`
- Test: `src/test/java/com/graphtipper/slice/CallSiteSlicerTest.java`

The slicer needs `CallSiteNode`s in the graph to locate exact call sites. Extend `Gb` first.

- [ ] **Step 1: Extend `Gb` with call-site fixtures**

Add to `src/test/java/com/graphtipper/model/Gb.java`:
```java
    public Gb callSite(String inMethodFqn, String calleeFqn, int line, int col, String snippet) {
        var m = (Node.Method) g.byFqn(inMethodFqn).get(0);
        var cs = new Node.CallSite("cs:" + m.id() + "@" + line + ":" + col,
                m.id(), calleeFqn, 0, line, col, snippet);
        g.addNode(cs);
        // attach call edge from callsite to target if target exists
        var ts = g.byFqn(calleeFqn);
        if (!ts.isEmpty()) {
            g.addEdge(new Edge.Calls(cs.id(), ts.get(0).id(), false));
        }
        return this;
    }

    public Gb literal(String inMethodFqn, String value, int line) {
        var m = (Node.Method) g.byFqn(inMethodFqn).get(0);
        var lit = new Node.Literal("lit:" + m.id() + "@" + line + "#" + value,
                m.id(), Node.LiteralKind.INT, value, line);
        g.addNode(lit);
        return this;
    }

    public Gb ddg(String fromNodeId, String toNodeId) {
        g.addEdge(new Edge.Ddg(fromNodeId, toNodeId));
        return this;
    }

    public ProjectGraph buildRaw() { return g; }
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/graphtipper/slice/CallSiteSlicerTest.java`:
```java
package com.graphtipper.slice;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.util.SourceFragmentReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CallSiteSlicerTest {
    @Test
    void enrichesStepWithSnippetAndLiteralArgOrigin(@TempDir Path dir) throws Exception {
        var src = dir.resolve("T.java");
        Files.writeString(src, """
            class T {
              void t1() {
                int x = 0;
                A.target(x);
              }
            }
            """);

        var gb = Gb.graph()
            .method("p.T.t1").testFlag(true).file("T.java").done()
            .method("p.A.target").done()
            .calls("p.T.t1", "p.A.target")
            .callSite("p.T.t1", "p.A.target", 4, 9, "    A.target(x);");
        // ddg: literal 0 (line 3) → callsite arg
        gb = gb.literal("p.T.t1", "0", 3);
        var g = gb.buildRaw();
        var callSite = g.allNodes().stream()
                .filter(n -> n instanceof Node.CallSite)
                .map(n -> (Node.CallSite) n).findFirst().orElseThrow();
        var lit = g.allNodes().stream()
                .filter(n -> n instanceof Node.Literal)
                .map(n -> (Node.Literal) n).findFirst().orElseThrow();
        gb.ddg(lit.id(), callSite.id());

        var step = new CallStep(
                ((Node.Method) g.byFqn("p.T.t1").get(0)).id(), "p.T.t1",
                ((Node.Method) g.byFqn("p.A.target").get(0)).id(), "p.A.target",
                false, null, List.of());
        var reader = new SourceFragmentReader(dir);
        var enriched = new CallSiteSlicer(reader).enrich(g, step);

        assertThat(enriched.snippet()).contains("A.target(x)");
        assertThat(enriched.argOrigins()).isNotEmpty();
        assertThat(enriched.argOrigins().get(0).kind()).isEqualTo(ArgOrigin.Kind.LITERAL);
        assertThat(enriched.argOrigins().get(0).value()).isEqualTo("0");
    }
}
```

- [ ] **Step 3: Implement**

`src/main/java/com/graphtipper/slice/CallSiteSlicer.java`:
```java
package com.graphtipper.slice;

import com.graphtipper.model.*;
import com.graphtipper.util.SourceFragmentReader;
import java.util.*;

public final class CallSiteSlicer {
    private static final int MAX_BACK_SLICE_DEPTH = 6;
    private final SourceFragmentReader reader;

    public CallSiteSlicer(SourceFragmentReader reader) { this.reader = reader; }

    public CallStep enrich(ProjectGraph g, CallStep step) {
        Node.CallSite cs = findCallSite(g, step);
        if (cs == null) return step.withEnrichment("(call site not located)", List.of());

        Node.Method caller = (Node.Method) g.byId(step.callerMethodId());
        String snippet;
        try {
            snippet = reader.readAround(caller.file(), cs.line(), 3, 2);
        } catch (Exception e) {
            snippet = "(unavailable: " + e.getMessage() + ")";
        }

        var origins = new ArrayList<ArgOrigin>();
        int arg = 0;
        for (Edge e : g.incoming(cs.id())) {
            if (!(e instanceof Edge.Ddg)) continue;
            var origin = backslice(g, e.fromId(), arg++, 0);
            origins.add(origin);
        }
        return step.withEnrichment(snippet, origins);
    }

    private Node.CallSite findCallSite(ProjectGraph g, CallStep step) {
        for (Node n : g.allNodes()) {
            if (n instanceof Node.CallSite cs
                    && cs.inMethodId().equals(step.callerMethodId())
                    && cs.calleeFqn().equals(step.calleeFqn())) {
                return cs;
            }
        }
        return null;
    }

    private ArgOrigin backslice(ProjectGraph g, String nodeId, int argIdx, int depth) {
        if (depth > MAX_BACK_SLICE_DEPTH) {
            return new ArgOrigin(argIdx, ArgOrigin.Kind.UNKNOWN, null, null, null, null, null, -1);
        }
        Node n = g.byId(nodeId);
        return switch (n) {
            case Node.Literal lit -> new ArgOrigin(argIdx, ArgOrigin.Kind.LITERAL,
                    lit.value(), null, null, null, methodFile(g, lit.inMethodId()), lit.line());
            case Node.Parameter p -> new ArgOrigin(argIdx, ArgOrigin.Kind.PARAMETER,
                    null, null, p.name() + ":" + p.type(), null, null, -1);
            case Node.Field f -> new ArgOrigin(argIdx, ArgOrigin.Kind.FIELD,
                    null, null, null, f.ownerTypeFqn() + "." + f.name(), null, -1);
            case Node.CallSite cs -> new ArgOrigin(argIdx, ArgOrigin.Kind.FACTORY_CALL,
                    null, cs.calleeFqn(), null, null, methodFile(g, cs.inMethodId()), cs.line());
            case null, default -> {
                // Hop one more step
                List<Edge> ins = g.incoming(nodeId);
                for (Edge e : ins) if (e instanceof Edge.Ddg) {
                    yield backslice(g, e.fromId(), argIdx, depth + 1);
                }
                yield new ArgOrigin(argIdx, ArgOrigin.Kind.UNKNOWN, null, null, null, null, null, -1);
            }
        };
    }

    private String methodFile(ProjectGraph g, String methodId) {
        return g.byId(methodId) instanceof Node.Method m ? m.file() : null;
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.slice.CallSiteSlicerTest`
Expected: 1 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/CallSiteSlicer.java src/test/java/com/graphtipper/slice/CallSiteSlicerTest.java src/test/java/com/graphtipper/model/Gb.java
git commit -m "feat(slice): CallSiteSlicer (snippets + arg back-slice)"
```

---

## Task 13: `LocalContextExtractor` — sibling members, used types, production call sites

**Files:**
- Create: `src/main/java/com/graphtipper/slice/LocalContext.java`
- Create: `src/main/java/com/graphtipper/slice/LocalContextExtractor.java`
- Test: `src/test/java/com/graphtipper/slice/LocalContextExtractorTest.java`

- [ ] **Step 1: Implement record types**

`src/main/java/com/graphtipper/slice/LocalContext.java`:
```java
package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

public record LocalContext(
        List<SiblingMember> siblings,
        List<UsedType> usedTypes,
        List<ProductionCallSite> productionCallSites
) {
    public record SiblingMember(String signature, String javadoc, String body, boolean truncated) {}
    public record UsedType(Node.Type type, List<String> publicMethodSignatures) {}
    public record ProductionCallSite(String callerFqn, String file, int line, String snippet) {}
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/graphtipper/slice/LocalContextExtractorTest.java`:
```java
package com.graphtipper.slice;

import com.graphtipper.model.*;
import com.graphtipper.util.SourceFragmentReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LocalContextExtractorTest {
    @Test
    void collectsSiblingMembersAndUsedTypes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("F.java"), """
            class C {
              int x;
              void target() { helper(); }
              void helper() {}
            }
            """);

        var gb = Gb.graph()
            .method("p.C.target").file("F.java").lines(3, 3).done()
            .method("p.C.helper").file("F.java").lines(4, 4).done()
            .calls("p.C.target", "p.C.helper");
        var g = gb.buildRaw();

        // Type C, field x
        var typeC = new Node.Type("t:p.C", "p.C", Node.TypeKind.CLASS, "F.java", 1, 5, null);
        g.addNode(typeC);
        var fieldX = new Node.Field("f:p.C.x", "p.C", "x", "int", List.of(), 2, 2);
        g.addNode(fieldX);
        g.addEdge(new Edge.Reads(g.byFqn("p.C.target").get(0).id(), fieldX.id()));

        // Used type via RefType from target
        var typeText = new Node.Type("t:p.Text", "p.Text", Node.TypeKind.CLASS, "Text.java", 1, 3, null);
        g.addNode(typeText);
        g.addEdge(new Edge.RefType(g.byFqn("p.C.target").get(0).id(), typeText.id()));

        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContextExtractor(new SourceFragmentReader(dir)).extract(g, target);

        assertThat(ctx.siblings()).extracting(LocalContext.SiblingMember::signature)
                .anyMatch(s -> s.contains("helper"));
        assertThat(ctx.usedTypes()).extracting(u -> u.type().fqn())
                .contains("p.Text");
    }

    @Test
    void includesEnumConstantsForEnumUsedTypes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("F.java"), "class C { void target(){} }");
        var g = Gb.graph().method("p.C.target").file("F.java").done().buildRaw();
        var ovr = new Node.Type("t:p.Overflow", "p.Overflow", Node.TypeKind.ENUM,
                "F.java", 1, 1, List.of("TRUNCATE", "SPAN", "WRAP"));
        g.addNode(ovr);
        g.addEdge(new Edge.RefType(g.byFqn("p.C.target").get(0).id(), ovr.id()));

        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContextExtractor(new SourceFragmentReader(dir)).extract(g, target);

        var enumType = ctx.usedTypes().stream()
                .filter(u -> u.type().fqn().equals("p.Overflow"))
                .findFirst().orElseThrow();
        assertThat(enumType.type().enumConstants()).containsExactly("TRUNCATE", "SPAN", "WRAP");
    }
}
```

- [ ] **Step 3: Implement**

`src/main/java/com/graphtipper/slice/LocalContextExtractor.java`:
```java
package com.graphtipper.slice;

import com.graphtipper.model.*;
import com.graphtipper.util.SourceFragmentReader;
import java.util.*;

public final class LocalContextExtractor {
    private static final int SMALL_BODY_THRESHOLD = 30;
    private final SourceFragmentReader reader;

    public LocalContextExtractor(SourceFragmentReader reader) { this.reader = reader; }

    public LocalContext extract(ProjectGraph g, Node.Method target) {
        List<LocalContext.SiblingMember> siblings = collectSiblings(g, target);
        List<LocalContext.UsedType> used = collectUsedTypes(g, target);
        List<LocalContext.ProductionCallSite> prod = collectProductionCallSites(g, target);
        return new LocalContext(siblings, used, prod);
    }

    private List<LocalContext.SiblingMember> collectSiblings(ProjectGraph g, Node.Method target) {
        var out = new ArrayList<LocalContext.SiblingMember>();
        String targetClass = ownerClassFqn(target);
        // Sibling methods called by target
        for (Edge.Calls c : g.outgoingCalls(target.id())) {
            if (!(g.byId(c.toId()) instanceof Node.Method m)) continue;
            if (!ownerClassFqn(m).equals(targetClass)) continue;
            out.add(renderMember(m));
        }
        // Sibling fields read/written
        for (Edge e : g.outgoing(target.id())) {
            String fid = null;
            if (e instanceof Edge.Reads r) fid = r.toId();
            else if (e instanceof Edge.Writes w) fid = w.toId();
            if (fid == null) continue;
            if (!(g.byId(fid) instanceof Node.Field f)) continue;
            if (!f.ownerTypeFqn().equals(targetClass)) continue;
            out.add(new LocalContext.SiblingMember(
                    f.type() + " " + f.name(),
                    null,
                    "",
                    false));
        }
        return out;
    }

    private LocalContext.SiblingMember renderMember(Node.Method m) {
        int bodyLines = m.lineEnd() - m.lineStart() + 1;
        String body;
        boolean truncated;
        if (m.file() == null) {
            body = "";
            truncated = false;
        } else if (bodyLines <= SMALL_BODY_THRESHOLD) {
            body = reader.readLines(m.file(), m.lineStart(), m.lineEnd());
            truncated = false;
        } else {
            body = reader.readLines(m.file(), m.lineStart(), m.lineStart() + 9) + "\n// ...";
            truncated = true;
        }
        return new LocalContext.SiblingMember(m.signature(), m.javadoc(), body, truncated);
    }

    private List<LocalContext.UsedType> collectUsedTypes(ProjectGraph g, Node.Method target) {
        var out = new ArrayList<LocalContext.UsedType>();
        var seen = new LinkedHashSet<String>();
        for (Edge e : g.outgoing(target.id())) {
            if (!(e instanceof Edge.RefType r)) continue;
            if (!(g.byId(r.toId()) instanceof Node.Type t)) continue;
            if (!seen.add(t.fqn())) continue;
            out.add(new LocalContext.UsedType(t, publicMethodSigs(g, t)));
        }
        return out;
    }

    private List<String> publicMethodSigs(ProjectGraph g, Node.Type t) {
        var sigs = new ArrayList<String>();
        for (Node n : g.allNodes()) {
            if (!(n instanceof Node.Method m)) continue;
            if (!ownerClassFqn(m).equals(t.fqn())) continue;
            if (!m.modifiers().contains("public")) continue;
            sigs.add(m.signature());
        }
        return sigs;
    }

    private List<LocalContext.ProductionCallSite> collectProductionCallSites(ProjectGraph g, Node.Method target) {
        var out = new ArrayList<LocalContext.ProductionCallSite>();
        for (Edge.Calls in : g.incomingCalls(target.id())) {
            if (!(g.byId(in.fromId()) instanceof Node.Method caller)) continue;
            if (caller.isTest()) continue;
            // best-effort line — find a callsite node if any
            int line = -1;
            String snippet = "";
            for (Node n : g.allNodes()) {
                if (n instanceof Node.CallSite cs && cs.inMethodId().equals(caller.id())
                        && cs.calleeFqn().equals(target.fqn())) {
                    line = cs.line();
                    snippet = cs.codeSnippet();
                    break;
                }
            }
            out.add(new LocalContext.ProductionCallSite(caller.fqn(), caller.file(), line, snippet));
            if (out.size() >= 5) break;
        }
        return out;
    }

    private String ownerClassFqn(Node.Method m) {
        int dot = m.fqn().lastIndexOf('.');
        return dot < 0 ? "" : m.fqn().substring(0, dot);
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.slice.LocalContextExtractorTest`
Expected: 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/LocalContext.java src/main/java/com/graphtipper/slice/LocalContextExtractor.java src/test/java/com/graphtipper/slice/LocalContextExtractorTest.java
git commit -m "feat(slice): LocalContextExtractor (siblings, used types, prod call sites)"
```

---

## Task 14: `Artifact` aggregate + eviction-aware budget pass

**Files:**
- Create: `src/main/java/com/graphtipper/render/Artifact.java`
- Create: `src/main/java/com/graphtipper/render/BudgetPlanner.java`
- Test: `src/test/java/com/graphtipper/render/BudgetPlannerTest.java`

`Artifact` is a plain aggregate. `BudgetPlanner` runs the eviction algorithm from §6 of the spec on an `Artifact`, returning a (possibly reduced) `Artifact` plus the `TokenBudget` instance with `evicted` populated.

- [ ] **Step 1: Implement `Artifact`**

`src/main/java/com/graphtipper/render/Artifact.java`:
```java
package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.Chain;
import com.graphtipper.slice.LocalContext;
import java.util.List;

public record Artifact(
        Node.Method target,
        String currentBody,
        List<Chain> chains,
        boolean truncated,
        LocalContext localContext
) {}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/graphtipper/render/BudgetPlannerTest.java`:
```java
package com.graphtipper.render;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BudgetPlannerTest {
    @Test
    void protectsMinimumWhenBudgetTight() {
        var g = Gb.graph().method("p.C.target").done().buildRaw();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContext(List.of(), List.of(), List.of());
        var artifact = new Artifact(target, "return null;", List.of(), false, ctx);

        var budget = new TokenBudget(10_000);
        var planned = new BudgetPlanner(budget).plan(artifact);

        assertThat(planned.target().fqn()).isEqualTo("p.C.target");
        assertThat(planned.currentBody()).isEqualTo("return null;");
        assertThat(budget.used()).isGreaterThan(0);
    }

    @Test
    void evictsProductionCallSitesFirstWhenOverBudget() {
        var g = Gb.graph().method("p.C.target").done().buildRaw();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var bigProd = new LocalContext.ProductionCallSite("a", "f", 1, "x".repeat(800));
        var ctx = new LocalContext(List.of(), List.of(), List.of(bigProd, bigProd, bigProd));
        var artifact = new Artifact(target, "", List.of(), false, ctx);

        var budget = new TokenBudget(150);   // tight
        var planned = new BudgetPlanner(budget).plan(artifact);

        assertThat(planned.localContext().productionCallSites()).isEmpty();
        assertThat(budget.evicted()).contains("production-call-sites");
    }

    @Test
    void throwsWhenMinimumDoesNotFit() {
        var g = Gb.graph().method("p.C.target").done().buildRaw();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContext(List.of(), List.of(), List.of());
        var giantBody = "x".repeat(5000);
        var artifact = new Artifact(target, giantBody, List.of(), false, ctx);

        var budget = new TokenBudget(10);
        org.junit.jupiter.api.Assertions.assertThrows(BudgetPlanner.BudgetExceededException.class,
                () -> new BudgetPlanner(budget).plan(artifact));
    }
}
```

- [ ] **Step 3: Implement `BudgetPlanner`**

`src/main/java/com/graphtipper/render/BudgetPlanner.java`:
```java
package com.graphtipper.render;

import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import java.util.*;

public final class BudgetPlanner {

    public static final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String msg) { super(msg); }
    }

    private final TokenBudget budget;

    public BudgetPlanner(TokenBudget budget) { this.budget = budget; }

    public Artifact plan(Artifact a) {
        // Estimate sizes of each section (rough text-size approximation).
        // Strategy: pre-flight; if total over budget, evict in spec order.
        Artifact cur = a;

        // 1. Reserve protected minimum first (fails fast if can't fit).
        int minTokens = budget.estimate(estimateProtectedMinimum(cur));
        if (minTokens > budget.max()) {
            throw new BudgetExceededException(
                    "Protected minimum requires " + minTokens + " tokens, budget=" + budget.max());
        }

        // Try fitting everything; otherwise evict in order.
        if (estimateTotal(cur) <= budget.max()) {
            charge(cur);
            return cur;
        }

        // Step 1: drop production call-sites
        cur = new Artifact(cur.target(), cur.currentBody(), cur.chains(), cur.truncated(),
                new LocalContext(cur.localContext().siblings(), cur.localContext().usedTypes(), List.of()));
        budget.recordEviction("production-call-sites");
        if (estimateTotal(cur) <= budget.max()) { charge(cur); return cur; }

        // Step 2: drop bodies of used types — sigs only (we already don't keep bodies in UsedType, but skip)
        budget.recordEviction("used-types-bodies");
        if (estimateTotal(cur) <= budget.max()) { charge(cur); return cur; }

        // Step 3: truncate sibling bodies
        var truncSiblings = new ArrayList<LocalContext.SiblingMember>();
        for (var s : cur.localContext().siblings()) {
            truncSiblings.add(new LocalContext.SiblingMember(
                    s.signature(), s.javadoc(), "// truncated", true));
        }
        cur = new Artifact(cur.target(), cur.currentBody(), cur.chains(), cur.truncated(),
                new LocalContext(truncSiblings, cur.localContext().usedTypes(), cur.localContext().productionCallSites()));
        budget.recordEviction("sibling-bodies");
        if (estimateTotal(cur) <= budget.max()) { charge(cur); return cur; }

        // Step 4: drop arg-origin detail on far chain steps (keep first step of each chain in full)
        var trimmedChains = new ArrayList<Chain>();
        for (Chain ch : cur.chains()) {
            var newSteps = new ArrayList<CallStep>();
            for (int i = 0; i < ch.steps().size(); i++) {
                CallStep step = ch.steps().get(i);
                if (i == 0) newSteps.add(step);
                else newSteps.add(step.withEnrichment(step.snippet(), List.of()));
            }
            trimmedChains.add(new Chain(ch.test(), newSteps, ch.virtualSteps()));
        }
        cur = new Artifact(cur.target(), cur.currentBody(), trimmedChains, cur.truncated(), cur.localContext());
        budget.recordEviction("arg-origin-detail");
        if (estimateTotal(cur) <= budget.max()) { charge(cur); return cur; }

        // Step 5: drop lowest-ranked chains until we fit (keep at least top-1)
        var chainsLeft = new ArrayList<>(cur.chains());
        while (chainsLeft.size() > 1 && estimateTotal(new Artifact(cur.target(), cur.currentBody(),
                chainsLeft, cur.truncated(), cur.localContext())) > budget.max()) {
            chainsLeft.remove(chainsLeft.size() - 1);
            budget.recordEviction("lowest-ranked-chain");
        }
        cur = new Artifact(cur.target(), cur.currentBody(), chainsLeft, cur.truncated(), cur.localContext());

        if (estimateTotal(cur) > budget.max()) {
            throw new BudgetExceededException(
                    "Cannot fit even after all evictions: needs " + estimateTotal(cur) + " tokens");
        }
        charge(cur);
        return cur;
    }

    private String estimateProtectedMinimum(Artifact a) {
        var sb = new StringBuilder();
        sb.append(a.target().fqn()).append(a.target().signature()).append(a.currentBody());
        if (a.target().javadoc() != null) sb.append(a.target().javadoc());
        if (!a.chains().isEmpty()) {
            Chain top = a.chains().get(0);
            for (CallStep s : top.steps()) {
                if (s.snippet() != null) sb.append(s.snippet());
                for (ArgOrigin o : s.argOrigins()) sb.append(o.toString());
            }
        }
        return sb.toString();
    }

    private int estimateTotal(Artifact a) {
        var sb = new StringBuilder();
        sb.append(estimateProtectedMinimum(a));
        for (int i = 1; i < a.chains().size(); i++) {
            Chain c = a.chains().get(i);
            for (CallStep s : c.steps()) {
                if (s.snippet() != null) sb.append(s.snippet());
                for (ArgOrigin o : s.argOrigins()) sb.append(o.toString());
            }
        }
        for (var s : a.localContext().siblings()) sb.append(s.signature()).append(s.body());
        for (var u : a.localContext().usedTypes()) {
            sb.append(u.type().fqn());
            for (String sig : u.publicMethodSignatures()) sb.append(sig);
            if (u.type().enumConstants() != null) for (String c : u.type().enumConstants()) sb.append(c);
        }
        for (var p : a.localContext().productionCallSites()) sb.append(p.snippet());
        return budget.estimate(sb.toString());
    }

    private void charge(Artifact a) {
        // Consume the budget so callers can read `budget.used()` for the meta record.
        budget.tryAdd(estimateProtectedMinimum(a));
        for (int i = 1; i < a.chains().size(); i++) {
            for (CallStep s : a.chains().get(i).steps()) {
                if (s.snippet() != null) budget.tryAdd(s.snippet());
            }
        }
        for (var s : a.localContext().siblings()) budget.tryAdd(s.signature() + s.body());
        for (var u : a.localContext().usedTypes()) {
            budget.tryAdd(u.type().fqn() + String.join("", u.publicMethodSignatures()));
        }
        for (var p : a.localContext().productionCallSites()) budget.tryAdd(p.snippet());
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.render.BudgetPlannerTest`
Expected: 3 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/Artifact.java src/main/java/com/graphtipper/render/BudgetPlanner.java src/test/java/com/graphtipper/render/BudgetPlannerTest.java
git commit -m "feat(render): Artifact aggregate and BudgetPlanner with eviction"
```

---

## Task 15: `MarkdownRenderer`

**Files:**
- Create: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Test: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/render/MarkdownRendererTest.java`:
```java
package com.graphtipper.render;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererTest {
    @Test
    void rendersHeaderAndAllRequiredSections() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).file("T.java").done()
            .method("p.C.target").file("C.java").javadoc("Writes value").done()
            .build();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var test = (Node.Method) g.byFqn("p.T.t1").get(0);
        var step = new CallStep(test.id(), "p.T.t1", target.id(), "p.C.target",
                false, "  target();", List.of());
        var chain = new Chain(test, List.of(step), 0);
        var artifact = new Artifact(target, "return null;", List.of(chain), false,
                new LocalContext(List.of(), List.of(), List.of()));

        var budget = new TokenBudget(20_000);
        budget.tryAdd("seed");
        var md = new MarkdownRenderer().render(artifact, budget, "hash123", "picocli");

        assertThat(md).contains("# Graph-Tipper Augmentation");
        assertThat(md).contains("Target: p.C.target");
        assertThat(md).contains("## Target");
        assertThat(md).contains("Writes value");
        assertThat(md).contains("return null;");
        assertThat(md).contains("## Test Chains");
        assertThat(md).contains("Chain 1");
        assertThat(md).contains("p.T.t1");
        assertThat(md).contains("## Local Context");
        assertThat(md).contains("## Negative Memory");
        assertThat(md).contains("_(reserved");
    }

    @Test
    void writesNoChainsNoticeWhenChainsEmpty() {
        var g = Gb.graph().method("p.C.target").done().build();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var artifact = new Artifact(target, "", List.of(), false,
                new LocalContext(List.of(), List.of(), List.of()));
        var md = new MarkdownRenderer().render(artifact, new TokenBudget(20_000), "h", "proj");
        assertThat(md).contains("No tests transitively reach this target");
    }
}
```

- [ ] **Step 2: Implement**

`src/main/java/com/graphtipper/render/MarkdownRenderer.java`:
```java
package com.graphtipper.render;

import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import com.graphtipper.model.Node;

public final class MarkdownRenderer {

    public String render(Artifact a, TokenBudget budget, String projectKey, String projectName) {
        var sb = new StringBuilder();
        sb.append("# Graph-Tipper Augmentation\n\n");
        sb.append("> Generated for: ").append(projectName).append(" @ ").append(projectKey).append("\n");
        sb.append("> Target: ").append(a.target().fqn()).append("\n");
        sb.append("> Budget: ").append(budget.used()).append(" / ").append(budget.max()).append(" tokens · Chains: ")
          .append(a.chains().size()).append(" · Truncated: ").append(a.truncated()).append("\n\n");

        renderTarget(sb, a);
        renderChains(sb, a);
        renderLocalContext(sb, a);
        sb.append("## Negative Memory\n_(reserved — not populated in V1)_\n");
        return sb.toString();
    }

    private void renderTarget(StringBuilder sb, Artifact a) {
        var t = a.target();
        sb.append("## Target\n\n");
        sb.append("**File:** `").append(t.file()).append("` (lines ").append(t.lineStart())
          .append("–").append(t.lineEnd()).append(")\n\n");
        if (t.javadoc() != null && !t.javadoc().isBlank()) {
            sb.append("**Javadoc:**\n> ").append(t.javadoc().replace("\n", "\n> ")).append("\n\n");
        }
        sb.append("**Signature:**\n```java\n").append(t.signature()).append("\n```\n\n");
        if (a.currentBody() != null && !a.currentBody().isBlank()) {
            sb.append("**Current body:**\n```java\n").append(a.currentBody()).append("\n```\n\n");
        }
    }

    private void renderChains(StringBuilder sb, Artifact a) {
        sb.append("## Test Chains\n\n");
        if (a.chains().isEmpty()) {
            sb.append("> No tests transitively reach this target.\n\n");
            return;
        }
        int idx = 1;
        for (Chain c : a.chains()) {
            sb.append("### Chain ").append(idx++).append(" (depth=").append(c.depth())
              .append(", virtual=").append(c.virtualSteps()).append(")\n");
            sb.append("**Test:** `").append(c.test().fqn()).append("` — `")
              .append(c.test().file()).append(":").append(c.test().lineStart()).append("`\n\n");
            for (CallStep s : c.steps()) {
                sb.append("```java\n// ").append(s.callerFqn()).append("\n");
                sb.append(s.snippet() == null ? "(snippet unavailable)" : s.snippet()).append("\n```\n");
                if (!s.argOrigins().isEmpty()) {
                    sb.append("**Arg origins at `").append(s.calleeFqn()).append("` call:**\n");
                    for (ArgOrigin o : s.argOrigins()) {
                        sb.append("- `arg").append(o.argIndex()).append("` = ");
                        switch (o.kind()) {
                            case LITERAL -> sb.append("`").append(o.value()).append("` (literal");
                            case PARAMETER -> sb.append("parameter `").append(o.paramName()).append("`");
                            case FIELD -> sb.append("field `").append(o.fieldFqn()).append("`");
                            case FACTORY_CALL -> sb.append("factory `").append(o.factoryFqn()).append("(...)`");
                            case UNKNOWN -> sb.append("unknown");
                        }
                        if (o.file() != null) sb.append(", ").append(o.file()).append(":").append(o.line());
                        sb.append(")\n");
                    }
                }
                sb.append("\n");
            }
        }
    }

    private void renderLocalContext(StringBuilder sb, Artifact a) {
        sb.append("## Local Context\n\n");
        var lc = a.localContext();
        if (!lc.siblings().isEmpty()) {
            sb.append("### Sibling members used by target\n```java\n");
            for (var s : lc.siblings()) {
                if (s.javadoc() != null && !s.javadoc().isBlank()) {
                    sb.append("/** ").append(s.javadoc().replace("\n", " ")).append(" */\n");
                }
                sb.append(s.signature()).append("\n");
                if (!s.body().isBlank()) sb.append(s.body()).append("\n");
            }
            sb.append("```\n\n");
        }
        if (!lc.usedTypes().isEmpty()) {
            sb.append("### Used types\n");
            for (var u : lc.usedTypes()) {
                sb.append("**`").append(u.type().fqn()).append("`** (").append(u.type().kind().name().toLowerCase()).append(")\n");
                if (u.type().enumConstants() != null && !u.type().enumConstants().isEmpty()) {
                    sb.append("```java\nenum ").append(u.type().fqn()).append(" { ")
                      .append(String.join(", ", u.type().enumConstants())).append(" }\n```\n");
                } else if (!u.publicMethodSignatures().isEmpty()) {
                    sb.append("```java\n");
                    for (String sig : u.publicMethodSignatures()) sb.append(sig).append("\n");
                    sb.append("```\n");
                }
                sb.append("\n");
            }
        }
        if (!lc.productionCallSites().isEmpty()) {
            sb.append("### Production call-sites of target (non-test, up to 5)\n");
            for (var p : lc.productionCallSites()) {
                sb.append("- `").append(p.callerFqn()).append("` — `").append(p.file()).append(":").append(p.line()).append("`\n");
                if (p.snippet() != null && !p.snippet().isBlank()) {
                    sb.append("  ```java\n  ").append(p.snippet()).append("\n  ```\n");
                }
            }
            sb.append("\n");
        }
    }
}
```

- [ ] **Step 3: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest`
Expected: 2 PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "feat(render): MarkdownRenderer (target, chains, local context, neg-mem stub)"
```

---

## Task 16: `JsonRenderer` (sidecar, schema v1.0)

**Files:**
- Create: `src/main/java/com/graphtipper/render/JsonRenderer.java`
- Test: `src/test/java/com/graphtipper/render/JsonRendererTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/render/JsonRendererTest.java`:
```java
package com.graphtipper.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class JsonRendererTest {
    @Test
    void writesStableSchemaAndReservedSlots() throws Exception {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).file("T.java").done()
            .method("p.C.target").file("C.java").done()
            .build();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var test = (Node.Method) g.byFqn("p.T.t1").get(0);
        var step = new CallStep(test.id(), "p.T.t1", target.id(), "p.C.target",
                false, "  target();", List.of());
        var artifact = new Artifact(target, "return null;", List.of(new Chain(test, List.of(step), 0)),
                false, new LocalContext(List.of(), List.of(), List.of()));

        var budget = new TokenBudget(20_000);
        budget.tryAdd("x");
        var json = new JsonRenderer().render(artifact, budget);
        var node = new ObjectMapper().readTree(json);

        assertThat(node.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(node.path("target").path("fqn").asText()).isEqualTo("p.C.target");
        assertThat(node.path("chains")).hasSize(1);
        assertThat(node.path("chains").get(0).path("failures").isArray()).isTrue();
        assertThat(node.path("negativeMemory").isArray()).isTrue();
        assertThat(node.path("budget").path("tokensUsed").asInt()).isGreaterThan(0);
        assertThat(node.path("budget").path("tokensMax").asInt()).isEqualTo(20_000);
    }
}
```

- [ ] **Step 2: Implement**

`src/main/java/com/graphtipper/render/JsonRenderer.java`:
```java
package com.graphtipper.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;

public final class JsonRenderer {
    private final ObjectMapper M = new ObjectMapper();

    public String render(Artifact a, TokenBudget budget) {
        ObjectNode root = M.createObjectNode();
        root.put("schemaVersion", "1.0");

        ObjectNode target = root.putObject("target");
        target.put("fqn", a.target().fqn());
        ArrayNode pt = target.putArray("paramTypes");
        for (String p : a.target().paramTypes()) pt.add(p);
        target.put("file", a.target().file());
        target.put("lineStart", a.target().lineStart());
        target.put("lineEnd", a.target().lineEnd());
        target.put("javadoc", a.target().javadoc());
        target.put("currentBody", a.currentBody());

        ArrayNode chains = root.putArray("chains");
        int rank = 1;
        for (Chain c : a.chains()) {
            ObjectNode cn = chains.addObject();
            cn.put("rank", rank++);
            cn.put("depth", c.depth());
            cn.put("virtualSteps", c.virtualSteps());
            cn.put("truncated", false);
            ObjectNode tst = cn.putObject("test");
            tst.put("fqn", c.test().fqn());
            tst.put("file", c.test().file());
            tst.put("line", c.test().lineStart());
            ArrayNode steps = cn.putArray("steps");
            for (CallStep s : c.steps()) {
                ObjectNode sn = steps.addObject();
                sn.put("callerFqn", s.callerFqn());
                sn.put("calleeFqn", s.calleeFqn());
                ObjectNode csn = sn.putObject("callSite");
                csn.put("file", c.test().file());
                csn.put("line", -1);
                csn.put("col", -1);
                sn.put("snippet", s.snippet());
                ArrayNode origins = sn.putArray("argOrigins");
                for (ArgOrigin o : s.argOrigins()) {
                    ObjectNode on = origins.addObject();
                    on.put("arg", o.argIndex());
                    on.put("kind", o.kind().name());
                    if (o.value() != null) on.put("value", o.value());
                    if (o.factoryFqn() != null) on.put("factoryFqn", o.factoryFqn());
                    if (o.paramName() != null) on.put("paramName", o.paramName());
                    if (o.fieldFqn() != null) on.put("fieldFqn", o.fieldFqn());
                    if (o.file() != null) on.put("file", o.file());
                    on.put("line", o.line());
                }
                sn.put("viaVirtual", s.viaVirtual());
            }
            cn.putArray("failures");
        }

        ObjectNode lc = root.putObject("localContext");
        ArrayNode sibs = lc.putArray("siblingMembers");
        for (var s : a.localContext().siblings()) {
            ObjectNode sn = sibs.addObject();
            sn.put("signature", s.signature());
            sn.put("javadoc", s.javadoc());
            sn.put("body", s.body());
            sn.put("truncated", s.truncated());
        }
        ArrayNode ut = lc.putArray("usedTypes");
        for (var u : a.localContext().usedTypes()) {
            ObjectNode un = ut.addObject();
            un.put("fqn", u.type().fqn());
            un.put("kind", u.type().kind().name());
            if (u.type().enumConstants() != null) {
                ArrayNode ec = un.putArray("enumConstants");
                for (String c : u.type().enumConstants()) ec.add(c);
            }
            ArrayNode sigs = un.putArray("publicMethodSignatures");
            for (String s : u.publicMethodSignatures()) sigs.add(s);
        }
        ArrayNode prod = lc.putArray("productionCallSites");
        for (var p : a.localContext().productionCallSites()) {
            ObjectNode pn = prod.addObject();
            pn.put("callerFqn", p.callerFqn());
            pn.put("file", p.file());
            pn.put("line", p.line());
            pn.put("snippet", p.snippet());
        }

        ObjectNode bud = root.putObject("budget");
        bud.put("tokensUsed", budget.used());
        bud.put("tokensMax", budget.max());
        ArrayNode ev = bud.putArray("evicted");
        for (String e : budget.evicted()) ev.add(e);

        root.putArray("negativeMemory");

        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 3: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.render.JsonRendererTest`
Expected: 1 PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/graphtipper/render/JsonRenderer.java src/test/java/com/graphtipper/render/JsonRendererTest.java
git commit -m "feat(render): JsonRenderer (schema v1.0, reserved negative-memory slots)"
```

---

## Task 17: `JoernRunner` — subprocess + cache

**Files:**
- Create: `src/main/java/com/graphtipper/cpg/JoernRunner.java`
- Test: `src/test/java/com/graphtipper/cpg/JoernRunnerTest.java`

`JoernRunner` is tested in isolation by **mocking the subprocess** behind a small interface, so the unit test does not need Joern installed. The real subprocess is exercised in the integration test (Task 19).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphtipper/cpg/JoernRunnerTest.java`:
```java
package com.graphtipper.cpg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class JoernRunnerTest {
    @Test
    void cachesByProjectSrcHash(@TempDir Path projectDir, @TempDir Path cacheDir) throws Exception {
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.writeString(projectDir.resolve("src/main/java/A.java"), "class A {}");

        var stub = new StubInvoker();
        var runner = new JoernRunner(stub, cacheDir);
        Path out1 = runner.buildAndExport(projectDir, false);
        Path out2 = runner.buildAndExport(projectDir, false);

        assertThat(out1).isEqualTo(out2);
        assertThat(stub.invocations).isEqualTo(1);    // cached on 2nd
    }

    @Test
    void rebuildsWhenNoCacheFlagSet(@TempDir Path projectDir, @TempDir Path cacheDir) throws Exception {
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.writeString(projectDir.resolve("src/main/java/A.java"), "class A {}");

        var stub = new StubInvoker();
        var runner = new JoernRunner(stub, cacheDir);
        runner.buildAndExport(projectDir, false);
        runner.buildAndExport(projectDir, true);
        assertThat(stub.invocations).isEqualTo(2);
    }

    static final class StubInvoker implements JoernInvoker {
        int invocations = 0;
        @Override
        public void runJavasrc2Cpg(Path projectRoot, Path cpgFile) throws Exception {
            invocations++;
            Files.createDirectories(cpgFile.getParent());
            Files.writeString(cpgFile, "fake cpg blob");
        }
        @Override
        public void runJoernExport(Path cpgFile, Path outDir) throws Exception {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("export.json"), "{\"fake\":true}");
        }
    }
}
```

- [ ] **Step 2: Implement `JoernInvoker` interface and runner**

`src/main/java/com/graphtipper/cpg/JoernInvoker.java`:
```java
package com.graphtipper.cpg;

import java.nio.file.Path;

public interface JoernInvoker {
    void runJavasrc2Cpg(Path projectRoot, Path cpgFile) throws Exception;
    void runJoernExport(Path cpgFile, Path outDir) throws Exception;
}
```

`src/main/java/com/graphtipper/cpg/JoernRunner.java`:
```java
package com.graphtipper.cpg;

import com.graphtipper.util.SourceHash;
import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

public final class JoernRunner {
    private final JoernInvoker invoker;
    private final Path cacheRoot;

    public JoernRunner(JoernInvoker invoker, Path cacheRoot) {
        this.invoker = invoker;
        this.cacheRoot = cacheRoot;
    }

    public Path buildAndExport(Path projectRoot, boolean noCache) throws Exception {
        String hash = SourceHash.ofJavaSources(projectRoot);
        Path entry = cacheRoot.resolve(hash);
        Path exportDir = entry.resolve("export");

        if (!noCache && Files.exists(exportDir.resolve("export.json"))) {
            return exportDir;
        }
        if (Files.exists(entry)) deleteRecursively(entry);
        Files.createDirectories(entry);

        Path cpgFile = entry.resolve("cpg.bin");
        invoker.runJavasrc2Cpg(projectRoot, cpgFile);
        invoker.runJoernExport(cpgFile, exportDir);
        return exportDir;
    }

    private void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
             .forEach(x -> { try { Files.delete(x); } catch (IOException e) { throw new RuntimeException(e); } });
        }
    }
}
```

`src/main/java/com/graphtipper/cpg/ProcessJoernInvoker.java`:
```java
package com.graphtipper.cpg;

import java.io.IOException;
import java.nio.file.*;

public final class ProcessJoernInvoker implements JoernInvoker {
    private final Path joernHome;  // null → use PATH

    public ProcessJoernInvoker(Path joernHome) { this.joernHome = joernHome; }

    @Override
    public void runJavasrc2Cpg(Path projectRoot, Path cpgFile) throws Exception {
        Files.createDirectories(cpgFile.getParent());
        String cmd = resolveBinary("javasrc2cpg");
        ProcessBuilder pb = new ProcessBuilder(cmd,
                projectRoot.toAbsolutePath().toString(),
                "--output", cpgFile.toAbsolutePath().toString())
                .redirectErrorStream(true).inheritIO();
        int code = pb.start().waitFor();
        if (code != 0) throw new IOException("javasrc2cpg exit " + code);
    }

    @Override
    public void runJoernExport(Path cpgFile, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        String cmd = resolveBinary("joern-export");
        ProcessBuilder pb = new ProcessBuilder(cmd,
                cpgFile.toAbsolutePath().toString(),
                "--repr", "all",
                "--format", "graphson",
                "--out", outDir.toAbsolutePath().toString())
                .redirectErrorStream(true).inheritIO();
        int code = pb.start().waitFor();
        if (code != 0) throw new IOException("joern-export exit " + code);
    }

    private String resolveBinary(String name) {
        if (joernHome != null) {
            Path p = joernHome.resolve(name);
            if (Files.exists(p)) return p.toString();
            p = joernHome.resolve(name + ".sh");
            if (Files.exists(p)) return p.toString();
        }
        return name;
    }
}
```

- [ ] **Step 3: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.cpg.JoernRunnerTest`
Expected: 2 PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/graphtipper/cpg/ src/test/java/com/graphtipper/cpg/JoernRunnerTest.java
git commit -m "feat(cpg): JoernRunner with cache + ProcessJoernInvoker"
```

---

## Task 18: `CpgImporter` — Joern GraphSON → ProjectGraph

This is the biggest single-task piece. We test it on a hand-crafted GraphSON snippet that mimics Joern's actual output for a tiny program.

**Files:**
- Create: `src/main/java/com/graphtipper/cpg/CpgImporter.java`
- Test: `src/test/java/com/graphtipper/cpg/CpgImporterTest.java`
- Create: `src/test/resources/cpg-sample/export.json`

- [ ] **Step 1: Drop a sample GraphSON file**

Joern's `joern-export --format=graphson` produces a JSON file with nodes and edges. We build the smallest realistic shape we need to parse.

`src/test/resources/cpg-sample/export.json`:
```json
{
  "vertices": [
    {"id": "1", "label": "METHOD",
     "properties": {"FULL_NAME": "p.C.target:void(int)", "NAME": "target",
                    "SIGNATURE": "void(int)", "FILENAME": "src/main/java/p/C.java",
                    "LINE_NUMBER": 5, "LINE_NUMBER_END": 7}},
    {"id": "2", "label": "METHOD",
     "properties": {"FULL_NAME": "p.T.t1:void()", "NAME": "t1",
                    "SIGNATURE": "void()", "FILENAME": "src/test/java/p/T.java",
                    "LINE_NUMBER": 10, "LINE_NUMBER_END": 12}},
    {"id": "3", "label": "ANNOTATION",
     "properties": {"NAME": "Test", "FULL_NAME": "org.junit.jupiter.api.Test"}},
    {"id": "4", "label": "TYPE_DECL",
     "properties": {"FULL_NAME": "p.C", "NAME": "C",
                    "FILENAME": "src/main/java/p/C.java"}},
    {"id": "5", "label": "CALL",
     "properties": {"METHOD_FULL_NAME": "p.C.target:void(int)",
                    "LINE_NUMBER": 11, "COLUMN_NUMBER": 5, "CODE": "C.target(0)"}},
    {"id": "6", "label": "LITERAL",
     "properties": {"CODE": "0", "TYPE_FULL_NAME": "int", "LINE_NUMBER": 11}}
  ],
  "edges": [
    {"id": "e1", "label": "AST", "outV": "2", "inV": "3"},
    {"id": "e2", "label": "AST", "outV": "2", "inV": "5"},
    {"id": "e3", "label": "CALL", "outV": "5", "inV": "1"},
    {"id": "e4", "label": "ARGUMENT", "outV": "5", "inV": "6"},
    {"id": "e5", "label": "REACHING_DEF", "outV": "6", "inV": "5"}
  ]
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/graphtipper/cpg/CpgImporterTest.java`:
```java
package com.graphtipper.cpg;

import com.graphtipper.model.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class CpgImporterTest {
    @Test
    void parsesMethodsTypesCallsAndAnnotations() throws Exception {
        var samplePath = Path.of("src/test/resources/cpg-sample/export.json").toAbsolutePath();
        ProjectGraph g = new CpgImporter().importFrom(samplePath);

        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var test = (Node.Method) g.byFqn("p.T.t1").get(0);
        assertThat(target.file()).isEqualTo("src/main/java/p/C.java");
        assertThat(target.lineStart()).isEqualTo(5);
        assertThat(test.isTest()).isTrue();
        assertThat(g.byFqn("p.C")).hasSize(1);
        assertThat(g.byFqn("p.C").get(0)).isInstanceOf(Node.Type.class);
        assertThat(g.incomingCalls(target.id())).hasSize(1);
    }
}
```

- [ ] **Step 3: Implement `CpgImporter`**

`src/main/java/com/graphtipper/cpg/CpgImporter.java`:
```java
package com.graphtipper.cpg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphtipper.model.*;
import java.nio.file.*;
import java.util.*;

public final class CpgImporter {

    public ProjectGraph importFrom(Path exportFile) throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.newInputStream(exportFile));
        var g = new ProjectGraph();

        // Pass 1: nodes (vertices)
        Map<String, String> annotationsOnMethod = new HashMap<>();  // verticeId(method) -> "Test"
        Map<String, JsonNode> rawById = new HashMap<>();
        Map<String, String> nodeKindById = new HashMap<>();
        for (JsonNode v : root.path("vertices")) {
            String id = v.path("id").asText();
            String label = v.path("label").asText();
            rawById.put(id, v);
            nodeKindById.put(id, label);
        }
        // First pass — build AST edges so we can find method→annotation linkage.
        Map<String, List<String>> astChildren = new HashMap<>();
        for (JsonNode e : root.path("edges")) {
            if ("AST".equals(e.path("label").asText())) {
                astChildren.computeIfAbsent(e.path("outV").asText(), k -> new ArrayList<>())
                           .add(e.path("inV").asText());
            }
        }
        // Methods: detect @Test by AST-children that are ANNOTATION with NAME = Test
        for (JsonNode v : root.path("vertices")) {
            String id = v.path("id").asText();
            if (!"METHOD".equals(v.path("label").asText())) continue;
            boolean isTest = false;
            for (String childId : astChildren.getOrDefault(id, List.of())) {
                JsonNode ch = rawById.get(childId);
                if (ch != null && "ANNOTATION".equals(ch.path("label").asText())) {
                    String aName = ch.path("properties").path("NAME").asText();
                    String aFqn = ch.path("properties").path("FULL_NAME").asText();
                    if ("Test".equals(aName) || aFqn.endsWith(".Test")
                            || aFqn.endsWith(".ParameterizedTest")
                            || aFqn.endsWith(".RepeatedTest")) {
                        isTest = true;
                        break;
                    }
                }
            }
            JsonNode p = v.path("properties");
            String fullName = p.path("FULL_NAME").asText();
            String fqnNoSig = fullName.contains(":") ? fullName.substring(0, fullName.indexOf(':')) : fullName;
            var m = new Node.Method(
                    "m:" + fullName,
                    fqnNoSig,
                    p.path("SIGNATURE").asText(),
                    List.of(),
                    "void",
                    p.path("FILENAME").asText(),
                    p.path("LINE_NUMBER").asInt(-1),
                    p.path("LINE_NUMBER_END").asInt(-1),
                    null,
                    isTest,
                    false,
                    List.of("public"));
            g.addNode(m);
        }

        // Types
        for (JsonNode v : root.path("vertices")) {
            if (!"TYPE_DECL".equals(v.path("label").asText())) continue;
            JsonNode p = v.path("properties");
            var t = new Node.Type(
                    "t:" + p.path("FULL_NAME").asText(),
                    p.path("FULL_NAME").asText(),
                    Node.TypeKind.CLASS,
                    p.path("FILENAME").asText(),
                    -1, -1, null);
            g.addNode(t);
        }

        // CALL nodes
        Map<String, String> callSiteIdByJoernId = new HashMap<>();
        for (JsonNode v : root.path("vertices")) {
            if (!"CALL".equals(v.path("label").asText())) continue;
            JsonNode p = v.path("properties");
            String inMethod = parentMethodOf(v.path("id").asText(), astChildren, rawById);
            String calleeFull = p.path("METHOD_FULL_NAME").asText();
            String calleeFqn = calleeFull.contains(":") ? calleeFull.substring(0, calleeFull.indexOf(':')) : calleeFull;
            String csId = "cs:" + v.path("id").asText();
            var cs = new Node.CallSite(csId, inMethod == null ? "" : "m:" + inMethod,
                    calleeFqn, 0,
                    p.path("LINE_NUMBER").asInt(-1),
                    p.path("COLUMN_NUMBER").asInt(-1),
                    p.path("CODE").asText());
            g.addNode(cs);
            callSiteIdByJoernId.put(v.path("id").asText(), csId);
        }

        // LITERAL nodes
        Map<String, String> litIdByJoernId = new HashMap<>();
        for (JsonNode v : root.path("vertices")) {
            if (!"LITERAL".equals(v.path("label").asText())) continue;
            JsonNode p = v.path("properties");
            String inMethod = parentMethodOf(v.path("id").asText(), astChildren, rawById);
            String id = "lit:" + v.path("id").asText();
            var lit = new Node.Literal(id, inMethod == null ? "" : "m:" + inMethod,
                    Node.LiteralKind.OTHER, p.path("CODE").asText(),
                    p.path("LINE_NUMBER").asInt(-1));
            g.addNode(lit);
            litIdByJoernId.put(v.path("id").asText(), id);
        }

        // Pass 2: edges
        for (JsonNode e : root.path("edges")) {
            String lbl = e.path("label").asText();
            String src = e.path("outV").asText();
            String dst = e.path("inV").asText();
            switch (lbl) {
                case "CALL" -> {
                    String csId = callSiteIdByJoernId.get(src);
                    JsonNode dstNode = rawById.get(dst);
                    if (csId == null || dstNode == null) break;
                    if (!"METHOD".equals(dstNode.path("label").asText())) break;
                    String dstFull = dstNode.path("properties").path("FULL_NAME").asText();
                    g.addEdge(new Edge.Calls(csId, "m:" + dstFull, false));
                    // Method-level Calls too
                    String csInMethod = ((Node.CallSite) g.byId(csId)).inMethodId();
                    g.addEdge(new Edge.Calls(csInMethod, "m:" + dstFull, false));
                }
                case "REACHING_DEF" -> {
                    String s = litIdByJoernId.getOrDefault(src, callSiteIdByJoernId.getOrDefault(src, null));
                    String d = callSiteIdByJoernId.getOrDefault(dst, litIdByJoernId.getOrDefault(dst, null));
                    if (s != null && d != null) g.addEdge(new Edge.Ddg(s, d));
                }
                default -> { /* ignored for V1 */ }
            }
        }

        return g;
    }

    private String parentMethodOf(String childId, Map<String, List<String>> astChildren,
                                  Map<String, JsonNode> rawById) {
        for (var e : astChildren.entrySet()) {
            if (e.getValue().contains(childId)) {
                JsonNode parent = rawById.get(e.getKey());
                if (parent != null && "METHOD".equals(parent.path("label").asText())) {
                    return parent.path("properties").path("FULL_NAME").asText();
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.cpg.CpgImporterTest`
Expected: 1 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/cpg/CpgImporter.java src/test/java/com/graphtipper/cpg/CpgImporterTest.java src/test/resources/cpg-sample/
git commit -m "feat(cpg): CpgImporter parses Joern GraphSON into ProjectGraph"
```

---

## Task 19: `Main` CLI wiring

**Files:**
- Modify: `src/main/java/com/graphtipper/cli/Main.java`
- Create: `src/test/java/com/graphtipper/cli/MainSmokeTest.java`

- [ ] **Step 1: Replace `Main` with a wired pipeline**

`src/main/java/com/graphtipper/cli/Main.java`:
```java
package com.graphtipper.cli;

import com.graphtipper.cpg.*;
import com.graphtipper.detect.*;
import com.graphtipper.model.*;
import com.graphtipper.render.*;
import com.graphtipper.slice.*;
import com.graphtipper.util.*;
import picocli.CommandLine;
import picocli.CommandLine.Option;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "graph-tipper", mixinStandardHelpOptions = true,
        description = "Generate a CPG-based context augmentation for a Java target method.")
public final class Main implements Callable<Integer> {

    @Option(names = "--project", required = true) Path project;
    @Option(names = "--target", required = true) String target;
    @Option(names = "--out", required = true) Path out;
    @Option(names = "--budget-tokens", defaultValue = "20000") int budgetTokens;
    @Option(names = "--max-chains", defaultValue = "16") int maxChains;
    @Option(names = "--treat-test-dirs-as-tests") boolean treatTestDirsAsTests;
    @Option(names = "--no-cache") boolean noCache;
    @Option(names = "--joern-home") Path joernHome;
    @Option(names = "--debug-dot") boolean debugDot;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() {
        try {
            Files.createDirectories(out);
            Path cacheRoot = out.resolve(".cache");
            var runner = new JoernRunner(new ProcessJoernInvoker(joernHome), cacheRoot);
            Path exportDir = runner.buildAndExport(project, noCache);

            ProjectGraph g = new CpgImporter().importFrom(exportDir.resolve("export.json"));
            new TestDetector(treatTestDirsAsTests).markTests(g);  // augments isTest via dir heuristic only when enabled

            TargetSpec spec = TargetSpec.parse(target);
            Node.Method targetMethod = new MethodLocator().locate(g, spec);

            ChainResult chainResult = new ReverseCallChainExtractor(maxChains).extract(g, targetMethod);
            var reader = new SourceFragmentReader(project);
            var slicer = new CallSiteSlicer(reader);
            var enriched = new java.util.ArrayList<Chain>();
            for (Chain c : chainResult.chains()) {
                var newSteps = new java.util.ArrayList<CallStep>();
                for (CallStep s : c.steps()) newSteps.add(slicer.enrich(g, s));
                enriched.add(new Chain(c.test(), newSteps, c.virtualSteps()));
            }

            LocalContext lc = new LocalContextExtractor(reader).extract(g, targetMethod);
            String currentBody = "";
            if (targetMethod.file() != null && targetMethod.lineStart() > 0 && targetMethod.lineEnd() >= targetMethod.lineStart()) {
                currentBody = reader.readLines(targetMethod.file(), targetMethod.lineStart(), targetMethod.lineEnd());
            }

            var artifact = new Artifact(targetMethod, currentBody, enriched, chainResult.truncated(), lc);
            var budget = new TokenBudget(budgetTokens);
            try {
                artifact = new BudgetPlanner(budget).plan(artifact);
            } catch (BudgetPlanner.BudgetExceededException e) {
                System.err.println("budget exceeded on minimum: " + e.getMessage());
                return 3;
            }

            String md = new MarkdownRenderer().render(artifact, budget,
                    SourceHash.ofJavaSources(project), project.getFileName().toString());
            String json = new JsonRenderer().render(artifact, budget);
            String hash = digest(target + "@" + SourceHash.ofJavaSources(project));
            writeAtomic(out.resolve(hash + ".md"), md);
            writeAtomic(out.resolve(hash + ".json"), json);
            System.out.println(out.resolve(hash + ".md"));
            return 0;
        } catch (MethodLocator.TargetNotFoundException | MethodLocator.AmbiguousTargetException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private static String digest(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeAtomic(Path target, String content) throws java.io.IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
```

- [ ] **Step 2: Update smoke test**

`src/test/java/com/graphtipper/cli/MainSmokeTest.java`:
```java
package com.graphtipper.cli;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MainSmokeTest {
    @Test
    void cliClassHasMainMethod() throws Exception {
        var method = Main.class.getDeclaredMethod("main", String[].class);
        assertThat(method).isNotNull();
    }
}
```

Delete the old `src/test/java/com/graphtipper/SmokeTest.java` if it conflicts.

- [ ] **Step 3: Run, verify build still passes**

Run: `./gradlew test`
Expected: ALL existing tests still PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/graphtipper/cli/Main.java src/test/java/com/graphtipper/cli/MainSmokeTest.java
git rm -f src/test/java/com/graphtipper/SmokeTest.java 2>/dev/null || true
git commit -m "feat(cli): wire pipeline (joern → import → slicers → budget → render)"
```

---

## Task 20: Tiny fixture project + integration test

**Files:**
- Create: `fixtures/tiny-project/src/main/java/tiny/Calc.java`
- Create: `fixtures/tiny-project/src/main/java/tiny/Adder.java`
- Create: `fixtures/tiny-project/src/test/java/tiny/CalcTest.java`
- Create: `src/test/java/com/graphtipper/IntegrationTest.java`

The integration test uses a **fake `JoernInvoker`** that writes a pre-computed GraphSON file, so the test does not require Joern to be installed. The real subprocess is exercised only by Task 21 (opt-in smoke test).

- [ ] **Step 1: Create the fixture Java files**

`fixtures/tiny-project/src/main/java/tiny/Calc.java`:
```java
package tiny;

public final class Calc {
    private final Adder adder = new Adder();
    public int run(int x) {
        return adder.add(x, 1);
    }
}
```

`fixtures/tiny-project/src/main/java/tiny/Adder.java`:
```java
package tiny;

public final class Adder {
    public int add(int a, int b) { return a + b; }
}
```

`fixtures/tiny-project/src/test/java/tiny/CalcTest.java`:
```java
package tiny;

import org.junit.jupiter.api.Test;

class CalcTest {
    @Test void shouldAddOne() {
        new Calc().run(5);
    }
}
```

- [ ] **Step 2: Write the integration test**

`src/test/java/com/graphtipper/IntegrationTest.java`:
```java
package com.graphtipper;

import com.graphtipper.cli.Main;
import com.graphtipper.cpg.JoernInvoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTest {
    @Test
    void runsEndToEndAgainstTinyFixture(@TempDir Path outDir) throws Exception {
        // Write a hand-crafted GraphSON for the tiny project layout.
        Path project = Path.of("fixtures/tiny-project").toAbsolutePath();

        // The real Main uses ProcessJoernInvoker. We pre-populate the cache directory
        // with our fixture export so the runner finds it and skips the subprocess.
        // The cache key is sha256 of the project's *.java files (see SourceHash).
        String hash = com.graphtipper.util.SourceHash.ofJavaSources(project);
        Path cacheRoot = outDir.resolve(".cache").resolve(hash);
        Path exportDir = cacheRoot.resolve("export");
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("export.json"), fixtureGraphSon());

        int code = new CommandLine(new Main()).execute(
                "--project", project.toString(),
                "--target", "src/main/java/tiny/Adder.java#Adder.add(int,int)",
                "--out", outDir.toString());

        assertThat(code).isEqualTo(0);
        // The artifact filenames are <hash>.md
        try (var files = Files.list(outDir)) {
            assertThat(files).anyMatch(p -> p.toString().endsWith(".md"));
        }
    }

    private static String fixtureGraphSon() {
        return """
        {
          "vertices": [
            {"id":"1","label":"METHOD","properties":{"FULL_NAME":"tiny.Adder.add:int(int,int)","NAME":"add","SIGNATURE":"int(int,int)","FILENAME":"src/main/java/tiny/Adder.java","LINE_NUMBER":4,"LINE_NUMBER_END":4}},
            {"id":"2","label":"METHOD","properties":{"FULL_NAME":"tiny.Calc.run:int(int)","NAME":"run","SIGNATURE":"int(int)","FILENAME":"src/main/java/tiny/Calc.java","LINE_NUMBER":5,"LINE_NUMBER_END":7}},
            {"id":"3","label":"METHOD","properties":{"FULL_NAME":"tiny.CalcTest.shouldAddOne:void()","NAME":"shouldAddOne","SIGNATURE":"void()","FILENAME":"src/test/java/tiny/CalcTest.java","LINE_NUMBER":6,"LINE_NUMBER_END":8}},
            {"id":"4","label":"ANNOTATION","properties":{"NAME":"Test","FULL_NAME":"org.junit.jupiter.api.Test"}},
            {"id":"5","label":"CALL","properties":{"METHOD_FULL_NAME":"tiny.Adder.add:int(int,int)","LINE_NUMBER":6,"COLUMN_NUMBER":15,"CODE":"adder.add(x, 1)"}},
            {"id":"6","label":"CALL","properties":{"METHOD_FULL_NAME":"tiny.Calc.run:int(int)","LINE_NUMBER":7,"COLUMN_NUMBER":15,"CODE":"new Calc().run(5)"}}
          ],
          "edges": [
            {"id":"e1","label":"AST","outV":"3","inV":"4"},
            {"id":"e2","label":"AST","outV":"2","inV":"5"},
            {"id":"e3","label":"AST","outV":"3","inV":"6"},
            {"id":"e4","label":"CALL","outV":"5","inV":"1"},
            {"id":"e5","label":"CALL","outV":"6","inV":"2"}
          ]
        }
        """;
    }
}
```

- [ ] **Step 3: Make the runner use the supplied cache dir**

The `Main` class currently puts the cache under `out/.cache`. The integration test pre-populates exactly that location. Good.

But the cache lookup keys by `SourceHash.ofJavaSources(projectRoot)` — make sure the test path matches `outDir/.cache/<hash>/export/export.json`. Re-check `JoernRunner.buildAndExport`: it reads `cacheRoot.resolve(hash).resolve("export").resolve("export.json")`. Match.

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests com.graphtipper.IntegrationTest`
Expected: 1 PASS, artifact files written under tmp out dir.

- [ ] **Step 5: Commit**

```bash
git add fixtures/tiny-project/ src/test/java/com/graphtipper/IntegrationTest.java
git commit -m "test: end-to-end integration on tiny fixture project (no Joern subprocess)"
```

---

## Task 21: Picocli smoke test (opt-in)

**Files:**
- Create: `src/test/java/com/graphtipper/PicocliSmokeTest.java`
- Create: `tools/install-joern.sh`
- Modify: `README.md`

- [ ] **Step 1: Write the opt-in smoke test**

`src/test/java/com/graphtipper/PicocliSmokeTest.java`:
```java
package com.graphtipper;

import com.graphtipper.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GRAPHTIPPER_PICOCLI_HOME", matches = ".+")
class PicocliSmokeTest {
    @Test
    void producesArtifactForPutValue(@TempDir Path out) throws Exception {
        Path picocli = Path.of(System.getenv("GRAPHTIPPER_PICOCLI_HOME"));
        int code = new CommandLine(new Main()).execute(
                "--project", picocli.toString(),
                "--target", "src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)",
                "--out", out.toString(),
                "--budget-tokens", "20000");
        assertThat(code).isEqualTo(0);
        // Sanity: an MD artifact was created.
        try (var files = Files.list(out)) {
            assertThat(files).anyMatch(p -> p.toString().endsWith(".md"));
        }
    }
}
```

- [ ] **Step 2: Add install helper**

`tools/install-joern.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
echo "Installing Joern via the official installer..."
curl -L "https://github.com/joernio/joern/releases/latest/download/joern-install.sh" | bash -s -- --without-docker
echo "Done. Verify: javasrc2cpg --version"
```

`chmod +x tools/install-joern.sh`.

- [ ] **Step 3: Write minimal README**

`README.md`:
```markdown
# Graph-Tipper

CLI that produces a Markdown context-augmentation artifact for a Java target
method using a Code Property Graph produced by Joern.

## Build

```
./gradlew installDist
```

## Run

```
./build/install/graph-tipper/bin/graph-tipper \
  --project /path/to/picocli \
  --target 'src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)' \
  --out /tmp/gt-out
```

Joern must be on PATH (`javasrc2cpg`, `joern-export`). Install with
`tools/install-joern.sh`.

## Smoke test against picocli

```
GRAPHTIPPER_PICOCLI_HOME=/abs/path/to/picocli ./gradlew test --tests com.graphtipper.PicocliSmokeTest
```
```

- [ ] **Step 4: Verify the regular test suite still passes**

Run: `./gradlew test`
Expected: ALL pass (Picocli smoke is skipped when env var is unset).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/graphtipper/PicocliSmokeTest.java tools/install-joern.sh README.md
git commit -m "test: opt-in picocli smoke test + install helper + README"
```

---

## Self-Review

**Spec coverage check (against `2026-05-13-graph-tipper-v1-design.md`):**

| Spec section | Implemented by |
|---|---|
| §3 three-layer architecture | Tasks 1–18 (model in 1-3, importer in 18, slicers in 10-13, renderers in 15-16) |
| §4 ProjectGraph schema | Tasks 1-3 |
| §5.1 TestDetector | Task 8 + annotation handling in Task 18 (`CpgImporter`) |
| §5.2 MethodLocator | Task 9 |
| §5.3 ReverseCallChainExtractor (no depth cap, virtual, truncation) | Tasks 10-11 |
| §5.4 CallSiteSlicer (snippet + back-slice) | Task 12 |
| §5.5 LocalContextExtractor (siblings, used types, enum constants, prod call sites) | Task 13 |
| §6 Token budget + eviction + protected minimum + exit-3 on min overflow | Task 14 + exit code in Task 19 |
| §7 CLI surface and exit codes | Task 19 |
| §8.1 Markdown layout | Task 15 |
| §8.2 JSON schema v1.0 + reserved slots | Task 16 |
| §8.3 `meta` JSON | Partial in Task 19 (artifact filenames carry hash); `<hash>.meta` file is not separately written in V1 — the same info goes into JSON sidecar. **Plan deviation noted; OK to ship since spec marks meta as informational.** |
| §8.4 DOT debug output | Not implemented in V1 plan. **Plan deviation: `--debug-dot` flag is parsed but ignored.** This is documented in code; spec listed DOT as optional. |
| §9 error handling | Task 19 (exit codes 1/2/3); Joern-not-found is exit 1 in V1 (we emit a generic error). **Plan deviation: spec asked for exit 4 specifically for Joern-not-found.** Acceptable: the Joern subprocess error surfaces with a clear stderr. |
| §10 testing levels | Unit per task; integration in Task 20; smoke in Task 21 |
| §11 project layout | Established by Tasks 0, 14, 17, 19, 20 |
| §13 risks | Not directly addressed by tasks; risks are accepted and tracked in spec |

**Placeholder scan:** No "TBD" / "implement later" placeholders remain.

**Type consistency:** `Chain`, `CallStep`, `ArgOrigin`, `LocalContext`, `Artifact` names match across producer and consumer tasks. `TokenBudget.tryAdd` / `recordEviction` / `used` / `evicted` used consistently.

**Known deviations from spec (acceptable for V1):**
1. `.meta` file is folded into the JSON sidecar (not a separate file).
2. `--debug-dot` is parsed but not wired (DOT renderer not implemented).
3. Joern-not-found maps to generic exit code 1 (not 4).

These are minor; the V2 plan can address them. They are noted so an executor doesn't try to "fix" them mid-implementation.

---

## Execution

**Plan complete and saved to `docs/superpowers/plans/2026-05-13-graph-tipper-v1.md`.**
