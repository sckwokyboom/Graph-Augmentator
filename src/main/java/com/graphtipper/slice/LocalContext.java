package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

/** Local context around the target. v2: production call-sites migrated to {@link ConsumerContract}. */
public record LocalContext(
        List<SiblingMember> siblings,
        List<UsedType> usedTypes
) {
    public LocalContext {
        siblings = List.copyOf(siblings);
        usedTypes = List.copyOf(usedTypes);
    }
    public record SiblingMember(String signature, String javadoc, String body, boolean truncated) {}
    public record UsedType(Node.Type type, List<String> publicMethodSignatures) {}
}
