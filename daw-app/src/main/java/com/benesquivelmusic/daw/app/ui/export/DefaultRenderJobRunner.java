package com.benesquivelmusic.daw.app.ui.export;

import com.benesquivelmusic.daw.sdk.export.JobControl;
import com.benesquivelmusic.daw.sdk.export.RenderJob;
import com.benesquivelmusic.daw.sdk.export.RenderJobRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Story 186 — Default {@link RenderJobRunner} used by the application's
 * singleton {@code RenderQueue}. Dispatches across the sealed
 * {@link RenderJob} hierarchy via an exhaustive {@code switch} so the
 * compiler enforces that every job type has a handler.
 *
 * <p>Each handler:</p>
 * <ol>
 *   <li>registers the job's {@code primaryOutput} for cleanup via
 *       {@link JobControl#registerCleanupPath(Path)} <em>before</em>
 *       creating the file, so cancellation always leaves the disk clean;</li>
 *   <li>publishes coarse progress events so the UI's per-job progress bars
 *       move predictably;</li>
 *   <li>cooperates with pause / cancel via
 *       {@link JobControl#publishProgress(String, double)} (which performs
 *       a checkpoint internally).</li>
 * </ol>
 *
 * <p><strong>Design note:</strong> the application currently ships
 * pluggable but stubbed-out per-type implementations. Each per-type case
 * is the single seam where the corresponding existing exporter
 * ({@code DefaultAudioExporter}, {@code StemExporter}, {@code AdmBwfExporter},
 * {@code BundleExportService}, etc.) will be wired in once the
 * "Add to queue" buttons in the export dialogs ship. The dispatcher and
 * queue infrastructure are fully functional today: jobs are dequeued,
 * progress is emitted, partial outputs are deleted on cancel, and the
 * queue is persisted across restarts.</p>
 *
 * <p>This class is public so {@code MainController} (in a sibling
 * package) can instantiate it. It is an application integration adapter,
 * not part of the SDK API.</p>
 */
public final class DefaultRenderJobRunner implements RenderJobRunner {

    /** Filesystem to use for all I/O — defaults to the real one. */
    private final FileSystemAdapter fs;

    public DefaultRenderJobRunner() {
        this(new FileSystemAdapter() { });
    }

    /**
     * Test seam: inject a different file system adapter so tests can
     * verify dispatch and progress-emission without touching real files.
     */
    public DefaultRenderJobRunner(FileSystemAdapter fs) {
        this.fs = Objects.requireNonNull(fs, "fs");
    }

    @Override
    public void run(RenderJob job, JobControl control) throws Exception {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(control, "control");
        // Exhaustive switch over the sealed RenderJob hierarchy — Java 21+
        // pattern matching guarantees a compile error if a new case is added.
        switch (job) {
            case RenderJob.StereoMasterJob master      -> renderStereoMaster(master, control);
            case RenderJob.StemBundleJob stems         -> renderStemBundle(stems, control);
            case RenderJob.AtmosBundleJob atmos        -> renderAtmosBundle(atmos, control);
            case RenderJob.DdpImageJob ddp             -> renderDdpImage(ddp, control);
            case RenderJob.BundleDeliverableJob bundle -> renderBundleDeliverable(bundle, control);
        }
    }

    // ── per-type handlers ───────────────────────────────────────────────

    private void renderStereoMaster(RenderJob.StereoMasterJob job, JobControl control) throws Exception {
        runWithStandardPhases(job.primaryOutput(), OutputShape.FILE, control,
                "Stereo master: " + job.displayName());
    }

    private void renderStemBundle(RenderJob.StemBundleJob job, JobControl control) throws Exception {
        // StemBundleJob.primaryOutput() is the directory that receives
        // one rendered file per StemSpec.
        runWithStandardPhases(job.primaryOutput(), OutputShape.DIRECTORY, control,
                "Stems: " + job.displayName() + " (" + job.stems().size() + ")");
    }

    private void renderAtmosBundle(RenderJob.AtmosBundleJob job, JobControl control) throws Exception {
        // AtmosBundleJob.primaryOutput() is a single ADM BWF file.
        runWithStandardPhases(job.primaryOutput(), OutputShape.FILE, control,
                "ADM BWF: " + job.displayName());
    }

    private void renderDdpImage(RenderJob.DdpImageJob job, JobControl control) throws Exception {
        // DDP images are directory-shaped (one folder with descriptor + data files).
        runWithStandardPhases(job.primaryOutput(), OutputShape.DIRECTORY, control,
                "DDP image: " + job.displayName());
    }

    private void renderBundleDeliverable(RenderJob.BundleDeliverableJob job, JobControl control) throws Exception {
        runWithStandardPhases(job.primaryOutput(), OutputShape.FILE, control,
                "Deliverable bundle: " + job.displayName());
    }

    /** Whether a job's primary output is a single file or a directory. */
    public enum OutputShape { FILE, DIRECTORY }

    // ── shared progress + write path ────────────────────────────────────

    /**
     * Common five-phase render skeleton: register cleanup → setup →
     * encode → finalize → done. Writes a placeholder marker file at
     * {@code primaryOutput} so a follow-on consumer can verify the job
     * touched disk. Each phase performs a cooperative checkpoint so
     * pause / cancel take effect promptly.
     */
    private void runWithStandardPhases(Path primaryOutput, OutputShape shape,
                                       JobControl control, String label) throws Exception {
        // Register the output for cleanup BEFORE we touch it, so cancelling
        // immediately after this call still tears down any partial write.
        control.registerCleanupPath(primaryOutput);

        control.publishProgress(label + " — preparing", 0.05);
        fs.createParentDirectories(primaryOutput);

        control.publishProgress(label + " — rendering", 0.30);
        // Coarse pacing so cancellation has multiple opportunities to fire
        // even though the placeholder write is essentially instantaneous.
        control.publishProgress(label + " — encoding", 0.60);

        fs.writePlaceholder(primaryOutput, shape, label);

        control.publishProgress(label + " — finalizing", 0.90);
        control.publishProgress(label + " — done", 1.00);
    }

    // ── test seam ───────────────────────────────────────────────────────

    /**
     * Indirection over filesystem operations so unit tests can verify
     * dispatch without writing real files.
     */
    public interface FileSystemAdapter {
        default void createParentDirectories(Path output) throws IOException {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }
        default void writePlaceholder(Path output, OutputShape shape, String label) throws IOException {
            if (shape == OutputShape.DIRECTORY) {
                Files.createDirectories(output);
            } else {
                Files.writeString(output, "render-queue placeholder: " + label + System.lineSeparator());
            }
        }
    }
}
