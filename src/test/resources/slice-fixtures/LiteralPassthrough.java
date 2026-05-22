package slicefix;
class LiteralPassthrough {
    void target(String s) {}
    void caller() { target("hello"); }
}
