package com.graphtipper.slice;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReverseCallChainExtractorTest {
    @Test
    void findsSingleStepChain() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).done()
            .method("p.A.target").done()
            .calls("p.T.t1", "p.A.target")
            .build();
        var target = (Node.Method) g.byFqn("p.A.target").get(0);
        var result = new ReverseCallChainExtractor(16).extract(g, target);
        assertThat(result.chains()).hasSize(1);
        assertThat(result.chains().get(0).steps()).hasSize(1);
        assertThat(result.chains().get(0).steps().get(0).callerFqn()).isEqualTo("p.T.t1");
        assertThat(result.chains().get(0).steps().get(0).calleeFqn()).isEqualTo("p.A.target");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void findsTwoStepChain() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).done()
            .method("p.B.bridge").done()
            .method("p.A.target").done()
            .calls("p.T.t1", "p.B.bridge")
            .calls("p.B.bridge", "p.A.target")
            .build();
        var target = (Node.Method) g.byFqn("p.A.target").get(0);
        var result = new ReverseCallChainExtractor(16).extract(g, target);
        assertThat(result.chains()).hasSize(1);
        assertThat(result.chains().get(0).steps()).hasSize(2);
        // closest-to-test first ordering: t1 → bridge → target
        assertThat(result.chains().get(0).steps().get(0).callerFqn()).isEqualTo("p.T.t1");
        assertThat(result.chains().get(0).steps().get(1).callerFqn()).isEqualTo("p.B.bridge");
    }

    @Test
    void emitsNoChainsWhenNoTestReachesTarget() {
        var g = Gb.graph()
            .method("p.A.target").done()
            .build();
        var target = (Node.Method) g.byFqn("p.A.target").get(0);
        var result = new ReverseCallChainExtractor(16).extract(g, target);
        assertThat(result.chains()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }
}
