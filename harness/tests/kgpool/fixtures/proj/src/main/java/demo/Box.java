package demo;

public class Box {
    public static class Inner {
        public Result put(int idx, Payload p, String tag) {
            return compute(idx, p);
        }
        Result compute(int idx, Payload p) { return new Result(idx); }
    }
}
