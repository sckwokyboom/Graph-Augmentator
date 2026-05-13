package tiny;

import org.junit.jupiter.api.Test;

class CalcTest {
    @Test void shouldAddOne() {
        new Calc().run(5);
    }
}
