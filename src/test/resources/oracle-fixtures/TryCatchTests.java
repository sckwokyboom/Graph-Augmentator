package oraclefix;
import static org.junit.jupiter.api.Assertions.*;
class TryCatchTests {
    void testTryCatchExactMessage() {
        try { foo(-1); fail(); }
        catch (IllegalArgumentException e) {
            assertEquals("neg value: -1", e.getMessage());
        }
    }
    void testTryCatchContainsMessage() {
        try { foo(-2); fail(); }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("neg"));
        }
    }
    void foo(int x) { if (x < 0) throw new IllegalArgumentException("neg value: " + x); }
}
