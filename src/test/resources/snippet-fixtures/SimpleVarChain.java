package fixtures;

public class SimpleVarChain {
    public void runChain() {
        int n = 42;
        String name = "test-" + n;
        process(name, n);
    }
    public void process(String s, int x) {}
}
