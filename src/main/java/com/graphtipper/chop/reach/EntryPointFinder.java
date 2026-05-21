package com.graphtipper.chop.reach;

import com.graphtipper.model.Node;

public final class EntryPointFinder {

    public boolean isEntry(Node.Method m) {
        if (m.isTest()) return true;
        String file = m.file() == null ? "" : m.file();
        String norm = "/" + file.replace('\\', '/');
        if (norm.contains("/src/test/")) return true;
        String fqn = m.fqn();
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return false;
        String classFqn = fqn.substring(0, lastDot);
        int classDot = classFqn.lastIndexOf('.');
        String simple = classDot < 0 ? classFqn : classFqn.substring(classDot + 1);
        if (simple.endsWith("Test") || simple.endsWith("Tests") || simple.endsWith("IT")) return true;
        String methodSimple = fqn.substring(lastDot + 1);
        return methodSimple.startsWith("test");
    }
}
