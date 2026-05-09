package com.benesquivelmusic.daw.app.ui.export;

import com.benesquivelmusic.daw.core.export.RenderQueue;
import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.DeliverableBundle;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.export.JobProgress;
import com.benesquivelmusic.daw.sdk.export.RenderJob;
import com.benesquivelmusic.daw.sdk.export.StemSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 186 — verifies the default render-job runner used by the
 * application's singleton {@link RenderQueue} dispatches across every
 * permitted {@link RenderJob} subtype, writes its primary output, and
 * publishes terminal {@code COMPLETED} events.
 *
 * <p>Headless: no JavaFX required.</p>
 */
class DefaultRenderJobRunnerTest {

    private static final AudioExportConfig CFG = new AudioExportConfig(
            AudioExportFormat.WAV, 44100, 24, DitherType.NONE);

    @Test
    void dispatchesAcrossEverySealedJobSubtypeAndCompletes(@TempDir Path tmp) throws Exception {
        try (var queue = new RenderQueue(new DefaultRenderJobRunner(), 1)) {
            queue.setPersistencePath(tmp.resolve("queue.json"));

            RenderJob master = RenderJob.StereoMasterJob.of(
                    "Master", tmp.resolve("master.wav"), CFG);
            RenderJob stems = RenderJob.StemBundleJob.of(
                    "Stems", tmp.resolve("stems"),
                    List.of(new StemSpec(0, "kick", CFG)));
            RenderJob atmos = RenderJob.AtmosBundleJob.of(
                    "Atmos", tmp.resolve("master.bwf"));
            RenderJob ddp = RenderJob.DdpImageJob.of(
                    "DDP", tmp.resolve("ddp"));
            RenderJob bundle = RenderJob.BundleDeliverableJob.of(
                    "Bundle",
                    new DeliverableBundle(
                            tmp.resolve("bundle.zip"),
                            new com.benesquivelmusic.daw.sdk.export.MasterFormat(CFG, "master"),
                            List.of(new StemSpec(0, "kick", CFG)),
                            com.benesquivelmusic.daw.sdk.export.BundleMetadata.template(
                                    "Title", "Engineer", 120.0, "Cmaj", 44100, 24),
                            false));

            for (var j : List.of(master, stems, atmos, ddp, bundle)) {
                queue.enqueue(j);
            }

            assertThat(queue.awaitQuiescence(10, TimeUnit.SECONDS)).isTrue();

            // Every job reports COMPLETED.
            assertThat(queue.snapshot())
                    .extracting(RenderQueue.JobSnapshot::phase)
                    .containsOnly(JobProgress.Phase.COMPLETED);

            // Each primary output exists on disk (file or directory).
            assertThat(Files.exists(master.primaryOutput())).isTrue();
            assertThat(Files.isDirectory(stems.primaryOutput())).isTrue();
            assertThat(Files.exists(atmos.primaryOutput())).isTrue();
            assertThat(Files.isDirectory(ddp.primaryOutput())).isTrue();
            assertThat(Files.exists(bundle.primaryOutput())).isTrue();
        }
    }
}
