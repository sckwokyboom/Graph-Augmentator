package consumerfix;

class MultiCallConsumer {
    static class Cell { int row; int column; }

    Cell target(int r, int c) { return new Cell(); }

    void useAssignAndFieldRead() {
        Cell cell = target(0, 0);
        int x = cell.row;
    }

    void useInCondition() {
        Cell cell = target(0, 0);
        if (cell.row != 0) {
            System.out.println("changed");
        }
    }

    Cell useReturnedUnchanged() {
        return target(0, 0);
    }

    void useDiscarded() {
        target(0, 0);
    }

    void usePassedAsArg() {
        process(target(0, 0));
    }

    void process(Cell c) {}
}
