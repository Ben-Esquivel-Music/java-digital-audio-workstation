package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.analysis.LoudnessMeter;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.concurrent.DawTask;
import com.benesquivelmusic.daw.core.concurrent.DawTaskRunner;
import com.benesquivelmusic.daw.core.concurrent.TaskCategory;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.BundleMetadata;
import com.benesquivelmusic.daw.sdk.export.DeliverableBundle;
import com.benesquivelmusic.daw.sdk.export.ExportProgressListener;
import com.benesquivelmusic.daw.sdk.export.ExportResult;
import com.benesquivelmusic.daw.sdk.export.MasterFormat;
import com.benesquivelmusic.daw.sdk.export.StemMetadata;
import com.benesquivelmusic.daw.sdk.export.StemSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Single-click deliverable bundle export — orchestrates a master render,
 * stem renders, metadata JSON build, optional track-sheet PDF, and final
 * zip assembly. Mirrors the "Mixdown to Stems" / "Bounce Mix" feature in
 * Studio One, Pro Tools, and Cubase.
 *
 * <p>Pipeline (per {@link DeliverableBundle}):</p>
 * <ol>
 *   <li>For each {@link StemSpec}, bounce the track and apply its mixer
 *       channel (volume, pan, insert effects) — same behaviour as
 *       {@link StemExporter}. Capture the post-channel buffer.</li>
 *   <li>Sum the post-channel stem buffers into the master buffer; apply
 *       the project's master mixer-channel volume/pan.</li>
 *   <li>If a {@link MasterFormat} is configured, encode the master buffer
 *       to the staging directory using {@link DefaultAudioExporter}.</li>
 *   <li>Encode each stem buffer to the staging directory.</li>
 *   <li>Measure peak / RMS / integrated LUFS for each stem and the master
 *       (matches the live {@link LoudnessMeter} within ≤ 0.2 LUFS for
 *       comparable signals).</li>
 *   <li>Build the final {@link BundleMetadata} (with measurements and
 *       per-stem descriptors) and serialise it as {@code metadata.json}.</li>
 *   <li>If {@link DeliverableBundle#includeTrackSheet()} is {@code true},
 *       write a single-page text PDF via {@link SimplePdfWriter}.</li>
 *   <li>Stream all artefacts into the output ZIP archive (deflate
 *       compression for stems/master/metadata, store for already-compressed
 *       FLAC content). Per ISO 21320-1 / ZIP appnote.</li>
 * </ol>
 *
 * <p>The synchronous {@link #export(DawProject, double, DeliverableBundle,
 * ExportProgressListener)} method runs the full pipeline on the calling
 * thread. The async {@link #exportAsync(DawProject, double, DeliverableBundle,
 * ExportProgressListener)} method submits the work to a virtual-thread
 * executor (story 205): each bundle export gets its own virtual thread, so
 * a UI thread that submits a bundle is never blocked.</p>
 */
public final class BundleExportService {

    private final DefaultAudioExporter exporter;

    /**
     * Creates a bundle export service backed by the default audio exporter.
     */
    public BundleExportService() {
        this.exporter = new DefaultAudioExporter();
    }

    /**
     * Creates a bundle export service with a custom audio exporter (testing).
     *
     * @param exporter the audio exporter to delegate to
     */
    BundleExportService(DefaultAudioExporter exporter) {
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
    }

