package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StaticSlicerTest {

    @Test
    void unresolvedReason_covers_all_documented_categories() {
        // Spec §5.1: 12 reason categories.
        assertThat(UnresolvedReason.values())
                .containsExactlyInAnyOrder(
                        UnresolvedReason.FIELD_READ,
                        UnresolvedReason.METHOD_CALL,
                        UnresolvedReason.REFLECTION,
                        UnresolvedReason.BRANCH_EXPLOSION,
                        UnresolvedReason.DEPTH_LIMIT,
                        UnresolvedReason.PARSE_ERROR,
                        UnresolvedReason.NOT_FOUND,
                        UnresolvedReason.ENTRY_POINT_REACHED,
                        UnresolvedReason.COMPLEX_EXPR,
                        UnresolvedReason.CYCLE,
                        UnresolvedReason.FILE_TOO_LARGE,
                        UnresolvedReason.UNSUPPORTED);
    }
}
