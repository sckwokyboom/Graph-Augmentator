package com.graphtipper.slice;

import java.util.List;

public record ChainResult(List<Chain> chains, boolean truncated) {}
