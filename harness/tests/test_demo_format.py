from harness.demo_format import strip_demo

SAMPLE = """\
# Graph-Tipper Augmentation

> Generated for: picocli @ deadbeef
> Target: foo.bar
> Budget: 10000 / 20000 tokens
> Consumers: 1 · Path clusters: 10
> Direct tests: 2 · Long-tail singletons: 76

## Target

**Signature:**
```java
void bar(int x)
```

## Direct tests

| Test | Args | Oracle |
|---|---|---|
| `foo.testBar` | (1) | returns 0 |

## Consumer contracts

### Consumer 1: foo.Caller
**Body slice:**
```java
void caller() { bar(1); }
```

#### 4.4.1.a Cluster: foo.parseArgs path (498 chains)

[hub: foo.parseArgs, foo.assertTrue]

**Entry-point:** `foo.parseArgs`
**Path:** parseArgs → bar
**Depth:** 12

**Static slice (Tier 2):**

arg0:
  ← <UNRESOLVED: METHOD_CALL>

**Primary representative:** `foo.FooTest.testThing` — `src/test/java/foo/FooTest.java:42`

**Differential matrix (5 representatives of 498):**

| Test | Sliced args | Oracle |
|---|---|---|
| t1 | (<UNRESOLVED>) | returns 0 |

**Behavior signals (from differential analysis):**
- arg0_invariant_in_cluster: shared

#### 4.4.1.b Cluster: foo.X path (302 chains)

**Entry-point:** `foo.X`
**Path:** X → bar
**Depth:** 8

**Static slice (Tier 2):**

arg0:
  ← <UNRESOLVED: BRANCH_EXPLOSION>

## Long tail
76 additional singletons.

## Local Context

### Sibling members used by target
```java
int(foo.Bar.X)
            public int helper(X x) {
                return 42;
            }
void(foo.Bar.Y)
            private void other() {}
```

## Negative Memory
_(reserved)_
"""


def test_strip_demo_drops_clusters_and_long_tail():
    out = strip_demo(SAMPLE)  # default keep_chains="none"
    assert "#### 4.4.1.a" not in out
    assert "#### 4.4.1.b" not in out
    assert "UNRESOLVED" not in out
    assert "Long tail" not in out
    assert "Negative Memory" not in out


def test_keep_chains_paths_keeps_headers_drops_noise():
    out = strip_demo(SAMPLE, keep_chains="paths")
    # cluster headers + paths kept
    assert "#### 4.4.1.a Cluster: foo.parseArgs path (498 chains)" in out
    assert "**Path:** parseArgs → bar" in out
    # but the UNRESOLVED noise dropped
    assert "UNRESOLVED" not in out


def test_keep_chains_paths_keeps_representative_test_pointer():
    # The "which test to grep" pointer must survive paths mode (dropping it loses signal).
    out = strip_demo(SAMPLE, keep_chains="paths")
    assert "**Primary representative:** `foo.FooTest.testThing`" in out
    assert "src/test/java/foo/FooTest.java:42" in out
    # but the differential matrix table itself is gone
    assert "Differential matrix" not in out
    assert "| t1 |" not in out


def test_keep_chains_paths_snippets_injects_callsite_fragments():
    chains = [
        {
            "test": {"fqn": "foo.FooTest.testThing"},
            "steps": [
                {"callerFqn": "foo.FooTest.testThing", "calleeFqn": "foo.parseArgs",
                 "snippet": "void testThing() { parseArgs(\"-a\"); }"},
                {"callerFqn": "foo.parseArgs", "calleeFqn": "foo.bar",
                 "snippet": "void parseArgs(String a) { bar(a); }"},
            ],
        },
    ]
    out = strip_demo(SAMPLE, keep_chains="paths+snippets", chains=chains, chain_hops=3)
    # call-site section injected under the matching cluster
    assert "Call-sites" in out
    assert "void parseArgs(String a) { bar(a); }" in out
    assert "parseArgs → bar" in out


def test_keep_chains_paths_snippets_dedups_shared_edges():
    # Two clusters whose representative chains share the same final edge -> snippet once,
    # second occurrence is a back-reference.
    sample = SAMPLE  # 4.4.1.a (FooTest.testThing) ; 4.4.1.b has no representative -> only a matches
    chains = [
        {"test": {"fqn": "foo.FooTest.testThing"},
         "steps": [
             {"callerFqn": "foo.X", "calleeFqn": "foo.bar", "snippet": "shared tail"},
         ]},
    ]
    out = strip_demo(sample, keep_chains="paths+snippets", chains=chains, chain_hops=1)
    # only one snippet body even though edge could recur
    assert out.count("shared tail") == 1


def test_keep_chains_full_keeps_everything():
    out = strip_demo(SAMPLE, keep_chains="full")
    assert "#### 4.4.1.a" in out
    assert "#### 4.4.1.b" in out
    assert "UNRESOLVED" in out  # full keeps the noise too


def test_keep_chains_rejects_bad_value():
    import pytest
    with pytest.raises(ValueError, match="keep_chains"):
        strip_demo(SAMPLE, keep_chains="bogus")


def test_strip_demo_keeps_consumer_header_and_body_slice():
    out = strip_demo(SAMPLE)
    assert "## Consumer contracts" in out
    assert "Consumer 1: foo.Caller" in out
    assert "void caller() { bar(1); }" in out


def test_strip_demo_keeps_target_and_direct_tests():
    out = strip_demo(SAMPLE)
    assert "## Target" in out
    assert "## Direct tests" in out
    assert "void bar(int x)" in out
    assert "returns 0" in out


def test_strip_demo_drops_joern_type_sigs_from_siblings():
    out = strip_demo(SAMPLE)
    assert "int(foo.Bar.X)" not in out
    assert "void(foo.Bar.Y)" not in out
    # but real Java declarations stay:
    assert "public int helper(X x)" in out
    assert "private void other()" in out


def test_strip_demo_drops_chatty_metadata():
    out = strip_demo(SAMPLE)
    assert "Budget:" not in out
    assert "Generated for:" not in out
    assert "Consumers:" not in out
    # but target and signature stay
    assert "Target:" in out or "## Target" in out


def test_strip_demo_is_substantially_smaller():
    out = strip_demo(SAMPLE)
    assert len(out) < len(SAMPLE) * 0.7
