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
        System.err.println("chop: pipeline not implemented yet");
        return 1;
    }
}
