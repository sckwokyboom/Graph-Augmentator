package com.graphtipper.chop.model;

import java.util.Objects;

public record MethodRef(String fqn, String signature) {
    public MethodRef {
        Objects.requireNonNull(fqn, "fqn");
        Objects.requireNonNull(signature, "signature");
    }
    public String display() { return fqn + "#" + signature; }
}
