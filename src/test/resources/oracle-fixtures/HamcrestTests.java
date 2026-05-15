package oraclefix;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
class HamcrestTests {
    void testAssertTrue() { assertTrue(value() > 0); }
    void testAssertFalse() { assertFalse(value() < 0); }
    void testAssertNull() { assertNull(maybe()); }
    void testAssertNotNull() { assertNotNull(maybe()); }
    void testAssertThatContains() { assertThat(text(), containsString("hello")); }
    int value() { return 1; }
    Object maybe() { return null; }
    String text() { return "hello world"; }
}
