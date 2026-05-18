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

    @Test
    void consumerContract_constructs_with_all_fields() {
        var contract = new ConsumerContract(
            "C.consumer", "C.java", 42, "public void consumer() { target(); }",
            ReturnValueUsage.empty(),
            ExceptionHandlingNearCall.none(),
            List.of(new ImpliedRequirement("test")),
            List.of(),
            1511);
        assertThat(contract.consumerFqn()).isEqualTo("C.consumer");
        assertThat(contract.chainsCovered()).isEqualTo(1511);
        assertThat(contract.implications()).hasSize(1);
    }

    @Test
    void directTest_carries_test_method_args_oracle_and_snippet() {
        var method = new com.graphtipper.model.Node.Method(
            "m_test", "TestClass.t1", "", List.of(), "", "Test.java", 1, 10, "", true, false, List.of());
        var dt = new DirectTest(method, List.of(), new Oracle.None(), "@Test void t1() {}");
        assertThat(dt.testMethod().fqn()).isEqualTo("TestClass.t1");
        assertThat(dt.oracle()).isInstanceOf(Oracle.None.class);
        assertThat(dt.snippet()).contains("@Test");
    }

    private java.nio.file.Path consumerFixture(String name) {
        return java.nio.file.Paths.get("src/test/resources/consumer-fixtures", name);
    }

    @Test
    void classifyReturnValueUsage_detects_assign_and_field_read() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("MultiCallConsumer.java"),
            "consumerfix.MultiCallConsumer.useAssignAndFieldRead",
            "target");
        assertThat(usage.kinds()).contains(UsageKind.ASSIGNED_TO_LOCAL, UsageKind.FIELD_READ);
        assertThat(usage.fieldsRead()).contains("row");
    }

    @Test
    void classifyReturnValueUsage_detects_condition() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("MultiCallConsumer.java"),
            "consumerfix.MultiCallConsumer.useInCondition",
            "target");
        assertThat(usage.kinds()).contains(UsageKind.USED_IN_CONDITION);
    }

    @Test
    void classifyReturnValueUsage_detects_returned_unchanged() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("MultiCallConsumer.java"),
            "consumerfix.MultiCallConsumer.useReturnedUnchanged",
            "target");
        assertThat(usage.kinds()).contains(UsageKind.RETURNED_UNCHANGED);
    }

    @Test
    void classifyReturnValueUsage_detects_discarded() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("MultiCallConsumer.java"),
            "consumerfix.MultiCallConsumer.useDiscarded",
            "target");
        assertThat(usage.kinds()).contains(UsageKind.DISCARDED);
    }

    @Test
    void classifyReturnValueUsage_detects_passed_as_arg() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("MultiCallConsumer.java"),
            "consumerfix.MultiCallConsumer.usePassedAsArg",
            "target");
        assertThat(usage.kinds()).contains(UsageKind.PASSED_AS_ARG);
    }

    @Test
    void classifyExceptionHandling_detects_try_catch_around_target() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var ex = d.classifyExceptionHandling(
            consumerFixture("TryCatchConsumer.java"),
            "consumerfix.TryCatchConsumer.wrappedConsumer",
            "target");
        assertThat(ex.inTryCatch()).isTrue();
        assertThat(ex.caughtTypes()).contains("IOException");
    }

    @Test
    void classifyExceptionHandling_returns_none_when_call_outside_try() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var ex = d.classifyExceptionHandling(
            consumerFixture("TryCatchConsumer.java"),
            "consumerfix.TryCatchConsumer.unwrappedConsumer",
            "target");
        assertThat(ex.inTryCatch()).isFalse();
    }

    @Test
    void classifyExceptionHandling_collects_multi_catch_types() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var ex = d.classifyExceptionHandling(
            consumerFixture("TryCatchConsumer.java"),
            "consumerfix.TryCatchConsumer.multiCatchConsumer",
            "target");
        assertThat(ex.inTryCatch()).isTrue();
        assertThat(ex.caughtTypes()).contains("IOException", "IllegalStateException");
    }

    @Test
    void derive_assembles_consumer_contracts_grouped_by_consumer_fqn() {
        // Two clusters that both funnel through consumer A; one through consumer B.
        var sigA1 = new PathSignature(List.of("E1.entry", "consumerfix.MultiCallConsumer.useAssignAndFieldRead", "target"));
        var sigA2 = new PathSignature(List.of("E2.entry", "consumerfix.MultiCallConsumer.useAssignAndFieldRead", "target"));
        var sigB = new PathSignature(List.of("E3.entry", "consumerfix.MultiCallConsumer.useDiscarded", "target"));
        var clusterA1 = new PathCluster(sigA1, "E1.entry", "consumerfix.MultiCallConsumer.useAssignAndFieldRead",
                3, List.of(stubMember("Test.a")), List.of());
        var clusterA2 = new PathCluster(sigA2, "E2.entry", "consumerfix.MultiCallConsumer.useAssignAndFieldRead",
                3, List.of(stubMember("Test.b"), stubMember("Test.c")), List.of());
        var clusterB = new PathCluster(sigB, "E3.entry", "consumerfix.MultiCallConsumer.useDiscarded",
                3, List.of(stubMember("Test.d")), List.of());

        var d = new ConsumerDeriver(new AstSnippetExtractor());
        // Construct a mini fileMap that the deriver can use to look up consumer source.
        var contracts = d.derive(List.of(clusterA1, clusterA2, clusterB), "target",
                fqn -> {
                    if (fqn.startsWith("consumerfix.MultiCallConsumer."))
                        return consumerFixture("MultiCallConsumer.java");
                    return null;
                });

        assertThat(contracts).hasSize(2);
        var assignContract = contracts.stream()
                .filter(c -> c.consumerFqn().endsWith("useAssignAndFieldRead"))
                .findFirst().orElseThrow();
        assertThat(assignContract.chainsCovered()).isEqualTo(3); // 1 + 2
        assertThat(assignContract.clusters()).hasSize(2);
        assertThat(assignContract.returnValueUsage().kinds()).contains(UsageKind.ASSIGNED_TO_LOCAL);
        assertThat(assignContract.implications()).isNotEmpty();
    }

    private ClusterMember stubMember(String testFqn) {
        var node = new com.graphtipper.model.Node.Method("m_" + testFqn, testFqn, "", List.of(), "", "Test.java", 1, 10, "", true, false, List.of());
        return new ClusterMember(node, List.of(), new Oracle.None());
    }

    @Test
    void classifyReturnValueUsage_picks_overload_that_actually_calls_target() {
        // OverloadedConsumer has TWO methods named `addRowValues`: the first delegates
        // (no call to target), the second is the real immediate consumer (assigns target's
        // return to `cell` and reads `cell.row`). Without overload disambiguation by
        // target-call presence, the first overload would be picked and usage extraction
        // would return empty (the original picocli/putValue bug).
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("OverloadedConsumer.java"),
            "consumerfix.OverloadedConsumer.addRowValues",
            "target");
        assertThat(usage.kinds())
            .as("must reach the overload that actually calls target, not the delegating one")
            .contains(UsageKind.ASSIGNED_TO_LOCAL, UsageKind.FIELD_READ, UsageKind.USED_IN_CONDITION);
        assertThat(usage.fieldsRead()).contains("row");
    }

    @Test
    void classifyExceptionHandling_picks_overload_that_actually_calls_target() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var ex = d.classifyExceptionHandling(
            consumerFixture("OverloadedConsumer.java"),
            "consumerfix.OverloadedConsumer.addRowValues",
            "target");
        // The right overload has no try/catch around target.
        assertThat(ex.inTryCatch()).isFalse();
    }

    @Test
    void sliceConsumerBody_picks_overload_that_actually_calls_target() {
        var extractor = new AstSnippetExtractor();
        String slice = extractor.sliceConsumerBody(
            consumerFixture("OverloadedConsumer.java"),
            "consumerfix.OverloadedConsumer.addRowValues",
            "target");
        // The right overload contains `target(rowSeed, col, ...)` and the cell branch.
        assertThat(slice)
            .as("slice must come from the overload that actually contains target(...)")
            .contains("target(rowSeed, col,")
            .contains("cell.row");
        // And NOT the delegating one (which contains only `addRowValues(values, 0)`)
        assertThat(slice).doesNotContain("addRowValues(values, 0)");
    }
}
