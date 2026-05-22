package slicefix;
public class SimpleChain {
    void test1() { entry("hello"); }
    void entry(String s) { target(s); }
    void target(String t) {}
}
