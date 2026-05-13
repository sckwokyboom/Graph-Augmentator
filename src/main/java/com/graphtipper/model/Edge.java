package com.graphtipper.model;

public sealed interface Edge permits
        Edge.Calls, Edge.AstContains, Edge.Ddg, Edge.Cdg,
        Edge.RefType, Edge.Overrides, Edge.Reads, Edge.Writes {

    String fromId();
    String toId();

    record Calls(String fromId, String toId, boolean viaVirtual) implements Edge {}
    record AstContains(String fromId, String toId) implements Edge {}
    record Ddg(String fromId, String toId) implements Edge {}
    record Cdg(String fromId, String toId) implements Edge {}
    record RefType(String fromId, String toId) implements Edge {}
    record Overrides(String fromId, String toId) implements Edge {}
    record Reads(String fromId, String toId) implements Edge {}
    record Writes(String fromId, String toId) implements Edge {}
}
