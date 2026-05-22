package com.graphtipper.render;

import com.graphtipper.chop.model.MethodRef;
import com.graphtipper.chop.score.KatzScorer;
import com.graphtipper.slice.PathCluster;
import com.graphtipper.slice.PathSignature;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererHubMarkerTest {

    @Test void renderHubMarkerReturnsTop2KatzMethods() {
        KatzScorer fake = new KatzScorer(new com.graphtipper.chop.model.ChopGraph(
                new MethodRef("__sentinel__", "()"),
                List.of(), java.util.Set.of())) {
            @Override public double score(MethodRef m) {
                return switch (m.fqn()) {
                    case "com.example.HubA" -> 9.0;
                    case "com.example.HubB" -> 5.0;
                    case "com.example.Leaf" -> 0.1;
                    default -> 0.0;
                };
            }
        };
        var cluster = new PathCluster(
                new PathSignature(List.of("com.example.HubA", "com.example.Leaf", "com.example.HubB")),
                "com.example.HubA", "com.example.HubB", 3, List.of(), List.of());
        String marker = MarkdownRenderer.renderHubMarker(cluster, fake);
        assertThat(marker).isEqualTo("[hub: com.example.HubA, com.example.HubB]");
    }

    @Test void renderHubMarkerEmptyWhenScorerNull() {
        var cluster = new PathCluster(
                new PathSignature(List.of("com.example.X")),
                "com.example.X", "com.example.X", 1, List.of(), List.of());
        assertThat(MarkdownRenderer.renderHubMarker(cluster, null)).isEmpty();
    }

    @Test void renderHubMarkerEmptyWhenAllScoresZero() {
        KatzScorer zeroScorer = new KatzScorer(new com.graphtipper.chop.model.ChopGraph(
                new MethodRef("__sentinel__", "()"),
                List.of(), java.util.Set.of())) {
            @Override public double score(MethodRef m) { return 0.0; }
        };
        var cluster = new PathCluster(
                new PathSignature(List.of("com.example.X")),
                "com.example.X", "com.example.X", 1, List.of(), List.of());
        assertThat(MarkdownRenderer.renderHubMarker(cluster, zeroScorer)).isEmpty();
    }
}
