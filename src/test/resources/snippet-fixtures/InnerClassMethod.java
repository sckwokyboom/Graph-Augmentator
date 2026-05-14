package fixtures;

public class InnerClassMethod {
    static class Outer {
        static class Inner {
            void target(int x) {
                helper(x);
            }
            void helper(int n) {}
        }
    }
}
