package fixtures;

public class TruncationLimit {
    void runner() {
        int a = 1;
        int b = a + 1;
        int c = b + 1;
        int d = c + 1;
        int e = d + 1;
        use(e);
    }
    void use(int x) {}
}
