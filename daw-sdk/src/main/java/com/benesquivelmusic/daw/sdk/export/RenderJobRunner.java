package com.benesquivelmusic.daw.sdk.export;

/**
 * Strategy that performs the actual offline render for a {@link RenderJob}.
 *
 * <p>Implementations are typically thin adapters over the existing export
 * services ({@code AudioExporter}, {@code StemExporter},
 * {@code BundleExportService}, etc.). They are responsible for:</p>
 *
 * <ol>
 *   <li>writing the deliverable files referenced by the job;</li>
 *   <li>publishing progress and observing pause / cancel via the
 *       supplied {@link JobControl};</li>
 *   <li>registering each output path with
 *       {@link JobControl#registerCleanupPath(java.nio.file.Path)} <em>before</em>
 *       creating the file so partial outputs can be removed on cancellation.</li>
 * </ol>
 *
 * <p>Runners are dispatched by the runtime type of the {@link RenderJob}
 * (typically via an exhaustive {@code switch} over the sealed hierarchy)
 * and run on a bounded-parallelism executor in the queue.</p>
 */
@FunctionalInterface
public interface RenderJobRunner {

    /**
     * Render the job, cooperating with {@code control} for progress, pause
     * and cancellation.
     *
     * @param job     the job to render
     * @param control the cooperation channel with the queue
     * @throws Exception if rendering fails or is cancelled
     */
    void run(RenderJob job, JobControl control) throws Exception;
}
