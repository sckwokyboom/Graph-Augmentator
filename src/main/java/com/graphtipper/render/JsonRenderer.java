package com.graphtipper.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;

public final class JsonRenderer {
    private final ObjectMapper M = new ObjectMapper();

    // -------------------------------------------------------------------------
    // v2 schema render — no TokenBudget argument
    // -------------------------------------------------------------------------

    public String render(Artifact a) {
        ObjectNode root = M.createObjectNode();
        root.put("schemaVersion", "2.0");

        // Target
        ObjectNode target = root.putObject("target");
        target.put("fqn", a.target().fqn());
        target.put("file", a.target().file());
        target.put("lineStart", a.target().lineStart());
        target.put("lineEnd", a.target().lineEnd());

        // Direct tests
        ArrayNode dts = root.putArray("directTests");
        for (var dt : a.directTests()) {
            ObjectNode o = dts.addObject();
            o.put("fqn", dt.testMethod().fqn());
            o.put("file", dt.testMethod().file());
            o.put("line", dt.testMethod().lineStart());
            o.put("snippet", dt.snippet());
            renderArgs(o.putArray("args"), dt.args());
            o.set("oracle", renderOracleNode(dt.oracle()));
        }

        // Consumers + clusters
        ArrayNode consumers = root.putArray("consumers");
        for (var c : a.consumers()) {
            ObjectNode co = consumers.addObject();
            co.put("fqn", c.consumerFqn());
            co.put("file", c.file());
            co.put("line", c.line());
            co.put("chainsCovered", c.chainsCovered());
            co.put("bodySlice", c.bodySlice());
            ArrayNode kinds = co.putArray("returnValueUsageKinds");
            for (var k : c.returnValueUsage().kinds()) kinds.add(k.name());
            ArrayNode fields = co.putArray("returnValueFieldsRead");
            for (var f : c.returnValueUsage().fieldsRead()) fields.add(f);
            ObjectNode eh = co.putObject("exceptionHandling");
            eh.put("inTryCatch", c.exceptionHandling().inTryCatch());
            ArrayNode caught = eh.putArray("caughtTypes");
            for (var t : c.exceptionHandling().caughtTypes()) caught.add(t);
            ArrayNode imps = co.putArray("implications");
            for (var i : c.implications()) imps.add(i.text());
            ArrayNode cls = co.putArray("clusters");
            for (var cluster : c.clusters()) cls.add(renderClusterNode(cluster));
        }

        // Long tail
        ObjectNode lt = root.putObject("longTail");
        lt.put("uncoveredSingletonCount", a.longTailSingletons().size());
        ArrayNode lts = lt.putArray("singletons");
        for (var s : a.longTailSingletons()) lts.add(renderClusterNode(s));

        // Local context
        ObjectNode lc = root.putObject("localContext");
        ArrayNode sibs = lc.putArray("siblings");
        for (var s : a.localContext().siblings()) {
            ObjectNode o = sibs.addObject();
            o.put("signature", s.signature());
            if (s.javadoc() != null) o.put("javadoc", s.javadoc());
            o.put("body", s.body());
            o.put("truncated", s.truncated());
        }

        // Budget / truncated
        root.put("truncated", a.truncated());

        // Reserved
        root.putObject("negativeMemory");

        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ObjectNode renderClusterNode(PathCluster cluster) {
        ObjectNode out = M.createObjectNode();
        out.put("entryPoint", cluster.entryPoint());
        out.put("immediateConsumer", cluster.immediateConsumer());
        out.put("depth", cluster.depth());
        out.put("chainsCovered", cluster.chainsCovered());
        ArrayNode sig = out.putArray("pathSignature");
        for (var fqn : cluster.signature().fqns()) sig.add(fqn);
        ArrayNode members = out.putArray("members");
        for (var m : cluster.members()) {
            ObjectNode mo = members.addObject();
            mo.put("testFqn", m.testMethod().fqn());
            mo.put("file", m.testMethod().file());
            mo.put("line", m.testMethod().lineStart());
            renderArgs(mo.putArray("argsAtTarget"), m.argsAtTarget());
            mo.set("oracle", renderOracleNode(m.oracle()));
        }
        ArrayNode sigs = out.putArray("behaviorSignals");
        for (var s : cluster.signals()) {
            ObjectNode so = sigs.addObject();
            so.put("tag", s.tag());
            so.put("evidence", s.evidence());
        }
        return out;
    }

    private void renderArgs(ArrayNode out, java.util.List<ArgOrigin> args) {
        for (var a : args) {
            ObjectNode o = out.addObject();
            o.put("index", a.argIndex());
            o.put("kind", a.kind().name());
            if (a.value() != null) o.put("value", a.value());
            if (a.exprText() != null) o.put("exprText", a.exprText());
            if (a.paramName() != null) o.put("paramName", a.paramName());
        }
    }

    private ObjectNode renderOracleNode(Oracle o) {
        ObjectNode out = M.createObjectNode();
        switch (o) {
            case Oracle.Equals eq -> {
                out.put("kind", "Equals");
                out.put("expected", eq.expected());
                out.put("actualExpr", eq.actualExpr());
            }
            case Oracle.Exception ex -> {
                out.put("kind", "Exception");
                out.put("type", ex.type());
            }
            case Oracle.ExceptionMessage em -> {
                out.put("kind", "ExceptionMessage");
                out.put("type", em.type());
                out.put("matchKind", em.kind().name());
                out.put("message", em.message());
            }
            case Oracle.Boolean b -> {
                out.put("kind", "Boolean");
                out.put("expected", b.expected());
                out.put("expr", b.expr());
            }
            case Oracle.Nullability n -> {
                out.put("kind", "Nullability");
                out.put("expectNonNull", n.expectNonNull());
                out.put("expr", n.expr());
            }
            case Oracle.Contains c -> {
                out.put("kind", "Contains");
                out.put("expr", c.expr());
                out.put("substring", c.substring());
            }
            case Oracle.None __ -> out.put("kind", "None");
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // v1 schema render — retained for backward compatibility (GraphJsonRenderer
    // and legacy callers still use this path)
    // -------------------------------------------------------------------------

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
                    if (o.exprText() != null) on.put("exprText", o.exprText());
                    if (o.definedAtLine() > 0) {
                        on.put("definedAtLine", o.definedAtLine());
                        if (o.definedAtSnippet() != null) on.put("definedAtSnippet", o.definedAtSnippet());
                    }
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
