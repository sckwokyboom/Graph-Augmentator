package com.graphtipper.chop.compose;

import com.graphtipper.chop.model.*;
import com.graphtipper.chop.pdg.MethodPDG;
import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ChopComposer {

    public ChopGraph compose(MethodRef target,
                             List<StatementId> targetStatements,
                             Set<MethodRef> entryPoints,
                             Map<MethodRef, MethodPDG> pdgs,
                             ProjectGraph projectGraph) {
        ChopGraph g = new ChopGraph(target, targetStatements, entryPoints);
        for (MethodPDG pdg : pdgs.values()) {
            g.addNode(pdg.methodNode());
            pdg.statements().forEach(g::addNode);
            pdg.expressions().forEach(g::addNode);
            pdg.intraEdges().forEach(g::addEdge);
        }
        for (Map.Entry<MethodRef, MethodPDG> entry : pdgs.entrySet()) {
            MethodPDG caller = entry.getValue();
            for (ExprNode call : caller.expressions()) {
                if (call.kind() != ExpressionKind.CALLSITE) continue;
                Node.Method callerMethod = methodByRef(projectGraph, entry.getKey());
                if (callerMethod == null) continue;
                for (Edge.Calls c : projectGraph.outgoingCalls(callerMethod.id())) {
                    Node target2 = projectGraph.byId(c.toId());
                    if (!(target2 instanceof Node.Method targetMethod)) continue;
                    MethodRef calleeRef = new MethodRef(targetMethod.fqn(), targetMethod.signature());
                    MethodPDG callee = pdgs.get(calleeRef);
                    if (callee == null) continue;
                    ResolutionKind rk = c.viaVirtual() ? ResolutionKind.CHA : ResolutionKind.EXACT;
                    for (int i = 0; i < callee.parameters().size(); i++) {
                        g.addEdge(new ChopEdge(call, callee.parameters().get(i),
                            EdgeLayer.ARG_PASS, rk, DataKind.ARG, "arg" + i, new HashSet<>()));
                    }
                    for (ExprNode rv : callee.returnValues()) {
                        g.addEdge(new ChopEdge(rv, call, EdgeLayer.RETURN_BIND, rk,
                            DataKind.RETURN, "return", new HashSet<>()));
                    }
                    g.addEdge(new ChopEdge(caller.methodNode(), callee.methodNode(),
                        EdgeLayer.CG, rk, null, "call", new HashSet<>()));
                }
            }
        }
        return g;
    }

    private static Node.Method methodByRef(ProjectGraph pg, MethodRef ref) {
        for (Node n : pg.byFqn(ref.fqn())) {
            if (n instanceof Node.Method m && m.signature().equals(ref.signature())) return m;
        }
        return null;
    }
}
