package com.graphtipper.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;

public final class JsonRenderer {
    private final ObjectMapper M = new ObjectMapper();

    public String render(Artifact a, TokenBudget budget) {
        ObjectNode root = M.createObjectNode();
        root.put("schemaVersion", "1.0");

        ObjectNode target = root.putObject("target");
        target.put("fqn", a.target().fqn());
        ArrayNode pt = target.putArray("paramTypes");
        for (String p : a.target().paramTypes()) pt.add(p);
        target.put("file", a.target().file());
        target.put("lineStart", a.target().lineStart());
        target.put("lineEnd", a.target().lineEnd());
        target.put("javadoc", a.target().javadoc());
        target.put("currentBody", a.currentBody());

        ArrayNode chains = root.putArray("chains");
        int rank = 1;
        for (Chain c : a.chains()) {
            ObjectNode cn = chains.addObject();
            cn.put("rank", rank++);
            cn.put("depth", c.depth());
            cn.put("virtualSteps", c.virtualSteps());
            cn.put("truncated", false);
            ObjectNode tst = cn.putObject("test");
            tst.put("fqn", c.test().fqn());
            tst.put("file", c.test().file());
            tst.put("line", c.test().lineStart());
            ArrayNode steps = cn.putArray("steps");
            for (CallStep s : c.steps()) {
                ObjectNode sn = steps.addObject();
                sn.put("callerFqn", s.callerFqn());
                sn.put("calleeFqn", s.calleeFqn());
                ObjectNode csn = sn.putObject("callSite");
                // V1: precise call-site file/line/col not yet plumbed through CallStep.
                // Use the first non-null arg-origin file as a best-effort; otherwise null.
                String csFile = null; int csLine = -1;
                for (ArgOrigin o : s.argOrigins()) {
                    if (o.file() != null) { csFile = o.file(); csLine = o.line(); break; }
                }
                csn.put("file", csFile);
                csn.put("line", csLine);
                csn.put("col", -1);
                sn.put("snippet", s.snippet());
                ArrayNode origins = sn.putArray("argOrigins");
                for (ArgOrigin o : s.argOrigins()) {
                    ObjectNode on = origins.addObject();
                    on.put("arg", o.argIndex());
                    on.put("kind", o.kind().name());
                    if (o.value() != null) on.put("value", o.value());
                    if (o.factoryFqn() != null) on.put("factoryFqn", o.factoryFqn());
                    if (o.paramName() != null) on.put("paramName", o.paramName());
                    if (o.fieldFqn() != null) on.put("fieldFqn", o.fieldFqn());
                    if (o.file() != null) on.put("file", o.file());
                    on.put("line", o.line());
                }
                sn.put("viaVirtual", s.viaVirtual());
            }
            cn.putArray("failures");
        }

        ObjectNode lc = root.putObject("localContext");
        ArrayNode sibs = lc.putArray("siblingMembers");
        for (var s : a.localContext().siblings()) {
            ObjectNode sn = sibs.addObject();
            sn.put("signature", s.signature());
            sn.put("javadoc", s.javadoc());
            sn.put("body", s.body());
            sn.put("truncated", s.truncated());
        }
        ArrayNode ut = lc.putArray("usedTypes");
        for (var u : a.localContext().usedTypes()) {
            ObjectNode un = ut.addObject();
            un.put("fqn", u.type().fqn());
            un.put("kind", u.type().kind().name());
            if (u.type().enumConstants() != null) {
                ArrayNode ec = un.putArray("enumConstants");
                for (String c : u.type().enumConstants()) ec.add(c);
            }
            ArrayNode sigs = un.putArray("publicMethodSignatures");
            for (String s : u.publicMethodSignatures()) sigs.add(s);
        }
        ArrayNode prod = lc.putArray("productionCallSites");
        for (var p : a.localContext().productionCallSites()) {
            ObjectNode pn = prod.addObject();
            pn.put("callerFqn", p.callerFqn());
            pn.put("file", p.file());
            pn.put("line", p.line());
            pn.put("snippet", p.snippet());
        }

        ObjectNode bud = root.putObject("budget");
        bud.put("tokensUsed", budget.used());
        bud.put("tokensMax", budget.max());
        ArrayNode ev = bud.putArray("evicted");
        for (String e : budget.evicted()) ev.add(e);

        root.putArray("negativeMemory");

        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
