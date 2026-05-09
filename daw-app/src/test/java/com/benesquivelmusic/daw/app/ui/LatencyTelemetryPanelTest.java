package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.fx.GpuPipeline;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the non-JavaFX helpers on {@link LatencyTelemetryPanel}
 * that can be exercised without a live toolkit.
 */
class LatencyTelemetryPanelTest {

    @Test
    void shouldFormatSessionPdcWithSamplesAndMillis() {
        // 2048 / 48000 * 1000 = 42.666… ms
        assertThat(LatencyTelemetryPanel.formatSessionPdc(2048, 48_000.0))
                .isEqualTo("PDC 2048 sp / 42.67 ms");
    }

    @Test
    void shouldRenderZeroPdcGracefully() {
        assertThat(LatencyTelemetryPanel.formatSessionPdc(0, 48_000.0))
                .isEqualTo("PDC 0 sp / 0.00 ms");
    }

    @Test
    void shouldFallBackWhenSampleRateIsNonPositive() {
        // Non-positive rates should not throw from a pure formatter — they
        // simply display 0.00 ms (the transport cannot resolve ms yet).
        assertThat(LatencyTelemetryPanel.formatSessionPdc(1024, 0.0))
                .isEqualTo("PDC 1024 sp / 0.00 ms");
    }

    @Test
    void shouldFormatRendererForEachPipeline() {
        assertThat(LatencyTelemetryPanel.formatRenderer(GpuPipeline.DIRECT3D))
                .isEqualTo("Direct3D 11 (GPU)");
        assertThat(LatencyTelemetryPanel.formatRenderer(GpuPipeline.METAL))
                .isEqualTo("Metal (GPU)");
        assertThat(LatencyTelemetryPanel.formatRenderer(GpuPipeline.OPEN_GL))
                .isEqualTo("OpenGL ES2 (GPU)");
        assertThat(LatencyTelemetryPanel.formatRenderer(GpuPipeline.SOFTWARE))
                .isEqualTo("Software");
        assertThat(LatencyTelemetryPanel.formatRenderer(GpuPipeline.UNKNOWN))
                .isEqualTo("Unknown");
    }
}
