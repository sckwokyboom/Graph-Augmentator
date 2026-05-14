package fixtures;

public class NestedBlocks {
    void runner(boolean cond) {
        int v = 7;
        if (cond) {
            use(v);
        }
    }
    void use(int n) {}
}
