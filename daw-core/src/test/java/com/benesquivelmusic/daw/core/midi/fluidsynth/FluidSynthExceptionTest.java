package com.benesquivelmusic.daw.core.midi.fluidsynth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FluidSynthExceptionTest {

    @Test
    void shouldCreateWithMessageAndErrorCode() {
        var ex = new FluidSynthException("test failure", -1);
        assertThat(ex.getMessage()).contains("test failure");
        assertThat(ex.getMessage()).contains("-1");
        assertThat(ex.getErrorCode()).isEqualTo(-1);
    }

    @Test
    void shouldCreateWithCause() {
        var cause = new RuntimeException("root");
        var ex = new FluidSynthException("test failure", -1, cause);
        assertThat(ex.getMessage()).contains("test failure");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getErrorCode()).isEqualTo(-1);
    }

    @Test
    void shouldBeSoundFontRendererException() {
        assertThat(new FluidSynthException("test", -1))
                .isInstanceOf(com.benesquivelmusic.daw.sdk.midi.SoundFontRendererException.class);
    }

    @Test
    void shouldCheckResultAndPassOnSuccess() {
        // Should not throw for FLUID_OK
        FluidSynthException.checkResult(FluidSynthBindings.FLUID_OK, "test operation");
    }

    @Test
    void shouldCheckResultAndThrowOnFailure() {
        assertThatThrownBy(() ->
                FluidSynthException.checkResult(FluidSynthBindings.FLUID_FAILED, "test operation"))
                .isInstanceOf(FluidSynthException.class)
                .hasMessageContaining("test operation");
    }

    @Test
    void shouldCheckResultAndPassOnPositiveId() {
        // SoundFont IDs are positive integers — should not throw
        FluidSynthException.checkResult(1, "fluid_synth_sfload");
        FluidSynthException.checkResult(42, "fluid_synth_sfload");
    }
}
