package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.Chain;
import com.graphtipper.slice.LocalContext;
import java.util.List;

public record Artifact(
        Node.Method target,
        String currentBody,
        List<Chain> chains,
        boolean truncated,
        LocalContext localContext
) {}
