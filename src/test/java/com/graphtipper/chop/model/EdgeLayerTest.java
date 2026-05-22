package com.graphtipper.chop.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeLayerTest {

    @Test
    void parseExactMatchIsCaseInsensitive() {
        assertThat(EdgeLayer.parse("CG")).isEqualTo(EdgeLayer.CG);
        assertThat(EdgeLayer.parse("ddg")).isEqualTo(EdgeLayer.DDG);
        assertThat(EdgeLayer.parse("Arg_Pass")).isEqualTo(EdgeLayer.ARG_PASS);
    }

    @Test
    void parseUnknownThrows() {
        assertThatThrownBy(() -> EdgeLayer.parse("NOPE"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NOPE");
    }
}
