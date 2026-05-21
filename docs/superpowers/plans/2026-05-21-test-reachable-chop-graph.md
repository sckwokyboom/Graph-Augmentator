# Test-reachable Chop Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a new `graph-tipper chop` subcommand that builds and renders a union backward+forward inter-procedural chop graph from a target method's statements up to JUnit test entry points, with per-statement attribution.

**Architecture:** Hybrid pipeline: Joern (via existing `CpgImporter`/`ProjectGraph`) supplies the inter-procedural call graph and virtual-call resolution; JavaParser builds per-method intra-procedural CFG/CDG/DDG; JGraphT holds the composed graph and provides reachability for chops; renderers emit DOT, GraphML, and standalone Cytoscape.js HTML.

**Tech Stack:** Java 21, Gradle Kotlin DSL, JUnit 5 + AssertJ, picocli 4.7, JavaParser 3.27 (with SymbolSolver), Jackson 2.18, JGraphT 1.5 (new), Joern (existing wiring).

**Spec:** [docs/superpowers/specs/2026-05-21-test-reachable-chop-graph-design.md](../specs/2026-05-21-test-reachable-chop-graph-design.md)

---

## File Structure

New code under `src/main/java/com/graphtipper/chop/`:

```
chop/
├── model/
│   ├── MethodRef.java          (record)
│   ├── StatementId.java        (record)
│   ├── ExprId.java             (record)
│   ├── SourceRange.java        (record)
│   ├── EdgeLayer.java          (enum)
│   ├── ResolutionKind.java     (enum)
│   ├── DataKind.java           (enum)
│   ├── StatementKind.java      (enum)
│   ├── ExpressionKind.java     (enum)
│   ├── ChopNode.java           (sealed interface)
│   ├── StatementNode.java      (record)
│   ├── ExprNode.java           (record)
│   ├── MethodNode.java         (record)
│   ├── ChopEdge.java           (record)
│   └── ChopGraph.java          (final class)
├── reach/
│   ├── EntryPointFinder.java
│   └── ReachabilityScan.java
├── pdg/
│   ├── JavaParserContext.java
│   ├── MethodPDG.java          (record)
│   ├── CfgConstructor.java
│   ├── CdgConstructor.java
│   ├── DdgConstructor.java
│   ├── ExpressionExtractor.java
│   └── PdgBuilder.java
├── compose/
│   └── ChopComposer.java
├── annotate/
│   └── ChopAnnotator.java
├── render/
│   ├── DotRenderer.java
│   ├── GraphMLRenderer.java
│   ├── CytoscapeJson.java
│   └── CytoscapeRenderer.java
└── cli/
    └── ChopCommand.java
```

Modified files:
- `build.gradle.kts` — add JGraphT
- `src/main/java/com/graphtipper/cli/Main.java` — refactor to picocli subcommand-capable root, register `ChopCommand`

Tests mirror the structure under `src/test/java/com/graphtipper/chop/...`.

---

## Phase 0 — Setup

### Task 1: Add JGraphT dependency

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Edit build.gradle.kts**

Add to the `dependencies` block (alphabetical with existing deps):

```kotlin
implementation("org.jgrapht:jgrapht-core:1.5.2")
implementation("org.jgrapht:jgrapht-io:1.5.2")
```

`jgrapht-core` for `DirectedMultigraph` + algorithms; `jgrapht-io` for `DOTExporter` and `GraphMLExporter`.

- [ ] **Step 2: Verify resolution**

Run: `gradle dependencies --configuration compileClasspath | grep jgrapht`
Expected: shows `org.jgrapht:jgrapht-core:1.5.2` and `org.jgrapht:jgrapht-io:1.5.2`.

- [ ] **Step 3: Compile**

Run: `gradle compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build(chop): add JGraphT dependency for chop graph"
```

---

### Task 2: Refactor Main into picocli subcommand-capable root

`Main.java` currently is a single `@Command` doing everything. We need a root command that holds shared flags and dispatches to either the legacy slice/budget pipeline (now under `slice` subcommand) or the new `chop` subcommand.

**Files:**
- Modify: `src/main/java/com/graphtipper/cli/Main.java`
- Create: `src/main/java/com/graphtipper/cli/SliceCommand.java`
- Create: `src/main/java/com/graphtipper/chop/cli/ChopCommand.java` (stub)
- Create: `src/test/java/com/graphtipper/chop/cli/ChopCommandSmokeTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/graphtipper/chop/cli/ChopCommandSmokeTest.java`:

```java
package com.graphtipper.chop.cli;

import com.graphtipper.cli.Main;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChopCommandSmokeTest {

    @Test
    void chopHelpListsRequiredOptions() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine cl = new CommandLine(new Main());
        cl.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int code = cl.execute("chop", "--help");
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(code).isZero();
        assertThat(text).contains("--project").contains("--target").contains("--out");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle test --tests com.graphtipper.chop.cli.ChopCommandSmokeTest`
Expected: FAIL because `chop` subcommand does not exist.

- [ ] **Step 3: Extract existing Main body into SliceCommand**

Move the existing `Main.call()` body and all its `@Option`-annotated fields into a new `SliceCommand` class. `Main` becomes a parent command with no logic of its own.

Create `src/main/java/com/graphtipper/cli/SliceCommand.java` with the **existing** Main's body, annotated:

```java
package com.graphtipper.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "slice",
    mixinStandardHelpOptions = true,
    description = "Build augmentation artefact for a target method (legacy slice pipeline)."
)
public final class SliceCommand implements Callable<Integer> {
    // Move all existing @Option fields and the call() body from Main here verbatim.
    // No behavioural change; this is a rename + relocation only.
    @Override
    public Integer call() throws Exception {
        // ... existing Main.call() body verbatim ...
        return 0;
    }
}
```

The exact body comes from current `Main.java` lines 16–230 approx. Verify by `git diff` after move that no business logic is altered.

- [ ] **Step 4: Rewrite Main as subcommand root**

Replace `src/main/java/com/graphtipper/cli/Main.java` body with:

```java
package com.graphtipper.cli;

import com.graphtipper.chop.cli.ChopCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "graph-tipper",
    mixinStandardHelpOptions = true,
    versionProvider = Main.VersionProvider.class,
    subcommands = { SliceCommand.class, ChopCommand.class }
)
public final class Main {

    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override public String[] getVersion() { return new String[] { "graph-tipper 0.2" }; }
    }
}
```

- [ ] **Step 5: Create ChopCommand stub**

Create `src/main/java/com/graphtipper/chop/cli/ChopCommand.java`:

```java
package com.graphtipper.chop.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "chop",
    mixinStandardHelpOptions = true,
    description = "Build a backward+forward inter-procedural chop graph for a target method."
)
public final class ChopCommand implements Callable<Integer> {

    @Option(names = "--project", required = true, description = "Absolute path to target repository.")
    Path project;

    @Option(names = "--target", required = true, description = "Target as FQN#method or path#Class.method(types).")
    String target;

    @Option(names = "--out", required = true, description = "Output directory.")
    Path out;

    @Option(names = "--max-depth", description = "Maximum reverse-call traversal depth. Default: unlimited.")
    Integer maxDepth = null;

    @Option(names = "--max-methods", description = "Guardrail; exit 3 if exceeded. Default: 500.")
    int maxMethods = 500;

    @Option(names = "--layers", split = ",",
            description = "Default render layers. Default: CG,DDG,CDG,ARG_PASS,RETURN_BIND.")
    String[] layers = { "CG", "DDG", "CDG", "ARG_PASS", "RETURN_BIND" };

    @Option(names = "--joern-home", description = "Joern installation directory.")
    Path joernHome;

    @Option(names = "--no-cache", description = "Bypass cached Joern export.")
    boolean noCache;

    @Override
    public Integer call() throws Exception {
        // Wired in Task 22.
        System.err.println("chop: pipeline not implemented yet");
        return 1;
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `gradle test --tests com.graphtipper.chop.cli.ChopCommandSmokeTest`
Expected: PASS.

Also confirm existing tests still pass: `gradle test --tests com.graphtipper.cli.MainSmokeTest`
Expected: PASS (after adjusting the test to invoke `slice` subcommand if it called the root with non-subcommand args — fix if necessary in this task).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphtipper/cli/Main.java \
        src/main/java/com/graphtipper/cli/SliceCommand.java \
        src/main/java/com/graphtipper/chop/cli/ChopCommand.java \
        src/test/java/com/graphtipper/chop/cli/ChopCommandSmokeTest.java
git commit -m "refactor(cli): split Main into slice subcommand, scaffold chop subcommand"
```

---

## Phase 1 — Data Model

### Task 3: Enums

**Files:**
- Create: `src/main/java/com/graphtipper/chop/model/EdgeLayer.java`
- Create: `src/main/java/com/graphtipper/chop/model/ResolutionKind.java`
- Create: `src/main/java/com/graphtipper/chop/model/DataKind.java`
- Create: `src/main/java/com/graphtipper/chop/model/StatementKind.java`
- Create: `src/main/java/com/graphtipper/chop/model/ExpressionKind.java`
- Create: `src/test/java/com/graphtipper/chop/model/EdgeLayerTest.java`

- [ ] **Step 1: Write failing test for layer parsing**

```java
package com.graphtipper.chop.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeLayerTest {

    @Test
    void parseExactMatchIsCaseInsensitive() {
        assertThat(EdgeLayer.parse("CG")).isEqualTo(EdgeLayer.CG);
        assertThat(EdgeLayer.parse("ddg")).isEqualTo(EdgeLayer.DDG);
        assertThat(EdgeLayer.parse("Arg_Pass")).isEqualTo(EdgeLayer.ARG_PASS);
    }

    @Test
    void parseUnknownThrows() {
        assertThatThrownBy(() -> EdgeLayer.parse("NOPE"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NOPE");
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.model.EdgeLayerTest`
Expected: FAIL (EdgeLayer not found).

- [ ] **Step 3: Implement enums**

`EdgeLayer.java`:
```java
package com.graphtipper.chop.model;

import java.util.Locale;

public enum EdgeLayer {
    AST, CFG, CDG, DDG, CG, OVERRIDES, ARG_PASS, RETURN_BIND;

    public static EdgeLayer parse(String s) {
        try { return EdgeLayer.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown EdgeLayer: " + s
                + ". Valid: AST, CFG, CDG, DDG, CG, OVERRIDES, ARG_PASS, RETURN_BIND");
        }
    }
}
```

`ResolutionKind.java`:
```java
package com.graphtipper.chop.model;
public enum ResolutionKind { EXACT, CHA, UNKNOWN }
```

`DataKind.java`:
```java
package com.graphtipper.chop.model;
public enum DataKind { DEF_USE, KILL, ARG, RETURN }
```

`StatementKind.java`:
```java
package com.graphtipper.chop.model;
public enum StatementKind {
    IF, WHILE, FOR, FOREACH, DO, RETURN, EXPR, THROW, TRY, CATCH, FINALLY, SWITCH, BLOCK, ASSERT, OTHER
}
```

`ExpressionKind.java`:
```java
package com.graphtipper.chop.model;
public enum ExpressionKind {
    CALLSITE, PARAM, LOCAL_DEF, FIELD_REF, LITERAL, RETURN_VALUE, BRANCH_PREDICATE, OTHER
}
```

- [ ] **Step 4: Run tests pass**

Run: `gradle test --tests com.graphtipper.chop.model.EdgeLayerTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/model/EdgeLayer.java \
        src/main/java/com/graphtipper/chop/model/ResolutionKind.java \
        src/main/java/com/graphtipper/chop/model/DataKind.java \
        src/main/java/com/graphtipper/chop/model/StatementKind.java \
        src/main/java/com/graphtipper/chop/model/ExpressionKind.java \
        src/test/java/com/graphtipper/chop/model/EdgeLayerTest.java
git commit -m "feat(chop/model): add EdgeLayer/ResolutionKind/DataKind/StatementKind/ExpressionKind enums"
```

---

### Task 4: Identifier records

**Files:**
- Create: `src/main/java/com/graphtipper/chop/model/MethodRef.java`
- Create: `src/main/java/com/graphtipper/chop/model/StatementId.java`
- Create: `src/main/java/com/graphtipper/chop/model/ExprId.java`
- Create: `src/main/java/com/graphtipper/chop/model/SourceRange.java`
- Create: `src/test/java/com/graphtipper/chop/model/MethodRefTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.graphtipper.chop.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MethodRefTest {

    @Test
    void displayIncludesFqnAndSignature() {
        MethodRef m = new MethodRef("com.example.Foo", "bar:int(java.lang.String)");
        assertThat(m.display()).isEqualTo("com.example.Foo#bar:int(java.lang.String)");
    }

    @Test
    void equalityIsValueBased() {
        MethodRef a = new MethodRef("p.C", "m:void()");
        MethodRef b = new MethodRef("p.C", "m:void()");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void statementIdFingerprintIsStable() {
        MethodRef m = new MethodRef("p.C", "m:void()");
        StatementId s1 = new StatementId(m, 42);
        StatementId s2 = new StatementId(m, 42);
        assertThat(s1).isEqualTo(s2).hasSameHashCodeAs(s2);
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.model.MethodRefTest`
Expected: FAIL.