    /**
     * Performs a synchronous bundle export.
     *
     * @param project           the DAW project
     * @param totalProjectBeats the total project length in beats (≥ longest clip)
     * @param bundle            the deliverable bundle specification
     * @param listener          progress listener (0.0 – 1.0)
     * @return the bundle export result
     * @throws IOException if any step fails
     */
    public BundleExportResult export(
            DawProject project,
            double totalProjectBeats,
            DeliverableBundle bundle,
            ExportProgressListener listener) throws IOException {

        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(bundle, "bundle must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (totalProjectBeats <= 0) {
            throw new IllegalArgumentException(
                    "totalProjectBeats must be positive: " + totalProjectBeats);
        }

        long startMs = System.currentTimeMillis();

        AudioFormat fmt = project.getFormat();
        int sampleRate = (int) fmt.sampleRate();
        int channels = fmt.channels();
        double tempo = project.getTransport().getTempo();
        int totalFrames = TrackBouncer.beatsToFrames(totalProjectBeats, sampleRate, tempo);

        List<Track> allTracks = project.getTracks();
        List<StemSpec> stemSpecs = bundle.stems();
        for (StemSpec spec : stemSpecs) {
            if (spec.trackIndex() >= allTracks.size()) {
                throw new IllegalArgumentException(
                        "track index out of range: " + spec.trackIndex());
            }
        }

        Path stagingDir = Files.createTempDirectory("daw-bundle-");
        try {
            listener.onProgress(0.0, "Preparing bundle");

            // ── Step 1: Render stems (post-channel buffers) ───────────────
            List<StemRender> stemRenders = new ArrayList<>();
            float[][] masterMix = new float[channels][totalFrames];

            int totalSteps = stemSpecs.size()
                    + (bundle.master() != null ? 1 : 0)
                    + 2 /* metadata + zip */
                    + (bundle.includeTrackSheet() ? 1 : 0);
            int stepIndex = 0;

            for (int i = 0; i < stemSpecs.size(); i++) {
                StemSpec spec = stemSpecs.get(i);
                Track track = allTracks.get(spec.trackIndex());
                listener.onProgress(progress(stepIndex, totalSteps),
                        "Rendering stem " + (i + 1) + "/" + stemSpecs.size()
                                + ": " + spec.stemName());

                float[][] stemBuffer = renderTrack(project, track, sampleRate, tempo,
                        channels, totalFrames);

                // Sum into master mix BEFORE encoding (master = sum of post-channel stems)
                for (int ch = 0; ch < channels; ch++) {
                    for (int f = 0; f < totalFrames; f++) {
                        masterMix[ch][f] += stemBuffer[ch][f];
                    }
                }

                // Encode the stem to the staging dir
                String fileName = sanitize(spec.stemName());
                ExportResult result = exporter.export(stemBuffer, sampleRate, stagingDir,
                        fileName, spec.audioConfig(), ExportProgressListener.NONE);
                if (!result.success()) {
                    throw new IOException("Failed to render stem '"
                            + spec.stemName() + "': " + result.message());
                }
                stemRenders.add(new StemRender(spec, stemBuffer, result.outputPath()));
                stepIndex++;
            }

            // ── Step 2: Apply master channel + render master ──────────────
            MixerChannel master = project.getMixer().getMasterChannel();
            applyMasterChannel(masterMix, master, totalFrames, channels);

            Path masterPath = null;
            String masterFileName = null;
            int masterChannels = channels;
            if (bundle.master() != null) {
                MasterFormat mf = bundle.master();
                listener.onProgress(progress(stepIndex, totalSteps),
                        "Rendering master");
                String baseName = sanitize(mf.baseName());
                ExportResult result = exporter.export(masterMix, sampleRate, stagingDir,
                        baseName, mf.audioConfig(), ExportProgressListener.NONE);
                if (!result.success()) {
                    throw new IOException("Failed to render master: " + result.message());
                }
                masterPath = result.outputPath();
                masterFileName = baseName + "." + mf.audioConfig().format().fileExtension();
                stepIndex++;
            }

            // ── Step 3: Measurements ──────────────────────────────────────
            List<StemMetadata> stemMetadata = new ArrayList<>();
            for (StemRender s : stemRenders) {
                AudioExportConfig cfg = s.spec().audioConfig();
                Measurements m = measure(s.buffer(), cfg.sampleRate());
                stemMetadata.add(new StemMetadata(
                        sanitize(s.spec().stemName()) + "."
                                + cfg.format().fileExtension(),
                        cfg.format().name(),
                        s.buffer().length,
                        cfg.sampleRate(),
                        cfg.bitDepth(),
                        m.peakDbfs(),
                        m.rmsDbfs(),
                        m.integratedLufs()));
            }

            Measurements masterMeas = measure(masterMix, sampleRate);

            // ── Step 4: Final metadata ────────────────────────────────────
            BundleMetadata finalMetadata = bundle.metadata().withMeasurements(
                    masterChannels,
                    masterMeas.integratedLufs(),
                    masterMeas.peakDbfs(),
                    Instant.now(),
                    stemMetadata);

            listener.onProgress(progress(stepIndex, totalSteps), "Writing metadata.json");
            Path metadataPath = stagingDir.resolve("metadata.json");
            Files.writeString(metadataPath,
                    MetadataJsonWriter.toJson(finalMetadata, masterFileName));
            stepIndex++;

            // ── Step 5: Optional track-sheet PDF ──────────────────────────
            Path trackSheetPath = null;
            if (bundle.includeTrackSheet()) {
                listener.onProgress(progress(stepIndex, totalSteps),
                        "Generating track sheet");
                trackSheetPath = stagingDir.resolve("track_sheet.pdf");
                writeTrackSheet(trackSheetPath, finalMetadata, masterFileName,
                        masterMeas);
                stepIndex++;
            }

            // ── Step 6: Assemble ZIP ──────────────────────────────────────
            listener.onProgress(progress(stepIndex, totalSteps), "Assembling ZIP");
            Path parent = bundle.zipOutput().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (ZipOutputStream zos = new ZipOutputStream(
                    Files.newOutputStream(bundle.zipOutput()))) {
                if (masterPath != null) {
                    addZipEntry(zos, masterFileName, masterPath,
                            isStoreOnly(bundle.master().audioConfig().format()));
                }
                if (!stemRenders.isEmpty()) {
                    // Sort stems by file name for deterministic zip order
                    List<StemMetadata> sortedStems = new ArrayList<>(stemMetadata);
                    sortedStems.sort(Comparator.comparing(StemMetadata::fileName));
                    for (StemMetadata sm : sortedStems) {
                        Path stemPath = stagingDir.resolve(sm.fileName());
                        boolean store = isStoreOnly(formatFromName(sm.format()));
                        addZipEntry(zos, "stems/" + sm.fileName(), stemPath, store);
                    }
                }
                addZipEntry(zos, "metadata.json", metadataPath, false);
                if (trackSheetPath != null) {
                    addZipEntry(zos, "track_sheet.pdf", trackSheetPath, false);
                }
            }

            listener.onProgress(1.0, "Bundle export complete");

            long durationMs = System.currentTimeMillis() - startMs;
            return new BundleExportResult(bundle.zipOutput(), finalMetadata,
                    durationMs, true, List.of());
        } finally {
            // Best-effort cleanup of staging directory.
            cleanupQuietly(stagingDir);
        }
    }

