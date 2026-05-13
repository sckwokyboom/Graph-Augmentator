package com.graphtipper.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetTest {
    @Test
    void approximatesByFourCharsPerToken() {
        var b = new TokenBudget(10);
        assertThat(b.estimate("abcdefgh")).isEqualTo(2);  // 8/4
        assertThat(b.estimate("ab")).isEqualTo(1);        // ceil
    }

    @Test
    void tryAddSucceedsWhenFits() {
        var b = new TokenBudget(10);
        assertThat(b.tryAdd("x".repeat(20))).isTrue();   // 20/4 = 5
        assertThat(b.used()).isEqualTo(5);
        assertThat(b.remaining()).isEqualTo(5);
    }

    @Test
    void tryAddFailsAndDoesNotConsumeWhenOver() {
        var b = new TokenBudget(5);
        b.tryAdd("x".repeat(16));   // 4 tokens
        assertThat(b.tryAdd("x".repeat(8))).isFalse();   // 2 tokens, would exceed
        assertThat(b.used()).isEqualTo(4);
    }

    @Test
    void recordsEvictedSections() {
        var b = new TokenBudget(100);
        b.recordEviction("production-call-sites");
        b.recordEviction("used-types-bodies");
        assertThat(b.evicted()).containsExactly("production-call-sites", "used-types-bodies");
    }
}
