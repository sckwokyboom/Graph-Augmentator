package consumerfix;

class OverloadedConsumer {
    static class Cell { int row; int column; }

    Cell target(int r, int c, String v) { return new Cell(); }

    // Overload 1: NO call to target. Just delegates to overload 2.
    void addRowValues(String... values) {
        addRowValues(values, 0);
    }

    // Overload 2: ACTUALLY calls target. This is the real immediate consumer.
    void addRowValues(String[] values, int rowSeed) {
        for (int col = 0; col < values.length; col++) {
            Cell cell = target(rowSeed, col, values[col]);
            if (cell.row != rowSeed) {
                System.out.println("wrapped");
            }
        }
    }
}
