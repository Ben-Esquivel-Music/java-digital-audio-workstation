package com.benesquivelmusic.daw.fx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class GpuPipelineTest {

    @Test
    void detectReturnsNonNull() {
        GpuPipeline pipeline = GpuPipeline.detect();

        assertThat(pipeline).isNotNull();
    }

    @Test
    void hardwareEnumValuesAreFlaggedAccelerated() {
        assertThat(GpuPipeline.DIRECT3D.isHardwareAccelerated()).isTrue();
        assertThat(GpuPipeline.METAL.isHardwareAccelerated()).isTrue();
        assertThat(GpuPipeline.OPEN_GL.isHardwareAccelerated()).isTrue();
        assertThat(GpuPipeline.SOFTWARE.isHardwareAccelerated()).isFalse();
        assertThat(GpuPipeline.UNKNOWN.isHardwareAccelerated()).isFalse();
    }

    @Test
    void detectDoesNotThrow() {
        // Prism only commits to a pipeline when something forces rasterisation
        // (typically when a Stage is shown). In the headless surefire JVM that
        // hasn't happened, so detect() may legitimately return UNKNOWN. The
        // contract we *can* verify: it never throws and always yields a
        // recognised enum value.
        GpuPipeline pipeline = GpuPipeline.detect();

        assertThat(pipeline).isIn(
                GpuPipeline.DIRECT3D,
                GpuPipeline.METAL,
                GpuPipeline.OPEN_GL,
                GpuPipeline.SOFTWARE,
                GpuPipeline.UNKNOWN);
    }
}
