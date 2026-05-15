package com.graphtipper.slice;

/**
 * A short human-readable requirement on the target derived from AST observations
 * about how its consumer uses it. Produced by {@link ImpliedRequirementTemplates}.
 */
public record ImpliedRequirement(String text) {}
