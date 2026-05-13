package tiny;

public final class Calc {
    private final Adder adder = new Adder();
    public int run(int x) {
        return adder.add(x, 1);
    }
}