- [ ] **Step 3: Implement records**

`MethodRef.java`:
```java
package com.graphtipper.chop.model;
import java.util.Objects;
public record MethodRef(String fqn, String signature) {
    public MethodRef {
        Objects.requireNonNull(fqn, "fqn");
        Objects.requireNonNull(signature, "signature");
    }
    public String display() { return fqn + "#" + signature; }
}
```

`StatementId.java`:
```java
package com.graphtipper.chop.model;
import java.util.Objects;
public record StatementId(MethodRef owner, int astNodeId) {
    public StatementId { Objects.requireNonNull(owner, "owner"); }
}
```

`ExprId.java`:
```java
package com.graphtipper.chop.model;
import java.util.Objects;
public record ExprId(MethodRef owner, int astNodeId) {
    public ExprId { Objects.requireNonNull(owner, "owner"); }
}
```

`SourceRange.java`:
```java
package com.graphtipper.chop.model;
import java.util.Objects;
public record SourceRange(String filePath, int startLine, int startCol, int endLine, int endCol) {
    public SourceRange { Objects.requireNonNull(filePath, "filePath"); }
    public String display() {
        return filePath + ":" + startLine + ":" + startCol + "-" + endLine + ":" + endCol;
    }
}
```

- [ ] **Step 4: Run tests pass**

Run: `gradle test --tests com.graphtipper.chop.model.MethodRefTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/model/*.java \
        src/test/java/com/graphtipper/chop/model/MethodRefTest.java
git commit -m "feat(chop/model): add MethodRef/StatementId/ExprId/SourceRange identifier records"
```

---

### Task 5: ChopNode hierarchy

**Files:**
- Create: `src/main/java/com/graphtipper/chop/model/ChopNode.java`
- Create: `src/main/java/com/graphtipper/chop/model/StatementNode.java`
- Create: `src/main/java/com/graphtipper/chop/model/ExprNode.java`
- Create: `src/main/java/com/graphtipper/chop/model/MethodNode.java`
- Create: `src/test/java/com/graphtipper/chop/model/ChopNodeTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.model;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.assertThat;

class ChopNodeTest {

    @Test
    void statementNodeIsChopNodeAndIdentifiable() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        StatementId id = new StatementId(m, 7);
        SourceRange src = new SourceRange("p/C.java", 3, 5, 3, 25);
        StatementNode n = new StatementNode(id, m, StatementKind.IF, "if (x > 0)",
            src, new HashSet<>(), false, false);
        ChopNode asBase = n;
        assertThat(asBase.owner()).isEqualTo(m);
        assertThat(n.id()).isEqualTo(id);
    }

    @Test
    void exprNodeCarriesEnclosingStatement() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        StatementId stmt = new StatementId(m, 7);
        ExprId expr = new ExprId(m, 9);
        SourceRange src = new SourceRange("p/C.java", 3, 9, 3, 14);
        ExprNode e = new ExprNode(expr, m, stmt, ExpressionKind.CALLSITE, "foo()",
            src, new HashSet<>(), false, false);
        assertThat(e.enclosingStatement()).isEqualTo(stmt);
    }

    @Test
    void methodNodeIdentifiedByRef() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        MethodNode mn = new MethodNode(m, false, true, new HashSet<>());
        assertThat(mn.isTarget()).isTrue();
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.model.ChopNodeTest`
Expected: FAIL.

- [ ] **Step 3: Implement sealed hierarchy**

`ChopNode.java`:
```java
package com.graphtipper.chop.model;

import java.util.Set;

public sealed interface ChopNode permits StatementNode, ExprNode, MethodNode {
    MethodRef owner();
    Set<StatementId> touchedBy();
    boolean isTarget();
    boolean isEntryPoint();
}
```

`StatementNode.java`:
```java
package com.graphtipper.chop.model;

import java.util.Set;

public record StatementNode(
    StatementId id,
    MethodRef owner,
    StatementKind kind,
    String displayText,
    SourceRange src,
    Set<StatementId> touchedBy,
    boolean isTarget,
    boolean isEntryPoint
) implements ChopNode {}
```

`ExprNode.java`:
```java
package com.graphtipper.chop.model;

import java.util.Set;

public record ExprNode(
    ExprId id,
    MethodRef owner,
    StatementId enclosingStatement,
    ExpressionKind kind,
    String displayText,
    SourceRange src,
    Set<StatementId> touchedBy,
    boolean isTarget,
    boolean isEntryPoint
) implements ChopNode {}
```

`MethodNode.java`:
```java
package com.graphtipper.chop.model;

import java.util.Set;

public record MethodNode(
    MethodRef owner,
    boolean isTest,
    boolean isTarget,
    Set<StatementId> touchedBy
) implements ChopNode {
    @Override public boolean isEntryPoint() { return isTest; }
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.model.ChopNodeTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/model/ChopNode.java \
        src/main/java/com/graphtipper/chop/model/StatementNode.java \
        src/main/java/com/graphtipper/chop/model/ExprNode.java \
        src/main/java/com/graphtipper/chop/model/MethodNode.java \
        src/test/java/com/graphtipper/chop/model/ChopNodeTest.java
git commit -m "feat(chop/model): add ChopNode sealed hierarchy"
```

---

### Task 6: ChopEdge + ChopGraph container

**Files:**
- Create: `src/main/java/com/graphtipper/chop/model/ChopEdge.java`
- Create: `src/main/java/com/graphtipper/chop/model/ChopGraph.java`
- Create: `src/test/java/com/graphtipper/chop/model/ChopGraphTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.model;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ChopGraphTest {

    @Test
    void addNodeAndEdgeStoresThemInJgraph() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        ChopGraph g = new ChopGraph(m, List.of(), Set.of());
        MethodNode mn = new MethodNode(m, false, true, new HashSet<>());
        g.addNode(mn);
        assertThat(g.jgraph().containsVertex(mn)).isTrue();
    }

    @Test
    void edgeRecordsLayerAndDataKind() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        ChopGraph g = new ChopGraph(m, List.of(), Set.of());
        MethodNode a = new MethodNode(m, false, true, new HashSet<>());
        MethodNode b = new MethodNode(new MethodRef("p.D", "g:void()"), false, false, new HashSet<>());
        g.addNode(a); g.addNode(b);
        ChopEdge e = new ChopEdge(a, b, EdgeLayer.CG, ResolutionKind.EXACT, null, "call", new HashSet<>());
        g.addEdge(e);
        assertThat(g.jgraph().edgeSet()).contains(e);
        assertThat(e.layer()).isEqualTo(EdgeLayer.CG);
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.model.ChopGraphTest`
Expected: FAIL.

- [ ] **Step 3: Implement ChopEdge and ChopGraph**

`ChopEdge.java`:
```java
package com.graphtipper.chop.model;

import java.util.Objects;
import java.util.Set;

public record ChopEdge(
    ChopNode src,
    ChopNode dst,
    EdgeLayer layer,
    ResolutionKind resolution,    // nullable when not CG/OVERRIDES
    DataKind dataKind,            // nullable when not DDG/ARG_PASS/RETURN_BIND
    String label,
    Set<StatementId> touchedBy
) {
    public ChopEdge {
        Objects.requireNonNull(src);
        Objects.requireNonNull(dst);
        Objects.requireNonNull(layer);
        Objects.requireNonNull(touchedBy);
    }
}
```

`ChopGraph.java`:
```java
package com.graphtipper.chop.model;

import org.jgrapht.graph.DirectedMultigraph;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ChopGraph {

    private final DirectedMultigraph<ChopNode, ChopEdge> jgraph =
        new DirectedMultigraph<>(ChopEdge.class);
    private final MethodRef target;
    private final List<StatementId> targetStatements;
    private final Set<MethodRef> entryPoints;
    private final Set<MethodRef> involvedMethods = new HashSet<>();

    public ChopGraph(MethodRef target, List<StatementId> targetStatements, Set<MethodRef> entryPoints) {
        this.target = Objects.requireNonNull(target);
        this.targetStatements = List.copyOf(targetStatements);
        this.entryPoints = Set.copyOf(entryPoints);
    }

    public DirectedMultigraph<ChopNode, ChopEdge> jgraph() { return jgraph; }
    public MethodRef target() { return target; }
    public List<StatementId> targetStatements() { return targetStatements; }
    public Set<MethodRef> entryPoints() { return entryPoints; }
    public Set<MethodRef> involvedMethods() { return involvedMethods; }

    public boolean addNode(ChopNode n) { involvedMethods.add(n.owner()); return jgraph.addVertex(n); }
    public boolean addEdge(ChopEdge e) {
        if (!jgraph.containsVertex(e.src())) addNode(e.src());
        if (!jgraph.containsVertex(e.dst())) addNode(e.dst());
        return jgraph.addEdge(e.src(), e.dst(), e);
    }
}
```

- [ ] **Step 4: Run tests pass**

Run: `gradle test --tests com.graphtipper.chop.model.ChopGraphTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/model/ChopEdge.java \
        src/main/java/com/graphtipper/chop/model/ChopGraph.java \
        src/test/java/com/graphtipper/chop/model/ChopGraphTest.java
git commit -m "feat(chop/model): add ChopEdge record and ChopGraph JGraphT wrapper"
```

---

## Phase 2 — Reachability

### Task 7: EntryPointFinder

Determines whether a `Node.Method` is a test entry point. JUnit annotations first (via `isTest` already set by `CpgImporter`), then heuristic fallback (file path under `src/test/`, class name suffix, method name prefix).

**Files:**
- Create: `src/main/java/com/graphtipper/chop/reach/EntryPointFinder.java`
- Create: `src/test/java/com/graphtipper/chop/reach/EntryPointFinderTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.reach;

import com.graphtipper.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EntryPointFinderTest {

    private static Node.Method method(String fqn, String file, boolean isTest) {
        return new Node.Method("m:" + fqn, fqn, "void()", List.of(), "void",
            file, 1, 10, "", isTest, false, List.of());
    }

    @Test
    void detectsJoernIsTestFlag() {
        EntryPointFinder f = new EntryPointFinder();
        assertThat(f.isEntry(method("p.FooTest.t1", "src/main/java/p/Foo.java", true))).isTrue();
    }

    @Test
    void heuristicSrcTestPath() {
        EntryPointFinder f = new EntryPointFinder();
        assertThat(f.isEntry(method("p.FooBar.helper", "src/test/java/p/FooBar.java", false))).isTrue();
    }

    @Test
    void heuristicClassNameSuffix() {
        EntryPointFinder f = new EntryPointFinder();
        assertThat(f.isEntry(method("p.BarIT.helper", "src/main/java/p/BarIT.java", false))).isTrue();
        assertThat(f.isEntry(method("p.BazTests.helper", "src/main/java/p/BazTests.java", false))).isTrue();
    }

    @Test
    void heuristicMethodNamePrefixOnly() {
        EntryPointFinder f = new EntryPointFinder();
        assertThat(f.isEntry(method("p.Util.testFoo", "src/main/java/p/Util.java", false))).isTrue();
    }

    @Test
    void plainMethodIsNotEntry() {
        EntryPointFinder f = new EntryPointFinder();
        assertThat(f.isEntry(method("p.Util.format", "src/main/java/p/Util.java", false))).isFalse();
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.reach.EntryPointFinderTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`EntryPointFinder.java`:
```java
package com.graphtipper.chop.reach;

import com.graphtipper.model.Node;

public final class EntryPointFinder {

