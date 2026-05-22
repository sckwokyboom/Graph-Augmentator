package com.graphtipper.chop.cli;

import com.graphtipper.chop.annotate.ChopAnnotator;
import com.graphtipper.chop.compose.ChopComposer;
import com.graphtipper.chop.model.ChopGraph;
import com.graphtipper.chop.model.MethodNode;
import com.graphtipper.chop.model.MethodRef;
import com.graphtipper.chop.model.StatementId;
import com.graphtipper.chop.model.StatementNode;
import com.graphtipper.chop.pdg.JavaParserContext;
import com.graphtipper.chop.pdg.MethodPDG;
import com.graphtipper.chop.pdg.PdgBuilder;
import com.graphtipper.chop.reach.EntryPointFinder;
import com.graphtipper.chop.reach.MaxMethodsExceededException;
import com.graphtipper.chop.reach.ReachabilityScan;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Programmatic entry point to the inter-procedural chop graph builder. Mirrors the body of
 * {@link ChopCommand#call()} minus CLI / Joern / rendering concerns; consumers that already
 * own a {@link ProjectGraph} (notably {@code SliceCommand} when {@code --katz-rank} is set)
 * call {@link #build(ProjectGraph, Node.Method)} directly.
 */
public final class ChopPipeline {

    public static final int DEFAULT_MAX_METHODS = 500;

    private final Path project;
    private final int maxDepth;
    private final int maxMethods;

    public ChopPipeline(Path project) {
        this(project, Integer.MAX_VALUE, DEFAULT_MAX_METHODS);
    }

    public ChopPipeline(Path project, int maxDepth, int maxMethods) {
        this.project = project;
        this.maxDepth = maxDepth;
        this.maxMethods = maxMethods;
    }

    /**
     * Builds a fully annotated chop graph for {@code targetMethod} reachable in {@code pg}.
     *
     * @throws MaxMethodsExceededException if reverse-call scan exceeds {@code maxMethods}
     * @throws EmptyTargetBodyException    if the target has no PDG (empty body / parse failure)
     */
    public ChopGraph build(ProjectGraph pg, Node.Method targetMethod)
            throws MaxMethodsExceededException, EmptyTargetBodyException {
        ReachabilityScan.Result reach = new ReachabilityScan(new EntryPointFinder(), maxDepth, maxMethods)
                .run(pg, targetMethod);

        JavaParserContext jpCtx = JavaParserContext.forProject(project);
        PdgBuilder builder = new PdgBuilder(jpCtx);

        Map<MethodRef, MethodPDG> pdgs = new LinkedHashMap<>();
        MethodRef targetRef = new MethodRef(targetMethod.fqn(), targetMethod.signature());
        for (Node.Method m : reach.involved()) {
            try {
                boolean isTarget = new MethodRef(m.fqn(), m.signature()).equals(targetRef);
                pdgs.put(new MethodRef(m.fqn(), m.signature()), builder.build(m, isTarget));
            } catch (Exception e) {
                System.err.println("chop: skipped " + m.fqn() + ": " + e.getMessage());
            }
        }

        MethodPDG targetPdg = pdgs.get(targetRef);
        if (targetPdg == null) {
            throw new EmptyTargetBodyException(targetRef);
        }
        List<StatementId> targetStmts = targetPdg.statements().stream()
                .map(StatementNode::id).toList();
        Set<MethodRef> entries = new HashSet<>();
        for (Node.Method e : reach.entryPoints()) {
            entries.add(new MethodRef(e.fqn(), e.signature()));
        }

        Map<MethodRef, MethodPDG> annotatedPdgs = new LinkedHashMap<>();
        for (Map.Entry<MethodRef, MethodPDG> entry : pdgs.entrySet()) {
            MethodNode mn = entry.getValue().methodNode();
            boolean isTarget = entry.getKey().equals(targetRef);
            boolean isTest = entries.contains(entry.getKey()) || mn.isTest();
            MethodNode marked = new MethodNode(mn.owner(), isTest, isTarget, mn.touchedBy());
            annotatedPdgs.put(entry.getKey(),
                    new MethodPDG(entry.getValue().ref(), marked,
                            entry.getValue().statements(), entry.getValue().expressions(),
                            entry.getValue().intraEdges(), entry.getValue().parameters(),
                            entry.getValue().returnValues(), entry.getValue().bodyByStatement()));
        }

        ChopGraph graph = new ChopComposer().compose(targetRef, targetStmts, entries, annotatedPdgs, pg);
        new ChopAnnotator().annotate(graph);
        return graph;
    }

    public static final class EmptyTargetBodyException extends Exception {
        private final MethodRef target;
        public EmptyTargetBodyException(MethodRef target) {
            super("target has empty body, nothing to chop: " + target.fqn() + target.signature());
            this.target = target;
        }
        public MethodRef target() { return target; }
    }
}
