package com.product.piecesexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** CodeRabbit finding on PR4 (#45): a zero or negative cap would make every non-empty export fail
 * with a confusing 413 instead of surfacing a startup configuration error. */
class PiecesExportPropertiesTest {

    @Test
    void acceptsAPositiveCap() {
        assertThat(new PiecesExportProperties(1).maxUncompressedBytes()).isEqualTo(1);
    }

    @Test
    void rejectsAZeroOrNegativeCap() {
        assertThatThrownBy(() -> new PiecesExportProperties(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PiecesExportProperties(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
