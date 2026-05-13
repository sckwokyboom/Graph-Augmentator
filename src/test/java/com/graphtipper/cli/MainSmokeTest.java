package com.graphtipper.cli;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MainSmokeTest {
    @Test
    void cliClassHasMainMethod() throws Exception {
        var method = Main.class.getDeclaredMethod("main", String[].class);
        assertThat(method).isNotNull();
    }
}
