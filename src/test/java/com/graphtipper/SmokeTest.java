package com.graphtipper;

import com.graphtipper.cli.Main;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest {
    @Test
    void mainExists() {
        assertThat(Main.class).isNotNull();
    }
}
