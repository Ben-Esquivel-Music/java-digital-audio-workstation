package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class EditToolTest {

    @Test
    void shouldHaveFiveTools() {
        assertThat(EditTool.values()).hasSize(5);
    }

    @Test
    void shouldContainAllToolsInOrder() {
        assertThat(EditTool.values())
                .containsExactly(
                        EditTool.POINTER,
                        EditTool.PENCIL,
                        EditTool.ERASER,
                        EditTool.SCISSORS,
                        EditTool.GLUE);
    }

    @ParameterizedTest
    @EnumSource(EditTool.class)
    void valueOfShouldRoundTrip(EditTool tool) {
        assertThat(EditTool.valueOf(tool.name())).isEqualTo(tool);
    }

    @Test
    void pointerShouldBeFirstValue() {
        assertThat(EditTool.values()[0]).isEqualTo(EditTool.POINTER);
    }

    @Test
    void pointerShouldBeDefaultTool() {
        // POINTER is first in the enum and documented as the default
        assertThat(EditTool.POINTER.ordinal()).isZero();
    }
}
