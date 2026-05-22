package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ClusterEnricherTest {

    private static com.graphtipper.model.Node.Method testMethod(String fqn) {
        return new com.graphtipper.model.Node.Method("m_" + fqn, fqn, "",
                List.of(), "", "src/test/resources/oracle-fixtures/AssertEqualsTests.java",
                10, 20, "", true, false, List.of());
    }

    @Test
    void enrich_attaches_oracle_extracted_from_test_method_source() {
        var sig = new PathSignature(List.of("E.entry", "C.consumer", "target"));
        var stubMember = new ClusterMember(
                testMethod("oraclefix.AssertEqualsTests.testReturnEquals"),
                List.of(), new Oracle.None());
        var cluster = new PathCluster(sig, "E.entry", "C.consumer", 3, List.of(stubMember), List.of());

        var enricher = new ClusterEnricher(new OracleExtractor());
        var enriched = enricher.enrich(List.of(cluster),
                fqn -> Paths.get("src/test/resources/oracle-fixtures/AssertEqualsTests.java"),
                java.util.Map.of());

        assertThat(enriched).hasSize(1);
        var member = enriched.get(0).members().get(0);
        assertThat(member.oracle()).isInstanceOf(Oracle.Equals.class);
    }

    @Test
    void enrich_attaches_argsAtTarget_from_supplied_chain_map() {
        var sig = new PathSignature(List.of("E.entry", "C.consumer", "target"));
        var stubMember = new ClusterMember(
                testMethod("oraclefix.AssertEqualsTests.testReturnEquals"),
                List.of(), new Oracle.None());
        var cluster = new PathCluster(sig, "E.entry", "C.consumer", 3, List.of(stubMember), List.of());

        var args = List.of(
                ArgOrigin.literal(0, "0", "F.java", 1),
                ArgOrigin.literal(1, "0", "F.java", 1));
        var chainArgsMap = java.util.Map.of(
                "oraclefix.AssertEqualsTests.testReturnEquals", (List<ArgOrigin>) args);

        var enricher = new ClusterEnricher(new OracleExtractor());
        var enriched = enricher.enrich(List.of(cluster),
                fqn -> Paths.get("src/test/resources/oracle-fixtures/AssertEqualsTests.java"),
                chainArgsMap);

        assertThat(enriched.get(0).members().get(0).argsAtTarget()).hasSize(2);
    }

    @Test
    void enrich_populates_argSlices_and_clusterSlice() {
        var sig = new PathSignature(List.of(
                "slicefix.SimpleChain.test1",
                "slicefix.SimpleChain.entry",
                "slicefix.SimpleChain.target"));
        var stubMember = new ClusterMember(
                testMethod("slicefix.SimpleChain.test1"),
                List.of(), new Oracle.None());
        var cluster = new PathCluster(sig, "slicefix.SimpleChain.test1", "slicefix.SimpleChain.entry",
                3, List.of(stubMember), List.of());

        var enricher = new ClusterEnricher(new OracleExtractor());
        Path fixture = Paths.get("src/test/resources/slice-fixtures/SimpleChain.java");
        var enriched = enricher.enrich(
                List.of(cluster),
                fqn -> fixture,             // testFile resolver
                consumerFqn -> fixture,     // consumerFile resolver
                "slicefix.SimpleChain.target",
                List.of("t"),
                List.of("String"),
                java.util.Map.of());

        assertThat(enriched).hasSize(1);
        var pc = enriched.get(0);
        assertThat(pc.clusterSlice().args()).hasSize(1);
        var member = pc.members().get(0);
        assertThat(member.argSlices()).hasSize(1);
        assertThat(member.argSlices().get(0).argName()).isEqualTo("t");
        assertThat(member.argSlices().get(0).result())
                .isInstanceOfSatisfying(SliceResult.ParamFromCaller.class, pf ->
                        assertThat(pf.callerSlice()).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                                assertThat(r.value()).isEqualTo("hello")));
    }
}
