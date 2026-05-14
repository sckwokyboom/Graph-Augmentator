package com.graphtipper.render;

/**
 * Stub. Real implementation lands in Tasks 12-15: emits vertices+edges+chains JSON
 * with deduplication and a schema-validated shape (see graph-schema.json).
 */
public final class GraphJsonRenderer {
    public String render(Artifact a, String projectKey, String projectName) {
        return "{\"schema_version\":\"1\",\"_stub\":true}\n";
    }
}
