package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

/**
 * A test that calls the target directly (chain depth = 1).
 * Surfaces in artifact §4.3 as a short Tier-A table plus snippet.
 */
public record DirectTest(
        Node.Method testMethod,
        List<ArgOrigin> args,
        Oracle oracle,
        String snippet
) {
    public DirectTest {
        args = List.copyOf(args);
    }
}
