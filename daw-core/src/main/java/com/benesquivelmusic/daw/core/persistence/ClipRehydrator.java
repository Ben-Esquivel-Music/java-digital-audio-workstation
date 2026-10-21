package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * The clip rehydrator (Persistence Integrity book §4.3, §5.3 step 4
 * REHYDRATE): after a project is deserialized, every {@link AudioClip} that
 * carries a source reference but no sample data gets its audio back,
 * decoded off the owner thread and attached on it.
 *
 * <p>{@link #start} runs on the owner thread. It snapshots the clips that
 * qualify — no audio, an {@linkplain AssetReference#isOpenable() openable}
 * reference, and not already a row of the open's missing-assets report —
 * groups them by {@link AssetReference} (one decode per source, decision
 * D4), orders the sources so the playhead's come first (D10), and starts a
 * {@link RehydrationSession} that decodes them on virtual threads. Readiness
 * is per clip, not a whole-project gate: playback never waits for the run,
 * and a clip that lands mid-playback joins in place, because rendering is
 * position-based.</p>
 *
 * <p>Decoding rebuilds what the producing path held in memory (D1): a
 * single file through the import's own decode and conversion to the project
 * rate; a recorded take at its native rate with the capture path's
 * {@code SourceRateMetadata}; an ADM BWF file's bed and objects by clip name
 * (D3). Nothing is added to the render path: the audio thread sees the
 * existing volatile {@link AudioClip#setAudioData} publication.</p>
 *
 * <p>Concurrency: at most {@code max(1, min(4, processors − 2))} decodes at
 * once, within a budget of a quarter of the heap; a source estimated above
 * the budget runs alone, and one estimated above half of the heap is not
 * attempted ({@link MissingAssetReason#TOO_LARGE}).</p>
 */
public final class ClipRehydrator {

    private final SourceDecoder decoder;
    private final int permits;
    private final long maxMemoryBytes;
    /** Supplies each session's decode-thread factory. */
    private final Supplier<ThreadFactory> decodeThreads;

    /**
     * Creates the production rehydrator: the file decoders, the default
     * concurrency, {@link Runtime#maxMemory()} as the heap the budget is
     * taken from, and a fresh virtual-thread factory per session.
     */
    public ClipRehydrator() {
        this(FileSourceDecoder.INSTANCE, defaultPermits(), Runtime.getRuntime().maxMemory(),
                ClipRehydrator::virtualDecodeThreads);
    }

    /**
     * Creates the production rehydrator whose sessions take their decode
     * threads from {@code decodeThreads} — every job, the decode and the
     * report to the listener, runs on a thread it makes. The seam another
     * module's tests use to hold decodes and to see which thread runs them;
     * the application itself uses {@link #ClipRehydrator()}.
     *
     * @param decodeThreads makes the decode threads of every session; a
     *                      thread it returns must run the task it was given
     */
    public ClipRehydrator(ThreadFactory decodeThreads) {
        this(FileSourceDecoder.INSTANCE, defaultPermits(), Runtime.getRuntime().maxMemory(),
                constant(Objects.requireNonNull(decodeThreads, "decodeThreads must not be null")));
    }

    /**
     * Test seam: a rehydrator with its own decoder, concurrency and heap
     * figure (the budget is a quarter of it, the too-large limit half).
     */
    ClipRehydrator(SourceDecoder decoder, int permits, long maxMemoryBytes) {
        this(decoder, permits, maxMemoryBytes, ClipRehydrator::virtualDecodeThreads);
    }

    private ClipRehydrator(SourceDecoder decoder, int permits, long maxMemoryBytes,
                           Supplier<ThreadFactory> decodeThreads) {
        this.decoder = Objects.requireNonNull(decoder, "decoder must not be null");
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1: " + permits);
        }
        if (maxMemoryBytes < 0) {
            throw new IllegalArgumentException("maxMemoryBytes must not be negative: " + maxMemoryBytes);
        }
        this.permits = permits;
        this.maxMemoryBytes = maxMemoryBytes;
        this.decodeThreads = decodeThreads;
    }

    /** One session's own virtual decode threads, numbered from 0. */
    private static ThreadFactory virtualDecodeThreads() {
        return Thread.ofVirtual().name("clip-rehydration-", 0).factory();
    }

    private static Supplier<ThreadFactory> constant(ThreadFactory factory) {
        return () -> factory;
    }

    /**
     * The default number of concurrent decodes: {@code max(1, min(4,
     * availableProcessors − 2))}, leaving cores for the audio and FX threads.
     *
     * @return the default permits
     */
    static int defaultPermits() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
    }

    /**
     * Starts rehydrating {@code project}. Owner thread; returns at once —
     * decoding happens on the session's decode threads, which report to
     * {@code listener} (see {@link RehydrationListener} for its threading).
     *
     * <p>The decode order: sources with a clip spanning {@code playheadBeat}
     * first, then sources by the earliest clip start at or after it, then
     * the rest by their earliest clip start.</p>
     *
     * @param project      the project just installed; its sample rate is read here, once
     * @param atOpen       the open's missing-assets report: sources it names are not decoded
     * @param playheadBeat the transport position the user will hear first
     * @param listener     receives the outcomes; must not block
     * @return the running session
     */
    public RehydrationSession start(DawProject project, MissingAssetsReport atOpen, double playheadBeat,
                                    RehydrationListener listener) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(atOpen, "atOpen must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (!Double.isFinite(playheadBeat)) {
            throw new IllegalArgumentException("playheadBeat must be finite: " + playheadBeat);
        }
        int projectSampleRate = (int) project.getFormat().sampleRate();
        RehydrationSession session = new RehydrationSession(project, projectSampleRate,
                scheduledSources(project, atOpen, playheadBeat), decoder, permits, maxMemoryBytes, listener,
                decodeThreads.get());
        session.launch();
        return session;
    }

    /** The sources of the qualifying clips, one per asset reference, in decode order. */
    static List<AssetReference> scheduledSources(DawProject project, MissingAssetsReport skip,
                                                 double playheadBeat) {
        Map<AssetReference, Placement> placements = new LinkedHashMap<>();
        for (Track track : project.getTracks()) {
            for (AudioClip clip : track.getClips()) {
                if (clip.getAudioData() != null) {
                    continue;
                }
                Optional<AssetReference> source = AssetReference.of(clip);
                if (source.isEmpty() || !source.get().isOpenable() || skip.contains(source.get())) {
                    continue;
                }
                placements.merge(source.get(), Placement.of(clip, playheadBeat), Placement::merge);
            }
        }
        List<Map.Entry<AssetReference, Placement>> ordered = new ArrayList<>(placements.entrySet());
        ordered.sort(Map.Entry.comparingByValue(Placement.DECODE_ORDER));
        List<AssetReference> sources = new ArrayList<>(ordered.size());
        for (Map.Entry<AssetReference, Placement> entry : ordered) {
            sources.add(entry.getKey());
        }
        return List.copyOf(sources);
    }

    /**
     * Where a source's clips sit relative to the playhead.
     *
     * @param coversPlayhead       a clip spans the playhead ({@code start ≤ p < end})
     * @param firstStartAtPlayhead the earliest clip start at or after the playhead
     *                             ({@link Double#POSITIVE_INFINITY} when none)
     * @param firstStart           the earliest clip start
     */
    private record Placement(boolean coversPlayhead, double firstStartAtPlayhead, double firstStart) {

        /** Covering sources, then sources ahead of the playhead, then the rest; stable on ties. */
        static final Comparator<Placement> DECODE_ORDER =
                Comparator.comparingInt(Placement::tier).thenComparingDouble(Placement::orderBeat);

        static Placement of(AudioClip clip, double playheadBeat) {
            double start = clip.getStartBeat();
            boolean covers = start <= playheadBeat && playheadBeat < clip.getEndBeat();
            return new Placement(covers, start >= playheadBeat ? start : Double.POSITIVE_INFINITY, start);
        }

        Placement merge(Placement other) {
            return new Placement(coversPlayhead || other.coversPlayhead,
                    Math.min(firstStartAtPlayhead, other.firstStartAtPlayhead),
                    Math.min(firstStart, other.firstStart));
        }

        int tier() {
            if (coversPlayhead) {
                return 0;
            }
            return firstStartAtPlayhead != Double.POSITIVE_INFINITY ? 1 : 2;
        }

        double orderBeat() {
            return tier() == 1 ? firstStartAtPlayhead : firstStart;
        }
    }
}
