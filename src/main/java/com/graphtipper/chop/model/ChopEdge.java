package com.graphtipper.chop.model;

import java.util.Objects;
import java.util.Set;

public record ChopEdge(
    ChopNode src,
    ChopNode dst,
    EdgeLayer layer,
    ResolutionKind resolution,
    DataKind dataKind,
    String label,
    Set<StatementId> touchedBy
) {
    public ChopEdge {
        Objects.requireNonNull(src);
        Objects.requireNonNull(dst);
        Objects.requireNonNull(layer);
        Objects.requireNonNull(touchedBy);
    }
}
