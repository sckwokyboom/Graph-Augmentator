package com.graphtipper.slice;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Populates {@link ClusterMember#argsAtTarget()} and {@link ClusterMember#oracle()}
 * for each member of each cluster. Inputs:
 *  - {@code resolver}: maps a test FQN to its .java file path.
 *  - {@code chainArgsMap}: a precomputed map from test FQN → args reaching target on that chain.
 *    (Populated from the chain's last {@code CallStep.argOrigins} in the pipeline orchestration.)
 *
 * <p>v2.2+ overload also runs the static slicer ({@link StaticSlicer}) to populate
 * {@link ClusterMember#argSlices()} and {@link PathCluster#clusterSlice()}.
 */
public final class ClusterEnricher {

    private final OracleExtractor oracleExtractor;
    private final int maxSliceDepth;
    private final int maxSliceBranches;

    public ClusterEnricher(OracleExtractor oracleExtractor) {
        this(oracleExtractor, StaticSlicer.DEFAULT_MAX_DEPTH, StaticSlicer.DEFAULT_MAX_BRANCHES);
    }

    public ClusterEnricher(OracleExtractor oracleExtractor, int maxSliceDepth, int maxSliceBranches) {
        this.oracleExtractor = oracleExtractor;
        this.maxSliceDepth = maxSliceDepth;
        this.maxSliceBranches = maxSliceBranches;
    }

    @FunctionalInterface
    public interface TestFileResolver {
        Path resolve(String testFqn);
    }

    @FunctionalInterface
    public interface ConsumerFileResolver {
        Path resolve(String consumerFqn);
    }

    /** Legacy overload — oracle + argsAtTarget only, no static slicing. */
    public List<PathCluster> enrich(List<PathCluster> clusters,
                                     TestFileResolver resolver,
                                     Map<String, List<ArgOrigin>> chainArgsMap) {
        return enrich(clusters, resolver, null, "", List.of(), List.of(), chainArgsMap);
    }

    /**
     * Enrich clusters with oracle, argsAtTarget, and (when {@code consumerFileResolver} and
     * {@code targetFqn} are supplied) static slice results.
     *
     * <p>For each cluster:
     *  1. resolve oracle + argsAtTarget per member (existing behavior).
     *  2. resolve immediate consumer's {@link MethodDeclaration} and the target call inside it.
     *  3. resolve each test method's {@link MethodDeclaration} (forms the slicer's callChain).
     *  4. invoke {@link StaticSlicer#sliceCluster} → per-member {@link ArgSlice}s.
     *  5. invoke {@link StaticSlicer#aggregateCluster} → per-cluster {@link ClusterSlice}.
     *
     * <p>If consumer/target resolution fails, every member is padded with
     * {@link SliceResult.Unresolved} arg slices keyed by {@code targetParamNames}/types,
     * and the cluster's aggregate reflects that.
     */
    public List<PathCluster> enrich(List<PathCluster> clusters,
                                     TestFileResolver testFileResolver,
                                     ConsumerFileResolver consumerFileResolver,
                                     String targetFqn,
                                     List<String> targetParamNames,
                                     List<String> targetParamTypes,
                                     Map<String, List<ArgOrigin>> chainArgsMap) {
        boolean doSlicing = consumerFileResolver != null && targetFqn != null && !targetFqn.isEmpty();
        StaticSlicer slicer = doSlicing ? new StaticSlicer(maxSliceDepth, maxSliceBranches) : null;
        var out = new ArrayList<PathCluster>(clusters.size());

        for (PathCluster c : clusters) {
            // Phase 1: oracle + argsAtTarget.
            var enrichedMembers = new ArrayList<ClusterMember>(c.members().size());
            for (ClusterMember m : c.members()) {
                String testFqn = m.testMethod().fqn();
                Path file = testFileResolver.resolve(testFqn);
                Oracle oracle = file == null
                        ? new Oracle.None()
                        : oracleExtractor.primaryFor(file, testFqn, targetFqn);
                List<ArgOrigin> args = chainArgsMap.getOrDefault(testFqn, m.argsAtTarget());
                enrichedMembers.add(new ClusterMember(m.testMethod(), args, oracle));
            }

            if (!doSlicing) {
                out.add(c.withMembers(enrichedMembers));
                continue;
            }

            // Phase 2: static slice pass.
            Path consumerFile = consumerFileResolver.resolve(c.immediateConsumer());
            Optional<MethodDeclaration> consumerMd = consumerFile == null
                    ? Optional.empty()
                    : resolveMethodDecl(consumerFile, c.immediateConsumer());

            if (consumerMd.isEmpty()) {
                var padded = padMembersWithUnresolved(enrichedMembers, targetParamNames, targetParamTypes,
                        UnresolvedReason.PARSE_ERROR, "consumer " + c.immediateConsumer() + " not resolvable");
                out.add(c.withMembers(padded).withClusterSlice(aggregateOrEmpty(padded, slicer)));
                continue;
            }

            String targetSimple = targetFqn.contains(".") ? targetFqn.substring(targetFqn.lastIndexOf('.') + 1) : targetFqn;
            List<MethodCallExpr> targetCallsInConsumer = consumerMd.get().findAll(MethodCallExpr.class).stream()
                    .filter(call -> call.getNameAsString().equals(targetSimple))
                    .toList();

            if (targetCallsInConsumer.isEmpty()) {
                var padded = padMembersWithUnresolved(enrichedMembers, targetParamNames, targetParamTypes,
                        UnresolvedReason.NOT_FOUND, "no call to " + targetSimple + " in consumer");
                out.add(c.withMembers(padded).withClusterSlice(aggregateOrEmpty(padded, slicer)));
                continue;
            }

            // Per-member: pick a call (first one for now) + test method as callChain leaf.
            List<MethodCallExpr> calls = new ArrayList<>(enrichedMembers.size());
            List<List<MethodDeclaration>> chains = new ArrayList<>(enrichedMembers.size());
            for (ClusterMember m : enrichedMembers) {
                calls.add(targetCallsInConsumer.get(0));
                Path testFile = testFileResolver.resolve(m.testMethod().fqn());
                Optional<MethodDeclaration> testMd = testFile == null
                        ? Optional.empty()
                        : resolveMethodDecl(testFile, m.testMethod().fqn());
                chains.add(testMd.<List<MethodDeclaration>>map(List::of).orElse(List.of()));
            }
            var perMember = slicer.sliceCluster(calls, chains, consumerMd.get(),
                    targetParamNames, targetParamTypes);
            var fullyEnriched = new ArrayList<ClusterMember>(enrichedMembers.size());
            for (int i = 0; i < enrichedMembers.size(); i++) {
                ClusterMember m = enrichedMembers.get(i);
                fullyEnriched.add(new ClusterMember(m.testMethod(), m.argsAtTarget(), m.oracle(), perMember.get(i)));
            }
            ClusterSlice cs = slicer.aggregateCluster(perMember);
            out.add(c.withMembers(fullyEnriched).withClusterSlice(cs));
        }
        return out;
    }

    private static List<ClusterMember> padMembersWithUnresolved(
            List<ClusterMember> members,
            List<String> paramNames,
            List<String> paramTypes,
            UnresolvedReason reason,
            String detail) {
        var padded = new ArrayList<ClusterMember>(members.size());
        for (ClusterMember m : members) {
            List<ArgSlice> argSlices = new ArrayList<>(paramNames.size());
            for (int a = 0; a < paramNames.size(); a++) {
                String name = paramNames.get(a);
                String type = a < paramTypes.size() ? paramTypes.get(a) : "?";
                argSlices.add(new ArgSlice(a, name, type, new SliceResult.Unresolved(reason, detail)));
            }
            padded.add(new ClusterMember(m.testMethod(), m.argsAtTarget(), m.oracle(), argSlices));
        }
        return padded;
    }

    private static ClusterSlice aggregateOrEmpty(List<ClusterMember> members, StaticSlicer slicer) {
        if (members.isEmpty() || slicer == null) return ClusterSlice.empty();
        return slicer.aggregateCluster(members.stream().map(ClusterMember::argSlices).toList());
    }

    private static Optional<MethodDeclaration> resolveMethodDecl(Path file, String fqn) {
        try {
            var cu = com.github.javaparser.StaticJavaParser.parse(file.toFile());
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot < 0) return Optional.empty();
            String methodName = fqn.substring(lastDot + 1);
            String enclosingFqn = fqn.substring(0, lastDot);
            int sep = Math.max(enclosingFqn.lastIndexOf('.'), enclosingFqn.lastIndexOf('$'));
            String simpleClass = enclosingFqn.substring(sep + 1);
            return cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(methodName))
                    .filter(m -> m.findAncestor(TypeDeclaration.class)
                            .map(t -> t.getNameAsString().equals(simpleClass)).orElse(false))
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
