package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

public record LocalContext(
        List<SiblingMember> siblings,
        List<UsedType> usedTypes,
        List<ProductionCallSite> productionCallSites
) {
    public record SiblingMember(String signature, String javadoc, String body, boolean truncated) {}
    public record UsedType(Node.Type type, List<String> publicMethodSignatures) {}
    public record ProductionCallSite(String callerFqn, String file, int line, String snippet) {}
}
