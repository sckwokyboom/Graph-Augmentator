package com.graphtipper.chop.model;

import java.util.Objects;

public record SourceRange(String filePath, int startLine, int startCol, int endLine, int endCol) {
    public SourceRange { Objects.requireNonNull(filePath, "filePath"); }
    public String display() {
        return filePath + ":" + startLine + ":" + startCol + "-" + endLine + ":" + endCol;
    }
}
