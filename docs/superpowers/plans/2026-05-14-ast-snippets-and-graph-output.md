# AST-aware Snippets + Graph Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace line-based call-site snippets with AST-aware backward-sliced snippets, and emit three artifacts per run (`<hash>.budget.md`, `<hash>.full.md`, `<hash>.graph.json`) so an agent can pick the right input for its context window.

**Architecture:** Adds a JavaParser-backed `AstSnippetExtractor` (intra-method backward slice on call-argument identifiers) and a `GraphJsonRenderer` (vertices/edges/chains JSON for LLM consumption). `CallSiteSlicer` delegates snippet extraction to `AstSnippetExtractor` instead of `SourceFragmentReader.readAround`. `ReverseCallChainExtractor` always extracts everything; budget/max-chains caps move to the rendering layer.

**Tech Stack:** Java 21, Gradle Kotlin DSL, JUnit 5 + AssertJ, Jackson, JavaParser 3.27.0+ (core-only, no symbol-solver). No new external tools.

**Spec:** [docs/superpowers/specs/2026-05-14-ast-snippets-and-graph-output-design.md](../specs/2026-05-14-ast-snippets-and-graph-output-design.md)

---

## File inventory

**Create:**
- `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java` — JavaParser-based slicer, parse cache, embedded `SnippetAt` record.
- `src/main/java/com/graphtipper/render/GraphJsonRenderer.java` — vertices/edges/chains JSON rendering.
- `src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java`
- `src/test/java/com/graphtipper/render/GraphJsonRendererTest.java`
- `src/test/resources/snippet-fixtures/SimpleVarChain.java` and seven sibling fixtures (one per test scenario).
- `src/test/resources/graph-schema.json` (JSON Schema for graph.json validation).

**Modify:**
- `build.gradle.kts` — add JavaParser dependency, add JSON Schema validator test dependency.
- `src/main/java/com/graphtipper/slice/ArgOrigin.java` — add new `Kind` values and fields.
- `src/main/java/com/graphtipper/slice/CallSiteSlicer.java` — delegate snippet+arg-origin extraction to `AstSnippetExtractor`.
- `src/main/java/com/graphtipper/slice/ReverseCallChainExtractor.java` — always extract unbounded; rank pure post-sort; safe frontier guard at fixed 100 000.
- `src/main/java/com/graphtipper/cli/Main.java` — orchestrate three renders from one enriched artifact; new flag semantics.
- `src/main/java/com/graphtipper/render/MarkdownRenderer.java` — header tweak (already done), surface new ArgOrigin fields when present.
- `src/main/java/com/graphtipper/render/JsonRenderer.java` — surface new ArgOrigin fields.
- `src/test/java/com/graphtipper/slice/CallSiteSlicerTest.java` — extend.
- `src/test/java/com/graphtipper/cli/MainSmokeTest.java` — verify three files are emitted.

---

## Task 1: Add JavaParser dependency

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Read current dependencies block**

Run: `grep -n 'dependencies' /Users/sckwoky/Projects/Graph-Tipper/build.gradle.kts`
Expected: a `dependencies {` block opens; note the line number.

- [ ] **Step 2: Add JavaParser to dependencies**

Open `build.gradle.kts`, inside the `dependencies { ... }` block, add (alongside existing `implementation` lines):

```kotlin
    implementation("com.github.javaparser:javaparser-core:3.27.0")
    testImplementation("com.networknt:json-schema-validator:1.5.5")
```

- [ ] **Step 3: Resolve dependencies**

