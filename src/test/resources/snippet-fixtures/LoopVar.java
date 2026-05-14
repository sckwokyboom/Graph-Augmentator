package fixtures;

public class LoopVar {
    void runner() {
        int[] arr = new int[]{1, 2, 3};
        for (int col = 0; col < arr.length; col++) {
            visit(col);
        }
    }
    void visit(int x) {}
}
