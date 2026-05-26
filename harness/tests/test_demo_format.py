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
**Entry-point:** foo.parseArgs
**Path:** parseArgs → bar
[lots of UNRESOLVED noise]

#### 4.4.1.b Cluster: foo.X path (302 chains)
[more noise]

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
    out = strip_demo(SAMPLE)
    assert "#### 4.4.1.a" not in out
    assert "#### 4.4.1.b" not in out
    assert "UNRESOLVED" not in out
    assert "Long tail" not in out
    assert "Negative Memory" not in out


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
