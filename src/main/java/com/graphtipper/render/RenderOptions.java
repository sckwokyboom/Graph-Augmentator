package com.graphtipper.render;

import com.graphtipper.chop.score.KatzScorer;
import com.graphtipper.slice.SnippetCoveragePruner;

public record RenderOptions(boolean bare, SnippetCoveragePruner pruner, KatzScorer scorer) {

    public static RenderOptions defaults() {
        return new RenderOptions(false, null, null);
    }

    public RenderOptions withBare(boolean b) { return new RenderOptions(b, pruner, scorer); }
    public RenderOptions withPruner(SnippetCoveragePruner p) { return new RenderOptions(bare, p, scorer); }
    public RenderOptions withScorer(KatzScorer s) { return new RenderOptions(bare, pruner, s); }
}