    /**
     * Performs an asynchronous bundle export on a dedicated virtual thread.
     *
     * <p>This overload spins up a transient virtual-thread executor for the
     * single export and shuts it down when the task completes. For
     * applications that already hold a long-lived {@link DawTaskRunner},
     * prefer {@link #exportAsync(DawProject, double, DeliverableBundle,
     * ExportProgressListener, DawTaskRunner)} — it routes the work through
     * the shared runner so the export shows up in
     * {@link DawTaskRunner#snapshot()} debug views.</p>
     *
     * @param project           the DAW project
     * @param totalProjectBeats the total project length in beats
     * @param bundle            the deliverable bundle specification
     * @param listener          progress listener (0.0 – 1.0)
     * @return a future that completes with the bundle export result
     */
    public CompletableFuture<BundleExportResult> exportAsync(
            DawProject project,
            double totalProjectBeats,
            DeliverableBundle bundle,
            ExportProgressListener listener) {

        CompletableFuture<BundleExportResult> future = new CompletableFuture<>();
        // JEP 444: virtual-thread-per-task executor — one task = one virtual thread.
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(() -> {
            try {
                future.complete(export(project, totalProjectBeats, bundle, listener));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                executor.shutdown();
            }
        });
        return future;
    }

    /**
     * Performs an asynchronous bundle export on the shared
     * {@link DawTaskRunner}. The work is routed to the runner's
     * virtual-thread executor (I/O-bound — JEP 444) and registered for
     * the active-task snapshot used by the debug view.
     *
     * @param project           the DAW project
     * @param totalProjectBeats the total project length in beats
     * @param bundle            the deliverable bundle specification
     * @param listener          progress listener (0.0 – 1.0)
     * @param runner            the application's shared task runner
     * @return a future that completes with the bundle export result
     */
    public CompletableFuture<BundleExportResult> exportAsync(
            DawProject project,
            double totalProjectBeats,
            DeliverableBundle bundle,
            ExportProgressListener listener,
            DawTaskRunner runner) {
        Objects.requireNonNull(runner, "runner must not be null");
        String taskName = "bundle-export:" + bundle.zipOutput().getFileName();
        return runner.submit(new DawTask<>(
                taskName,
                TaskCategory.EXPORT,
                () -> export(project, totalProjectBeats, bundle, listener)));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Renders a single track (bounce + mixer channel) to a buffer matching
     * the project duration. Mirrors {@link StemExporter}'s pipeline so
     * stems and bundle have identical sound.
     */
    private static float[][] renderTrack(DawProject project, Track track,
                                          int sampleRate, double tempo,
                                          int channels, int totalFrames) {
        float[][] bounced = TrackBouncer.bounce(track, sampleRate, tempo, channels);
        float[][] buffer = new float[channels][totalFrames];
        if (bounced != null) {
            for (int ch = 0; ch < channels; ch++) {
                int srcCh = Math.min(ch, bounced.length - 1);
                int copyLen = Math.min(bounced[srcCh].length, totalFrames);
                System.arraycopy(bounced[srcCh], 0, buffer[ch], 0, copyLen);
            }
        }
        MixerChannel mc = project.getMixerChannelForTrack(track);
        if (mc != null) {
            StemExporter.applyMixerChannel(buffer, mc, totalFrames, channels);
        }
        return buffer;
    }

    /**
     * Applies the master channel volume and pan to the summed stem mix.
     * Insert effects on the master are intentionally not applied here —
     * the bundle's "stems sum to master" guarantee would otherwise be
     * broken.
     */
    private static void applyMasterChannel(float[][] buffer, MixerChannel master,
                                           int numFrames, int channels) {
        if (master == null) {
            return;
        }
        double volume = master.getVolume();
        double pan = master.getPan();
        if (channels >= 2) {
            double angle = (pan + 1.0) * 0.25 * Math.PI;
            float left = (float) (Math.cos(angle) * volume);
            float right = (float) (Math.sin(angle) * volume);
            for (int f = 0; f < numFrames; f++) {
                buffer[0][f] *= left;
            }
            for (int f = 0; f < numFrames; f++) {
                buffer[1][f] *= right;
            }
            for (int ch = 2; ch < channels; ch++) {
                for (int f = 0; f < numFrames; f++) {
                    buffer[ch][f] *= (float) volume;
                }
            }
        } else {
            for (int f = 0; f < numFrames; f++) {
                buffer[0][f] *= (float) volume;
            }
        }
    }

    /**
     * Computes peak (dBFS), RMS (dBFS) and gated integrated LUFS for the
     * given multi-channel buffer. The LUFS measurement uses
     * {@link LoudnessMeter} so it stays within the documented 0.2 LUFS
     * tolerance of the live meter for comparable audio.
     */
    static Measurements measure(float[][] buffer, int sampleRate) {
        if (buffer.length == 0 || buffer[0].length == 0) {
            return new Measurements(Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        }
        int numFrames = buffer[0].length;
        int channels = buffer.length;

        double peak = 0.0;
        double sumSquares = 0.0;
        long sampleCount = 0;
        for (int ch = 0; ch < channels; ch++) {
            for (int f = 0; f < numFrames; f++) {
                double s = buffer[ch][f];
                double abs = Math.abs(s);
                if (abs > peak) {
                    peak = abs;
                }
                sumSquares += s * s;
                sampleCount++;
            }
        }
        double rms = Math.sqrt(sumSquares / sampleCount);
        double peakDb = (peak > 0) ? 20.0 * Math.log10(peak) : Double.NEGATIVE_INFINITY;
        double rmsDb = (rms > 0) ? 20.0 * Math.log10(rms) : Double.NEGATIVE_INFINITY;

        // Integrated LUFS via LoudnessMeter
        int blockSize = 4096;
        LoudnessMeter meter = new LoudnessMeter(sampleRate, blockSize);
        float[] left = buffer[0];
        float[] right = (channels >= 2) ? buffer[1] : buffer[0];
        int offset = 0;
        while (offset < numFrames) {
            int n = Math.min(blockSize, numFrames - offset);
            float[] l = new float[n];
            float[] r = new float[n];
            System.arraycopy(left, offset, l, 0, n);
            System.arraycopy(right, offset, r, 0, n);
            meter.process(l, r, n);
            offset += n;
        }
        double lufs = meter.hasData()
                ? meter.getLatestData().integratedLufs()
                : Double.NEGATIVE_INFINITY;
        return new Measurements(peakDb, rmsDb, lufs);
    }

    private static void addZipEntry(ZipOutputStream zos, String entryName, Path source,
                                    boolean store) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        if (store) {
            entry.setMethod(ZipEntry.STORED);
            byte[] data = Files.readAllBytes(source);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            entry.setCrc(crc.getValue());
            zos.putNextEntry(entry);
            zos.write(data);
        } else {
            entry.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(entry);
            Files.copy(source, zos);
        }
        zos.closeEntry();
    }

    /** FLAC, OGG, MP3, AAC are already compressed — store, don't deflate. */
    private static boolean isStoreOnly(AudioExportFormat fmt) {
        return fmt != null && fmt != AudioExportFormat.WAV;
    }

    private static AudioExportFormat formatFromName(String name) {
        try {
            return AudioExportFormat.valueOf(name);
        } catch (IllegalArgumentException e) {
            return AudioExportFormat.WAV;
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9\\-_ ]", "_").trim();
    }

    private static double progress(int step, int total) {
        return Math.min(0.99, (double) step / Math.max(total, 1));
    }

    private static void cleanupQuietly(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best-effort
                }
            });
        } catch (IOException ignore) {
            // best-effort
        }
    }

    /**
     * Writes a single-page text track sheet listing the master and per-stem
     * file names, formats, and peak / RMS / LUFS measurements.
     */
    static void writeTrackSheet(Path target, BundleMetadata metadata,
                                String masterFileName, Measurements masterMeas)
            throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Project:  " + metadata.projectTitle());
        lines.add("Engineer: " + metadata.engineer());
        lines.add(String.format(Locale.ROOT, "Tempo:    %.2f BPM    Key: %s",
                metadata.tempo(), metadata.key().isEmpty() ? "(unspecified)" : metadata.key()));
        lines.add(String.format(Locale.ROOT, "Format:   %d Hz / %d-bit / %d ch",
                metadata.sampleRate(), metadata.bitDepth(), metadata.masterChannels()));
        lines.add("Rendered: " + metadata.renderedAt().toString());
        lines.add("");
        lines.add(String.format(Locale.ROOT, "%-40s %6s %6s %7s %7s",
                "File", "Ch", "kHz", "Peak", "LUFS-I"));
        lines.add("--------------------------------------------------------------------------");
        if (masterFileName != null) {
            lines.add(formatLine(masterFileName, metadata.masterChannels(),
                    metadata.sampleRate(),
                    masterMeas.peakDbfs(), metadata.integratedLufs()));
        }
        for (StemMetadata s : metadata.stems()) {
            lines.add(formatLine(s.fileName(), s.channels(), s.sampleRate(),
                    s.peakDbfs(), s.integratedLufs()));
        }

        SimplePdfWriter.writeTextPage(target, "Deliverable Track Sheet", lines);
    }

    private static String formatLine(String name, int channels, int sampleRate,
                                     double peakDb, double lufs) {
        String trimmed = name.length() > 40 ? name.substring(0, 40) : name;
        return String.format(Locale.ROOT, "%-40s %6d %6.1f %7s %7s",
                trimmed, channels, sampleRate / 1000.0,
                formatDb(peakDb), formatDb(lufs));
    }

    private static String formatDb(double v) {
        if (Double.isInfinite(v) || v <= -120.0) {
            return "-inf";
        }
        return String.format(Locale.ROOT, "%+.1f", v);
    }

    private record StemRender(StemSpec spec, float[][] buffer, Path stagingFile) {
    }

    /** Internal measurement carrier (peak/RMS/LUFS). */
    record Measurements(double peakDbfs, double rmsDbfs, double integratedLufs) {
    }

    /**
     * Result of a {@link BundleExportService#export} invocation.
     *
     * @param zipOutput     the path to the assembled zip
     * @param finalMetadata the metadata that was written into the zip
     * @param durationMs    wall-clock time in milliseconds
     * @param success       whether all stages completed without errors
     * @param errors        any non-fatal errors collected during export
     */
    public record BundleExportResult(
            Path zipOutput,
            BundleMetadata finalMetadata,
            long durationMs,
            boolean success,
            List<String> errors
    ) {
        public BundleExportResult {
            Objects.requireNonNull(zipOutput, "zipOutput must not be null");
            Objects.requireNonNull(finalMetadata, "finalMetadata must not be null");
            Objects.requireNonNull(errors, "errors must not be null");
            errors = List.copyOf(errors);
        }
    }

    /**
     * Convenience: synchronously join the result of {@link #exportAsync},
     * unwrapping any {@link IOException} thrown by the worker.
     */
    static BundleExportResult await(CompletableFuture<BundleExportResult> future)
            throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while awaiting bundle export", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException("bundle export failed", cause);
        }
    }

}
