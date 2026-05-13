package com.graphtipper.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GbTest {
    @Test
    void buildsMinimalGraphFluently() {
        var g = Gb.graph()
            .method("p.A.foo").testFlag(false).done()
            .method("p.T.t1").testFlag(true).done()
            .calls("p.T.t1", "p.A.foo")
            .build();
        assertThat(g.testMethods()).hasSize(1);
        assertThat(g.incomingCalls(g.byFqn("p.A.foo").get(0).id())).hasSize(1);
    }
}
