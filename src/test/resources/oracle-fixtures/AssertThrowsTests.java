package oraclefix;
import static org.junit.jupiter.api.Assertions.*;
class AssertThrowsTests {
    void testThrowsLambda() {
        assertThrows(IllegalArgumentException.class, () -> foo(-1));
    }
    void testThrowsExecutable() {
        assertThrows(RuntimeException.class, this::bar);
    }
    void foo(int x) { if (x < 0) throw new IllegalArgumentException("neg"); }
    void bar() { throw new RuntimeException(); }
}
