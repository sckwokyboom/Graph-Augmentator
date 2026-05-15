package oraclefix;
import static org.junit.jupiter.api.Assertions.*;
class AssertEqualsTests {
    void testReturnEquals() {
        int x = foo();
        assertEquals(42, x);
    }
    void testStringEquals() {
        String s = greet();
        assertEquals("hello", s);
    }
    void testReversedArgOrder() {
        assertEquals(compute(), 100); // older JUnit/TestNG style
    }
    int foo() { return 42; }
    String greet() { return "hello"; }
    int compute() { return 100; }
}
