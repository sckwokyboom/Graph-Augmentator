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
    public record SiblingMember(String signature, String javadoc, String body, boolean truncated,
                                 String file, int lineStart, int lineEnd) {
        /** Legacy 4-arg constructor for callers that don't track source location yet. */
        public SiblingMember(String signature, String javadoc, String body, boolean truncated) {
            this(signature, javadoc, body, truncated, null, -1, -1);
        }
    }
    public record UsedType(Node.Type type, List<String> publicMethodSignatures) {}
}
