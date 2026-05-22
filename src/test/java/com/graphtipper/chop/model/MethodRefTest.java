package com.graphtipper.chop.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MethodRefTest {

    @Test
    void displayIncludesFqnAndSignature() {
        MethodRef m = new MethodRef("com.example.Foo", "bar:int(java.lang.String)");
        assertThat(m.display()).isEqualTo("com.example.Foo#bar:int(java.lang.String)");
    }

    @Test
    void equalityIsValueBased() {
        MethodRef a = new MethodRef("p.C", "m:void()");
        MethodRef b = new MethodRef("p.C", "m:void()");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void statementIdFingerprintIsStable() {
        MethodRef m = new MethodRef("p.C", "m:void()");
        StatementId s1 = new StatementId(m, 42);
        StatementId s2 = new StatementId(m, 42);
        assertThat(s1).isEqualTo(s2).hasSameHashCodeAs(s2);
    }
}
