package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ConsumerDeriverTest {

    @Test
    void usageKind_values_match_spec() {
        assertThat(UsageKind.values()).contains(
            UsageKind.ASSIGNED_TO_LOCAL, UsageKind.ASSIGNED_TO_FIELD,
            UsageKind.FIELD_READ, UsageKind.METHOD_CALL_ON_RESULT,
            UsageKind.USED_IN_CONDITION, UsageKind.USED_IN_LOOP, UsageKind.USED_IN_INDEX_EXPR,
            UsageKind.PASSED_AS_ARG, UsageKind.RETURNED_UNCHANGED, UsageKind.DISCARDED);
    }

    @Test
    void returnValueUsage_constructs_with_kinds_and_fields() {
        var usage = new ReturnValueUsage(
            EnumSet.of(UsageKind.ASSIGNED_TO_LOCAL, UsageKind.FIELD_READ),
            List.of("row", "column"));
        assertThat(usage.kinds()).contains(UsageKind.ASSIGNED_TO_LOCAL);
        assertThat(usage.fieldsRead()).containsExactly("row", "column");
    }

    @Test
    void exceptionHandlingNearCall_distinguishes_try_catch_from_propagation() {
        var noTry = new ExceptionHandlingNearCall(false, List.of());
        var inTry = new ExceptionHandlingNearCall(true, List.of("IOException"));
        assertThat(noTry.inTryCatch()).isFalse();
        assertThat(inTry.caughtTypes()).containsExactly("IOException");
    }

    @Test
    void impliedRequirement_carries_text() {
        var req = new ImpliedRequirement("MUST return non-null");
        assertThat(req.text()).isEqualTo("MUST return non-null");
    }

    @Test
    void templates_map_field_read_to_non_null_requirement() {
        var usage = new ReturnValueUsage(EnumSet.of(UsageKind.FIELD_READ), List.of("row"));
        var reqs = ImpliedRequirementTemplates.derive(usage, ExceptionHandlingNearCall.none());
        assertThat(reqs).extracting(ImpliedRequirement::text)
                .anyMatch(t -> t.contains("MUST return non-null"));
    }

    @Test
    void templates_map_condition_to_control_flow_requirement() {
        var usage = new ReturnValueUsage(
            EnumSet.of(UsageKind.USED_IN_CONDITION, UsageKind.FIELD_READ),
            List.of("row"));
        var reqs = ImpliedRequirementTemplates.derive(usage, ExceptionHandlingNearCall.none());
        assertThat(reqs).extracting(ImpliedRequirement::text)
                .anyMatch(t -> t.contains("control flow"));
    }

    @Test
    void templates_map_returned_unchanged_to_pass_through_note() {
        var usage = new ReturnValueUsage(EnumSet.of(UsageKind.RETURNED_UNCHANGED), List.of());
        var reqs = ImpliedRequirementTemplates.derive(usage, ExceptionHandlingNearCall.none());
        assertThat(reqs).extracting(ImpliedRequirement::text)
                .anyMatch(t -> t.contains("forwards target's return"));
    }

    @Test
    void templates_emit_propagation_note_when_no_try_catch() {
        var reqs = ImpliedRequirementTemplates.derive(
            ReturnValueUsage.empty(), ExceptionHandlingNearCall.none());
        assertThat(reqs).extracting(ImpliedRequirement::text)
                .anyMatch(t -> t.contains("exceptions propagate"));
    }

    @Test
    void templates_emit_caught_types_when_try_catch_present() {
        var reqs = ImpliedRequirementTemplates.derive(
            ReturnValueUsage.empty(),
            new ExceptionHandlingNearCall(true, List.of("IOException")));
        assertThat(reqs).extracting(ImpliedRequirement::text)
                .anyMatch(t -> t.contains("IOException"));
    }
}
