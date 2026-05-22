package com.graphtipper.chop.reach;

public final class MaxMethodsExceededException extends RuntimeException {
    public final int count;
    public MaxMethodsExceededException(int count) {
        super("Reachable methods exceeded limit (" + count + ")");
        this.count = count;
    }
}
