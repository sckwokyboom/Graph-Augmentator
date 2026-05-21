package com.graphtipper.cli;

import com.graphtipper.chop.cli.ChopCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "graph-tipper",
    mixinStandardHelpOptions = true,
    versionProvider = Main.VersionProvider.class,
    description = "Graph-Tipper: CPG-based context augmentation for Java target methods.",
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
