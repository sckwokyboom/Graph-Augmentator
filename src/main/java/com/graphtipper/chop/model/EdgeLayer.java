package com.graphtipper.chop.model;

import java.util.Locale;

public enum EdgeLayer {
    AST, CFG, CDG, DDG, CG, OVERRIDES, ARG_PASS, RETURN_BIND;

    public static EdgeLayer parse(String s) {
        try {
            return EdgeLayer.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown EdgeLayer: " + s
                + ". Valid: AST, CFG, CDG, DDG, CG, OVERRIDES, ARG_PASS, RETURN_BIND");
        }
    }
}
