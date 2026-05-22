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
        assertThat(cmd.pruneByCoverage.toString()).endsWith("exec.xml");
        assertThat(cmd.katzRank).isTrue();
        assertThat(cmd.bare).isTrue();
    }

    @Test void defaultsAreFalseAndNull() {
        SliceCommand cmd = new SliceCommand();
        new CommandLine(cmd).parseArgs(
                "--project", "/tmp/p",
                "--target", "Foo#bar",
                "--out", "/tmp/o");
        assertThat(cmd.pruneByCoverage).isNull();
        assertThat(cmd.katzRank).isFalse();
        assertThat(cmd.bare).isFalse();
    }
}
