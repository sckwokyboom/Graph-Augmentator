package fixtures;

public class LiteralOnly {
    void caller() {
        process(0, "x", null);
    }
    void process(int a, String b, Object c) {}
}
