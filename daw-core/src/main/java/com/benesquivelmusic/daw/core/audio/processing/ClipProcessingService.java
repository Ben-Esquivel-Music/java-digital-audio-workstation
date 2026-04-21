package com.benesquivelmusic.daw.core.audio.processing;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audioimport.WavFileReader;
import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.core.undo.CompoundUndoableAction;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Destructive clip operations that rewrite the audio data backing an
 * {@link AudioClip}.
 *
 * <p>Each operation writes a brand-new WAV file beside the clip's
 * original source asset (naming pattern {@code Reversed-<uuid>.wav}
 * or {@code Normalized-<uuid>.wav}), updates
 * {@link AudioClip#setSourceFilePath(String)} to point at the new file,
 * and records the prior asset path in a {@link ClipAssetHistory} so
 * that undo can restore the original reference for as long as the
 * in-memory history manifest is available. Persisting the manifest
 * across session reloads is out of scope for this service; the API is
 * structured so serialization can be added without code changes here.
 * Retention-window cleanup is triggered by
 * {@link ClipAssetHistory#purgeUnused()}.</p>
 *
 * <p>The returned {@link UndoableAction} can be executed through the
 * project's {@link com.benesquivelmusic.daw.core.undo.UndoManager}.
 * The batch variants ({@link #reverse(List)}, {@link #normalize(List, double)})
 * produce a {@link CompoundUndoableAction} so a multi-select apply is
 * undone as a single step.</p>
 *
 * <p><b>Pin lifecycle.</b> Actions returned by this service implement
 * {@link ClipAssetReferencing}, exposing the prior and produced asset
 * paths. Register {@link #createHistoryListener()} on your
 * {@link com.benesquivelmusic.daw.core.undo.UndoManager} so that
 * {@link ClipAssetHistory}'s pin set is rebuilt from the live history
 * on every mutation — this automatically releases assets referenced
 * only by actions that the undo manager has discarded (via
 * history-depth trimming or a redo-stack clear), avoiding pin leaks.</p>
 *
 * <h2>Normalize algorithm</h2>
 * <p>The normalize operation is inter-sample-peak (true-peak) aware:
 * before computing the gain scalar, the signal is 4× oversampled via
 * four-point Catmull-Rom / cubic Hermite interpolation at the three
 * inter-sample positions (t&nbsp;=&nbsp;0.25, 0.5, 0.75) and the peak
 * is taken across all source and interpolated samples. This prevents
 * a true-peak overshoot after normalization.</p>
 */
public final class ClipProcessingService {

    private static final double SILENCE_EPSILON = 1.0e-12;

    /** Sample rate used when a clip's asset cannot be inspected (fallback). */
    private static final int FALLBACK_SAMPLE_RATE = 48_000;

    /** Bit depth used when writing processed files. 32-bit float preserves reverse bit-exactness. */
    private static final int OUTPUT_BIT_DEPTH = 32;

    private final ClipAssetHistory history;

    /**
     * Creates a service that records prior assets into the given history.
     *
     * @param history the history manifest (must not be {@code null})
     */
    public ClipProcessingService(ClipAssetHistory history) {
        this.history = Objects.requireNonNull(history, "history must not be null");
    }

    /** Returns the history this service writes into. */
    public ClipAssetHistory history() {
        return history;
    }

    /**
     * Returns an {@link com.benesquivelmusic.daw.core.undo.UndoHistoryListener}
     * that keeps the {@link ClipAssetHistory}'s pin set in sync with
     * the live undo/redo history of the supplied
     * {@link com.benesquivelmusic.daw.core.undo.UndoManager}. Register
     * it via {@code undoManager.addHistoryListener(...)} once per
     * manager to ensure pins are released automatically when actions
     * are discarded.
     */
    public com.benesquivelmusic.daw.core.undo.UndoHistoryListener createHistoryListener() {
        return manager -> history.syncPinsFromHistory(manager);
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /** Returns an {@link UndoableAction} that reverses the audio backing {@code clip}. */
    public UndoableAction reverse(AudioClip clip) {
        return new DestructiveClipAction(clip, Mode.REVERSE, 0.0);
    }

    /**
     * Returns an {@link UndoableAction} that normalizes {@code clip}'s
     * audio so its 4×-oversampled inter-sample peak hits
     * {@code targetPeakDbfs}.
     *
     * @param clip            the clip to normalize
     * @param targetPeakDbfs  target peak in dBFS (e.g. {@code -1.0})
     */
    public UndoableAction normalize(AudioClip clip, double targetPeakDbfs) {
        if (!Double.isFinite(targetPeakDbfs)) {
            throw new IllegalArgumentException("targetPeakDbfs must be finite: " + targetPeakDbfs);
        }
        return new DestructiveClipAction(clip, Mode.NORMALIZE, targetPeakDbfs);
    }

    /**
     * Batch reverse: produces a single {@link CompoundUndoableAction}
     * covering the given clips.
     */
    public UndoableAction reverse(List<AudioClip> clips) {
        Objects.requireNonNull(clips, "clips must not be null");
        if (clips.isEmpty()) {
            throw new IllegalArgumentException("clips must not be empty");
        }
        List<UndoableAction> children = new ArrayList<>(clips.size());
        for (AudioClip c : clips) children.add(reverse(c));
        return new CompoundUndoableAction("Reverse Clips", children);
    }

    /**
     * Batch normalize: produces a single {@link CompoundUndoableAction}
     * covering the given clips, each normalized to {@code targetPeakDbfs}.
     */
    public UndoableAction normalize(List<AudioClip> clips, double targetPeakDbfs) {
        Objects.requireNonNull(clips, "clips must not be null");
        if (clips.isEmpty()) {
            throw new IllegalArgumentException("clips must not be empty");
        }
        List<UndoableAction> children = new ArrayList<>(clips.size());
        for (AudioClip c : clips) children.add(normalize(c, targetPeakDbfs));
        return new CompoundUndoableAction("Normalize Clips", children);
    }

    // ---------------------------------------------------------------------
    // DSP helpers (package-private so they are unit-testable)
    // ---------------------------------------------------------------------

    /**
     * Reverses audio in place along the time axis for every channel.
     * The result is bit-exact: two successive reversals restore the
     * original samples.
     */
    static float[][] reverseAudio(float[][] audio) {
        float[][] out = new float[audio.length][];
        for (int ch = 0; ch < audio.length; ch++) {
            float[] src = audio[ch];
            float[] dst = new float[src.length];
            for (int i = 0, n = src.length; i < n; i++) {
                dst[i] = src[n - 1 - i];
            }
            out[ch] = dst;
        }
        return out;
    }

    /**
     * Estimates the inter-sample (true) peak of {@code audio} using
     * 4× oversampling via four-point Catmull-Rom cubic interpolation.
     */
    static double interSamplePeak4x(float[][] audio) {
        if (audio == null || audio.length == 0) return 0.0;
        double peak = 0.0;
        for (float[] ch : audio) {
            if (ch == null || ch.length == 0) continue;
            for (int i = 0; i < ch.length; i++) {
                double a = Math.abs(ch[i]);
                if (a > peak) peak = a;
            }
            // Interpolate at t = 0.25, 0.5, 0.75 between consecutive samples.
            for (int i = 0; i < ch.length - 1; i++) {
                float p0 = i > 0 ? ch[i - 1] : ch[i];
                float p1 = ch[i];
                float p2 = ch[i + 1];
                float p3 = i + 2 < ch.length ? ch[i + 2] : ch[i + 1];
                for (int k = 1; k <= 3; k++) {
                    double t = k * 0.25;
                    double v = catmullRom(p0, p1, p2, p3, t);
                    double a = Math.abs(v);
                    if (a > peak) peak = a;
                }
            }
        }
        return peak;
    }

    /** Catmull-Rom / cubic Hermite interpolation at fractional position {@code t in [0,1]}. */
    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2.0 * p1)
                + (-p0 + p2) * t
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }

    /**
     * Scales {@code audio} so its 4×-oversampled true peak equals
     * {@code targetDbfs}. No-op for silent input.
     */
    static void normalizeInPlace(float[][] audio, double targetDbfs) {
        double peak = interSamplePeak4x(audio);
        if (peak <= SILENCE_EPSILON) return;
        double targetLinear = Math.pow(10.0, targetDbfs / 20.0);
        double gain = targetLinear / peak;
        for (float[] ch : audio) {
            if (ch == null) continue;
            for (int i = 0; i < ch.length; i++) {
                ch[i] = (float) (ch[i] * gain);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Implementation
    // ---------------------------------------------------------------------

    private enum Mode { REVERSE, NORMALIZE }

    private final class DestructiveClipAction implements UndoableAction, ClipAssetReferencing {

        private final AudioClip clip;
        private final Mode mode;
        private final double targetDbfs;

        private boolean executedOnce;
        private Path previousPath;     // the asset before the first execute()
        private Path newPath;          // the asset produced by execute()

        DestructiveClipAction(AudioClip clip, Mode mode, double targetDbfs) {
            this.clip = Objects.requireNonNull(clip, "clip must not be null");
            this.mode = mode;
            this.targetDbfs = targetDbfs;
        }

        @Override
        public String description() {
            return switch (mode) {
                case REVERSE   -> "Reverse Clip";
                case NORMALIZE -> "Normalize Clip";
            };
        }

        @Override
        public void execute() {
            try {
                if (!executedOnce) {
                    String source = clip.getSourceFilePath();
                    if (source == null || source.isBlank()) {
                        throw new IllegalStateException(
                                "Clip has no source asset to process: " + clip.getId());
                    }
                    previousPath = Paths.get(source);
                    newPath = produceProcessedFile(previousPath);
                    history.recordPriorAsset(clip.getId(), previousPath);
                    // Only the newly-written file is DAW-managed and therefore
                    // eligible for deletion by purgeUnused(); previousPath may
                    // be an external user-imported file and is deliberately
                    // not marked managed.
                    history.markManaged(newPath);
                    executedOnce = true;
                }
                clip.setSourceFilePath(newPath.toString());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to process clip " + clip.getId(), e);
            }
        }

        @Override
        public void undo() {
            if (!executedOnce) {
                throw new IllegalStateException("undo() called before execute()");
            }
            clip.setSourceFilePath(previousPath.toString());
        }

        @Override
        public List<Path> referencedAssets() {
            if (!executedOnce) {
                return List.of();
            }
            return List.of(previousPath, newPath);
        }

        private Path produceProcessedFile(Path source) throws IOException {
            WavFileReader.WavReadResult read = WavFileReader.read(source);
            float[][] processed = switch (mode) {
                case REVERSE   -> reverseAudio(read.audioData());
                case NORMALIZE -> {
                    // Copy so the original file's in-memory buffer is not mutated.
                    float[][] copy = new float[read.audioData().length][];
                    for (int i = 0; i < copy.length; i++) {
                        copy[i] = read.audioData()[i].clone();
                    }
                    normalizeInPlace(copy, targetDbfs);
                    yield copy;
                }
            };
            String prefix = mode == Mode.REVERSE ? "Reversed-" : "Normalized-";
            Path parent = source.getParent();
            String name = prefix + UUID.randomUUID() + ".wav";
            Path target = parent == null ? Paths.get(name) : parent.resolve(name);
            int sampleRate = read.sampleRate() > 0 ? read.sampleRate() : FALLBACK_SAMPLE_RATE;
            WavExporter.write(processed, sampleRate, OUTPUT_BIT_DEPTH,
                    DitherType.NONE, AudioMetadata.EMPTY, target);
            return target;
        }
    }
}
