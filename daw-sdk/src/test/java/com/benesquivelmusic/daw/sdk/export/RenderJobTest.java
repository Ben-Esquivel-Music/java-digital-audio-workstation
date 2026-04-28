package com.benesquivelmusic.daw.sdk.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderJobTest {

    private static AudioExportConfig wav() {
        return new AudioExportConfig(AudioExportFormat.WAV, 44100, 24, DitherType.NONE);
    }

    @Test
    void stereoMasterJobFactoryAssignsRandomId() {
        var job = RenderJob.StereoMasterJob.of("Master", Path.of("/tmp/m.wav"), wav());
        assertThat(job.jobId()).isNotBlank();
        assertThat(job.displayName()).isEqualTo("Master");
        assertThat(job.primaryOutput()).isEqualTo(Path.of("/tmp/m.wav"));
    }

    @Test
    void stemBundleJobRequiresStems() {
        assertThatThrownBy(() -> new RenderJob.StemBundleJob(
                "id", "name", Path.of("/tmp"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renderJobIsSealedAndExhaustive() {
        // Compile-time exhaustiveness check via switch over sealed type.
        RenderJob job = RenderJob.AtmosBundleJob.of("Atmos", Path.of("/tmp/atmos.adm"));
        String label = switch (job) {
            case RenderJob.StereoMasterJob s -> "stereo";
            case RenderJob.StemBundleJob s -> "stems";
            case RenderJob.AtmosBundleJob s -> "atmos";
            case RenderJob.DdpImageJob s -> "ddp";
            case RenderJob.BundleDeliverableJob s -> "bundle";
        };
        assertThat(label).isEqualTo("atmos");
    }

    @Test
    void bundleDeliverableJobUsesBundleZipPath() {
        var bundle = new DeliverableBundle(
                Path.of("/tmp/album.zip"),
                null,
                List.of(new StemSpec(0, "drums", wav())),
                BundleMetadata.template("test", "engineer", 120.0, "C", 44100, 24),
                false);
        var job = RenderJob.BundleDeliverableJob.of("Album", bundle);
        assertThat(job.primaryOutput()).isEqualTo(Path.of("/tmp/album.zip"));
    }
}
