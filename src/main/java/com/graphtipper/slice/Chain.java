package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

public record Chain(
        Node.Method test,
        List<CallStep> steps,
        int virtualSteps
) {
    public int depth() { return steps.size(); }
}
