package com.graphtipper.slice;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Populates {@link ClusterMember#argsAtTarget()} and {@link ClusterMember#oracle()}
 * for each member of each cluster. Inputs:
 *  - {@code resolver}: maps a test FQN to its .java file path.
 *  - {@code chainArgsMap}: a precomputed map from test FQN → args reaching target on that chain.
 *    (Populated from the chain's last {@code CallStep.argOrigins} in the pipeline orchestration.)
 */
public final class ClusterEnricher {

    private final OracleExtractor oracleExtractor;

    public ClusterEnricher(OracleExtractor oracleExtractor) {
        this.oracleExtractor = oracleExtractor;
    }

    @FunctionalInterface
    public interface TestFileResolver {
        Path resolve(String testFqn);
    }

    public List<PathCluster> enrich(List<PathCluster> clusters,
                                     TestFileResolver resolver,
                                     Map<String, List<ArgOrigin>> chainArgsMap) {
        var out = new ArrayList<PathCluster>(clusters.size());
        for (PathCluster c : clusters) {
            var enrichedMembers = new ArrayList<ClusterMember>(c.members().size());
            for (ClusterMember m : c.members()) {
                String testFqn = m.testMethod().fqn();
                Path file = resolver.resolve(testFqn);
                Oracle oracle = file == null
                        ? new Oracle.None()
                        : oracleExtractor.primaryFor(file, testFqn, /*targetFqn*/ "");
                List<ArgOrigin> args = chainArgsMap.getOrDefault(testFqn, m.argsAtTarget());
                enrichedMembers.add(new ClusterMember(m.testMethod(), args, oracle));
            }
            out.add(c.withMembers(enrichedMembers));
        }
        return out;
    }
}
