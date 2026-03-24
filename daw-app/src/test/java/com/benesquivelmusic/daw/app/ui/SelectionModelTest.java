package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class SelectionModelTest {

    @Test
    void shouldStartWithNoSelection() {
        var model = new SelectionModel();
        assertThat(model.hasSelection()).isFalse();
    }

    @Test
    void setSelectionShouldActivateSelection() {
        var model = new SelectionModel();
        model.setSelection(1.0, 5.0);
        assertThat(model.hasSelection()).isTrue();
        assertThat(model.getStartBeat()).isCloseTo(1.0, offset(0.001));
        assertThat(model.getEndBeat()).isCloseTo(5.0, offset(0.001));
    }

    @Test
    void clearSelectionShouldDeactivateSelection() {
        var model = new SelectionModel();
        model.setSelection(1.0, 5.0);
        model.clearSelection();
        assertThat(model.hasSelection()).isFalse();
    }

    @Test
    void setSelectionShouldRejectStartEqualToEnd() {
        var model = new SelectionModel();
        assertThatThrownBy(() -> model.setSelection(3.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setSelectionShouldRejectStartGreaterThanEnd() {
        var model = new SelectionModel();
        assertThatThrownBy(() -> model.setSelection(5.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearOnNoSelectionShouldBeNoOp() {
        var model = new SelectionModel();
        model.clearSelection();
        assertThat(model.hasSelection()).isFalse();
    }

    @Test
    void setSelectionMultipleTimesShouldUpdateRange() {
        var model = new SelectionModel();
        model.setSelection(1.0, 5.0);
        model.setSelection(2.0, 8.0);
        assertThat(model.hasSelection()).isTrue();
        assertThat(model.getStartBeat()).isCloseTo(2.0, offset(0.001));
        assertThat(model.getEndBeat()).isCloseTo(8.0, offset(0.001));
    }

    @Test
    void defaultBeatsShouldBeZero() {
        var model = new SelectionModel();
        assertThat(model.getStartBeat()).isCloseTo(0.0, offset(0.001));
        assertThat(model.getEndBeat()).isCloseTo(0.0, offset(0.001));
    }
}
