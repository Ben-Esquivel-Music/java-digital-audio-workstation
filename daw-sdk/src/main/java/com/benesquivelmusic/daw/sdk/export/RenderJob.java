package com.benesquivelmusic.daw.sdk.export;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Specification for a single batch-render job that can be enqueued in the
 * offline render queue. Implementations are immutable, transparent data
 * carriers (records) that describe <em>what</em> to render, never <em>how</em>.
 *
 * <p>The render queue treats every job uniformly: it dequeues the next job,
 * delegates execution to a runner registered for the concrete subtype,
 * publishes {@link JobProgress} events as the job progresses, and writes
 * the final deliverables to disk.</p>
 *
 * <p>Permitted subtypes match the deliverable shapes produced by the DAW's
 * export pipeline:</p>
 * <ul>
 *   <li>{@link StereoMasterJob} — a single stereo master file</li>
 *   <li>{@link StemBundleJob} — one rendered file per logical stem</li>
 *   <li>{@link AtmosBundleJob} — Dolby Atmos ADM-BWF master + binaural ref</li>
 *   <li>{@link DdpImageJob} — Red Book DDP image for CD replication</li>
 *   <li>{@link BundleDeliverableJob} — the single-click deliverable bundle
 *       (master + stems + metadata + optional track sheet) zipped together</li>
 * </ul>
 *
 * <p>Each job carries a unique {@link #jobId()} (used to address it for
 * cancel / pause / resume / reorder operations) and a human-readable
 * {@link #displayName()} for the UI.</p>
 */
public sealed interface RenderJob
        permits RenderJob.StereoMasterJob,
                RenderJob.StemBundleJob,
                RenderJob.AtmosBundleJob,
                RenderJob.DdpImageJob,
                RenderJob.BundleDeliverableJob {

    /** Unique identifier for this job (used to address it in queue operations). */
    String jobId();

    /** Human-readable display name for the queue UI. */
    String displayName();

    /** Primary output file or directory this job will produce. */
    Path primaryOutput();

    /**
     * A single stereo master render (e.g., 24-bit/44.1 kHz WAV).
     */
    record StereoMasterJob(
            String jobId,
            String displayName,
            Path primaryOutput,
            AudioExportConfig config
    ) implements RenderJob {
        public StereoMasterJob {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(primaryOutput, "primaryOutput");
            Objects.requireNonNull(config, "config");
        }

        public static StereoMasterJob of(String displayName, Path output, AudioExportConfig config) {
            return new StereoMasterJob(UUID.randomUUID().toString(), displayName, output, config);
        }
    }

    /**
     * A bundle of stems — one rendered file per {@link StemSpec}, written
     * into the directory referenced by {@link #primaryOutput()}.
     */
    record StemBundleJob(
            String jobId,
            String displayName,
            Path primaryOutput,
            List<StemSpec> stems
    ) implements RenderJob {
        public StemBundleJob {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(primaryOutput, "primaryOutput");
            Objects.requireNonNull(stems, "stems");
            if (stems.isEmpty()) {
                throw new IllegalArgumentException("StemBundleJob requires at least one stem");
            }
            stems = List.copyOf(stems);
        }

        public static StemBundleJob of(String displayName, Path output, List<StemSpec> stems) {
            return new StemBundleJob(UUID.randomUUID().toString(), displayName, output, stems);
        }
    }

    /**
     * A Dolby Atmos ADM-BWF master render, optionally accompanied by a
     * binaural reference.
     */
    record AtmosBundleJob(
            String jobId,
            String displayName,
            Path primaryOutput
    ) implements RenderJob {
        public AtmosBundleJob {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(primaryOutput, "primaryOutput");
        }

        public static AtmosBundleJob of(String displayName, Path output) {
            return new AtmosBundleJob(UUID.randomUUID().toString(), displayName, output);
        }
    }

    /**
     * A DDP (Disc Description Protocol) image for Red Book CD replication.
     */
    record DdpImageJob(
            String jobId,
            String displayName,
            Path primaryOutput
    ) implements RenderJob {
        public DdpImageJob {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(primaryOutput, "primaryOutput");
        }

        public static DdpImageJob of(String displayName, Path output) {
            return new DdpImageJob(UUID.randomUUID().toString(), displayName, output);
        }
    }

    /**
     * A complete single-click deliverable bundle — master + stems +
     * metadata + optional track sheet — zipped into a single archive.
     */
    record BundleDeliverableJob(
            String jobId,
            String displayName,
            DeliverableBundle bundle
    ) implements RenderJob {
        public BundleDeliverableJob {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(bundle, "bundle");
        }

        @Override
        public Path primaryOutput() {
            return bundle.zipOutput();
        }

        public static BundleDeliverableJob of(String displayName, DeliverableBundle bundle) {
            return new BundleDeliverableJob(UUID.randomUUID().toString(), displayName, bundle);
        }
    }
}
