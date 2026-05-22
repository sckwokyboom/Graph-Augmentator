package com.graphtipper.chop.cli;

import com.graphtipper.cli.Main;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChopCommandSmokeTest {

    @Test
    void chopHelpListsRequiredOptions() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine cl = new CommandLine(new Main());
        cl.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        int code = cl.execute("chop", "--help");
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(code).isZero();
        assertThat(text).contains("--project").contains("--target").contains("--out");
    }
}
