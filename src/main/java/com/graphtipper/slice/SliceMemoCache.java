package com.graphtipper.slice;

import java.util.HashMap;
import java.util.Map;

/**
 * Memoization cache for {@link StaticSlicer} keyed by
 * {@code (methodFqn, varName, callChainSignature)} (joined into a single string by the slicer).
 * Cache scope is one cluster-enrichment session; cleared between clusters.
 *
 * <p>Not thread-safe; slicer runs single-threaded per cluster.
 */
public final class SliceMemoCache {
    private final Map<String, SliceResult> cache = new HashMap<>();

    public SliceResult get(String key) { return cache.get(key); }
    public void put(String key, SliceResult value) { cache.put(key, value); }
    public void clear() { cache.clear(); }
    public int size() { return cache.size(); }
}
