from harness.kgpool.corridor import is_peripheral


def test_peripheral_examples_module():
    assert is_peripheral("picocli-examples/src/main/java/picocli/examples/leftalign/LeftAlignOptions.java", "x")
    assert is_peripheral("src/main/java/foo/examples/Demo.java", "foo.examples.Demo.run")


def test_peripheral_anonymous_class_fqn():
    # joern flattens anonymous/local classes into synthetic `$<digit>` chains
    assert is_peripheral("src/main/java/x/A.java", "x.A.m.IHelpFactory$0.create.Help$0.layout")


def test_not_peripheral_core_and_nested():
    assert not is_peripheral("src/main/java/picocli/CommandLine.java",
                             "picocli.CommandLine$Help$TextTable.putValue")
    assert not is_peripheral("src/main/java/picocli/CommandLine.java",
                             "picocli.CommandLine$Help.commandList")
