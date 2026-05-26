package com.graphtipper.render;

import com.graphtipper.chop.score.KatzScorer;
import com.graphtipper.slice.SnippetCoveragePruner;

public record RenderOptions(boolean bare, SnippetCoveragePruner pruner, KatzScorer scorer,
                             boolean noCurrentBody) {

    public static RenderOptions defaults() {
        return new RenderOptions(false, null, null, false);
    }

    public RenderOptions withBare(boolean b) { return new RenderOptions(b, pruner, scorer, noCurrentBody); }
    public RenderOptions withPruner(SnippetCoveragePruner p) { return new RenderOptions(bare, p, scorer, noCurrentBody); }
    public RenderOptions withScorer(KatzScorer s) { return new RenderOptions(bare, pruner, s, noCurrentBody); }
    public RenderOptions withNoCurrentBody(boolean b) { return new RenderOptions(bare, pruner, scorer, b); }
}
