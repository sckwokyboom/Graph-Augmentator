package com.graphtipper.chop.reach;

import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

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
        if (entries.isEntry(target)) entryPoints.add(target);

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
