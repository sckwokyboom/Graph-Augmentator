package com.graphtipper.slice;

import java.util.ArrayList;
import java.util.List;

/**
 * Constant-table mapping from {@link UsageKind} patterns + {@link ExceptionHandlingNearCall}
 * to short, template-based {@link ImpliedRequirement}s. Strict 1:1 mapping; no interpretation
 * beyond what AST observes. New patterns are added by extending this table — never via LLM.
 */
public final class ImpliedRequirementTemplates {

    private ImpliedRequirementTemplates() {}

    public static List<ImpliedRequirement> derive(
            ReturnValueUsage usage, ExceptionHandlingNearCall ex) {
        var out = new ArrayList<ImpliedRequirement>();

        if (usage.kinds().contains(UsageKind.FIELD_READ)
                || usage.kinds().contains(UsageKind.METHOD_CALL_ON_RESULT)) {
            String fields = usage.fieldsRead().isEmpty()
                    ? "the result"
                    : "`" + String.join("`, `", usage.fieldsRead()) + "`";
            out.add(new ImpliedRequirement(
                    "MUST return non-null (else NPE on " + fields + ")"));
        }

        if (!usage.fieldsRead().isEmpty()) {
            out.add(new ImpliedRequirement(
                    "Returned object's fields are observed by caller (not opaque): "
                            + String.join(", ", usage.fieldsRead())));
        }

        if (usage.kinds().contains(UsageKind.USED_IN_CONDITION)
                || usage.kinds().contains(UsageKind.USED_IN_LOOP)) {
            out.add(new ImpliedRequirement(
                    "Return value participates in caller's control flow"));
        }

        if (usage.kinds().contains(UsageKind.RETURNED_UNCHANGED)) {
            out.add(new ImpliedRequirement(
                    "Caller forwards target's return value; target's behavior is the caller's "
                            + "behavior on this path"));
        }

        if (usage.kinds().contains(UsageKind.PASSED_AS_ARG)) {
            out.add(new ImpliedRequirement(
                    "Return value is passed to another method; downstream usage may impose further constraints"));
        }

        if (usage.kinds().contains(UsageKind.DISCARDED) && usage.kinds().size() == 1) {
            out.add(new ImpliedRequirement(
                    "Caller discards return value; only side effects of target are observed"));
        }

        if (ex.inTryCatch()) {
            out.add(new ImpliedRequirement(
                    "Caller wraps call in try/catch for: "
                            + String.join(", ", ex.caughtTypes())
                            + " — exceptions of these types are translated/swallowed"));
        } else {
            out.add(new ImpliedRequirement(
                    "No try/catch around call — exceptions propagate to caller as-is"));
        }

        return out;
    }
}
