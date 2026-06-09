package com.graphtipper.render;

import com.graphtipper.chop.score.KatzScorer;
import com.graphtipper.slice.SnippetCoveragePruner;

public record RenderOptions(boolean bare, SnippetCoveragePruner pruner, KatzScorer scorer,
                             boolean noCurrentBody, boolean specMode) {

    public static RenderOptions defaults() {
        return new RenderOptions(false, null, null, false, false);
    }

    public RenderOptions withBare(boolean b) { return new RenderOptions(b, pruner, scorer, noCurrentBody, specMode); }
    public RenderOptions withPruner(SnippetCoveragePruner p) { return new RenderOptions(bare, p, scorer, noCurrentBody, specMode); }
    public RenderOptions withScorer(KatzScorer s) { return new RenderOptions(bare, pruner, s, noCurrentBody, specMode); }
    public RenderOptions withNoCurrentBody(boolean b) { return new RenderOptions(bare, pruner, scorer, b, specMode); }
    public RenderOptions withSpecMode(boolean b) { return new RenderOptions(bare, pruner, scorer, noCurrentBody, b); }
}