    public boolean isEntry(Node.Method m) {
        if (m.isTest()) return true;                          // Joern @Test detection
        String file = m.file() == null ? "" : m.file();
        if (file.replace('\\', '/').contains("/src/test/")) return true;
        String fqn = m.fqn();
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return false;
        String classFqn = fqn.substring(0, lastDot);
        int classDot = classFqn.lastIndexOf('.');
        String simple = classDot < 0 ? classFqn : classFqn.substring(classDot + 1);
        if (simple.endsWith("Test") || simple.endsWith("Tests") || simple.endsWith("IT")) return true;
        String methodSimple = fqn.substring(lastDot + 1);
        return methodSimple.startsWith("test");
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.reach.EntryPointFinderTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/reach/EntryPointFinder.java \
        src/test/java/com/graphtipper/chop/reach/EntryPointFinderTest.java
git commit -m "feat(chop/reach): EntryPointFinder with JUnit + heuristic detection"
```

---

### Task 8: ReachabilityScan

BFS upward from target through `ProjectGraph.incomingCalls`, collecting `involvedMethods` and noting entry points along the way. Honours `--max-methods` guardrail.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/reach/ReachabilityScan.java`
- Create: `src/main/java/com/graphtipper/chop/reach/MaxMethodsExceededException.java`
- Create: `src/test/java/com/graphtipper/chop/reach/ReachabilityScanTest.java`

- [ ] **Step 1: Failing test (uses small in-memory ProjectGraph)**

```java
package com.graphtipper.chop.reach;

import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReachabilityScanTest {

    private static Node.Method m(String fqn, boolean isTest, String file) {
        return new Node.Method("m:" + fqn, fqn, "void()", List.of(), "void",
            file, 1, 1, "", isTest, false, List.of());
    }

    private static ProjectGraph buildChain() {
        // test (p.AppTest.t1) -> caller (p.Helper.h) -> target (p.Lib.target)
        ProjectGraph g = new ProjectGraph();
        Node.Method t = m("p.AppTest.t1", true, "src/test/java/p/AppTest.java");
        Node.Method h = m("p.Helper.h",   false, "src/main/java/p/Helper.java");
        Node.Method x = m("p.Lib.target", false, "src/main/java/p/Lib.java");
        g.addNode(t); g.addNode(h); g.addNode(x);
        g.addEdge(new Edge.Calls("m:p.AppTest.t1", "m:p.Helper.h", false));
        g.addEdge(new Edge.Calls("m:p.Helper.h",   "m:p.Lib.target", false));
        return g;
    }

    @Test
    void collectsInvolvedMethodsAndEntries() {
        ProjectGraph g = buildChain();
        Node.Method target = (Node.Method) g.byId("m:p.Lib.target");
        ReachabilityScan scan = new ReachabilityScan(new EntryPointFinder(), Integer.MAX_VALUE, 500);
        ReachabilityScan.Result r = scan.run(g, target);
        assertThat(r.involved()).extracting(Node.Method::fqn)
            .containsExactlyInAnyOrder("p.Lib.target", "p.Helper.h", "p.AppTest.t1");
        assertThat(r.entryPoints()).extracting(Node.Method::fqn).containsExactly("p.AppTest.t1");
    }

    @Test
    void maxMethodsGuardrailThrows() {
        ProjectGraph g = buildChain();
        Node.Method target = (Node.Method) g.byId("m:p.Lib.target");
        ReachabilityScan scan = new ReachabilityScan(new EntryPointFinder(), Integer.MAX_VALUE, 2);
        assertThatThrownBy(() -> scan.run(g, target))
            .isInstanceOf(MaxMethodsExceededException.class);
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.reach.ReachabilityScanTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`MaxMethodsExceededException.java`:
```java
package com.graphtipper.chop.reach;
public final class MaxMethodsExceededException extends RuntimeException {
    public final int count;
    public MaxMethodsExceededException(int count) {
        super("Reachable methods exceeded limit (" + count + ")");
        this.count = count;
    }
}
```

`ReachabilityScan.java`:
```java
package com.graphtipper.chop.reach;

import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;

import java.util.*;

public final class ReachabilityScan {

    private final EntryPointFinder entries;
    private final int maxDepth;
    private final int maxMethods;

    public ReachabilityScan(EntryPointFinder entries, int maxDepth, int maxMethods) {
        this.entries = entries;
        this.maxDepth = maxDepth;
        this.maxMethods = maxMethods;
    }

    public record Result(Set<Node.Method> involved, Set<Node.Method> entryPoints) {}

    public Result run(ProjectGraph g, Node.Method target) {
        Set<Node.Method> involved = new LinkedHashSet<>();
        Set<Node.Method> entryPoints = new LinkedHashSet<>();
        Deque<Step> queue = new ArrayDeque<>();
        queue.add(new Step(target, 0));
        involved.add(target);

        while (!queue.isEmpty()) {
            Step s = queue.poll();
            if (s.depth >= maxDepth) continue;
            for (Edge.Calls c : g.incomingCalls(s.method.id())) {
                Node caller = g.byId(c.fromId());
                if (!(caller instanceof Node.Method cm)) continue;
                if (involved.add(cm)) {
                    if (involved.size() > maxMethods)
                        throw new MaxMethodsExceededException(involved.size());
                    if (entries.isEntry(cm)) entryPoints.add(cm);
                    queue.add(new Step(cm, s.depth + 1));
                }
            }
        }
        return new Result(involved, entryPoints);
    }

    private record Step(Node.Method method, int depth) {}
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.reach.ReachabilityScanTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/reach/ReachabilityScan.java \
        src/main/java/com/graphtipper/chop/reach/MaxMethodsExceededException.java \
        src/test/java/com/graphtipper/chop/reach/ReachabilityScanTest.java
git commit -m "feat(chop/reach): ReachabilityScan BFS over ProjectGraph"
```

---

## Phase 3 — JavaParser PDG

### Task 9: JavaParserContext

Reusable JavaParser setup with `JavaSymbolSolver` rooted at the project's `src/main/java` (and `src/test/java` when scanning test entry points). Mirrors the approach used by `StaticSlicer`.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/pdg/JavaParserContext.java`
- Create: `src/test/java/com/graphtipper/chop/pdg/JavaParserContextTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.pdg;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class JavaParserContextTest {

    @Test
    void parsesAndResolvesLocalSymbols(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/java/p/C.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package p;
            public class C {
                int f(int x) { int y = x + 1; return y; }
            }
            """);
        JavaParserContext ctx = JavaParserContext.forProject(tmp);
        Optional<CompilationUnit> cu = ctx.parser().parse(src).getResult();
        assertThat(cu).isPresent();
        MethodDeclaration md = cu.get().findFirst(MethodDeclaration.class).orElseThrow();
        assertThat(md.getNameAsString()).isEqualTo("f");
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.pdg.JavaParserContextTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`JavaParserContext.java`:
```java
package com.graphtipper.chop.pdg;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class JavaParserContext {

    private final JavaParser parser;
    private final Path projectRoot;

    private JavaParserContext(JavaParser parser, Path projectRoot) {
        this.parser = parser;
        this.projectRoot = projectRoot;
    }

    public JavaParser parser() { return parser; }
    public Path projectRoot() { return projectRoot; }

    public static JavaParserContext forProject(Path projectRoot) {
        Objects.requireNonNull(projectRoot);
        CombinedTypeSolver ts = new CombinedTypeSolver();
        ts.add(new ReflectionTypeSolver());
        Path mainSrc = projectRoot.resolve("src/main/java");
        Path testSrc = projectRoot.resolve("src/test/java");
        if (Files.isDirectory(mainSrc)) ts.add(new JavaParserTypeSolver(mainSrc));
        if (Files.isDirectory(testSrc)) ts.add(new JavaParserTypeSolver(testSrc));
        ParserConfiguration config = new ParserConfiguration()
            .setSymbolResolver(new JavaSymbolSolver(ts))
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        return new JavaParserContext(new JavaParser(config), projectRoot);
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.pdg.JavaParserContextTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/pdg/JavaParserContext.java \
        src/test/java/com/graphtipper/chop/pdg/JavaParserContextTest.java
git commit -m "feat(chop/pdg): JavaParserContext with SymbolSolver setup"
```

---

### Task 10: MethodPDG record

Container that bundles per-method nodes/edges produced by CFG/CDG/DDG/Expression constructors. Used to pass intermediate analyses between phases.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/pdg/MethodPDG.java`

- [ ] **Step 1: Create record (no test — passthrough container)**

```java
package com.graphtipper.chop.pdg;

import com.graphtipper.chop.model.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record MethodPDG(
    MethodRef ref,
    MethodNode methodNode,
    List<StatementNode> statements,
    List<ExprNode> expressions,
    List<ChopEdge> intraEdges,                  // CFG/CDG/DDG/AST inside this method
    List<ExprNode> parameters,                  // order matters; index i = parameter i
    List<ExprNode> returnValues,                // 0..N return expressions
    Map<StatementId, List<ExprNode>> bodyByStatement
) {}
```

- [ ] **Step 2: Compile**

Run: `gradle compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/graphtipper/chop/pdg/MethodPDG.java
git commit -m "feat(chop/pdg): MethodPDG record as analysis bundle"
```

---

### Task 11: CfgConstructor

Builds CFG inside one method using JavaParser's `MethodDeclaration` body. Produces `StatementNode`s with CFG edges between them.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/pdg/CfgConstructor.java`
- Create: `src/test/java/com/graphtipper/chop/pdg/CfgConstructorTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.pdg;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CfgConstructorTest {

    @Test
    void linearMethodHasSequentialCfgEdges() {
        CompilationUnit cu = StaticJavaParser.parse("""
            class C { void f() { int a = 1; int b = 2; int c = a + b; } }
            """);
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class).orElseThrow();
        MethodRef ref = new MethodRef("C", "f:void()");
        CfgConstructor cfg = new CfgConstructor();
        CfgConstructor.Result r = cfg.build(md, ref);
        assertThat(r.statements()).hasSize(3);
        assertThat(r.edges()).hasSize(2);                  // 1->2, 2->3
        assertThat(r.edges()).allMatch(e -> e.layer() == EdgeLayer.CFG);
    }

    @Test
    void ifStatementProducesPredicateAndTwoBranches() {
        CompilationUnit cu = StaticJavaParser.parse("""
            class C { int f(int x) { if (x > 0) { return 1; } else { return -1; } } }
            """);
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class).orElseThrow();
        MethodRef ref = new MethodRef("C", "f:int(int)");
        CfgConstructor.Result r = new CfgConstructor().build(md, ref);
        // statement count: if(1) + return-in-then(1) + return-in-else(1) = 3
        assertThat(r.statements()).extracting(StatementNode::kind)
            .contains(StatementKind.IF, StatementKind.RETURN, StatementKind.RETURN);
        // predicate has two outgoing CFG edges (one to then-return, one to else-return)
        StatementNode ifNode = r.statements().stream()
            .filter(s -> s.kind() == StatementKind.IF).findFirst().orElseThrow();
        long outDeg = r.edges().stream().filter(e -> e.src().equals(ifNode)).count();
        assertThat(outDeg).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.pdg.CfgConstructorTest`
Expected: FAIL.

- [ ] **Step 3: Implement** (focus on the common Java statements; see implementation notes)

`CfgConstructor.java`:
```java
package com.graphtipper.chop.pdg;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;
import com.graphtipper.chop.model.*;

import java.util.*;

public final class CfgConstructor {

    public record Result(List<StatementNode> statements, List<ChopEdge> edges,
                         Map<StatementId, Statement> astByStatement) {}

    public Result build(MethodDeclaration md, MethodRef ref) {
        Map<StatementId, StatementNode> nodes = new LinkedHashMap<>();
        Map<StatementId, Statement> astMap = new LinkedHashMap<>();
        List<ChopEdge> edges = new ArrayList<>();
        if (md.getBody().isEmpty()) return new Result(List.of(), List.of(), Map.of());
        BlockStmt body = md.getBody().get();
        // Walk in source order to collect statements with stable IDs.
        body.walk(Statement.class, s -> {
            StatementId id = new StatementId(ref, identityHash(s));
            StatementKind kind = classify(s);
            String text = oneLine(s.toString());
            SourceRange src = sourceRange(s, md);
            StatementNode sn = new StatementNode(id, ref, kind, text, src,
                new HashSet<>(), false, false);
            nodes.put(id, sn);
            astMap.put(id, s);
        });
        // Build CFG edges by following control flow semantics.
        Cfg cfg = new Cfg(ref, nodes, astMap, edges);
        cfg.visit(body);
        return new Result(new ArrayList<>(nodes.values()), edges, astMap);
    }

    // --- helpers ---

    private static int identityHash(Node n) {
        // Stable per AST identity; combine file path hash + JavaParser Range if available.
        int rangeHash = n.getRange().map(r -> r.begin.line * 1000 + r.begin.column).orElse(0);
        return rangeHash * 31 + n.getClass().getSimpleName().hashCode();
    }

    private static SourceRange sourceRange(Node s, MethodDeclaration md) {
        String file = md.findCompilationUnit()
            .flatMap(cu -> cu.getStorage().map(st -> st.getPath().toString()))
            .orElse("<unknown>");
        var r = s.getRange().orElse(null);
        if (r == null) return new SourceRange(file, 0, 0, 0, 0);
        return new SourceRange(file, r.begin.line, r.begin.column, r.end.line, r.end.column);
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 117) + "..." : t;
    }

    static StatementKind classify(Statement s) {
        if (s instanceof IfStmt) return StatementKind.IF;
        if (s instanceof WhileStmt) return StatementKind.WHILE;
        if (s instanceof DoStmt) return StatementKind.DO;
        if (s instanceof ForStmt) return StatementKind.FOR;
        if (s instanceof ForEachStmt) return StatementKind.FOREACH;
        if (s instanceof ReturnStmt) return StatementKind.RETURN;
        if (s instanceof ThrowStmt) return StatementKind.THROW;
        if (s instanceof TryStmt) return StatementKind.TRY;
        if (s instanceof SwitchStmt) return StatementKind.SWITCH;
        if (s instanceof BlockStmt) return StatementKind.BLOCK;
        if (s instanceof AssertStmt) return StatementKind.ASSERT;
        if (s instanceof ExpressionStmt) return StatementKind.EXPR;
        return StatementKind.OTHER;
    }

    // Inner CFG walker. Implements: sequence, if/else, while, for, foreach,
    // do-while, return (terminal), throw (terminal), try/catch (catches all join post-try).
    private static final class Cfg {
        final MethodRef ref;
        final Map<StatementId, StatementNode> nodes;
        final Map<StatementId, Statement> astMap;
        final List<ChopEdge> edges;

        Cfg(MethodRef ref, Map<StatementId, StatementNode> nodes,
            Map<StatementId, Statement> astMap, List<ChopEdge> edges) {
            this.ref = ref; this.nodes = nodes; this.astMap = astMap; this.edges = edges;
        }

        // Visits the body and connects fall-through edges. Returns the set of
        // "exit points" — statements whose successor would be the next statement
        // outside the current construct.
        List<StatementNode> visit(Statement s) {
            if (s instanceof BlockStmt b) return visitBlock(b);
            if (s instanceof IfStmt ifs) return visitIf(ifs);
            if (s instanceof WhileStmt w) return visitWhile(w);
            if (s instanceof ForStmt f) return visitFor(f);
            if (s instanceof ForEachStmt fe) return visitForEach(fe);
            if (s instanceof DoStmt d) return visitDo(d);
            if (s instanceof ReturnStmt || s instanceof ThrowStmt) {
                return List.of(); // terminal — no fall-through
            }
            return List.of(nodeOf(s));
        }

        private List<StatementNode> visitBlock(BlockStmt b) {
            List<StatementNode> prev = List.of();
            for (Statement s : b.getStatements()) {
                List<StatementNode> entry = entryPoints(s);
                connect(prev, entry);
                prev = visit(s);
            }
            return prev;
        }

        private List<StatementNode> visitIf(IfStmt ifs) {
            StatementNode predicate = nodeOf(ifs);
            List<StatementNode> thenExit = visit(ifs.getThenStmt());
            connect(List.of(predicate), entryPoints(ifs.getThenStmt()));
            List<StatementNode> elseExit;
            if (ifs.getElseStmt().isPresent()) {
                Statement el = ifs.getElseStmt().get();
                elseExit = visit(el);
                connect(List.of(predicate), entryPoints(el));
            } else {
                elseExit = List.of(predicate);
            }
            List<StatementNode> joined = new ArrayList<>(thenExit);
            joined.addAll(elseExit);
            return joined;
        }

        private List<StatementNode> visitWhile(WhileStmt w) {
            StatementNode predicate = nodeOf(w);
            connect(List.of(predicate), entryPoints(w.getBody()));
            List<StatementNode> bodyExit = visit(w.getBody());
            connect(bodyExit, List.of(predicate));
            return List.of(predicate);
        }

        private List<StatementNode> visitFor(ForStmt f) {
            StatementNode predicate = nodeOf(f);
            connect(List.of(predicate), entryPoints(f.getBody()));
            List<StatementNode> bodyExit = visit(f.getBody());
            connect(bodyExit, List.of(predicate));
            return List.of(predicate);
        }

        private List<StatementNode> visitForEach(ForEachStmt fe) {
            StatementNode predicate = nodeOf(fe);
            connect(List.of(predicate), entryPoints(fe.getBody()));
            List<StatementNode> bodyExit = visit(fe.getBody());
            connect(bodyExit, List.of(predicate));
            return List.of(predicate);
        }

        private List<StatementNode> visitDo(DoStmt d) {
            StatementNode predicate = nodeOf(d);
            List<StatementNode> bodyExit = visit(d.getBody());
            connect(bodyExit, List.of(predicate));
            connect(List.of(predicate), entryPoints(d.getBody()));
            return List.of(predicate);
        }

        private StatementNode nodeOf(Statement s) {
            StatementId id = new StatementId(ref, identityHash(s));
            return nodes.get(id);
        }

        private List<StatementNode> entryPoints(Statement s) {
            // For most statements, entry = the statement itself; for blocks, entry =
            // first statement.
            if (s instanceof BlockStmt b && !b.getStatements().isEmpty()) {
                return entryPoints(b.getStatements().get(0));
            }
            StatementNode n = nodeOf(s);
            return n == null ? List.of() : List.of(n);
        }

        private void connect(List<StatementNode> from, List<StatementNode> to) {
            for (StatementNode a : from) {
                for (StatementNode b : to) {
                    edges.add(new ChopEdge(a, b, EdgeLayer.CFG, null, null, "", new HashSet<>()));
                }
            }
        }
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.pdg.CfgConstructorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/pdg/CfgConstructor.java \
        src/test/java/com/graphtipper/chop/pdg/CfgConstructorTest.java
git commit -m "feat(chop/pdg): CfgConstructor for intra-method control flow"
```

---

### Task 12: CdgConstructor

Computes control dependence edges from CFG using post-dominator-based algorithm. Each statement S whose execution is controlled by a predicate P gets `P --CDG--> S`.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/pdg/CdgConstructor.java`
- Create: `src/test/java/com/graphtipper/chop/pdg/CdgConstructorTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.pdg;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CdgConstructorTest {

    @Test
    void ifThenStatementsAreControlDependentOnPredicate() {
        CompilationUnit cu = StaticJavaParser.parse("""
            class C { int f(int x) { if (x > 0) { int y = x + 1; return y; } return 0; } }
            """);
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class).orElseThrow();
        MethodRef ref = new MethodRef("C", "f:int(int)");
        CfgConstructor.Result cfg = new CfgConstructor().build(md, ref);
        List<ChopEdge> cdg = new CdgConstructor().build(cfg);
        // We expect at least one CDG edge from the IF predicate to the inner statements.
        StatementNode ifNode = cfg.statements().stream()
            .filter(s -> s.kind() == StatementKind.IF).findFirst().orElseThrow();
        long fromIf = cdg.stream().filter(e -> e.src().equals(ifNode)
            && e.layer() == EdgeLayer.CDG).count();
        assertThat(fromIf).isGreaterThanOrEqualTo(2);     // both inner stmts
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.pdg.CdgConstructorTest`
Expected: FAIL.

- [ ] **Step 3: Implement (Ferrante-Ottenstein-Warren)**

`CdgConstructor.java`:
```java
package com.graphtipper.chop.pdg;

import com.graphtipper.chop.model.*;
import org.jgrapht.alg.lca.NaiveLCAFinder;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;

import java.util.*;

public final class CdgConstructor {

    public List<ChopEdge> build(CfgConstructor.Result cfg) {
        if (cfg.statements().isEmpty()) return List.of();
        // 1. Build CFG as JGraphT for post-dominator computation.
        SimpleDirectedGraph<StatementNode, DefaultEdge> g =
            new SimpleDirectedGraph<>(DefaultEdge.class);
        for (StatementNode s : cfg.statements()) g.addVertex(s);
        for (ChopEdge e : cfg.edges()) {
            if (e.layer() == EdgeLayer.CFG && !g.containsEdge((StatementNode) e.src(),
                                                              (StatementNode) e.dst())) {
                g.addEdge((StatementNode) e.src(), (StatementNode) e.dst());
            }
        }
        // 2. Post-dominator tree = dominator tree of reversed CFG.
        StatementNode exit = pickExit(cfg.statements(), g);
        Map<StatementNode, StatementNode> postIdom = postIdom(g, exit);
        // 3. For each CFG edge (A → B): walk up post-dominator tree from B; every
        //    node L on the path until A's post-immediate-dominator is control-dependent on A.
        List<ChopEdge> result = new ArrayList<>();
        for (DefaultEdge edge : g.edgeSet()) {
            StatementNode a = g.getEdgeSource(edge);
            StatementNode b = g.getEdgeTarget(edge);
            StatementNode aPid = postIdom.get(a);
            StatementNode cur = b;
            while (cur != null && !cur.equals(aPid)) {
                result.add(new ChopEdge(a, cur, EdgeLayer.CDG, null, null, "", new HashSet<>()));
                cur = postIdom.get(cur);
            }
        }
        return result;
    }

    private static StatementNode pickExit(List<StatementNode> all,
                                          SimpleDirectedGraph<StatementNode, DefaultEdge> g) {
        // Synthetic exit isn't strictly needed if we treat all sink nodes (no outgoing CFG)
        // as terminals. Pick any sink as exit; for methods with multiple returns/throws,
        // they all become post-dominated by the same virtual exit node — we approximate
        // by picking the LAST statement in source order as exit.
        return all.get(all.size() - 1);
    }

    private static Map<StatementNode, StatementNode> postIdom(
        SimpleDirectedGraph<StatementNode, DefaultEdge> g, StatementNode exit) {
        // Compute immediate post-dominators using the simple iterative dominator
        // algorithm on the reversed graph.
        SimpleDirectedGraph<StatementNode, DefaultEdge> rev =
            new SimpleDirectedGraph<>(DefaultEdge.class);
        for (StatementNode v : g.vertexSet()) rev.addVertex(v);
        for (DefaultEdge e : g.edgeSet())
            rev.addEdge(g.getEdgeTarget(e), g.getEdgeSource(e));
        Map<StatementNode, Set<StatementNode>> dom = new HashMap<>();
        Set<StatementNode> all = new HashSet<>(g.vertexSet());
        for (StatementNode v : g.vertexSet())
            dom.put(v, v.equals(exit) ? Set.of(exit) : new HashSet<>(all));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StatementNode v : g.vertexSet()) {
                if (v.equals(exit)) continue;
                Set<StatementNode> next = null;
                for (DefaultEdge e : rev.incomingEdgesOf(v)) {   // predecessors in reversed graph
                    StatementNode p = rev.getEdgeSource(e);
                    if (next == null) next = new HashSet<>(dom.get(p));
                    else next.retainAll(dom.get(p));
                }
                if (next == null) next = new HashSet<>();
                next.add(v);
                if (!next.equals(dom.get(v))) { dom.put(v, next); changed = true; }
            }
        }
        // Immediate post-dominator = dom(v) \ {v} closest to v in the post-dom tree.
        Map<StatementNode, StatementNode> idom = new HashMap<>();
        for (StatementNode v : g.vertexSet()) {
            if (v.equals(exit)) continue;
            Set<StatementNode> domSet = new HashSet<>(dom.get(v));
            domSet.remove(v);
            StatementNode best = null;
            for (StatementNode d : domSet) {
                if (best == null) { best = d; continue; }
                // d is closer than best if dom(d) ⊇ dom(best)
                if (dom.get(d).containsAll(dom.get(best))) best = d;
            }
            idom.put(v, best);
        }
        return idom;
    }
}
```

> Implementation note: NaiveLCAFinder import retained intentionally — used in optional refinements; safe to remove if not needed by linter.

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.pdg.CdgConstructorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/pdg/CdgConstructor.java \
        src/test/java/com/graphtipper/chop/pdg/CdgConstructorTest.java
git commit -m "feat(chop/pdg): CdgConstructor via post-dominator analysis"
```

---

### Task 13: DdgConstructor

Intra-method def-use chains at variable level using JavaParser SymbolSolver. Produces `DDG{DEF_USE}` edges between expression-nodes (LOCAL_DEF / PARAM → use-site).

**Files:**
- Create: `src/main/java/com/graphtipper/chop/pdg/DdgConstructor.java`
- Create: `src/test/java/com/graphtipper/chop/pdg/DdgConstructorTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.pdg;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DdgConstructorTest {

    @Test
    void definitionFlowsToUse() {
        CompilationUnit cu = StaticJavaParser.parse("""
            class C { int f(int x) { int y = x + 1; return y; } }
            """);
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class).orElseThrow();
        MethodRef ref = new MethodRef("C", "f:int(int)");
        var cfg = new CfgConstructor().build(md, ref);
        var ee = new ExpressionExtractor().extract(md, ref, cfg);
        DdgConstructor.Result r = new DdgConstructor().build(md, ref, cfg, ee);
        // expect a DDG edge from def(y) to use(y) in the return
        long defUse = r.edges().stream()
            .filter(e -> e.layer() == EdgeLayer.DDG
                && e.dataKind() == DataKind.DEF_USE
                && e.label().contains("y"))
            .count();
        assertThat(defUse).isGreaterThanOrEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.pdg.DdgConstructorTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`DdgConstructor.java`:
```java
package com.graphtipper.chop.pdg;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.graphtipper.chop.model.*;

import java.util.*;

public final class DdgConstructor {

    public record Result(List<ChopEdge> edges) {}

    public Result build(MethodDeclaration md, MethodRef ref,
                        CfgConstructor.Result cfg, ExpressionExtractor.Result ee) {
        // Build map: variable name -> latest def ExprNode encountered in source order.
        // For each NameExpr use, link the most recent def in scope.
        // PoC limitation: scope = the whole method (ignores block scoping). This is
        // acceptable because the next pass (CFG-driven reaching defs) is left as
        // future work; for `slicePerReturn`-class code this gives correct results.
        Map<String, ExprNode> latestDef = new HashMap<>();
        for (ExprNode pn : ee.parameters()) {
            latestDef.put(pn.displayText().split(":")[0].trim(), pn);
        }
        List<ChopEdge> edges = new ArrayList<>();
        md.walk(com.github.javaparser.ast.Node.class, n -> {
            if (n instanceof VariableDeclarator vd) {
                ExprNode def = ee.exprFor(vd);
                if (def != null) latestDef.put(vd.getNameAsString(), def);
            } else if (n instanceof NameExpr ne) {
                ExprNode def = latestDef.get(ne.getNameAsString());
                ExprNode use = ee.exprFor(ne);
                if (def != null && use != null && !def.equals(use)) {
                    edges.add(new ChopEdge(def, use, EdgeLayer.DDG, null, DataKind.DEF_USE,
                        ne.getNameAsString(), new HashSet<>()));
                }
            }
        });
        return new Result(edges);
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.pdg.DdgConstructorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/pdg/DdgConstructor.java \
        src/test/java/com/graphtipper/chop/pdg/DdgConstructorTest.java
git commit -m "feat(chop/pdg): DdgConstructor for intra-method def-use chains"
```

---

### Task 14: ExpressionExtractor

For each statement, extract sub-expression `ExprNode`s: callsites, parameters, local definitions, literals, branch predicates, return values. Produces nodes + AST edges between statement → expressions.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/pdg/ExpressionExtractor.java`
- Create: `src/test/java/com/graphtipper/chop/pdg/ExpressionExtractorTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.pdg;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionExtractorTest {

    @Test
    void extractsCallsiteAndPredicate() {
        var cu = StaticJavaParser.parse("""
            class C { int f(int x) { if (g(x) > 0) { return 1; } return 0; }
                      int g(int y) { return y; } }
            """);
        MethodDeclaration md = cu.getClassByName("C").orElseThrow()
            .getMethodsByName("f").get(0);
        MethodRef ref = new MethodRef("C", "f:int(int)");
        var cfg = new CfgConstructor().build(md, ref);
        ExpressionExtractor.Result r = new ExpressionExtractor().extract(md, ref, cfg);
        assertThat(r.expressions()).extracting(ExprNode::kind)
            .contains(ExpressionKind.CALLSITE, ExpressionKind.BRANCH_PREDICATE,
                      ExpressionKind.PARAM, ExpressionKind.RETURN_VALUE);
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.pdg.ExpressionExtractorTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`ExpressionExtractor.java`:
```java
package com.graphtipper.chop.pdg;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.graphtipper.chop.model.*;

import java.util.*;

public final class ExpressionExtractor {

    public record Result(
        List<ExprNode> expressions,
        List<ExprNode> parameters,
        List<ExprNode> returnValues,
        List<ChopEdge> astEdges,
        Map<Node, ExprNode> astToExpr
    ) {
        public ExprNode exprFor(Node n) { return astToExpr.get(n); }
    }

    public Result extract(MethodDeclaration md, MethodRef ref, CfgConstructor.Result cfg) {
        Map<Node, ExprNode> map = new IdentityHashMap<>();
        List<ExprNode> all = new ArrayList<>();
        List<ExprNode> params = new ArrayList<>();
        List<ExprNode> returns = new ArrayList<>();
        List<ChopEdge> ast = new ArrayList<>();

        // 1. Parameters — synthetic statement id = -1 (no enclosing user statement)
        StatementId synthStmt = new StatementId(ref, -1);
        for (Parameter p : md.getParameters()) {
            ExprNode pn = mkExpr(p, ref, synthStmt, ExpressionKind.PARAM,
                p.getNameAsString() + ":" + p.getTypeAsString(), md);
            params.add(pn); all.add(pn); map.put(p, pn);
        }
        // 2. For each statement, walk for relevant expressions.
        for (StatementNode sn : cfg.statements()) {
            Statement astStmt = cfg.astByStatement().get(sn.id());
            if (astStmt == null) continue;
            astStmt.walk(Node.class, n -> {
                ExprNode created = null;
                if (n instanceof MethodCallExpr mc) {
                    created = mkExpr(mc, ref, sn.id(), ExpressionKind.CALLSITE,
                        callSig(mc), md);
                } else if (n instanceof VariableDeclarator vd) {
                    created = mkExpr(vd, ref, sn.id(), ExpressionKind.LOCAL_DEF,
                        vd.getNameAsString() + ":" + vd.getTypeAsString(), md);
                } else if (n instanceof LiteralExpr le) {
                    created = mkExpr(le, ref, sn.id(), ExpressionKind.LITERAL,
                        le.toString(), md);
                } else if (n instanceof FieldAccessExpr fa) {
                    created = mkExpr(fa, ref, sn.id(), ExpressionKind.FIELD_REF,
                        fa.toString(), md);
                }
                if (created != null) {
                    all.add(created); map.put(n, created);
                    StatementNode parent = sn;
                    ast.add(new ChopEdge(parent, created, EdgeLayer.AST, null, null, "",
                        new HashSet<>()));
                }
            });
            // Branch predicate
            Expression predicate = predicateOf(astStmt);
            if (predicate != null) {
                ExprNode bp = mkExpr(predicate, ref, sn.id(), ExpressionKind.BRANCH_PREDICATE,
                    oneLine(predicate.toString()), md);
                all.add(bp); map.putIfAbsent(predicate, bp);
                ast.add(new ChopEdge(sn, bp, EdgeLayer.AST, null, null, "predicate", new HashSet<>()));
            }
            // Return value
            if (astStmt instanceof ReturnStmt rs && rs.getExpression().isPresent()) {
                Expression rex = rs.getExpression().get();
                ExprNode rv = mkExpr(rex, ref, sn.id(), ExpressionKind.RETURN_VALUE,
                    oneLine(rex.toString()), md);
                all.add(rv); returns.add(rv); map.put(rex, rv);
                ast.add(new ChopEdge(sn, rv, EdgeLayer.AST, null, null, "return", new HashSet<>()));
            }
        }
        return new Result(all, params, returns, ast, map);
    }

    private static Expression predicateOf(Statement s) {
        if (s instanceof IfStmt is) return is.getCondition();
        if (s instanceof WhileStmt w) return w.getCondition();
        if (s instanceof ForStmt f) return f.getCompare().orElse(null);
        if (s instanceof DoStmt d) return d.getCondition();
        if (s instanceof ForEachStmt fe) return fe.getIterable();
        return null;
    }

    private static ExprNode mkExpr(Node n, MethodRef ref, StatementId stmt,
                                   ExpressionKind kind, String text, MethodDeclaration md) {
        ExprId id = new ExprId(ref, identityHash(n));
        SourceRange src = sourceRange(n, md);
        return new ExprNode(id, ref, stmt, kind, text, src, new HashSet<>(), false, false);
    }

    private static int identityHash(Node n) {
        int rangeHash = n.getRange().map(r -> r.begin.line * 1000 + r.begin.column).orElse(0);
        return rangeHash * 31 + n.getClass().getSimpleName().hashCode();
    }

    private static SourceRange sourceRange(Node s, MethodDeclaration md) {
        String file = md.findCompilationUnit()
            .flatMap(cu -> cu.getStorage().map(st -> st.getPath().toString()))
            .orElse("<unknown>");
        var r = s.getRange().orElse(null);
        if (r == null) return new SourceRange(file, 0, 0, 0, 0);
        return new SourceRange(file, r.begin.line, r.begin.column, r.end.line, r.end.column);
    }

    private static String callSig(MethodCallExpr mc) {
        return mc.getNameAsString() + "(" + mc.getArguments().size() + ")";
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 80 ? t.substring(0, 77) + "..." : t;
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.pdg.ExpressionExtractorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/pdg/ExpressionExtractor.java \
        src/test/java/com/graphtipper/chop/pdg/ExpressionExtractorTest.java
git commit -m "feat(chop/pdg): ExpressionExtractor for per-statement sub-expressions"
```

---

### Task 15: PdgBuilder orchestrator

Combines CFG + CDG + DDG + Expressions into a single `MethodPDG`.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/pdg/PdgBuilder.java`
- Create: `src/test/java/com/graphtipper/chop/pdg/PdgBuilderTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.pdg;

import com.graphtipper.chop.model.*;
import com.graphtipper.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PdgBuilderTest {

    @Test
    void buildsPdgForSimpleMethod(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/java/p/C.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package p;
            public class C {
                public int f(int x) { int y = x + 1; return y; }
            }
            """);
        JavaParserContext ctx = JavaParserContext.forProject(tmp);
        Node.Method method = new Node.Method("m:p.C.f", "p.C.f", "int(int)",
            List.of("int"), "int", "src/main/java/p/C.java", 3, 3, "", false, false, List.of());
        PdgBuilder b = new PdgBuilder(ctx);
        MethodPDG pdg = b.build(method);
        assertThat(pdg.statements()).hasSize(2);                  // local def + return
        assertThat(pdg.parameters()).hasSize(1);
        assertThat(pdg.returnValues()).hasSize(1);
        assertThat(pdg.intraEdges()).anyMatch(e -> e.layer() == EdgeLayer.CFG);
        assertThat(pdg.intraEdges()).anyMatch(e -> e.layer() == EdgeLayer.DDG);
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.pdg.PdgBuilderTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`PdgBuilder.java`:
```java
package com.graphtipper.chop.pdg;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.graphtipper.chop.model.*;
import com.graphtipper.model.Node;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public final class PdgBuilder {

    private final JavaParserContext ctx;

    public PdgBuilder(JavaParserContext ctx) { this.ctx = ctx; }

    public MethodPDG build(Node.Method method) throws Exception {
        Path file = ctx.projectRoot().resolve(method.file());
        CompilationUnit cu = ctx.parser().parse(file).getResult()
            .orElseThrow(() -> new IllegalStateException("Could not parse " + file));
        MethodDeclaration md = locate(cu, method);
        MethodRef ref = new MethodRef(method.fqn(), method.signature());

        CfgConstructor.Result cfg = new CfgConstructor().build(md, ref);
        ExpressionExtractor.Result ee = new ExpressionExtractor().extract(md, ref, cfg);
        List<ChopEdge> cdg = new CdgConstructor().build(cfg);
        DdgConstructor.Result ddg = new DdgConstructor().build(md, ref, cfg, ee);

        boolean isTest = method.isTest();
        MethodNode mn = new MethodNode(ref, isTest, false, new HashSet<>());
        List<ChopEdge> intra = new ArrayList<>();
        intra.addAll(cfg.edges());
        intra.addAll(cdg);
        intra.addAll(ddg.edges());
        intra.addAll(ee.astEdges());

        Map<StatementId, List<ExprNode>> bodyByStmt = ee.expressions().stream()
            .collect(Collectors.groupingBy(ExprNode::enclosingStatement));

        return new MethodPDG(ref, mn, cfg.statements(), ee.expressions(),
            intra, ee.parameters(), ee.returnValues(), bodyByStmt);
    }

    private static MethodDeclaration locate(CompilationUnit cu, Node.Method method) {
        String simpleName = method.fqn().substring(method.fqn().lastIndexOf('.') + 1);
        return cu.findAll(MethodDeclaration.class).stream()
            .filter(md -> md.getNameAsString().equals(simpleName))
            .filter(md -> md.getParameters().size() == method.paramTypes().size())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Method not found: " + method.fqn()));
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.pdg.PdgBuilderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/pdg/PdgBuilder.java \
        src/test/java/com/graphtipper/chop/pdg/PdgBuilderTest.java
git commit -m "feat(chop/pdg): PdgBuilder orchestrator (CFG + CDG + DDG + expressions)"
```

---

## Phase 4 — Composition

### Task 16: ChopComposer

Merges per-method PDGs into a single `ChopGraph` and adds inter-procedural splice edges: `callsite-expr → entry-param` (ARG_PASS), `return-value → callsite-expr` (RETURN_BIND), and a high-level `MethodNode → MethodNode` (CG) edge.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/compose/ChopComposer.java`
- Create: `src/test/java/com/graphtipper/chop/compose/ChopComposerTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.compose;

import com.graphtipper.chop.model.*;
import com.graphtipper.chop.pdg.JavaParserContext;
import com.graphtipper.chop.pdg.MethodPDG;
import com.graphtipper.chop.pdg.PdgBuilder;
import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ChopComposerTest {

    @Test
    void splicesArgPassAndReturnBindBetweenTwoMethods(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/java/p/C.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package p;
            public class C {
                public int caller(int n) { return helper(n); }
                public int helper(int x) { return x + 1; }
            }
            """);
        JavaParserContext ctx = JavaParserContext.forProject(tmp);

        Node.Method caller = new Node.Method("m:p.C.caller", "p.C.caller", "int(int)",
            List.of("int"), "int", "src/main/java/p/C.java", 3, 3, "", false, false, List.of());
        Node.Method helper = new Node.Method("m:p.C.helper", "p.C.helper", "int(int)",
            List.of("int"), "int", "src/main/java/p/C.java", 4, 4, "", false, false, List.of());

        ProjectGraph pg = new ProjectGraph();
        pg.addNode(caller); pg.addNode(helper);
        pg.addEdge(new Edge.Calls(caller.id(), helper.id(), false));

        MethodPDG callerPdg = new PdgBuilder(ctx).build(caller);
        MethodPDG helperPdg = new PdgBuilder(ctx).build(helper);

        ChopGraph g = new ChopComposer().compose(
            new MethodRef(helper.fqn(), helper.signature()),
            List.of(),
            java.util.Set.of(),
            Map.of(
                new MethodRef(caller.fqn(), caller.signature()), callerPdg,
                new MethodRef(helper.fqn(), helper.signature()), helperPdg),
            pg);

        long argPass = g.jgraph().edgeSet().stream()
            .filter(e -> e.layer() == EdgeLayer.ARG_PASS).count();
        long retBind = g.jgraph().edgeSet().stream()
            .filter(e -> e.layer() == EdgeLayer.RETURN_BIND).count();
        long cg = g.jgraph().edgeSet().stream().filter(e -> e.layer() == EdgeLayer.CG).count();
        assertThat(argPass).isEqualTo(1);
        assertThat(retBind).isGreaterThanOrEqualTo(1);
        assertThat(cg).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.compose.ChopComposerTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`ChopComposer.java`:
```java
package com.graphtipper.chop.compose;

import com.graphtipper.chop.model.*;
import com.graphtipper.chop.pdg.MethodPDG;
import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;

import java.util.*;

public final class ChopComposer {

    public ChopGraph compose(MethodRef target,
                             List<StatementId> targetStatements,
                             Set<MethodRef> entryPoints,
                             Map<MethodRef, MethodPDG> pdgs,
                             ProjectGraph projectGraph) {
        ChopGraph g = new ChopGraph(target, targetStatements, entryPoints);
        // 1. Add nodes and intra-method edges.
        for (MethodPDG pdg : pdgs.values()) {
            g.addNode(pdg.methodNode());
            pdg.statements().forEach(g::addNode);
            pdg.expressions().forEach(g::addNode);
            pdg.intraEdges().forEach(g::addEdge);
        }
        // 2. Splice inter-procedural edges for every CallSite where both caller and
        //    callee PDGs are present.
        for (Map.Entry<MethodRef, MethodPDG> entry : pdgs.entrySet()) {
            MethodPDG caller = entry.getValue();
            for (ExprNode call : caller.expressions()) {
                if (call.kind() != ExpressionKind.CALLSITE) continue;
                // Find callees in ProjectGraph: caller method id -> outgoing calls.
                Node.Method callerMethod = methodByRef(projectGraph, entry.getKey());
                if (callerMethod == null) continue;
                for (Edge.Calls c : projectGraph.outgoingCalls(callerMethod.id())) {
                    Node target2 = projectGraph.byId(c.toId());
                    if (!(target2 instanceof Node.Method targetMethod)) continue;
                    MethodRef calleeRef = new MethodRef(targetMethod.fqn(), targetMethod.signature());
                    MethodPDG callee = pdgs.get(calleeRef);
                    if (callee == null) continue;
                    ResolutionKind rk = c.viaVirtual() ? ResolutionKind.CHA : ResolutionKind.EXACT;
                    // ARG_PASS: each arg expr (if any in this PoC, we don't yet associate
                    // call args with sub-expr extraction; we use callee parameter index 0..N).
                    int n = Math.min(callee.parameters().size(), 1); // PoC: link first param
                    for (int i = 0; i < callee.parameters().size(); i++) {
                        g.addEdge(new ChopEdge(call, callee.parameters().get(i),
                            EdgeLayer.ARG_PASS, rk, DataKind.ARG, "arg" + i, new HashSet<>()));
                    }
                    // RETURN_BIND
                    for (ExprNode rv : callee.returnValues()) {
                        g.addEdge(new ChopEdge(rv, call, EdgeLayer.RETURN_BIND, rk,
                            DataKind.RETURN, "return", new HashSet<>()));
                    }
                    // CG (method-level)
                    g.addEdge(new ChopEdge(caller.methodNode(), callee.methodNode(),
                        EdgeLayer.CG, rk, null, "call", new HashSet<>()));
                }
            }
        }
        return g;
    }

    private static Node.Method methodByRef(ProjectGraph pg, MethodRef ref) {
        for (Node n : pg.byFqn(ref.fqn())) {
            if (n instanceof Node.Method m && m.signature().equals(ref.signature())) return m;
        }
        return null;
    }
}
```

> Note: the PoC links every callsite to all parameters / return values of the callee (positional matching of args is left for future). This is the documented Section 5 phase 3 simplification.

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.compose.ChopComposerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/compose/ChopComposer.java \
        src/test/java/com/graphtipper/chop/compose/ChopComposerTest.java
git commit -m "feat(chop/compose): ChopComposer splice (ARG_PASS, RETURN_BIND, CG)"
```

---

## Phase 5 — Annotation

### Task 17: ChopAnnotator

For each target statement, computes backward + forward reachable nodes/edges and adds the statement's ID to their `touchedBy` set.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/annotate/ChopAnnotator.java`
- Create: `src/test/java/com/graphtipper/chop/annotate/ChopAnnotatorTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.annotate;

import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ChopAnnotatorTest {

    @Test
    void backwardReachableNodesAreTouchedByStatement() {
        MethodRef m = new MethodRef("p.C", "f:int(int)");
        StatementId s1 = new StatementId(m, 1);
        StatementId s2 = new StatementId(m, 2);
        StatementNode n1 = new StatementNode(s1, m, StatementKind.EXPR, "int y = x+1;",
            new SourceRange("f.java", 1, 1, 1, 20), new HashSet<>(), false, false);
        StatementNode n2 = new StatementNode(s2, m, StatementKind.RETURN, "return y;",
            new SourceRange("f.java", 2, 1, 2, 10), new HashSet<>(), true, false);

        ChopGraph g = new ChopGraph(m, List.of(s2), Set.of());
        g.addNode(n1); g.addNode(n2);
        g.addEdge(new ChopEdge(n1, n2, EdgeLayer.DDG, null, DataKind.DEF_USE, "y", new HashSet<>()));

        new ChopAnnotator().annotate(g);
        assertThat(n1.touchedBy()).contains(s2);
        assertThat(n2.touchedBy()).contains(s2);
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.annotate.ChopAnnotatorTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`ChopAnnotator.java`:
```java
package com.graphtipper.chop.annotate;

import com.graphtipper.chop.model.*;
import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedMultigraph;

import java.util.*;

public final class ChopAnnotator {

    private static final EnumSet<EdgeLayer> BACKWARD = EnumSet.of(
        EdgeLayer.DDG, EdgeLayer.CDG, EdgeLayer.CFG, EdgeLayer.ARG_PASS,
        EdgeLayer.CG, EdgeLayer.OVERRIDES);
    private static final EnumSet<EdgeLayer> FORWARD = EnumSet.of(
        EdgeLayer.DDG, EdgeLayer.CFG, EdgeLayer.RETURN_BIND,
        EdgeLayer.CG, EdgeLayer.OVERRIDES);

    public void annotate(ChopGraph g) {
        DirectedMultigraph<ChopNode, ChopEdge> jg = g.jgraph();
        Map<StatementId, ChopNode> stmtToNode = new HashMap<>();
        for (ChopNode n : jg.vertexSet()) {
            if (n instanceof StatementNode sn) stmtToNode.put(sn.id(), sn);
        }
        for (StatementId s : g.targetStatements()) {
            ChopNode origin = stmtToNode.get(s);
            if (origin == null) continue;
            Set<ChopNode> bw = bfs(jg, origin, true, BACKWARD);
            Set<ChopNode> fw = bfs(jg, origin, false, FORWARD);
            Set<ChopNode> all = new HashSet<>(bw);
            all.addAll(fw);
            for (ChopNode n : all) n.touchedBy().add(s);
            for (ChopEdge e : jg.edgeSet()) {
                if (all.contains(e.src()) && all.contains(e.dst())
                    && (BACKWARD.contains(e.layer()) || FORWARD.contains(e.layer()))) {
                    e.touchedBy().add(s);
                }
            }
        }
    }

    private static Set<ChopNode> bfs(Graph<ChopNode, ChopEdge> g, ChopNode start,
                                      boolean reverse, EnumSet<EdgeLayer> layers) {
        Set<ChopNode> visited = new HashSet<>();
        Deque<ChopNode> q = new ArrayDeque<>();
        q.add(start); visited.add(start);
        while (!q.isEmpty()) {
            ChopNode cur = q.poll();
            Set<ChopEdge> edges = reverse ? g.incomingEdgesOf(cur) : g.outgoingEdgesOf(cur);
            for (ChopEdge e : edges) {
                if (!layers.contains(e.layer())) continue;
                ChopNode next = reverse ? e.src() : e.dst();
                if (visited.add(next)) q.add(next);
            }
        }
        return visited;
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.annotate.ChopAnnotatorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/annotate/ChopAnnotator.java \
        src/test/java/com/graphtipper/chop/annotate/ChopAnnotatorTest.java
git commit -m "feat(chop/annotate): per-statement touchedBy annotation via backward+forward BFS"
```

---

## Phase 6 — Renderers

### Task 18: DotRenderer

JGraphT `DOTExporter` with custom attribute providers for layer-based colour/style and method clusters via Graphviz subgraphs.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/render/DotRenderer.java`
- Create: `src/test/java/com/graphtipper/chop/render/DotRendererTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.render;

import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class DotRendererTest {

    @Test
    void emitsClusteredDotWithLayerColours() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        MethodNode mn = new MethodNode(m, false, true, new HashSet<>());
        StatementId sid = new StatementId(m, 1);
        StatementNode sn = new StatementNode(sid, m, StatementKind.RETURN, "return y;",
            new SourceRange("C.java", 1, 1, 1, 10), new HashSet<>(), true, false);
        ChopGraph g = new ChopGraph(m, List.of(sid), Set.of());
        g.addNode(mn); g.addNode(sn);
        g.addEdge(new ChopEdge(mn, sn, EdgeLayer.AST, null, null, "contains", new HashSet<>()));

        StringWriter w = new StringWriter();
        new DotRenderer().render(g, w);
        String dot = w.toString();
        assertThat(dot).contains("digraph").contains("p.C").contains("return y");
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.render.DotRendererTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`DotRenderer.java`:
```java
package com.graphtipper.chop.render;

import com.graphtipper.chop.model.*;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;

import java.io.Writer;
import java.util.*;

public final class DotRenderer {

    public void render(ChopGraph g, Writer out) {
        DOTExporter<ChopNode, ChopEdge> exp = new DOTExporter<>(this::idOf);
        exp.setVertexAttributeProvider(this::vertexAttrs);
        exp.setEdgeAttributeProvider(this::edgeAttrs);
        exp.setGraphAttributeProvider(() -> Map.of(
            "rankdir", DefaultAttribute.createAttribute("TB"),
            "splines", DefaultAttribute.createAttribute("true"),
            "fontname", DefaultAttribute.createAttribute("Helvetica")));
        exp.exportGraph(g.jgraph(), out);
    }

    private String idOf(ChopNode n) {
        if (n instanceof MethodNode mn) return "m_" + sanitize(mn.owner().display());
        if (n instanceof StatementNode sn) return "s_" + sn.id().astNodeId();
        if (n instanceof ExprNode en) return "e_" + en.id().astNodeId();
        return "n_" + System.identityHashCode(n);
    }

    private Map<String, Attribute> vertexAttrs(ChopNode n) {
        Map<String, Attribute> a = new LinkedHashMap<>();
        if (n instanceof MethodNode mn) {
            a.put("shape", DefaultAttribute.createAttribute("box3d"));
            a.put("style", DefaultAttribute.createAttribute("filled"));
            a.put("fillcolor", DefaultAttribute.createAttribute(
                mn.isTarget() ? "gold" : mn.isTest() ? "lightblue" : "white"));
            a.put("label", DefaultAttribute.createAttribute(mn.owner().display()));
        } else if (n instanceof StatementNode sn) {
            a.put("shape", DefaultAttribute.createAttribute("box"));
            a.put("label", DefaultAttribute.createAttribute(
                "L" + sn.src().startLine() + ": " + sn.displayText()));
            if (sn.isTarget()) a.put("fillcolor", DefaultAttribute.createAttribute("gold"));
        } else if (n instanceof ExprNode en) {
            a.put("shape", DefaultAttribute.createAttribute("ellipse"));
            a.put("label", DefaultAttribute.createAttribute(en.kind() + ":" + en.displayText()));
        }
        return a;
    }

    private Map<String, Attribute> edgeAttrs(ChopEdge e) {
        Map<String, Attribute> a = new LinkedHashMap<>();
        a.put("label", DefaultAttribute.createAttribute(e.layer() + (e.label().isEmpty() ? "" : ":" + e.label())));
        String color = switch (e.layer()) {
            case DDG -> "blue";
            case CFG -> "gray";
            case CDG -> "purple";
            case CG -> "black";
            case ARG_PASS, RETURN_BIND -> "green";
            case AST -> "lightgray";
            case OVERRIDES -> "orange";
        };
        a.put("color", DefaultAttribute.createAttribute(color));
        if (e.resolution() == ResolutionKind.CHA) a.put("style", DefaultAttribute.createAttribute("dashed"));
        if (e.resolution() == ResolutionKind.UNKNOWN) {
            a.put("style", DefaultAttribute.createAttribute("dotted"));
            a.put("color", DefaultAttribute.createAttribute("red"));
        }
        return a;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.render.DotRendererTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/render/DotRenderer.java \
        src/test/java/com/graphtipper/chop/render/DotRendererTest.java
git commit -m "feat(chop/render): DotRenderer with layer-coloured edges"
```

---

### Task 19: GraphMLRenderer

JGraphT `GraphMLExporter` with all custom attributes exported as keys (layer, resolution, dataKind, touchedBy joined).

**Files:**
- Create: `src/main/java/com/graphtipper/chop/render/GraphMLRenderer.java`
- Create: `src/test/java/com/graphtipper/chop/render/GraphMLRendererTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.render;

import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class GraphMLRendererTest {

    @Test
    void emitsParseableGraphMLWithAttributes() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        MethodNode mn = new MethodNode(m, false, true, new HashSet<>());
        ChopGraph g = new ChopGraph(m, List.of(), Set.of());
        g.addNode(mn);

        StringWriter w = new StringWriter();
        new GraphMLRenderer().render(g, w);
        String xml = w.toString();
        assertThat(xml).contains("graphml").contains("p.C");

        assertThatNoException().isThrownBy(() -> DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.render.GraphMLRendererTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`GraphMLRenderer.java`:
```java
package com.graphtipper.chop.render;

import com.graphtipper.chop.model.*;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.AttributeType;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.graphml.GraphMLExporter;

import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

public final class GraphMLRenderer {

    public void render(ChopGraph g, Writer out) {
        GraphMLExporter<ChopNode, ChopEdge> exp = new GraphMLExporter<>(n -> {
            if (n instanceof MethodNode mn) return "m_" + System.identityHashCode(mn);
            if (n instanceof StatementNode sn) return "s_" + sn.id().astNodeId();
            if (n instanceof ExprNode en) return "e_" + en.id().astNodeId();
            return "n_" + System.identityHashCode(n);
        });
        exp.registerAttribute("kind", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("label", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("owner", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("touchedBy", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("layer", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);
        exp.registerAttribute("resolution", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);
        exp.registerAttribute("dataKind", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);
        exp.registerAttribute("label", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);
        exp.registerAttribute("touchedBy", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);

        exp.setVertexAttributeProvider(n -> {
            Map<String, Attribute> m = new LinkedHashMap<>();
            String kind = (n instanceof MethodNode) ? "method"
                       : (n instanceof StatementNode) ? "statement" : "expr";
            m.put("kind", DefaultAttribute.createAttribute(kind));
            m.put("owner", DefaultAttribute.createAttribute(n.owner().display()));
            m.put("label", DefaultAttribute.createAttribute(labelOf(n)));
            m.put("touchedBy", DefaultAttribute.createAttribute(joinTouched(n.touchedBy())));
            return m;
        });
        exp.setEdgeAttributeProvider(e -> {
            Map<String, Attribute> m = new LinkedHashMap<>();
            m.put("layer", DefaultAttribute.createAttribute(e.layer().name()));
            m.put("resolution", DefaultAttribute.createAttribute(
                e.resolution() == null ? "" : e.resolution().name()));
            m.put("dataKind", DefaultAttribute.createAttribute(
                e.dataKind() == null ? "" : e.dataKind().name()));
            m.put("label", DefaultAttribute.createAttribute(e.label()));
            m.put("touchedBy", DefaultAttribute.createAttribute(joinTouched(e.touchedBy())));
            return m;
        });
        exp.exportGraph(g.jgraph(), out);
    }

    private static String labelOf(ChopNode n) {
        if (n instanceof MethodNode mn) return mn.owner().display();
        if (n instanceof StatementNode sn) return sn.displayText();
        if (n instanceof ExprNode en) return en.kind() + ":" + en.displayText();
        return "";
    }

    private static String joinTouched(Set<StatementId> s) {
        return s.stream().map(id -> String.valueOf(id.astNodeId()))
            .sorted().collect(Collectors.joining(","));
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.render.GraphMLRendererTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/chop/render/GraphMLRenderer.java \
        src/test/java/com/graphtipper/chop/render/GraphMLRendererTest.java
git commit -m "feat(chop/render): GraphMLRenderer with attribute keys"
```

---

### Task 20: CytoscapeRenderer

Standalone HTML with embedded Cytoscape.js JSON + per-statement filter + layer toggles.

**Files:**
- Create: `src/main/java/com/graphtipper/chop/render/CytoscapeJson.java`
- Create: `src/main/java/com/graphtipper/chop/render/CytoscapeRenderer.java`
- Create: `src/test/java/com/graphtipper/chop/render/CytoscapeRendererTest.java`

- [ ] **Step 1: Failing test**

```java
package com.graphtipper.chop.render;

import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CytoscapeRendererTest {

    @Test
    void emitsHtmlWithEmbeddedJson() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        MethodNode mn = new MethodNode(m, false, true, new HashSet<>());
        ChopGraph g = new ChopGraph(m, List.of(), Set.of());
        g.addNode(mn);

        StringWriter w = new StringWriter();
        new CytoscapeRenderer().render(g, w);
        String html = w.toString();
        assertThat(html).contains("<html").contains("cytoscape").contains("p.C");
        assertThat(html).contains("\"nodes\"").contains("\"edges\"");
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `gradle test --tests com.graphtipper.chop.render.CytoscapeRendererTest`
Expected: FAIL.

- [ ] **Step 3: Implement helper for JSON shape**

`CytoscapeJson.java`:
```java
package com.graphtipper.chop.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphtipper.chop.model.*;

import java.util.stream.Collectors;

public final class CytoscapeJson {

    private final ObjectMapper om = new ObjectMapper();

    public String build(ChopGraph g) throws Exception {
        ObjectNode root = om.createObjectNode();
        ArrayNode nodes = root.putArray("nodes");
        ArrayNode edges = root.putArray("edges");

        for (ChopNode n : g.jgraph().vertexSet()) {
            ObjectNode obj = nodes.addObject();
            ObjectNode data = obj.putObject("data");
            data.put("id", idOf(n));
            data.put("kind", kindOf(n));
            data.put("label", labelOf(n));
            data.put("owner", n.owner().display());
            if (n instanceof StatementNode sn) {
                data.put("parent", "m_" + sanitize(sn.owner().display()));
                data.put("isTarget", sn.isTarget());
            } else if (n instanceof ExprNode en) {
                data.put("parent", "m_" + sanitize(en.owner().display()));
                data.put("enclosing", "s_" + en.enclosingStatement().astNodeId());
            } else if (n instanceof MethodNode mn) {
                data.put("isTest", mn.isTest());
                data.put("isTarget", mn.isTarget());
            }
            data.put("touchedBy", n.touchedBy().stream()
                .map(s -> String.valueOf(s.astNodeId()))
                .sorted().collect(Collectors.joining(",")));
        }
        int idx = 0;
        for (ChopEdge e : g.jgraph().edgeSet()) {
            ObjectNode obj = edges.addObject();
            ObjectNode data = obj.putObject("data");
            data.put("id", "edge_" + (idx++));
            data.put("source", idOf(e.src()));
            data.put("target", idOf(e.dst()));
            data.put("layer", e.layer().name());
            if (e.resolution() != null) data.put("resolution", e.resolution().name());
            if (e.dataKind() != null) data.put("dataKind", e.dataKind().name());
            data.put("label", e.label());
            data.put("touchedBy", e.touchedBy().stream()
                .map(s -> String.valueOf(s.astNodeId()))
                .sorted().collect(Collectors.joining(",")));
        }
        return om.writeValueAsString(root);
    }

    private static String idOf(ChopNode n) {
        if (n instanceof MethodNode mn) return "m_" + sanitize(mn.owner().display());
        if (n instanceof StatementNode sn) return "s_" + sn.id().astNodeId();
        if (n instanceof ExprNode en) return "e_" + en.id().astNodeId();
        return "n_" + System.identityHashCode(n);
    }
    private static String kindOf(ChopNode n) {
        if (n instanceof MethodNode) return "method";
        if (n instanceof StatementNode) return "statement";
        return "expr";
    }
    private static String labelOf(ChopNode n) {
        if (n instanceof MethodNode mn) return mn.owner().display();
        if (n instanceof StatementNode sn) return "L" + sn.src().startLine() + ": " + sn.displayText();
        if (n instanceof ExprNode en) return en.kind() + ": " + en.displayText();
        return "";
    }
    private static String sanitize(String s) { return s.replaceAll("[^A-Za-z0-9_]", "_"); }
}
```

- [ ] **Step 4: Implement CytoscapeRenderer**

`CytoscapeRenderer.java`:
```java
package com.graphtipper.chop.render;

import com.graphtipper.chop.model.ChopGraph;

import java.io.Writer;

public final class CytoscapeRenderer {

    public void render(ChopGraph g, Writer out) {
        try {
            String json = new CytoscapeJson().build(g);
            String html = TEMPLATE.replace("/*__GRAPH_JSON__*/", json)
                                  .replace("/*__TARGET__*/", g.target().display());
            out.write(html);
        } catch (Exception e) {
            throw new RuntimeException("Cytoscape render failed", e);
        }
    }

    private static final String TEMPLATE = """
        <!doctype html><html><head><meta charset="utf-8">
        <title>Chop: /*__TARGET__*/</title>
        <style>
          body { font-family: Helvetica, Arial; margin:0; display:flex; height:100vh; }
          #side { width:280px; padding:8px; border-right:1px solid #ccc; overflow:auto; }
          #cy { flex:1; }
          label { display:block; font-size:12px; }
          h3 { font-size:14px; margin:8px 0 4px; }
        </style>
        <script src="https://unpkg.com/cytoscape@3.30.2/dist/cytoscape.min.js"></script>
        <script src="https://unpkg.com/dagre@0.8.5/dist/dagre.min.js"></script>
        <script src="https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js"></script>
        </head><body>
        <div id="side">
          <h3>Target</h3><div id="targetLabel">/*__TARGET__*/</div>
          <h3>Statements</h3><div id="stmtList"></div>
          <h3>Layers</h3><div id="layerList"></div>
          <h3>Resolution</h3><div id="resList"></div>
          <button id="reset">Reset view</button>
        </div>
        <div id="cy"></div>
        <script>
          const data = /*__GRAPH_JSON__*/;
          cytoscape.use(cytoscapeDagre);
          const cy = cytoscape({
            container: document.getElementById('cy'),
            elements: { nodes: data.nodes, edges: data.edges },
            style: [
              { selector: 'node[kind="method"]', style: { 'shape':'roundrectangle',
                  'background-color':'#eee', 'label':'data(label)', 'text-valign':'top',
                  'padding': '14px' } },
              { selector: 'node[kind="statement"]', style: { 'shape':'rectangle',
                  'background-color':'#fff', 'border-width':1, 'border-color':'#666',
                  'label':'data(label)', 'font-size':10 } },
              { selector: 'node[kind="expr"]', style: { 'shape':'ellipse',
                  'background-color':'#cce', 'label':'data(label)', 'font-size':9 } },
              { selector: 'node[isTarget = "true"]', style: { 'background-color':'gold' } },
              { selector: 'node[isTest = "true"]', style: { 'background-color':'#cdf' } },
              { selector: 'edge', style: { 'curve-style':'bezier', 'target-arrow-shape':'triangle',
                  'label':'data(layer)', 'font-size':8, 'width':1 } },
              { selector: 'edge[layer="DDG"]', style: { 'line-color':'blue', 'target-arrow-color':'blue' } },
              { selector: 'edge[layer="CFG"]', style: { 'line-color':'gray', 'target-arrow-color':'gray' } },
              { selector: 'edge[layer="CDG"]', style: { 'line-color':'purple', 'target-arrow-color':'purple',
                  'line-style':'dashed' } },
              { selector: 'edge[layer="CG"]', style: { 'line-color':'#000', 'target-arrow-color':'#000', 'width':2 } },
              { selector: 'edge[layer="ARG_PASS"], edge[layer="RETURN_BIND"]',
                  style: { 'line-color':'green', 'target-arrow-color':'green' } },
              { selector: 'edge[resolution="CHA"]', style: { 'line-style':'dashed' } },
              { selector: 'edge[resolution="UNKNOWN"]',
                  style: { 'line-style':'dotted', 'line-color':'red', 'target-arrow-color':'red' } },
              { selector: '.faded', style: { 'opacity':0.15 } }
            ],
            layout: { name: 'dagre', rankDir: 'TB' }
          });

          const layers = ['CG','DDG','CDG','CFG','AST','ARG_PASS','RETURN_BIND','OVERRIDES'];
          const layersOn = new Set(['CG','DDG','CDG','ARG_PASS','RETURN_BIND']);
          const layerList = document.getElementById('layerList');
          layers.forEach(l => {
            const wrap = document.createElement('label');
            const cb = document.createElement('input');
            cb.type = 'checkbox'; cb.checked = layersOn.has(l);
            cb.onchange = () => { cb.checked ? layersOn.add(l) : layersOn.delete(l); applyLayerFilter(); };
            wrap.appendChild(cb); wrap.appendChild(document.createTextNode(' '+l));
            layerList.appendChild(wrap);
          });
          function applyLayerFilter() {
            cy.edges().forEach(e => { e.style('display', layersOn.has(e.data('layer')) ? 'element' : 'none'); });
          }
          applyLayerFilter();

          // Per-statement filter
          const stmts = cy.nodes('node[kind="statement"][isTarget = "true"]').map(n => n.data());
          const stmtList = document.getElementById('stmtList');
          stmts.forEach(s => {
            const wrap = document.createElement('label');
            const cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.onchange = () => applyStmtFilter();
            cb.dataset.id = s.id;
            wrap.appendChild(cb); wrap.appendChild(document.createTextNode(' '+s.label));
            stmtList.appendChild(wrap);
          });
          function applyStmtFilter() {
            const selected = Array.from(stmtList.querySelectorAll('input:checked')).map(i => i.dataset.id);
            if (selected.length === 0) { cy.elements().removeClass('faded'); return; }
            const selStmtSet = new Set(selected.map(id => id.replace('s_','')));
            cy.elements().addClass('faded');
            cy.elements().forEach(el => {
              const tb = (el.data('touchedBy') || '').split(',').filter(x => x);
              if (tb.some(t => selStmtSet.has(t))) el.removeClass('faded');
            });
          }
          document.getElementById('reset').onclick = () => {
            stmtList.querySelectorAll('input').forEach(i => i.checked = false);
            applyStmtFilter();
          };
        </script>
        </body></html>
        """;
}
```

- [ ] **Step 5: Tests pass**

Run: `gradle test --tests com.graphtipper.chop.render.CytoscapeRendererTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/chop/render/CytoscapeJson.java \
        src/main/java/com/graphtipper/chop/render/CytoscapeRenderer.java \
        src/test/java/com/graphtipper/chop/render/CytoscapeRendererTest.java
git commit -m "feat(chop/render): CytoscapeRenderer with embedded HTML + per-stmt filter"
```

---

## Phase 7 — Integration

### Task 21: ChopCommand wire-up

Connect every phase: Joern → ProjectGraph → MethodLocator → ReachabilityScan → PdgBuilder × N → ChopComposer → ChopAnnotator → 3 renderers.

**Files:**
- Modify: `src/main/java/com/graphtipper/chop/cli/ChopCommand.java`

- [ ] **Step 1: Replace stub `call()` with full pipeline**

```java
@Override
public Integer call() throws Exception {
    System.err.println("chop: building CPG via Joern...");
    com.graphtipper.cpg.ProcessJoernInvoker invoker =
        new com.graphtipper.cpg.ProcessJoernInvoker(joernHome);
    java.nio.file.Path cacheRoot = out.resolve(".cache");
    com.graphtipper.cpg.JoernRunner runner =
        new com.graphtipper.cpg.JoernRunner(invoker, cacheRoot);
    java.nio.file.Path exportDir = runner.buildAndExport(project, noCache);
    java.nio.file.Path exportFile = exportDir.resolve("export.json");

    com.graphtipper.cpg.CpgImporter importer = new com.graphtipper.cpg.CpgImporter();
    com.graphtipper.model.ProjectGraph pg = importer.importFrom(exportFile);

    com.graphtipper.detect.TargetSpec spec = com.graphtipper.detect.TargetSpec.parse(target);
    com.graphtipper.detect.MethodLocator locator = new com.graphtipper.detect.MethodLocator();
    com.graphtipper.model.Node.Method targetMethod;
    try {
        targetMethod = locator.locate(pg, spec);
    } catch (com.graphtipper.detect.TargetNotFoundException e) {
        System.err.println("chop: target not found: " + target);
        e.getSuggestions().forEach(s -> System.err.println("  candidate: " + s));
        return 2;
    } catch (com.graphtipper.detect.AmbiguousTargetException e) {
        System.err.println("chop: ambiguous target: " + target);
        e.getCandidates().forEach(s -> System.err.println("  candidate: " + s));
        return 1;
    }

    int depthLimit = maxDepth == null ? Integer.MAX_VALUE : maxDepth;
    com.graphtipper.chop.reach.ReachabilityScan.Result reach;
    try {
        reach = new com.graphtipper.chop.reach.ReachabilityScan(
            new com.graphtipper.chop.reach.EntryPointFinder(), depthLimit, maxMethods)
            .run(pg, targetMethod);
    } catch (com.graphtipper.chop.reach.MaxMethodsExceededException e) {
        System.err.println("chop: --max-methods exceeded (" + e.count + "); raise --max-methods to proceed");
        return 3;
    }

    com.graphtipper.chop.pdg.JavaParserContext jpCtx =
        com.graphtipper.chop.pdg.JavaParserContext.forProject(project);
    com.graphtipper.chop.pdg.PdgBuilder builder = new com.graphtipper.chop.pdg.PdgBuilder(jpCtx);

    java.util.Map<com.graphtipper.chop.model.MethodRef, com.graphtipper.chop.pdg.MethodPDG> pdgs =
        new java.util.LinkedHashMap<>();
    for (com.graphtipper.model.Node.Method m : reach.involved()) {
        try {
            pdgs.put(new com.graphtipper.chop.model.MethodRef(m.fqn(), m.signature()),
                builder.build(m));
        } catch (Exception e) {
            System.err.println("chop: skipped " + m.fqn() + ": " + e.getMessage());
        }
    }

    com.graphtipper.chop.model.MethodRef targetRef =
        new com.graphtipper.chop.model.MethodRef(targetMethod.fqn(), targetMethod.signature());
    com.graphtipper.chop.pdg.MethodPDG targetPdg = pdgs.get(targetRef);
    if (targetPdg == null) {
        System.err.println("chop: target has empty body, nothing to chop");
        return 2;
    }
    java.util.List<com.graphtipper.chop.model.StatementId> targetStmts =
        targetPdg.statements().stream().map(com.graphtipper.chop.model.StatementNode::id).toList();
    java.util.Set<com.graphtipper.chop.model.MethodRef> entries = new java.util.HashSet<>();
    for (com.graphtipper.model.Node.Method e : reach.entryPoints()) {
        entries.add(new com.graphtipper.chop.model.MethodRef(e.fqn(), e.signature()));
    }
    // Mark target/entry flags on stored MethodNodes by rebuilding them.
    java.util.Map<com.graphtipper.chop.model.MethodRef, com.graphtipper.chop.pdg.MethodPDG> annotatedPdgs =
        new java.util.LinkedHashMap<>();
    for (var entry : pdgs.entrySet()) {
        var mn = entry.getValue().methodNode();
        boolean isTarget = entry.getKey().equals(targetRef);
        boolean isTest = entries.contains(entry.getKey()) || mn.isTest();
        com.graphtipper.chop.model.MethodNode marked =
            new com.graphtipper.chop.model.MethodNode(mn.owner(), isTest, isTarget, mn.touchedBy());
        annotatedPdgs.put(entry.getKey(),
            new com.graphtipper.chop.pdg.MethodPDG(entry.getValue().ref(), marked,
                entry.getValue().statements(), entry.getValue().expressions(),
                entry.getValue().intraEdges(), entry.getValue().parameters(),
                entry.getValue().returnValues(), entry.getValue().bodyByStatement()));
    }

    com.graphtipper.chop.model.ChopGraph graph =
        new com.graphtipper.chop.compose.ChopComposer().compose(
            targetRef, targetStmts, entries, annotatedPdgs, pg);

    new com.graphtipper.chop.annotate.ChopAnnotator().annotate(graph);

    java.nio.file.Files.createDirectories(out);
    try (var w = java.nio.file.Files.newBufferedWriter(out.resolve("chop.dot"))) {
        new com.graphtipper.chop.render.DotRenderer().render(graph, w);
    }
    try (var w = java.nio.file.Files.newBufferedWriter(out.resolve("chop.graphml"))) {
        new com.graphtipper.chop.render.GraphMLRenderer().render(graph, w);
    }
    try (var w = java.nio.file.Files.newBufferedWriter(out.resolve("chop.html"))) {
        new com.graphtipper.chop.render.CytoscapeRenderer().render(graph, w);
    }
    if (entries.isEmpty()) {
        System.err.println("chop: WARNING — no test entry points reach this target");
    }
    System.err.println("chop: wrote 3 artefacts to " + out.toAbsolutePath());
    return 0;
}
```

- [ ] **Step 2: Compile**

Run: `gradle compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run all existing tests**

Run: `gradle test`
Expected: all previous tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/graphtipper/chop/cli/ChopCommand.java
git commit -m "feat(chop/cli): wire full pipeline in ChopCommand"
```

---

### Task 22: Integration test on JGraphT-Builder

End-to-end acceptance test. Runs the command on the real JGraphT-Builder project, checks artefacts exist and have minimum expected content.

**Files:**
- Create: `src/test/java/com/graphtipper/chop/JGraphTBuilderChopIntegrationTest.java`

- [ ] **Step 1: Write the test**

```java
package com.graphtipper.chop;

import com.graphtipper.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class JGraphTBuilderChopIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "GRAPHTIPPER_JGRAPHT_BUILDER_HOME",
                                  matches = ".+")
    void chopsBackwardSlicerSlicePerReturn(@TempDir Path tmp) throws Exception {
        String project = System.getenv("GRAPHTIPPER_JGRAPHT_BUILDER_HOME");
        Path out = tmp.resolve("chop-out");
        int code = new CommandLine(new Main()).execute(
            "chop",
            "--project", project,
            "--target", "com.github.sckwoky.typegraph.flow.BackwardSlicer#slicePerReturn",
            "--out", out.toString()
        );
        assertThat(code).isZero();
        Path dot = out.resolve("chop.dot");
        Path graphml = out.resolve("chop.graphml");
        Path html = out.resolve("chop.html");
        assertThat(dot).exists();
        assertThat(graphml).exists();
        assertThat(html).exists();

        String dotText = Files.readString(dot);
        assertThat(dotText)
            .contains("BackwardSlicer").contains("slicePerReturn");
        String htmlText = Files.readString(html);
        assertThat(htmlText)
            .contains("BackwardSlicer")
            .contains("\"nodes\"")
            .contains("\"edges\"");
    }
}
```

The `@EnabledIfEnvironmentVariable` gate keeps the test out of normal CI (which lacks Joern and the sibling project). Local run:

```bash
GRAPHTIPPER_JGRAPHT_BUILDER_HOME=/Users/sckwoky/Projects/JGraphT-Builder \
GRAPHTIPPER_PICOCLI_HOME=... \
gradle test --tests com.graphtipper.chop.JGraphTBuilderChopIntegrationTest
```

- [ ] **Step 2: Run the test locally**

Run: see command above. Expected: PASS, three files generated. Open `chop.html` in a browser; manually verify per-statement filter and layer toggles behave per Section 6.2 of the spec.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/graphtipper/chop/JGraphTBuilderChopIntegrationTest.java
git commit -m "test(chop): integration test against JGraphT-Builder"
```

---

## Acceptance verification

Once all 22 tasks pass:

1. `gradle test` — all unit + integration tests green (integration test is skipped without env var).
2. With env var: `chop.dot`, `chop.graphml`, `chop.html` exist in `out/`.
3. Open `chop.html` in a browser — visible clustered method boxes, statements inside, target highlighted, per-statement filter functional, layer toggles functional.
4. Manual sanity: at least one `MethodFlowBuilderTest` test method is rendered as `[TEST]` and connected through to `slicePerReturn`.

This matches Section 10 of the spec.

---

## Notes on Approach

- **Each phase produces working software.** After Phase 0 the CLI subcommand is wired. After Phase 2 reachability can be invoked and result inspected. After Phase 5 the graph is rendered. Each phase's tests run in isolation, so a partial completion is still useful.
- **TDD throughout.** Every task starts with the failing test, then minimal implementation, then green, then commit.
- **DRY.** `JavaParserContext` is reused by all `pdg` constructors. `ProjectGraph` is reused for both reachability and composer's call lookup.
- **YAGNI.** Phase 3 `DdgConstructor` deliberately uses a simple latest-def heuristic instead of full reaching-defs. CDG uses a basic post-dominator on a single-exit approximation. These are documented Section 5 "deferred" items and not required for PoC acceptance.
- **Frequent commits.** Every task ends with a commit. A worker can interrupt mid-plan and resume with no dangling state.