Run: `./gradlew --quiet dependencies --configuration runtimeClasspath | grep javaparser`
Expected: `com.github.javaparser:javaparser-core:3.27.0` appears.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add JavaParser core + JSON schema validator (test scope)"
```

---

## Task 2: Expand ArgOrigin with new Kinds and fields

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/ArgOrigin.java`
- Test: `src/test/java/com/graphtipper/slice/ArgOriginTest.java` (create if absent)

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/graphtipper/slice/ArgOriginTest.java`:

```java
package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ArgOriginTest {

    @Test
    void localVarOriginCarriesDefinitionSiteAndSnippet() {
        var o = ArgOrigin.localVar(1, "values", "src/test/java/X.java", 17,
                "final Text[][] values = textArray;");
        assertThat(o.kind()).isEqualTo(ArgOrigin.Kind.LOCAL_VAR);
        assertThat(o.paramName()).isEqualTo("values");
        assertThat(o.definedAtLine()).isEqualTo(17);
        assertThat(o.definedAtSnippet()).isEqualTo("final Text[][] values = textArray;");
    }

    @Test
    void loopVarOriginCarriesForHeader() {
        var o = ArgOrigin.loopVar(0, "col", "src/main/java/CL.java", 17378,
                "for (int col = 0; col < values.length; col++)");
        assertThat(o.kind()).isEqualTo(ArgOrigin.Kind.LOOP_VAR);
        assertThat(o.definedAtLine()).isEqualTo(17378);
    }

    @Test
    void indexedAccessOriginCarriesExprText() {
        var o = ArgOrigin.indexedAccess(2, "values[col]");
        assertThat(o.kind()).isEqualTo(ArgOrigin.Kind.INDEXED_ACCESS);
        assertThat(o.exprText()).isEqualTo("values[col]");
    }

    @Test
    void literalOriginUnchanged() {
        var o = ArgOrigin.literal(0, "null", "src/test/java/X.java", 1992);
        assertThat(o.kind()).isEqualTo(ArgOrigin.Kind.LITERAL);
        assertThat(o.value()).isEqualTo("null");
        assertThat(o.line()).isEqualTo(1992);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.ArgOriginTest`
Expected: COMPILATION FAILURE (`literal`, `localVar`, `loopVar`, `indexedAccess` factories don't exist; `definedAtLine`, `definedAtSnippet`, `exprText` accessors don't exist).

- [ ] **Step 3: Replace ArgOrigin.java with expanded record**

Overwrite `src/main/java/com/graphtipper/slice/ArgOrigin.java`:

```java
package com.graphtipper.slice;

public record ArgOrigin(
        int argIndex,
        Kind kind,
        String value,           // LITERAL
        String factoryFqn,      // FACTORY_CALL
        String paramName,       // PARAMETER, LOCAL_VAR, LOOP_VAR (the identifier name)
        String fieldFqn,        // FIELD
        String file,
        int line,
        int definedAtLine,      // LOCAL_VAR / LOOP_VAR: line of definition; else -1
        String definedAtSnippet,// LOCAL_VAR / LOOP_VAR: one-line snippet of definition; else null
        String exprText         // FIELD_ACCESS / METHOD_CALL / INDEXED_ACCESS / CONSTRUCTOR
) {
    public enum Kind {
        LITERAL,
        PARAMETER,
        FIELD,
        FACTORY_CALL,
        LOCAL_VAR,
        LOOP_VAR,
        FIELD_ACCESS,
        METHOD_CALL,
        INDEXED_ACCESS,
        CONSTRUCTOR,
        UNKNOWN
    }

    // Factory helpers keep call sites short and ensure unused fields stay null/-1.
    public static ArgOrigin literal(int arg, String value, String file, int line) {
        return new ArgOrigin(arg, Kind.LITERAL, value, null, null, null, file, line, -1, null, null);
    }
    public static ArgOrigin parameter(int arg, String paramSignature) {
        return new ArgOrigin(arg, Kind.PARAMETER, null, null, paramSignature, null, null, -1, -1, null, null);
    }
    public static ArgOrigin field(int arg, String fieldFqn) {
        return new ArgOrigin(arg, Kind.FIELD, null, null, null, fieldFqn, null, -1, -1, null, null);
    }
    public static ArgOrigin factoryCall(int arg, String factoryFqn, String file, int line) {
        return new ArgOrigin(arg, Kind.FACTORY_CALL, null, factoryFqn, null, null, file, line, -1, null, null);
    }
    public static ArgOrigin localVar(int arg, String name, String file, int defLine, String defSnippet) {
        return new ArgOrigin(arg, Kind.LOCAL_VAR, null, null, name, null, file, defLine, defLine, defSnippet, null);
    }
    public static ArgOrigin loopVar(int arg, String name, String file, int defLine, String defSnippet) {
        return new ArgOrigin(arg, Kind.LOOP_VAR, null, null, name, null, file, defLine, defLine, defSnippet, null);
    }
    public static ArgOrigin fieldAccess(int arg, String exprText) {
        return new ArgOrigin(arg, Kind.FIELD_ACCESS, null, null, null, null, null, -1, -1, null, exprText);
    }
    public static ArgOrigin methodCall(int arg, String exprText) {
        return new ArgOrigin(arg, Kind.METHOD_CALL, null, null, null, null, null, -1, -1, null, exprText);
    }
    public static ArgOrigin indexedAccess(int arg, String exprText) {
        return new ArgOrigin(arg, Kind.INDEXED_ACCESS, null, null, null, null, null, -1, -1, null, exprText);
    }
    public static ArgOrigin constructor(int arg, String exprText) {
        return new ArgOrigin(arg, Kind.CONSTRUCTOR, null, null, null, null, null, -1, -1, null, exprText);
    }
    public static ArgOrigin unknown(int arg) {
        return new ArgOrigin(arg, Kind.UNKNOWN, null, null, null, null, null, -1, -1, null, null);
    }
}
```

- [ ] **Step 4: Fix call sites that constructed ArgOrigin directly**

Run: `grep -rn 'new ArgOrigin(' /Users/sckwoky/Projects/Graph-Tipper/src 2>/dev/null`
Expected: hits in `CallSiteSlicer.java`, `LocalContextExtractor.java`, and tests.

For each hit, replace `new ArgOrigin(...)` with the matching factory. Examples:
- `new ArgOrigin(i, ArgOrigin.Kind.LITERAL, lit.value(), null, null, null, file, line)` → `ArgOrigin.literal(i, lit.value(), file, line)`
- `new ArgOrigin(i, ArgOrigin.Kind.PARAMETER, null, null, name+":"+type, null, null, -1)` → `ArgOrigin.parameter(i, name + ":" + type)`
- `new ArgOrigin(i, ArgOrigin.Kind.UNKNOWN, null, null, null, null, null, -1)` → `ArgOrigin.unknown(i)`

- [ ] **Step 5: Update JsonRenderer for new fields**

Open `src/main/java/com/graphtipper/render/JsonRenderer.java`. Inside the `for (ArgOrigin o : s.argOrigins())` loop, after the existing `on.put(...)` calls, add (preserving order — the legacy fields stay first for backward compat):

```java
                    if (o.exprText() != null) on.put("exprText", o.exprText());
                    if (o.definedAtLine() > 0) {
                        on.put("definedAtLine", o.definedAtLine());
                        if (o.definedAtSnippet() != null) on.put("definedAtSnippet", o.definedAtSnippet());
                    }
```

- [ ] **Step 6: Run test to verify it passes and existing tests still pass**

Run: `./gradlew test`
Expected: PASS (all tests, including `ArgOriginTest`, existing `CallSiteSlicerTest`, etc.).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ArgOrigin.java \
        src/main/java/com/graphtipper/slice/CallSiteSlicer.java \
        src/main/java/com/graphtipper/slice/LocalContextExtractor.java \
        src/main/java/com/graphtipper/render/JsonRenderer.java \
        src/test/java/com/graphtipper/slice/ArgOriginTest.java
git commit -m "feat(slice): extend ArgOrigin with LOCAL_VAR/LOOP_VAR/expr kinds"
```

---

## Task 3: AstSnippetExtractor skeleton + SnippetAt record + parse cache

> **Note (spec divergence):** the spec describes a `Slice` record with the body and a separate `argOrigins` list returned alongside it. The plan consolidates both into one record named `SnippetAt` to keep the API surface tight. Field set is a superset of the spec's (adds `argOrigins` and `callColumn`).

**Files:**
- Create: `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java`
- Create: `src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java`
- Create: `src/test/resources/snippet-fixtures/UnparseableFile.java`
- Create: `src/test/resources/snippet-fixtures/SimpleVarChain.java`

- [ ] **Step 1: Write the test fixtures**

Create `src/test/resources/snippet-fixtures/SimpleVarChain.java`:

```java
package fixtures;

public class SimpleVarChain {
    public void runChain() {
        int n = 42;
        String name = "test-" + n;
        process(name, n);
    }
    public void process(String s, int x) {}
}
```

Create `src/test/resources/snippet-fixtures/UnparseableFile.java`:

```java
package fixtures;

public class UnparseableFile {
    public void broken( { // intentional syntax error
        not real Java;
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java`:

```java
package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class AstSnippetExtractorTest {

    private final AstSnippetExtractor extractor = new AstSnippetExtractor();

    @Test
    void parsesAndReturnsSliceForCallAtLine() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 6, 9, "process", 12);
        assertThat(s.warnings()).isEmpty();
        assertThat(s.enclosingMethodSignature()).contains("runChain");
        assertThat(String.join("\n", s.renderedBody())).contains("process(name, n)");
    }

    @Test
    void unparseableFileFallsBackToReadAround() {
        Path file = Path.of("src/test/resources/snippet-fixtures/UnparseableFile.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 4, 9, "broken", 12);
        assertThat(s.warnings()).contains("parse_failed");
        assertThat(s.renderedBody()).isNotEmpty();  // fallback body is non-empty
    }

    @Test
    void missingFileReturnsWarning() {
        Path file = Path.of("src/test/resources/snippet-fixtures/_does_not_exist_.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 1, 1, "x", 12);
        assertThat(s.warnings()).contains("file_not_found");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest`
Expected: COMPILATION FAILURE (`AstSnippetExtractor` and `SnippetAt` don't exist yet).

- [ ] **Step 4: Create AstSnippetExtractor skeleton**

Create `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java`:

```java
package com.graphtipper.slice;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class AstSnippetExtractor {

    public record SnippetAt(
            String enclosingMethodSignature,
            int callLine,
            int callColumn,
            List<String> renderedBody,
            List<ArgOrigin> argOrigins,
            boolean truncated,
            List<String> warnings) {}

    private static final int CACHE_LIMIT = 256;
    private static final int MAX_LINES_PER_SNIPPET = 60;

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    private final LinkedHashMap<Path, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, CacheEntry> e) {
            return size() > CACHE_LIMIT;
        }
    };

    private record CacheEntry(CompilationUnit cu, List<String> rawLines, boolean parseOk) {}

    public SnippetAt sliceAt(Path file, int callLine, int callColumn,
                              String calleeSimpleName, int maxSliceStmts) {
        CacheEntry entry = load(file);
        if (entry == null) {
            return new SnippetAt("", callLine, callColumn, List.of("(file not found)"),
                    List.of(), false, List.of("file_not_found"));
        }
        if (!entry.parseOk) {
            return fallback(entry.rawLines, callLine, callColumn, List.of("parse_failed"));
        }
        // Slicing logic in Tasks 4-8 will replace this stub.
        return fallback(entry.rawLines, callLine, callColumn, List.of("not_implemented_yet"));
    }

    private SnippetAt fallback(List<String> rawLines, int callLine, int callColumn,
                                List<String> warnings) {
        int from = Math.max(1, callLine - 3);
        int to = Math.min(rawLines.size(), callLine + 2);
        List<String> body = new ArrayList<>();
        for (int i = from; i <= to; i++) body.add(rawLines.get(i - 1));
        return new SnippetAt("(fallback)", callLine, callColumn, body, List.of(), false, warnings);
    }

    private CacheEntry load(Path file) {
        Path key = file.toAbsolutePath().normalize();
        CacheEntry hit = cache.get(key);
        if (hit != null) return hit;
        if (!Files.exists(key)) return null;
        try {
            List<String> raw = Files.readAllLines(key);
            ParseResult<CompilationUnit> result = parser.parse(key);
            CompilationUnit cu = result.getResult().orElse(null);
            CacheEntry entry = new CacheEntry(cu, raw, cu != null && result.isSuccessful());
            cache.put(key, entry);
            return entry;
        } catch (IOException io) {
            return new CacheEntry(null, List.of("(io error: " + io.getMessage() + ")"),
                    false);
        }
    }
}
```

- [ ] **Step 5: Run test to verify partial pass**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest`
Expected: `parsesAndReturnsSliceForCallAtLine` FAILS (slice logic not yet implemented — body says `not_implemented_yet`). `unparseableFileFallsBackToReadAround` and `missingFileReturnsWarning` PASS.

- [ ] **Step 6: Commit (partial green, stub)**

```bash
git add build.gradle.kts \
        src/main/java/com/graphtipper/slice/AstSnippetExtractor.java \
        src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java \
        src/test/resources/snippet-fixtures/SimpleVarChain.java \
        src/test/resources/snippet-fixtures/UnparseableFile.java
git commit -m "feat(slice): scaffold AstSnippetExtractor with parse cache + fallback path"
```

---

## Task 4: Locate call site at line/column

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java`
- Modify: `src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java`
- Create: `src/test/resources/snippet-fixtures/ConstructorCall.java`

- [ ] **Step 1: Add fixture for constructor calls**

Create `src/test/resources/snippet-fixtures/ConstructorCall.java`:

```java
package fixtures;

import java.util.ArrayList;

public class ConstructorCall {
    public void create() {
        ArrayList<String> list = new ArrayList<>();
        list.add("x");
    }
}
```

- [ ] **Step 2: Write failing tests for call-site location**

Append to `AstSnippetExtractorTest.java` (inside the class):

```java
    @Test
    void findsMethodCallAtLine() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 6, 9, "process", 12);
        assertThat(s.warnings()).doesNotContain("call_not_found");
        assertThat(s.callLine()).isEqualTo(6);
    }

    @Test
    void findsConstructorCall() {
        Path file = Path.of("src/test/resources/snippet-fixtures/ConstructorCall.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 7, 33, "ArrayList", 12);
        assertThat(s.warnings()).doesNotContain("call_not_found");
        assertThat(s.callLine()).isEqualTo(7);
    }

    @Test
    void unfoundCallEmitsWarning() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 6, 9, "doesNotExist", 12);
        assertThat(s.warnings()).contains("call_not_found");
    }
```

- [ ] **Step 3: Run tests to verify failure**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest`
Expected: at least one of the new tests FAILS (current stub emits `not_implemented_yet`, doesn't emit `call_not_found`, and doesn't set call_line/column from AST).

- [ ] **Step 4: Implement call-site location**

In `AstSnippetExtractor.java`, replace the `sliceAt` body (keep the cache + parse-error paths) with logic that locates the call node. Add the following imports at the top:

```java
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
```

Replace the body of `sliceAt(...)` after the parse-error guard with:

```java
        CompilationUnit cu = entry.cu;
        Node callNode = locateCallNode(cu, callLine, callColumn, calleeSimpleName);
        if (callNode == null) {
            return fallback(entry.rawLines, callLine, callColumn, List.of("call_not_found"));
        }
        // Slicing logic in Tasks 5-8 will replace this stub.
        List<String> body = new ArrayList<>();
        body.add("(call located at line " + callLine + ")");
        return new SnippetAt("(stub)", callLine, callColumn, body, List.of(), false, List.of());
```

And add the helper at the bottom of the class:

```java
    private Node locateCallNode(CompilationUnit cu, int line, int column, String calleeSimpleName) {
        Node best = null;
        int bestColDelta = Integer.MAX_VALUE;
        for (MethodCallExpr m : cu.findAll(MethodCallExpr.class)) {
            if (!m.getName().asString().equals(calleeSimpleName)) continue;
            if (!m.getBegin().isPresent()) continue;
            int begLine = m.getBegin().get().line;
            int begCol = m.getBegin().get().column;
            if (begLine != line) continue;
            int delta = Math.abs(begCol - column);
            if (delta < bestColDelta) { best = m; bestColDelta = delta; }
        }
        for (ObjectCreationExpr o : cu.findAll(ObjectCreationExpr.class)) {
            if (!o.getType().getName().asString().equals(calleeSimpleName)) continue;
            if (!o.getBegin().isPresent()) continue;
            int begLine = o.getBegin().get().line;
            int begCol = o.getBegin().get().column;
            if (begLine != line) continue;
            int delta = Math.abs(begCol - column);
            if (delta < bestColDelta) { best = o; bestColDelta = delta; }
        }
        return best;
    }
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest`
Expected: `findsMethodCallAtLine`, `findsConstructorCall`, `unfoundCallEmitsWarning` all PASS. `parsesAndReturnsSliceForCallAtLine` still FAILS (waiting on slicing).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/AstSnippetExtractor.java \
        src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java \
        src/test/resources/snippet-fixtures/ConstructorCall.java
git commit -m "feat(slice): locate call sites (method + constructor) by line/col + callee name"
```

---

## Task 5: Find enclosing method (including inner classes)

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java`
- Modify: `src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java`
- Create: `src/test/resources/snippet-fixtures/InnerClassMethod.java`

- [ ] **Step 1: Add inner-class fixture**

Create `src/test/resources/snippet-fixtures/InnerClassMethod.java`:

```java
package fixtures;

public class InnerClassMethod {
    static class Outer {
        static class Inner {
            void target(int x) {
                helper(x);
            }
            void helper(int n) {}
        }
    }
}
```

- [ ] **Step 2: Write failing test**

Append to `AstSnippetExtractorTest.java`:

```java
    @Test
    void findsEnclosingMethodForInnerClassCall() {
        Path file = Path.of("src/test/resources/snippet-fixtures/InnerClassMethod.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 7, 17, "helper", 12);
        assertThat(s.warnings()).doesNotContain("no_enclosing_method");
        assertThat(s.enclosingMethodSignature()).contains("target");
        assertThat(s.enclosingMethodSignature()).contains("int x");
    }
```

- [ ] **Step 3: Run test to verify failure**

Run: `./gradlew test --tests "com.graphtipper.slice.AstSnippetExtractorTest.findsEnclosingMethodForInnerClassCall"`
Expected: FAIL (`enclosingMethodSignature` is `(stub)`, not containing "target").

- [ ] **Step 4: Implement enclosing-method resolution**

Add imports:

```java
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
```

Add helper after `locateCallNode`:

```java
    /**
     * Walks parent nodes to find the closest enclosing method/constructor declaration.
     * Returns the declaration node, or null when the call lives outside any method
     * (e.g. a field initializer or static block — handled at the caller).
     */
    private CallableDeclaration<?> findEnclosingMethod(Node callNode) {
        Node n = callNode;
        while (n != null) {
            if (n instanceof MethodDeclaration md) return md;
            if (n instanceof ConstructorDeclaration cd) return cd;
            n = n.getParentNode().orElse(null);
        }
        return null;
    }

    /** Format the method signature as a single readable line. */
    private String signatureOf(CallableDeclaration<?> decl) {
        // JavaParser includes parameters but not body; toString() of the declaration's
        // declarationAsString gives us "public void runChain(int x, String y)" form.
        return decl.getDeclarationAsString(true, false, true);
    }

    /** Identify a static/instance initializer block as the "enclosing" if no method exists. */
    private boolean inInitializerBlock(Node callNode) {
        Node n = callNode;
        while (n != null) {
            if (n instanceof InitializerDeclaration) return true;
            n = n.getParentNode().orElse(null);
        }
        return false;
    }
```

Update the `sliceAt` method to use the enclosing-method resolver. Replace the stub-body section with:

```java
        CallableDeclaration<?> enclosing = findEnclosingMethod(callNode);
        if (enclosing == null) {
            List<String> warns = new ArrayList<>();
            warns.add(inInitializerBlock(callNode) ? "no_enclosing_method:initializer"
                                                   : "no_enclosing_method");
            return fallback(entry.rawLines, callLine, callColumn, warns);
        }
        String signature = signatureOf(enclosing);

        // Slicing logic in Tasks 6-8 will fill renderedBody and argOrigins.
        return new SnippetAt(signature, callLine, callColumn,
                List.of(signature + " { /* not yet sliced */ }"),
                List.of(), false, List.of());
```

- [ ] **Step 5: Run test to verify pass**

Run: `./gradlew test --tests "com.graphtipper.slice.AstSnippetExtractorTest.findsEnclosingMethodForInnerClassCall"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/AstSnippetExtractor.java \
        src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java \
        src/test/resources/snippet-fixtures/InnerClassMethod.java
git commit -m "feat(slice): resolve enclosing method, including inner-class methods"
```

---

## Task 6: Classify argument origins

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java`
- Modify: `src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java`
- Create: `src/test/resources/snippet-fixtures/LiteralOnly.java`

- [ ] **Step 1: Add literal-only fixture**

Create `src/test/resources/snippet-fixtures/LiteralOnly.java`:

```java
package fixtures;

public class LiteralOnly {
    void caller() {
        process(0, "x", null);
    }
    void process(int a, String b, Object c) {}
}
```

- [ ] **Step 2: Write failing tests**

Append to `AstSnippetExtractorTest.java`:

```java
    @Test
    void classifiesLiteralArguments() {
        Path file = Path.of("src/test/resources/snippet-fixtures/LiteralOnly.java");
        var s = extractor.sliceAt(file, 5, 9, "process", 12);
        assertThat(s.argOrigins()).hasSize(3);
        assertThat(s.argOrigins().get(0).kind()).isEqualTo(ArgOrigin.Kind.LITERAL);
        assertThat(s.argOrigins().get(0).value()).isEqualTo("0");
        assertThat(s.argOrigins().get(1).kind()).isEqualTo(ArgOrigin.Kind.LITERAL);
        assertThat(s.argOrigins().get(1).value()).isEqualTo("\"x\"");
        assertThat(s.argOrigins().get(2).kind()).isEqualTo(ArgOrigin.Kind.LITERAL);
        assertThat(s.argOrigins().get(2).value()).isEqualTo("null");
    }

    @Test
    void classifiesLocalVariableArgument() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        var s = extractor.sliceAt(file, 6, 9, "process", 12);
        assertThat(s.argOrigins()).hasSize(2);
        var a0 = s.argOrigins().get(0);
        assertThat(a0.kind()).isEqualTo(ArgOrigin.Kind.LOCAL_VAR);
        assertThat(a0.paramName()).isEqualTo("name");
        var a1 = s.argOrigins().get(1);
        assertThat(a1.kind()).isEqualTo(ArgOrigin.Kind.LOCAL_VAR);
        assertThat(a1.paramName()).isEqualTo("n");
    }
```

- [ ] **Step 3: Run tests to verify failure**

Run: `./gradlew test --tests "com.graphtipper.slice.AstSnippetExtractorTest.classifiesLiteralArguments"`
Expected: FAIL (`argOrigins()` is empty).

- [ ] **Step 4: Implement argument classification**

Add imports:

```java
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
```

Add helper methods inside `AstSnippetExtractor`:

```java
    private List<Expression> argumentsOf(Node callNode) {
        if (callNode instanceof MethodCallExpr m) return m.getArguments();
        if (callNode instanceof ObjectCreationExpr o) return o.getArguments();
        return new NodeList<>();
    }

    /**
     * Classify each argument and collect seed identifiers that the backward slice
     * should look up. Definition lookup happens later (Task 7); here we only set kind
     * and capture text. For NameExpr args, the kind starts as LOCAL_VAR by default;
     * Task 7 reclassifies to PARAMETER / LOOP_VAR / FIELD if the slice discovers the
     * source.
     */
    private List<ArgOrigin> classifyArguments(Node callNode, Set<String> seedsOut) {
        List<Expression> args = argumentsOf(callNode);
        List<ArgOrigin> origins = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            Expression arg = args.get(i);
            ArgOrigin o = classifyOne(i, arg, seedsOut);
            origins.add(o);
        }
        return origins;
    }

    private ArgOrigin classifyOne(int idx, Expression arg, Set<String> seedsOut) {
        if (arg instanceof NullLiteralExpr) return ArgOrigin.literal(idx, "null", null, -1);
        if (arg instanceof LiteralExpr lit) return ArgOrigin.literal(idx, lit.toString(), null, -1);
        if (arg instanceof NameExpr ne) {
            seedsOut.add(ne.getNameAsString());
            // Provisional classification; refined by the slice walk in Task 7.
            return ArgOrigin.localVar(idx, ne.getNameAsString(), null, -1, null);
        }
        if (arg instanceof FieldAccessExpr fa) {
            // Receiver may carry an identifier we should seed (e.g. `x.y.z` → seed "x").
            addLeftmostName(fa, seedsOut);
            return ArgOrigin.fieldAccess(idx, fa.toString());
        }
        if (arg instanceof ArrayAccessExpr aa) {
            addAllNames(aa, seedsOut);
            return ArgOrigin.indexedAccess(idx, aa.toString());
        }
        if (arg instanceof MethodCallExpr mc) {
            addAllNames(mc, seedsOut);
            return ArgOrigin.methodCall(idx, mc.toString());
        }
        if (arg instanceof ObjectCreationExpr oc) {
            addAllNames(oc, seedsOut);
            return ArgOrigin.constructor(idx, oc.toString());
        }
        // BinaryExpr, UnaryExpr, CastExpr, etc.: harvest identifiers, record as METHOD_CALL kind
        // with the literal expression text — gives the LLM the original text without committing
        // to a finer classification.
        addAllNames(arg, seedsOut);
        return ArgOrigin.methodCall(idx, arg.toString());
    }

    private void addLeftmostName(Node n, Set<String> seedsOut) {
        Node cur = n;
        while (cur instanceof FieldAccessExpr fa) cur = fa.getScope();
        if (cur instanceof NameExpr ne) seedsOut.add(ne.getNameAsString());
    }

    private void addAllNames(Node n, Set<String> seedsOut) {
        for (NameExpr ne : n.findAll(NameExpr.class)) seedsOut.add(ne.getNameAsString());
    }
```

Update `sliceAt` to call this and embed the result. Replace the body returning the placeholder with:

```java
        Set<String> seeds = new LinkedHashSet<>();
        List<ArgOrigin> argOrigins = classifyArguments(callNode, seeds);

        // Slicing of statements (and refinement of LOCAL_VAR → PARAMETER/LOOP_VAR/FIELD)
        // happens in Task 7. For now expose the argOrigins so the test can assert them.
        return new SnippetAt(signature, callLine, callColumn,
                List.of(signature + " { /* not yet sliced */ }"),
                argOrigins, false, List.of());
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew test --tests "com.graphtipper.slice.AstSnippetExtractorTest.classifiesLiteralArguments" --tests "com.graphtipper.slice.AstSnippetExtractorTest.classifiesLocalVariableArgument"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/AstSnippetExtractor.java \
        src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java \
        src/test/resources/snippet-fixtures/LiteralOnly.java
git commit -m "feat(slice): classify call arguments (literal / nameExpr / field / array / call / new)"
```

---

## Task 7: Backward slice + parameter/loop-var reclassification

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java`
- Modify: `src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java`
- Create: `src/test/resources/snippet-fixtures/ParameterArg.java`
- Create: `src/test/resources/snippet-fixtures/LoopVar.java`
- Create: `src/test/resources/snippet-fixtures/NestedBlocks.java`
- Create: `src/test/resources/snippet-fixtures/TruncationLimit.java`

- [ ] **Step 1: Add fixtures**

Create `src/test/resources/snippet-fixtures/ParameterArg.java`:

```java
package fixtures;

public class ParameterArg {
    public void runner(String value) {
        consume(value);
    }
    void consume(String v) {}
}
```

Create `src/test/resources/snippet-fixtures/LoopVar.java`:

```java
package fixtures;

public class LoopVar {
    void runner() {
        int[] arr = new int[]{1, 2, 3};
        for (int col = 0; col < arr.length; col++) {
            visit(col);
        }
    }
    void visit(int x) {}
}
```

Create `src/test/resources/snippet-fixtures/NestedBlocks.java`:

```java
package fixtures;

public class NestedBlocks {
    void runner(boolean cond) {
        int v = 7;
        if (cond) {
            use(v);
        }
    }
    void use(int n) {}
}
```

Create `src/test/resources/snippet-fixtures/TruncationLimit.java`:

```java
package fixtures;

public class TruncationLimit {
    void runner() {
        int a = 1;
        int b = a + 1;
        int c = b + 1;
        int d = c + 1;
        int e = d + 1;
        use(e);
    }
    void use(int x) {}
}
```

- [ ] **Step 2: Write failing tests**

Append to `AstSnippetExtractorTest.java`:

```java
    @Test
    void reclassifiesParameterArgument() {
        Path file = Path.of("src/test/resources/snippet-fixtures/ParameterArg.java");
        var s = extractor.sliceAt(file, 5, 9, "consume", 12);
        assertThat(s.argOrigins()).hasSize(1);
        assertThat(s.argOrigins().get(0).kind()).isEqualTo(ArgOrigin.Kind.PARAMETER);
        assertThat(s.argOrigins().get(0).paramName()).contains("value");
    }

    @Test
    void reclassifiesLoopVariable() {
        Path file = Path.of("src/test/resources/snippet-fixtures/LoopVar.java");
        var s = extractor.sliceAt(file, 7, 13, "visit", 12);
        assertThat(s.argOrigins()).hasSize(1);
        var a = s.argOrigins().get(0);
        assertThat(a.kind()).isEqualTo(ArgOrigin.Kind.LOOP_VAR);
        assertThat(a.paramName()).isEqualTo("col");
        assertThat(a.definedAtLine()).isEqualTo(6);
    }

    @Test
    void backwardSliceCapturesDefinition() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        var s = extractor.sliceAt(file, 6, 9, "process", 12);
        // origin 0 is `name`, defined on line 5; origin 1 is `n`, defined on line 4
        assertThat(s.argOrigins().get(0).definedAtLine()).isEqualTo(5);
        assertThat(s.argOrigins().get(0).definedAtSnippet()).contains("String name");
        assertThat(s.argOrigins().get(1).definedAtLine()).isEqualTo(4);
        assertThat(s.argOrigins().get(1).definedAtSnippet()).contains("int n = 42");
    }

    @Test
    void truncationFlagSetWhenLimitExceeded() {
        Path file = Path.of("src/test/resources/snippet-fixtures/TruncationLimit.java");
        var s = extractor.sliceAt(file, 9, 9, "use", 2);
        assertThat(s.truncated()).isTrue();
    }
```

- [ ] **Step 3: Run tests to verify failure**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest`
Expected: at least the four new tests FAIL (origins still LOCAL_VAR provisional, no definedAtLine populated, no truncation flag).

- [ ] **Step 4: Implement backward slice**

Add imports:

```java
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
```

Add the slice helper that returns the **selected statements** (in source order) and the **refined argOrigins** (PARAMETER / LOOP_VAR / LOCAL_VAR with definedAt populated). Place inside `AstSnippetExtractor`:

```java
    private record SliceResult(LinkedHashSet<Statement> selected,
                                List<ArgOrigin> refinedArgs,
                                boolean truncated) {}

    private SliceResult backwardSlice(CallableDeclaration<?> enclosing,
                                       Node callNode,
                                       List<ArgOrigin> argOrigins,
                                       Set<String> initialSeeds,
                                       int maxSliceStmts,
                                       List<String> rawLines) {
        // Refine each NameExpr-style argOrigin (LOCAL_VAR provisional) using the method's
        // parameter list first. PARAMETER kind requires no statement to be selected.
        Map<String, Parameter> paramByName = new HashMap<>();
        for (Parameter p : enclosing.getParameters()) paramByName.put(p.getNameAsString(), p);

        List<ArgOrigin> refined = new ArrayList<>(argOrigins);
        Set<String> needed = new LinkedHashSet<>(initialSeeds);

        for (int i = 0; i < refined.size(); i++) {
            ArgOrigin o = refined.get(i);
            if (o.kind() == ArgOrigin.Kind.LOCAL_VAR && paramByName.containsKey(o.paramName())) {
                Parameter p = paramByName.get(o.paramName());
                refined.set(i, ArgOrigin.parameter(i, p.getNameAsString() + ":" + p.getType()));
                needed.remove(o.paramName());
            }
        }

        // Walk statements ABOVE the call statement in source order, in reverse.
        Statement callStmt = enclosingStatement(callNode);
        if (callStmt == null) return new SliceResult(new LinkedHashSet<>(), refined, false);

        BlockStmt body = bodyOf(enclosing).orElse(null);
        if (body == null) return new SliceResult(new LinkedHashSet<>(), refined, false);

        List<Statement> ordered = flattenStatements(body);
        int callIdx = ordered.indexOf(callStmt);
        if (callIdx < 0) return new SliceResult(new LinkedHashSet<>(), refined, false);

        LinkedHashSet<Statement> selected = new LinkedHashSet<>();
        selected.add(callStmt);
        boolean truncated = false;

        for (int i = callIdx - 1; i >= 0 && !needed.isEmpty(); i--) {
            if (selected.size() >= maxSliceStmts) { truncated = true; break; }
            Statement s = ordered.get(i);
            if (matchesAssignmentOf(s, needed)) {
                selected.add(s);
                // Replace LOCAL_VAR provisional origins with definedAt populated, if applicable.
                String line = rawLines.size() >= s.getBegin().get().line
                        ? rawLines.get(s.getBegin().get().line - 1).trim() : "";
                refineLocalVarOrigins(refined, s, line);
                Set<String> newSeeds = identifiersInRhs(s);
                needed.addAll(newSeeds);
                needed.removeAll(definedBy(s));
            }
            if (matchesLoopHeader(s, needed)) {
                selected.add(s);
                String line = rawLines.size() >= s.getBegin().get().line
                        ? rawLines.get(s.getBegin().get().line - 1).trim() : "";
                refineLoopVarOrigins(refined, s, line);
                needed.removeAll(definedBy(s));
            }
        }
        if (!needed.isEmpty() && selected.size() >= maxSliceStmts) truncated = true;

        return new SliceResult(selected, refined, truncated);
    }

    /** Statement directly containing the call expression (climbs through expressions). */
    private Statement enclosingStatement(Node callNode) {
        Node n = callNode;
        while (n != null && !(n instanceof Statement)) n = n.getParentNode().orElse(null);
        return (Statement) n;
    }

    private List<Statement> flattenStatements(BlockStmt body) {
        List<Statement> out = new ArrayList<>();
        for (Statement s : body.getStatements()) collectStatements(s, out);
        return out;
    }

    private void collectStatements(Statement s, List<Statement> out) {
        out.add(s);
        if (s instanceof BlockStmt b) for (Statement c : b.getStatements()) collectStatements(c, out);
        else if (s instanceof IfStmt i) {
            collectStatements(i.getThenStmt(), out);
            i.getElseStmt().ifPresent(e -> collectStatements(e, out));
        } else if (s instanceof WhileStmt w) collectStatements(w.getBody(), out);
        else if (s instanceof ForStmt f) collectStatements(f.getBody(), out);
        else if (s instanceof ForEachStmt fe) collectStatements(fe.getBody(), out);
        else if (s instanceof TryStmt t) {
            collectStatements(t.getTryBlock(), out);
            t.getCatchClauses().forEach(c -> collectStatements(c.getBody(), out));
            t.getFinallyBlock().ifPresent(b -> collectStatements(b, out));
        }
    }

    private boolean matchesAssignmentOf(Statement s, Set<String> needed) {
        if (s instanceof ExpressionStmt es) {
            Expression e = es.getExpression();
            if (e instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator v : vde.getVariables()) {
                    if (needed.contains(v.getNameAsString())) return true;
                }
            }
            if (e instanceof AssignExpr ae && ae.getTarget() instanceof NameExpr ne) {
                if (needed.contains(ne.getNameAsString())) return true;
            }
        }
        return false;
    }

    private boolean matchesLoopHeader(Statement s, Set<String> needed) {
        if (s instanceof ForStmt f) {
            for (Expression e : f.getInitialization()) {
                if (e instanceof VariableDeclarationExpr vde) {
                    for (VariableDeclarator v : vde.getVariables()) {
                        if (needed.contains(v.getNameAsString())) return true;
                    }
                }
            }
        }
        if (s instanceof ForEachStmt fe && needed.contains(fe.getVariable().getVariable(0).getNameAsString())) return true;
        return false;
    }

    private Set<String> definedBy(Statement s) {
        Set<String> out = new LinkedHashSet<>();
        if (s instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr vde) {
            for (VariableDeclarator v : vde.getVariables()) out.add(v.getNameAsString());
        }
        if (s instanceof ExpressionStmt es && es.getExpression() instanceof AssignExpr ae
                && ae.getTarget() instanceof NameExpr ne) out.add(ne.getNameAsString());
        if (s instanceof ForStmt f) {
            for (Expression e : f.getInitialization()) {
                if (e instanceof VariableDeclarationExpr vde) {
                    for (VariableDeclarator v : vde.getVariables()) out.add(v.getNameAsString());
                }
            }
        }
        if (s instanceof ForEachStmt fe) out.add(fe.getVariable().getVariable(0).getNameAsString());
        return out;
    }

    private Set<String> identifiersInRhs(Statement s) {
        Set<String> out = new LinkedHashSet<>();
        if (s instanceof ExpressionStmt es) {
            Expression e = es.getExpression();
            if (e instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator v : vde.getVariables()) {
                    v.getInitializer().ifPresent(init -> addAllNames(init, out));
                }
            }
            if (e instanceof AssignExpr ae) addAllNames(ae.getValue(), out);
        }
        return out;
    }

    private void refineLocalVarOrigins(List<ArgOrigin> refined, Statement defStmt, String snippetLine) {
        Set<String> defined = definedBy(defStmt);
        int defLine = defStmt.getBegin().get().line;
        for (int i = 0; i < refined.size(); i++) {
            ArgOrigin o = refined.get(i);
            if (o.kind() == ArgOrigin.Kind.LOCAL_VAR && defined.contains(o.paramName())
                    && o.definedAtLine() <= 0) {
                refined.set(i, ArgOrigin.localVar(i, o.paramName(), null, defLine, snippetLine));
            }
        }
    }

    private void refineLoopVarOrigins(List<ArgOrigin> refined, Statement loopStmt, String snippetLine) {
        Set<String> defined = definedBy(loopStmt);
        int defLine = loopStmt.getBegin().get().line;
        for (int i = 0; i < refined.size(); i++) {
            ArgOrigin o = refined.get(i);
            if (o.kind() == ArgOrigin.Kind.LOCAL_VAR && defined.contains(o.paramName())) {
                refined.set(i, ArgOrigin.loopVar(i, o.paramName(), null, defLine, snippetLine));
            }
        }
    }
```

Add a tiny adapter on `CallableDeclaration` (the `bodyOf` helper is referenced above by `backwardSlice`):

```java
    // CallableDeclaration is the supertype of MethodDeclaration and ConstructorDeclaration;
    // getBody() differs, so this adapter fetches the body uniformly.
    private static java.util.Optional<BlockStmt> bodyOf(CallableDeclaration<?> decl) {
        if (decl instanceof MethodDeclaration md) return md.getBody();
        if (decl instanceof ConstructorDeclaration cd) return java.util.Optional.of(cd.getBody());
        return java.util.Optional.empty();
    }
```

Update `sliceAt` to invoke `backwardSlice`. Replace the placeholder return with:

```java
        Set<String> seeds = new LinkedHashSet<>();
        List<ArgOrigin> argOrigins = classifyArguments(callNode, seeds);
        SliceResult sliced = backwardSlice(enclosing, callNode, argOrigins, seeds,
                maxSliceStmts, entry.rawLines);
        // Body emission is implemented in Task 8; for now provide the signature + selected
        // statement texts joined by `// ...` separators so this Task's tests can pass.
        List<String> body = new ArrayList<>();
        body.add(signature + " {");
        Statement prev = null;
        java.util.List<Statement> ordered = new java.util.ArrayList<>(sliced.selected());
        ordered.sort(java.util.Comparator.comparingInt(st -> st.getBegin().get().line));
        for (Statement s : ordered) {
            if (prev != null && s.getBegin().get().line > prev.getEnd().get().line + 1) {
                body.add("    // ...");
            }
            String code = String.join("\n", entry.rawLines.subList(
                    s.getBegin().get().line - 1, s.getEnd().get().line));
            body.add(code);
            prev = s;
        }
        body.add("}");
        return new SnippetAt(signature, callLine, callColumn, body,
                sliced.refinedArgs(), sliced.truncated(), List.of());
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest`
Expected: all `AstSnippetExtractorTest` tests PASS, including `reclassifiesParameterArgument`, `reclassifiesLoopVariable`, `backwardSliceCapturesDefinition`, `truncationFlagSetWhenLimitExceeded`. The earlier `parsesAndReturnsSliceForCallAtLine` also PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/AstSnippetExtractor.java \
        src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java \
        src/test/resources/snippet-fixtures/ParameterArg.java \
        src/test/resources/snippet-fixtures/LoopVar.java \
        src/test/resources/snippet-fixtures/NestedBlocks.java \
        src/test/resources/snippet-fixtures/TruncationLimit.java
git commit -m "feat(slice): intra-method backward slice + parameter/loop-var refinement"
```

---

## Task 8: Polish snippet emission (indentation, control-structure headers)

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java`
- Modify: `src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java`

- [ ] **Step 1: Write failing test for control-header capture**

Append to `AstSnippetExtractorTest.java`:

```java
    @Test
    void includesEnclosingIfHeader() {
        Path file = Path.of("src/test/resources/snippet-fixtures/NestedBlocks.java");
        var s = extractor.sliceAt(file, 7, 13, "use", 12);
        String body = String.join("\n", s.renderedBody());
        assertThat(body).contains("int v = 7;");
        assertThat(body).contains("if (cond)");
        assertThat(body).contains("use(v)");
    }

    @Test
    void renderedBodyStartsWithSignatureAndEndsWithBrace() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        var s = extractor.sliceAt(file, 6, 9, "process", 12);
        assertThat(s.renderedBody().get(0)).contains("runChain");
        assertThat(s.renderedBody().get(s.renderedBody().size() - 1).trim()).isEqualTo("}");
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew test --tests "com.graphtipper.slice.AstSnippetExtractorTest.includesEnclosingIfHeader"`
Expected: FAIL (current `flattenStatements` includes the use statement but not the enclosing `if` header on its own line).

- [ ] **Step 3: Add control-structure header capture**

In `AstSnippetExtractor.backwardSlice`, after computing `selected` from the regular walk, walk up the call statement's parent chain to add enclosing control-structure headers. Insert the following block right before the final `return new SliceResult(...)`:

```java
        // Capture headers of enclosing control structures (if/while/for/try) so the rendered
        // snippet shows the code path that leads to the call.
        Node ctxNode = callStmt;
        while (ctxNode != null) {
            ctxNode = ctxNode.getParentNode().orElse(null);
            if (ctxNode == null || ctxNode == bodyOf(enclosing).orElse(null)) break;
            if (ctxNode instanceof IfStmt || ctxNode instanceof WhileStmt
                    || ctxNode instanceof ForStmt || ctxNode instanceof ForEachStmt
                    || ctxNode instanceof TryStmt) {
                selected.add((Statement) ctxNode);
            }
        }
```

- [ ] **Step 4: Override emission to render only the header line for control structures**

In `sliceAt`, where we render `String code = String.join("\n", ...)`, special-case control statements to render just the first line plus a trailing `{`. Replace the emission loop with:

```java
        for (Statement s : ordered) {
            if (prev != null && s.getBegin().get().line > prev.getEnd().get().line + 1) {
                body.add("    // ...");
            }
            String code;
            if (s instanceof IfStmt || s instanceof WhileStmt || s instanceof ForStmt
                    || s instanceof ForEachStmt || s instanceof TryStmt) {
                code = entry.rawLines.get(s.getBegin().get().line - 1);
                if (!code.trim().endsWith("{")) code = code + " {";
            } else {
                code = String.join("\n", entry.rawLines.subList(
                        s.getBegin().get().line - 1, s.getEnd().get().line));
            }
            body.add(code);
            prev = s;
        }
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/AstSnippetExtractor.java \
        src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java
git commit -m "feat(slice): capture enclosing control-flow headers, render header-only line"
```

---

## Task 9: Wire CallSiteSlicer to AstSnippetExtractor

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/CallSiteSlicer.java`
- Modify: `src/test/java/com/graphtipper/slice/CallSiteSlicerTest.java`

- [ ] **Step 1: Read existing CallSiteSlicerTest**

Run: `cat /Users/sckwoky/Projects/Graph-Tipper/src/test/java/com/graphtipper/slice/CallSiteSlicerTest.java`

Note the existing setup (mock graph, etc.). The integration test will reuse those fixtures.

- [ ] **Step 2: Write a failing test**

Append to `CallSiteSlicerTest.java`:

```java
    @Test
    void enrichDelegatesToAstExtractorForSnippet(@TempDir Path tmp) throws Exception {
        // Tiny one-file project; CallSiteSlicer should read the test's call site via AST.
        Path src = tmp.resolve("src/main/java/Caller.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                public class Caller {
                    public void runner() {
                        int n = 7;
                        target(n);
                    }
                    void target(int x) {}
                }
                """);
        var graph = new com.graphtipper.model.ProjectGraph();
        var caller = new com.graphtipper.model.Node.Method(
                "m:Caller.runner", "Caller.runner", "void()", java.util.List.of(),
                "void", "src/main/java/Caller.java", 2, 5, null, false, false, java.util.List.of("public"));
        var callee = new com.graphtipper.model.Node.Method(
                "m:Caller.target", "Caller.target", "void(int)", java.util.List.of("int"),
                "void", "src/main/java/Caller.java", 6, 6, null, false, false, java.util.List.of());
        graph.addNode(caller);
        graph.addNode(callee);
        var cs = new com.graphtipper.model.Node.CallSite(
                "cs:1", "m:Caller.runner", "Caller.target", 0, 4, 9, "target(n)");
        graph.addNode(cs);

        var reader = new com.graphtipper.util.SourceFragmentReader(tmp);
        var ast = new AstSnippetExtractor();
        var slicer = new CallSiteSlicer(reader, ast);
        var step = new CallStep("m:Caller.runner", "Caller.runner",
                "m:Caller.target", "Caller.target", false, null, java.util.List.of());

        CallStep enriched = slicer.enrich(graph, step);
        assertThat(enriched.snippet()).contains("int n = 7");
        assertThat(enriched.snippet()).contains("target(n)");
        assertThat(enriched.argOrigins()).hasSize(1);
        assertThat(enriched.argOrigins().get(0).kind())
                .isEqualTo(ArgOrigin.Kind.LOCAL_VAR);
    }
```

Add at top of `CallSiteSlicerTest.java`:

```java
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
```

- [ ] **Step 3: Run test to verify failure**

Run: `./gradlew test --tests "com.graphtipper.slice.CallSiteSlicerTest.enrichDelegatesToAstExtractorForSnippet"`
Expected: COMPILATION FAILURE (no `CallSiteSlicer(reader, ast)` constructor).

- [ ] **Step 4: Update CallSiteSlicer**

Replace the body of `CallSiteSlicer.java` with:

```java
package com.graphtipper.slice;

import com.graphtipper.model.*;
import com.graphtipper.util.SourceFragmentReader;

import java.nio.file.Path;
import java.util.*;

public final class CallSiteSlicer {
    private static final int MAX_SLICE_STMTS = 12;

    private final SourceFragmentReader reader;
    private final AstSnippetExtractor ast;

    /** Legacy constructor used by tests that don't exercise AST slicing. */
    public CallSiteSlicer(SourceFragmentReader reader) { this(reader, new AstSnippetExtractor()); }

    public CallSiteSlicer(SourceFragmentReader reader, AstSnippetExtractor ast) {
        this.reader = reader;
        this.ast = ast;
    }

    public CallStep enrich(ProjectGraph g, CallStep step) {
        Node.CallSite cs = findCallSite(g, step);
        if (cs == null) return step.withEnrichment("(call site not located)", List.of());
        if (!(g.byId(step.callerMethodId()) instanceof Node.Method caller)) {
            return step.withEnrichment("(caller not found)", List.of());
        }

        Path file = reader.resolveProject(caller.file());
        String calleeSimple = simpleName(step.calleeFqn());
        AstSnippetExtractor.SnippetAt snip = ast.sliceAt(file, cs.line(), cs.column(),
                calleeSimple, MAX_SLICE_STMTS);

        String rendered = String.join("\n", snip.renderedBody());
        return step.withEnrichment(rendered, snip.argOrigins());
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

    private static String simpleName(String fqn) {
        int colon = fqn.indexOf(':');
        String base = colon < 0 ? fqn : fqn.substring(0, colon);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? base : base.substring(dot + 1);
    }
}
```

- [ ] **Step 5: Add resolveProject(...) to SourceFragmentReader**

Open `src/main/java/com/graphtipper/util/SourceFragmentReader.java`. Add a public helper:

```java
    public Path resolveProject(String relPath) {
        return projectRoot.resolve(relPath);
    }
```

- [ ] **Step 6: Run tests**

Run: `./gradlew test --tests com.graphtipper.slice.CallSiteSlicerTest`
Expected: all tests PASS, including the new one.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphtipper/slice/CallSiteSlicer.java \
        src/main/java/com/graphtipper/util/SourceFragmentReader.java \
        src/test/java/com/graphtipper/slice/CallSiteSlicerTest.java
git commit -m "feat(slice): CallSiteSlicer delegates snippet + arg-origin to AstSnippetExtractor"
```

---

## Task 10: Always-unbounded chain extraction

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/ReverseCallChainExtractor.java`
- Modify: `src/test/java/com/graphtipper/slice/ReverseCallChainExtractorTest.java`

- [ ] **Step 1: Read existing extractor test**

Run: `cat /Users/sckwoky/Projects/Graph-Tipper/src/test/java/com/graphtipper/slice/ReverseCallChainExtractorTest.java`

Note what behaviors are asserted; some may need updating.

- [ ] **Step 2: Write a failing test asserting unbounded extraction**

Add to `ReverseCallChainExtractorTest.java`:

```java
    @Test
    void extractorReturnsAllChainsEvenWhenManyAreReachable() {
        var g = makeGraphWithTwentyChainsTo("target");  // helper below
        var target = (com.graphtipper.model.Node.Method) g.byId("m:target");
        var result = new ReverseCallChainExtractor(0 /* deprecated arg, ignored */)
                .extract(g, target);
        assertThat(result.chains()).hasSize(20);
    }

    private com.graphtipper.model.ProjectGraph makeGraphWithTwentyChainsTo(String targetId) {
        var g = new com.graphtipper.model.ProjectGraph();
        var target = new com.graphtipper.model.Node.Method("m:" + targetId, targetId, "void()",
                java.util.List.of(), "void", "T.java", 1, 1, null, false, false, java.util.List.of());
        g.addNode(target);
        for (int i = 0; i < 20; i++) {
            var test = new com.graphtipper.model.Node.Method("m:test" + i, "test" + i, "void()",
                    java.util.List.of(), "void", "T.java", i + 2, i + 2, null,
                    true, false, java.util.List.of());
            g.addNode(test);
            g.addEdge(new com.graphtipper.model.Edge.Calls("m:test" + i, "m:" + targetId, false));
        }
        return g;
    }
```

- [ ] **Step 3: Run test to verify failure**

Run: `./gradlew test --tests "com.graphtipper.slice.ReverseCallChainExtractorTest.extractorReturnsAllChainsEvenWhenManyAreReachable"`
Expected: FAIL (extractor caps at `maxChains`, default 16 in current code path).

- [ ] **Step 4: Implement unbounded extraction**

Edit `src/main/java/com/graphtipper/slice/ReverseCallChainExtractor.java`. The constructor still accepts the legacy `maxChains` for binary compat, but it is now ignored. Replace the relevant section:

```java
public final class ReverseCallChainExtractor {
    @SuppressWarnings("unused")  // legacy: caller can pass a value; we always extract everything.
    private final int legacyMaxChainsIgnored;

    public ReverseCallChainExtractor(int legacyMaxChains) {
        this.legacyMaxChainsIgnored = legacyMaxChains;
    }
    public ReverseCallChainExtractor() { this(Integer.MAX_VALUE); }

    public ChainResult extract(ProjectGraph g, Node.Method target) {
        record Path(String methodId, List<CallStep> stepsTowardTarget) {}

        List<Chain> chains = new ArrayList<>();
        Deque<Path> frontier = new ArrayDeque<>();
        Set<String> visitedEdges = new HashSet<>();
        frontier.add(new Path(target.id(), List.of()));
        int frontierGuard = 100_000;
        boolean truncated = false;

        while (!frontier.isEmpty()) {
            Path p = frontier.poll();
            if (!(g.byId(p.methodId()) instanceof Node.Method current)) continue;
            if (current.isTest() && !p.stepsTowardTarget().isEmpty()) {
                var reversed = new ArrayList<>(p.stepsTowardTarget());
                java.util.Collections.reverse(reversed);
                int v = (int) reversed.stream().filter(CallStep::viaVirtual).count();
                chains.add(new Chain(current, reversed, v));
                continue;
            }
            if (frontier.size() > frontierGuard) { truncated = true; break; }
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
            for (Edge over : g.outgoing(p.methodId())) {
                if (!(over instanceof Edge.Overrides ov)) continue;
                String parentId = ov.toId();
                for (Edge.Calls in : g.incomingCalls(parentId)) {
                    String edgeKey = in.fromId() + "->virtual->" + p.methodId();
                    if (!visitedEdges.add(edgeKey)) continue;
                    if (!(g.byId(in.fromId()) instanceof Node.Method caller)) continue;
                    var step = new CallStep(caller.id(), caller.fqn(),
                            current.id(), current.fqn(),
                            true, null, List.of());
                    var nextSteps = new ArrayList<>(p.stepsTowardTarget());
                    nextSteps.add(step);
                    frontier.add(new Path(caller.id(), nextSteps));
                }
            }
        }
        return new ChainResult(rank(chains), truncated);
    }

    /** Sort chains by (depth ASC, virtualSteps ASC). No truncation; rendering layer applies caps. */
    private List<Chain> rank(List<Chain> chains) {
        var sorted = new ArrayList<>(chains);
        sorted.sort(Comparator.comparingInt(Chain::depth).thenComparingInt(Chain::virtualSteps));
        return sorted;
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests com.graphtipper.slice.ReverseCallChainExtractorTest`
Expected: all tests PASS, including the new one.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ReverseCallChainExtractor.java \
        src/test/java/com/graphtipper/slice/ReverseCallChainExtractorTest.java
git commit -m "feat(extractor): always-unbounded extraction; rank is pure post-sort"
```

---

## Task 11: Main orchestrates three renders

**Files:**
- Modify: `src/main/java/com/graphtipper/cli/Main.java`
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/cli/MainSmokeTest.java`

- [ ] **Step 1: Write failing smoke test for three files**

The existing `MainSmokeTest` only checks that `Main.main` exists. We add a new test that pre-populates the Joern cache so `JoernRunner.buildAndExport` short-circuits (it reads `<out>/.cache/<hash>/export/export.json` if present), letting us exercise the full render pipeline without spawning Joern.

Replace the body of `src/test/java/com/graphtipper/cli/MainSmokeTest.java` with:

```java
package com.graphtipper.cli;

import com.graphtipper.util.SourceHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class MainSmokeTest {

    @Test
    void cliClassHasMainMethod() throws Exception {
        var method = Main.class.getDeclaredMethod("main", String[].class);
        assertThat(method).isNotNull();
    }

    @Test
    void mainEmitsBudgetFullAndGraphJsonWhenCacheIsPrepopulated(@TempDir Path tmp) throws Exception {
        // 1. Tiny project: one source file with target method on line 1.
        Path project = tmp.resolve("project");
        Files.createDirectories(project.resolve("src/main/java/p"));
        Files.writeString(project.resolve("src/main/java/p/C.java"),
                "package p; public class C { public void target(int x) {} }");

        // 2. Pre-populate cache so JoernRunner returns immediately without Joern.
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        String hash = SourceHash.ofJavaSources(project);
        Path exportDir = out.resolve(".cache").resolve(hash).resolve("export");
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("export.json"),
                "{\"vertices\":["
                        + "{\"id\":\"1\",\"label\":\"METHOD\",\"properties\":{"
                        + "\"FULL_NAME\":\"p.C.target:void(int)\",\"SIGNATURE\":\"void(int)\","
                        + "\"FILENAME\":\"src/main/java/p/C.java\",\"LINE_NUMBER\":1,\"LINE_NUMBER_END\":1,"
                        + "\"IS_TEST\":false}}"
                        + "],\"edges\":[]}");

        // 3. Invoke Main.
        int code = new CommandLine(new Main()).execute(
                "--project", project.toString(),
                "--target", "src/main/java/p/C.java#C.target(int)",
                "--out", out.toString());
        assertThat(code).isZero();

        // 4. Three artifact files plus the legacy artifact JSON.
        try (Stream<Path> names = Files.list(out)) {
            var fileNames = names.map(p -> p.getFileName().toString()).toList();
            assertThat(fileNames).anyMatch(n -> n.endsWith(".budget.md"));
            assertThat(fileNames).anyMatch(n -> n.endsWith(".full.md"));
            assertThat(fileNames).anyMatch(n -> n.endsWith(".graph.json"));
            assertThat(fileNames).anyMatch(n -> n.endsWith(".json") && !n.endsWith(".graph.json"));
        }
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "com.graphtipper.cli.MainSmokeTest.mainEmitsBudgetFullAndGraphJson"`
Expected: FAIL (no `.budget.md`, no `.full.md`, no `.graph.json` are produced yet).

- [ ] **Step 3: Update Main to orchestrate three renders**

In `src/main/java/com/graphtipper/cli/Main.java`, replace the rendering+writing section (the block beginning `var artifact = new Artifact(...)`) with:

```java
            var fullArtifact = new Artifact(targetMethod, currentBody, enriched, chainResult.truncated(), lc);

            // budget.md: top-N chains, planned for token budget.
            var topChains = enriched.subList(0, Math.min(maxChains, enriched.size()));
            var budgetArtifact = new Artifact(targetMethod, currentBody, topChains,
                    chainResult.truncated(), lc);
            var budget = new TokenBudget(budgetTokens);
            try {
                budgetArtifact = new BudgetPlanner(budget).plan(budgetArtifact);
            } catch (BudgetPlanner.BudgetExceededException e) {
                System.err.println("budget exceeded on minimum: " + e.getMessage());
                return 3;
            }

            // full.md: no truncation, no eviction. Budget meter is still charged for the header.
            var unlimitedBudget = new TokenBudget(Integer.MAX_VALUE);
            new BudgetPlanner(unlimitedBudget).planNoEvict(fullArtifact);

            String hash = digest(target + "@" + projectSrcHash);
            String projectName = project.getFileName().toString();

            String budgetMd = new MarkdownRenderer().render(budgetArtifact, budget, projectSrcHash, projectName);
            String fullMd = new MarkdownRenderer().render(fullArtifact, unlimitedBudget, projectSrcHash, projectName);
            String graphJson = new com.graphtipper.render.GraphJsonRenderer().render(
                    fullArtifact, projectSrcHash, projectName);
            String legacyJson = new JsonRenderer().render(budgetArtifact, budget);

            writeAtomic(out.resolve(hash + ".budget.md"), budgetMd);
            writeAtomic(out.resolve(hash + ".full.md"), fullMd);
            writeAtomic(out.resolve(hash + ".graph.json"), graphJson);
            writeAtomic(out.resolve(hash + ".json"), legacyJson);
            System.out.println(out.resolve(hash + ".budget.md"));
            return 0;
```

Also remove the now-unused `--no-budget` branch logic that wrapped the previous single-render path, and keep `noBudget` as a deprecation-only flag — add right after the option declaration block, inside `call()`, the warning:

```java
            if (noBudget) {
                System.err.println("[graph-tipper] --no-budget is deprecated and ignored; "
                        + "<hash>.full.md is always emitted.");
            }
```

`Main` will not compile until `GraphJsonRenderer` exists (Task 12). For this task, create a temporary stub renderer to keep Main compiling.

- [ ] **Step 4: Stub GraphJsonRenderer**

Create `src/main/java/com/graphtipper/render/GraphJsonRenderer.java` (real implementation comes in Tasks 12–15):

```java
package com.graphtipper.render;

public final class GraphJsonRenderer {
    public String render(Artifact a, String projectKey, String projectName) {
        return "{\"schema_version\":\"1\",\"_stub\":true}\n";
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests "com.graphtipper.cli.MainSmokeTest.mainEmitsBudgetFullAndGraphJson"`
Expected: PASS.

Also run all tests to confirm nothing else broke:

Run: `./gradlew test`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/cli/Main.java \
        src/main/java/com/graphtipper/render/GraphJsonRenderer.java \
        src/test/java/com/graphtipper/cli/MainSmokeTest.java
git commit -m "feat(cli): emit budget.md, full.md, graph.json (stub); --no-budget is now no-op"
```

---

## Task 12: GraphJsonRenderer – target, stats, degradations

**Files:**
- Modify: `src/main/java/com/graphtipper/render/GraphJsonRenderer.java`
- Create: `src/test/java/com/graphtipper/render/GraphJsonRendererTest.java`

- [ ] **Step 1: Write failing test for skeleton structure**

Create `src/test/java/com/graphtipper/render/GraphJsonRendererTest.java`:

```java
package com.graphtipper.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphtipper.model.Node;
import com.graphtipper.slice.Chain;
import com.graphtipper.slice.LocalContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GraphJsonRendererTest {

    @Test
    void emitsSchemaVersionTargetAndStats() throws Exception {
        var target = new Node.Method("m:p.C.target", "p.C.target", "void(int)",
                List.of("int"), "void", "src/main/java/p/C.java", 5, 7,
                null, false, false, List.of("public"));
        var artifact = new Artifact(target, "public void target(int x) { }",
                List.<Chain>of(), false, new LocalContext(List.of(), List.of(), List.of()));
        String out = new GraphJsonRenderer().render(artifact, "proj-key", "p");

        JsonNode root = new ObjectMapper().readTree(out);
        assertThat(root.get("schema_version").asText()).isEqualTo("1");
        assertThat(root.get("target").get("fqn").asText()).isEqualTo("p.C.target");
        assertThat(root.get("target").get("file").asText()).isEqualTo("src/main/java/p/C.java");
        assertThat(root.get("stats").get("total_chains").asInt()).isZero();
        assertThat(root.get("vertices").isArray()).isTrue();
        assertThat(root.get("edges").isArray()).isTrue();
        assertThat(root.get("chains").isArray()).isTrue();
        assertThat(root.get("degradations").isArray()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests com.graphtipper.render.GraphJsonRendererTest`
Expected: FAIL (stub returns `{"schema_version":"1","_stub":true}`, missing `target`, etc.).

- [ ] **Step 3: Implement skeleton**

Replace `GraphJsonRenderer.java`:

```java
package com.graphtipper.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphtipper.model.Node;
import com.graphtipper.slice.*;

import java.time.Instant;
import java.util.*;

public final class GraphJsonRenderer {

    private static final String SCHEMA_VERSION = "1";
    private final ObjectMapper M = new ObjectMapper();

    public String render(Artifact a, String projectKey, String projectName) {
        ObjectNode root = M.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);

        ObjectNode meta = root.putObject("generated_for");
        meta.put("project", projectName);
        meta.put("commit_hash_proxy", projectKey);
        meta.put("timestamp", Instant.now().toString());

        ObjectNode target = root.putObject("target");
        target.put("id", "target");
        target.put("fqn", a.target().fqn());
        target.put("signature", a.target().signature());
        target.put("file", a.target().file());
        target.put("line_start", a.target().lineStart());
        target.put("line_end", a.target().lineEnd());
        target.put("current_body", a.currentBody());

        root.putArray("vertices");
        root.putArray("edges");
        root.putArray("chains");

        ObjectNode stats = root.putObject("stats");
        stats.put("total_chains", a.chains().size());
        stats.put("distinct_tests", (int) a.chains().stream().map(Chain::test).distinct().count());
        stats.put("vertices", 0);
        stats.put("edges", 0);
        stats.put("truncated", a.truncated());

        root.putArray("degradations");

        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew test --tests com.graphtipper.render.GraphJsonRendererTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/GraphJsonRenderer.java \
        src/test/java/com/graphtipper/render/GraphJsonRendererTest.java
git commit -m "feat(render): GraphJsonRenderer emits target/stats skeleton"
```

---

## Task 13: GraphJsonRenderer – vertices with dedup

**Files:**
- Modify: `src/main/java/com/graphtipper/render/GraphJsonRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/GraphJsonRendererTest.java`

- [ ] **Step 1: Write failing test for vertex deduplication**

Append to `GraphJsonRendererTest.java`:

```java
    @Test
    void emitsTestAndIntermediateVerticesDeduped() throws Exception {
        var target = new Node.Method("m:p.C.target", "p.C.target", "void(int)",
                List.of("int"), "void", "src/main/java/p/C.java", 5, 7, null, false, false, List.of());
        var testM = new Node.Method("m:p.T.t1", "p.T.t1", "void()",
                List.of(), "void", "src/test/java/p/T.java", 10, 12, null, true, false, List.of());
        var mid = new Node.Method("m:p.M.helper", "p.M.helper", "void(int)",
                List.of("int"), "void", "src/main/java/p/M.java", 20, 25, null, false, false, List.of());
        var step1 = new com.graphtipper.slice.CallStep("m:p.T.t1", "p.T.t1",
                "m:p.M.helper", "p.M.helper", false, "snip1", List.of());
        var step2 = new com.graphtipper.slice.CallStep("m:p.M.helper", "p.M.helper",
                "m:p.C.target", "p.C.target", false, "snip2", List.of());
        var chainA = new Chain(testM, List.of(step1, step2), 0);
        // A second chain that re-uses the same intermediate must not duplicate the vertex.
        var testM2 = new Node.Method("m:p.T.t2", "p.T.t2", "void()",
                List.of(), "void", "src/test/java/p/T.java", 30, 32, null, true, false, List.of());
        var chainB = new Chain(testM2, List.of(
                new com.graphtipper.slice.CallStep("m:p.T.t2", "p.T.t2",
                        "m:p.M.helper", "p.M.helper", false, "snip3", List.of()),
                step2), 0);
        var artifact = new Artifact(target, "body", List.of(chainA, chainB), false,
                new LocalContext(List.of(), List.of(), List.of()));

        String out = new GraphJsonRenderer().render(artifact, "k", "p");
        JsonNode root = new ObjectMapper().readTree(out);
        var verts = root.get("vertices");
        // expected: t1, t2, helper — three vertices total
        assertThat(verts.size()).isEqualTo(3);
        long helperCount = 0;
        for (JsonNode v : verts) if ("v_method_p_M_helper".equals(v.get("id").asText())) helperCount++;
        assertThat(helperCount).isEqualTo(1L);
        assertThat(root.get("stats").get("vertices").asInt()).isEqualTo(3);
    }
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "com.graphtipper.render.GraphJsonRendererTest.emitsTestAndIntermediateVerticesDeduped"`
Expected: FAIL (`vertices` is empty).

- [ ] **Step 3: Implement vertex emission with dedup via a method registry**

A `CallStep` doesn't carry the full `Node.Method` for its endpoints — only the id and fqn. To render `file`/`line` correctly, build a method registry at the start of `render(...)`. Where the registry has no real `Node.Method` (an intermediate referenced only by id), synthesize a minimal stub.

At the **start** of `render(...)`, right after creating `root`, add:

```java
        Map<String, Node.Method> methodRegistry = new HashMap<>();
        methodRegistry.put(a.target().id(), a.target());
        for (Chain c : a.chains()) {
            methodRegistry.put(c.test().id(), c.test());
            for (CallStep s : c.steps()) {
                methodRegistry.computeIfAbsent(s.calleeMethodId(),
                        id -> synthesize(id, s.calleeFqn()));
                methodRegistry.computeIfAbsent(s.callerMethodId(),
                        id -> synthesize(id, s.callerFqn()));
            }
        }
```

**Replace** the existing `root.putArray("vertices")` (from the Task 12 skeleton) with the dedup emission, **and** update the stats count. Insert this block before the existing `root.putArray("edges")`:

```java
        Map<String, ObjectNode> vertices = new LinkedHashMap<>();
        for (Chain c : a.chains()) {
            String tid = idOf(c.test(), true);
            vertices.computeIfAbsent(tid, k -> vertex(k, "test_method", c.test(), ""));
            for (CallStep s : c.steps()) {
                if (s.calleeMethodId().equals(a.target().id())) continue;
                Node.Method callee = methodRegistry.get(s.calleeMethodId());
                if (callee == null) continue;
                String vid = idOf(callee, false);
                vertices.computeIfAbsent(vid, k -> vertex(k, "intermediate_method", callee, s.snippet()));
            }
        }
        ArrayNode vertsArr = root.putArray("vertices");
        for (ObjectNode v : vertices.values()) vertsArr.add(v);
```

And in the existing `stats.put("vertices", 0)` line, change `0` to `vertices.size()`.

Add helpers at the bottom of the class:

```java
    private static String idOf(Node.Method m, boolean isTest) {
        String prefix = isTest ? "v_test_" : "v_method_";
        return prefix + m.fqn().replace('.', '_').replace('$', '_');
    }

    private ObjectNode vertex(String id, String kind, Node.Method m, String snippet) {
        ObjectNode v = M.createObjectNode();
        v.put("id", id);
        v.put("kind", kind);
        v.put("fqn", m.fqn());
        v.put("file", m.file());
        v.put("line", m.lineStart());
        v.put("snippet", snippet);
        v.put("snippet_truncated", false);
        v.putArray("warnings");
        return v;
    }

    private static Node.Method synthesize(String id, String fqn) {
        return new Node.Method(id, fqn, "()", List.of(), "void",
                "(unknown)", -1, -1, null, false, false, List.of());
    }
```

(Task 14 will use the `methodRegistry` local; we'll change it from a local to a parameter passed to edge/chain helpers, or keep edge/chain emission inside `render(...)` so the local stays in scope. Easiest is to keep all emission inline within `render(...)` and pass `methodRegistry` directly to any helpers introduced later.)

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew test --tests com.graphtipper.render.GraphJsonRendererTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/GraphJsonRenderer.java \
        src/test/java/com/graphtipper/render/GraphJsonRendererTest.java
git commit -m "feat(render): emit deduped test_method/intermediate_method vertices"
```

---

## Task 14: GraphJsonRenderer – edges with dedup tuple

**Files:**
- Modify: `src/main/java/com/graphtipper/render/GraphJsonRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/GraphJsonRendererTest.java`

- [ ] **Step 1: Write failing test for edge dedup**

Append to `GraphJsonRendererTest.java`:

```java
    @Test
    void emitsEdgesPerCallSiteAndDedupsByTuple() throws Exception {
        var target = new Node.Method("m:p.C.target", "p.C.target", "void(int)",
                List.of("int"), "void", "src/main/java/p/C.java", 5, 7, null, false, false, List.of());
        var t1 = new Node.Method("m:p.T.t1", "p.T.t1", "void()",
                List.of(), "void", "src/test/java/p/T.java", 10, 12, null, true, false, List.of());
        var t2 = new Node.Method("m:p.T.t2", "p.T.t2", "void()",
                List.of(), "void", "src/test/java/p/T.java", 30, 32, null, true, false, List.of());
        // Both tests call target directly (depth=1). Same to-vertex, different from-vertex
        // and call site → must produce two edges.
        var stepA = new com.graphtipper.slice.CallStep("m:p.T.t1", "p.T.t1",
                "m:p.C.target", "p.C.target", false, "t1 snippet", List.of());
        var stepB = new com.graphtipper.slice.CallStep("m:p.T.t2", "p.T.t2",
                "m:p.C.target", "p.C.target", false, "t2 snippet", List.of());
        var artifact = new Artifact(target, "body",
                List.of(new Chain(t1, List.of(stepA), 0),
                        new Chain(t2, List.of(stepB), 0)),
                false, new LocalContext(List.of(), List.of(), List.of()));

        var root = new ObjectMapper().readTree(new GraphJsonRenderer().render(artifact, "k", "p"));
        assertThat(root.get("edges").size()).isEqualTo(2);
        var ids = new java.util.HashSet<String>();
        root.get("edges").forEach(e -> ids.add(e.get("id").asText()));
        assertThat(ids).hasSize(2);
        assertThat(root.get("edges").get(0).get("to").asText()).isEqualTo("target");
    }
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "com.graphtipper.render.GraphJsonRendererTest.emitsEdgesPerCallSiteAndDedupsByTuple"`
Expected: FAIL.

- [ ] **Step 3: Implement edge emission**

In `GraphJsonRenderer.render`, after emitting vertices and before emitting `chains`, add:

```java
        // CallStep lacks call-site coordinates in V1; in V2 the upstream pipeline populates
        // them via AstSnippetExtractor (we have callLine/callColumn on SnippetAt). For now,
        // dedup by (from, to, snippetHash) as a stable proxy.
        Map<String, ObjectNode> edges = new LinkedHashMap<>();
        for (Chain c : a.chains()) {
            for (CallStep s : c.steps()) {
                String from = idForCallStepEndpoint(s.callerMethodId(), s.callerFqn(),
                        c.test().id(), methodRegistry);
                String to = s.calleeMethodId().equals(a.target().id()) ? "target"
                        : "v_method_" + s.calleeFqn().replace('.', '_').replace('$', '_');
                String tupleKey = from + "->" + to + "#" + Integer.toHexString(
                        java.util.Objects.hashCode(s.snippet()));
                edges.computeIfAbsent(tupleKey, k -> {
                    ObjectNode e = M.createObjectNode();
                    e.put("id", "e_" + edges.size());
                    e.put("from", from);
                    e.put("to", to);
                    e.put("kind", "calls");
                    ObjectNode cs = e.putObject("call_site");
                    cs.put("file", methodRegistry.get(s.callerMethodId()) != null
                            ? methodRegistry.get(s.callerMethodId()).file() : null);
                    cs.put("line", -1);
                    cs.put("code", firstLineOf(s.snippet()));
                    ArrayNode args = e.putArray("args");
                    for (var origin : s.argOrigins()) args.add(originJson(origin));
                    e.put("virtual", s.viaVirtual());
                    return e;
                });
            }
        }
        ArrayNode edgesArr = root.putArray("edges");
        for (ObjectNode e : edges.values()) edgesArr.add(e);
        stats.put("edges", edges.size());
```

Add helpers:

```java
    private static String idForCallStepEndpoint(String methodId, String fqn,
            String testId, Map<String, Node.Method> registry) {
        Node.Method m = registry.get(methodId);
        boolean isTest = m != null && m.isTest();
        String prefix = isTest ? "v_test_" : "v_method_";
        return prefix + (m != null ? m.fqn() : fqn).replace('.', '_').replace('$', '_');
    }

    private static String firstLineOf(String snippet) {
        if (snippet == null) return "";
        int nl = snippet.indexOf('\n');
        return nl < 0 ? snippet : snippet.substring(0, nl);
    }

    private ObjectNode originJson(com.graphtipper.slice.ArgOrigin o) {
        ObjectNode n = M.createObjectNode();
        n.put("index", o.argIndex());
        n.put("origin", o.kind().name().toLowerCase());
        if (o.value() != null) n.put("value", o.value());
        if (o.paramName() != null) n.put("name", o.paramName());
        if (o.exprText() != null) n.put("expr", o.exprText());
        if (o.definedAtLine() > 0) {
            ObjectNode def = n.putObject("defined_at");
            def.put("line", o.definedAtLine());
            if (o.definedAtSnippet() != null) def.put("snippet", o.definedAtSnippet());
        }
        if (o.factoryFqn() != null) n.put("factory_fqn", o.factoryFqn());
        if (o.fieldFqn() != null) n.put("field_fqn", o.fieldFqn());
        return n;
    }
```

Also: replace the existing `root.putArray("edges")` (which clobbers our work) with `// edges array filled above`.

- [ ] **Step 4: Run test**

Run: `./gradlew test --tests com.graphtipper.render.GraphJsonRendererTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/GraphJsonRenderer.java \
        src/test/java/com/graphtipper/render/GraphJsonRendererTest.java
git commit -m "feat(render): emit edges with (from,to,snippet) dedup, args inline"
```

---

## Task 15: GraphJsonRenderer – chains array

**Files:**
- Modify: `src/main/java/com/graphtipper/render/GraphJsonRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/GraphJsonRendererTest.java`

- [ ] **Step 1: Write failing test**

Append to `GraphJsonRendererTest.java`:

```java
    @Test
    void emitsChainsWithPathAndEdges() throws Exception {
        // Reuse the dedup test's graph shape.
        var target = new Node.Method("m:p.C.target", "p.C.target", "void(int)",
                List.of("int"), "void", "src/main/java/p/C.java", 5, 7, null, false, false, List.of());
        var t1 = new Node.Method("m:p.T.t1", "p.T.t1", "void()",
                List.of(), "void", "src/test/java/p/T.java", 10, 12, null, true, false, List.of());
        var stepA = new com.graphtipper.slice.CallStep("m:p.T.t1", "p.T.t1",
                "m:p.C.target", "p.C.target", false, "snippet", List.of());
        var artifact = new Artifact(target, "body",
                List.of(new Chain(t1, List.of(stepA), 0)),
                false, new LocalContext(List.of(), List.of(), List.of()));

        var root = new ObjectMapper().readTree(new GraphJsonRenderer().render(artifact, "k", "p"));
        var chains = root.get("chains");
        assertThat(chains.size()).isEqualTo(1);
        var c = chains.get(0);
        assertThat(c.get("depth").asInt()).isEqualTo(1);
        assertThat(c.get("path").get(0).asText()).isEqualTo("v_test_p_T_t1");
        assertThat(c.get("path").get(1).asText()).isEqualTo("target");
        assertThat(c.get("edges").size()).isEqualTo(1);
    }
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "com.graphtipper.render.GraphJsonRendererTest.emitsChainsWithPathAndEdges"`
Expected: FAIL (chains array is still empty from Task 12 skeleton).

- [ ] **Step 3: Implement chain emission**

In `GraphJsonRenderer.render`, replace `root.putArray("chains")` with:

```java
        ArrayNode chainsArr = root.putArray("chains");
        int idx = 0;
        for (Chain c : a.chains()) {
            ObjectNode cn = chainsArr.addObject();
            cn.put("id", "chain_" + (idx++));
            cn.put("depth", c.depth());
            cn.put("virtual_steps", c.virtualSteps());

            ArrayNode path = cn.putArray("path");
            path.add(idForCallStepEndpoint(c.test().id(), c.test().fqn(), c.test().id(), methodRegistry));
            for (CallStep s : c.steps()) {
                if (s.calleeMethodId().equals(a.target().id())) {
                    path.add("target");
                } else {
                    Node.Method callee = methodRegistry.get(s.calleeMethodId());
                    String fqn = callee != null ? callee.fqn() : s.calleeFqn();
                    path.add("v_method_" + fqn.replace('.', '_').replace('$', '_'));
                }
            }

            ArrayNode edgeIds = cn.putArray("edges");
            for (CallStep s : c.steps()) {
                String from = idForCallStepEndpoint(s.callerMethodId(), s.callerFqn(),
                        c.test().id(), methodRegistry);
                String to = s.calleeMethodId().equals(a.target().id()) ? "target"
                        : "v_method_" + s.calleeFqn().replace('.', '_').replace('$', '_');
                String tupleKey = from + "->" + to + "#" + Integer.toHexString(
                        java.util.Objects.hashCode(s.snippet()));
                ObjectNode edgeNode = edges.get(tupleKey);
                if (edgeNode != null) edgeIds.add(edgeNode.get("id").asText());
            }
        }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.graphtipper.render.GraphJsonRendererTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/GraphJsonRenderer.java \
        src/test/java/com/graphtipper/render/GraphJsonRendererTest.java
git commit -m "feat(render): emit chains[] with path and edge references"
```

---

## Task 16: graph-schema.json + schema validation in tests

**Files:**
- Create: `src/test/resources/graph-schema.json`
- Modify: `src/test/java/com/graphtipper/render/GraphJsonRendererTest.java`

- [ ] **Step 1: Write the JSON schema**

Create `src/test/resources/graph-schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["schema_version", "generated_for", "target", "vertices", "edges", "chains", "stats", "degradations"],
  "properties": {
    "schema_version": { "type": "string", "const": "1" },
    "generated_for": {
      "type": "object",
      "required": ["project", "commit_hash_proxy", "timestamp"],
      "properties": {
        "project": { "type": "string" },
        "commit_hash_proxy": { "type": "string" },
        "timestamp": { "type": "string" }
      }
    },
    "target": {
      "type": "object",
      "required": ["id", "fqn", "file", "line_start", "line_end", "current_body"],
      "properties": {
        "id": { "type": "string", "const": "target" },
        "fqn": { "type": "string" },
        "signature": { "type": "string" },
        "file": { "type": "string" },
        "line_start": { "type": "integer" },
        "line_end": { "type": "integer" },
        "current_body": { "type": "string" }
      }
    },
    "vertices": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["id", "kind", "fqn", "file", "line", "snippet"],
        "properties": {
          "id": { "type": "string", "pattern": "^v_(test|method)_" },
          "kind": { "enum": ["test_method", "intermediate_method"] },
          "fqn": { "type": "string" },
          "file": { "type": "string" },
          "line": { "type": "integer" },
          "snippet": { "type": "string" },
          "snippet_truncated": { "type": "boolean" },
          "warnings": { "type": "array", "items": { "type": "string" } }
        }
      }
    },
    "edges": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["id", "from", "to", "kind", "call_site", "args", "virtual"],
        "properties": {
          "id": { "type": "string", "pattern": "^e_\\d+$" },
          "from": { "type": "string" },
          "to": { "type": "string" },
          "kind": { "type": "string", "const": "calls" },
          "call_site": {
            "type": "object",
            "required": ["file", "line", "code"],
            "properties": {
              "file": { "type": ["string", "null"] },
              "line": { "type": "integer" },
              "code": { "type": "string" }
            }
          },
          "args": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["index", "origin"],
              "properties": {
                "index": { "type": "integer" },
                "origin": { "type": "string" }
              }
            }
          },
          "virtual": { "type": "boolean" }
        }
      }
    },
    "chains": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["id", "depth", "virtual_steps", "path", "edges"],
        "properties": {
          "id": { "type": "string", "pattern": "^chain_\\d+$" },
          "depth": { "type": "integer" },
          "virtual_steps": { "type": "integer" },
          "path": { "type": "array", "items": { "type": "string" } },
          "edges": { "type": "array", "items": { "type": "string" } }
        }
      }
    },
    "stats": {
      "type": "object",
      "required": ["total_chains", "distinct_tests", "vertices", "edges", "truncated"],
      "properties": {
        "total_chains": { "type": "integer" },
        "distinct_tests": { "type": "integer" },
        "vertices": { "type": "integer" },
        "edges": { "type": "integer" },
        "truncated": { "type": "boolean" }
      }
    },
    "degradations": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["kind"],
        "properties": {
          "kind": { "type": "string" },
          "details": { "type": "string" },
          "file": { "type": "string" },
          "line": { "type": "integer" },
          "effect": { "type": "string" }
        }
      }
    }
  }
}
```

- [ ] **Step 2: Write failing test that validates schema**

Append to `GraphJsonRendererTest.java`:

```java
    @Test
    void renderedDocumentValidatesAgainstSchema() throws Exception {
        var target = new Node.Method("m:p.C.target", "p.C.target", "void(int)",
                List.of("int"), "void", "src/main/java/p/C.java", 5, 7, null, false, false, List.of());
        var t1 = new Node.Method("m:p.T.t1", "p.T.t1", "void()",
                List.of(), "void", "src/test/java/p/T.java", 10, 12, null, true, false, List.of());
        var artifact = new Artifact(target, "body",
                List.of(new Chain(t1, List.of(new com.graphtipper.slice.CallStep(
                        "m:p.T.t1", "p.T.t1", "m:p.C.target", "p.C.target", false, "snippet", List.of())), 0)),
                false, new LocalContext(List.of(), List.of(), List.of()));
        String doc = new GraphJsonRenderer().render(artifact, "k", "p");

        var factory = com.networknt.schema.JsonSchemaFactory.getInstance(
                com.networknt.schema.SpecVersion.VersionFlag.V202012);
        var schema = factory.getSchema(java.nio.file.Files.newInputStream(
                java.nio.file.Path.of("src/test/resources/graph-schema.json")));
        var errors = schema.validate(new ObjectMapper().readTree(doc));
        assertThat(errors).isEmpty();
    }
```

- [ ] **Step 3: Run test**

Run: `./gradlew test --tests "com.graphtipper.render.GraphJsonRendererTest.renderedDocumentValidatesAgainstSchema"`
Expected: PASS (the renderer already emits the right shape from earlier tasks; if there are schema mismatches, fix the renderer minimally to match — never modify the schema to match a bug).

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/graph-schema.json \
        src/test/java/com/graphtipper/render/GraphJsonRendererTest.java
git commit -m "test(render): JSON schema for graph.json + validation in unit test"
```

---

## Task 17: Picocli regression e2e (opt-in)

**Files:**
- Modify: `src/test/java/com/graphtipper/PicocliSmokeTest.java` (existing opt-in test)

- [ ] **Step 1: Extend the test with chain-count, schema, and snippet-quality assertions**

The existing test is gated by env var `GRAPHTIPPER_PICOCLI_HOME` and uses picocli's CLI runner. Append a new `@Test` method to `src/test/java/com/graphtipper/PicocliSmokeTest.java`:

```java
    @Test
    void v2RegressionTextTablePutValue(@TempDir Path out) throws Exception {
        Path picocli = Path.of(System.getenv("GRAPHTIPPER_PICOCLI_HOME"));
        int code = new picocli.CommandLine(new com.graphtipper.cli.Main()).execute(
                "--project", picocli.toString(),
                "--target", "src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)",
                "--out", out.toString());
        assertThat(code).isZero();

        Path graphJson;
        try (var stream = Files.list(out)) {
            graphJson = stream.filter(p -> p.toString().endsWith(".graph.json"))
                    .findFirst().orElseThrow();
        }
        var root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.newInputStream(graphJson));
        assertThat(root.get("stats").get("distinct_tests").asInt())
                .isGreaterThanOrEqualTo(1000);

        var factory = com.networknt.schema.JsonSchemaFactory.getInstance(
                com.networknt.schema.SpecVersion.VersionFlag.V202012);
        var schema = factory.getSchema(Files.newInputStream(
                Path.of("src/test/resources/graph-schema.json")));
        assertThat(schema.validate(root)).isEmpty();

        Path fullMd;
        try (var stream = Files.list(out)) {
            fullMd = stream.filter(p -> p.toString().endsWith(".full.md"))
                    .findFirst().orElseThrow();
        }
        String md = Files.readString(fullMd);
        // Find the snippet block for the named test and assert it shows real dataflow context.
        int testIdx = md.indexOf("picocli.HelpTest.testDefaultLayout_addsEachRowToTable");
        assertThat(testIdx).isPositive();
        int codeStart = md.indexOf("```java", testIdx);
        int codeEnd = md.indexOf("```", codeStart + 7);
        String snippet = md.substring(codeStart, codeEnd);
        assertThat(snippet).contains("final Text[][] values");
        assertThat(snippet).contains("Help.Layout layout");
        // Should not start with a closing brace on its own line (the V1 bug we're fixing).
        assertThat(snippet.lines().limit(3))
                .noneMatch(line -> line.trim().equals("}") || line.trim().equals("};"));
    }
```

- [ ] **Step 2: Run only this test, gated**

Run: `GRAPHTIPPER_PICOCLI_HOME=/tmp/picocli ./gradlew test --tests "com.graphtipper.PicocliSmokeTest.v2RegressionTextTablePutValue"`
Expected: PASS (assumes Joern is on PATH and `/tmp/picocli` is the picocli checkout from the design discussion).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/graphtipper/PicocliSmokeTest.java
git commit -m "test: V2 regression — graph.json validates, slicing produces clean snippets"
```

---

## Self-review checklist (run before declaring the plan executed)

After all tasks pass, walk this list once:

- [ ] `./gradlew test` passes with no `GRAPHTIPPER_PICOCLI_HOME` env set (opt-in regression test stays disabled).
- [ ] `GRAPHTIPPER_PICOCLI_HOME=/tmp/picocli ./gradlew test` passes (assumes a picocli checkout there and Joern on PATH).
- [ ] `./gradlew installDist` succeeds; the built jar contains `joern-scripts/prepare-and-export.sc` and the new `GraphJsonRenderer` class.
- [ ] Running `graph-tipper --project /tmp/picocli --target 'src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)' --out /tmp/gt-out` produces three files: `<hash>.budget.md`, `<hash>.full.md`, `<hash>.graph.json`. The fourth, `<hash>.json`, is also produced.
- [ ] `.full.md`: 1000+ chains; no snippet starts with a closing brace on its own line.
- [ ] `.graph.json`: validates against `src/test/resources/graph-schema.json`.
- [ ] `--no-budget` flag still parses; emits a deprecation notice on stderr; outputs are identical with or without it.
- [ ] CHANGELOG entry mentions the `<hash>.md` → `<hash>.budget.md` rename.
