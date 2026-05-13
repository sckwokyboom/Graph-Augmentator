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

    @Test
    void handlesCycleWithoutInfiniteLoop() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).done()
            .method("p.A.foo").done()
            .method("p.A.bar").done()
            .calls("p.T.t1", "p.A.foo")
            .calls("p.A.foo", "p.A.bar")
            .calls("p.A.bar", "p.A.foo")  // cycle
            .build();
        var target = (Node.Method) g.byFqn("p.A.bar").get(0);
        var result = new ReverseCallChainExtractor(16).extract(g, target);
        assertThat(result.chains()).isNotEmpty();
        assertThat(result.chains().get(0).steps()).hasSize(2); // t1 → foo → bar
    }

    @Test
    void usesOverridesEdgeForVirtualDispatch() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).done()
            .method("p.I.do").done()
            .method("p.Impl.do").done()
            .calls("p.T.t1", "p.I.do")        // test calls interface
            .overrides("p.Impl.do", "p.I.do") // Impl overrides interface
            .build();
        var target = (Node.Method) g.byFqn("p.Impl.do").get(0);
        var result = new ReverseCallChainExtractor(16).extract(g, target);
        assertThat(result.chains()).hasSize(1);
        var chain = result.chains().get(0);
        assertThat(chain.virtualSteps()).isEqualTo(1);
        assertThat(chain.steps()).hasSize(1);
        assertThat(chain.steps().get(0).viaVirtual()).isTrue();
    }

    @Test
    void truncatesWhenFrontierExceedsGuard() {
        // Create a fan-out: 200 tests each calling target.
        var b = Gb.graph().method("p.A.target").done();
        for (int i = 0; i < 200; i++) {
            b = b.method("p.T.t" + i).testFlag(true).done()
                 .calls("p.T.t" + i, "p.A.target");
        }
        var g = b.build();
        var target = (Node.Method) g.byFqn("p.A.target").get(0);
        var result = new ReverseCallChainExtractor(4).extract(g, target);
        assertThat(result.chains()).hasSize(4);
    }
}
