package com.graphtipper.util;

import java.util.ArrayList;
import java.util.List;

public final class TokenBudget {
    private final int max;
    private int used = 0;
    private final List<String> evicted = new ArrayList<>();

    public TokenBudget(int max) { this.max = max; }

    public int estimate(String text) {
        return (text.length() + 3) / 4;
    }

    public boolean tryAdd(String text) {
        int cost = estimate(text);
        if (used + cost > max) return false;
        used += cost;
        return true;
    }

    /** Unconditionally charges {@code tokens} against the budget (for pre-measured costs). */
    public void charge(int tokens) { used += tokens; }

    public void recordEviction(String section) { evicted.add(section); }

    public int used() { return used; }
    public int max() { return max; }
    public int remaining() { return max - used; }
    public List<String> evicted() { return List.copyOf(evicted); }
}
