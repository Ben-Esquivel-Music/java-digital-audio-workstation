package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One project open's clip rehydration run, started by
 * {@link ClipRehydrator#start} (Persistence Integrity book §4.3, §6.1).
 *
 * <p><strong>Decode threads.</strong> One job per source runs on the
 * session's own thread-per-task executor — virtual threads, unless the
 * rehydrator was given a thread factory — admitted in decode order under the
 * concurrency limit and memory budget ({@link DecodeGate}). A job decodes
 * only — it never touches the project — and reports exactly one
 * {@link RehydrationOutcome} to the {@link RehydrationListener}; a
 * {@link Throwable} is contained per job (an {@link OutOfMemoryError} is a
 * {@link MissingAssetReason#TOO_LARGE} outcome). When every job has
 * reported, the listener's {@code onFinished} is called once.</p>
 *
 * <p><strong>Owner thread.</strong> {@link #apply(RehydrationOutcome)},
 * {@link #settlePending()}, {@link #isPending}, {@link #pendingCount()} and
 * {@link #cancel()} belong to the thread that owns the project (the FX
 * thread in the application). Applying an outcome walks the <em>live</em>
 * project at that moment, so a clip split, duplicated or glued before its
 * audio landed gets it too; it never overwrites a clip's audio and never
 * marks the project dirty — loading is not an edit.</p>
 *
 * <p><strong>Cancellation.</strong> {@link #cancel()} is best-effort: it
 * stops the executor ({@code shutdownNow}, never a blocking
 * {@code close}), wakes waiting jobs, and from then on the session reports
 * nothing and applies nothing. The authoritative fence is the owner
 * thread's own check of which run is current before it applies anything.</p>
 */
public final class RehydrationSession {

    private static final Logger LOG = Logger.getLogger(RehydrationSession.class.getName());

    /** The detail of a source {@link #settlePending()} settles. */
    static final String LOADING_DID_NOT_COMPLETE = "loading did not complete";

    private final DawProject project;
    private final int projectSampleRate;
    private final List<AssetReference> sources;
    /** The sources whose outcome the owner thread has not applied yet; owner thread only. */
    private final Set<AssetReference> pending;
    private final SourceDecoder decoder;
    private final DecodeGate gate;
    private final long tooLargeBytes;
    private final RehydrationListener listener;
    private final ExecutorService executor;
    private final AtomicInteger unfinished;
    private final AtomicInteger decodedCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();
    private volatile boolean cancelled;
    /** Set once every job ended, just before the listener hears the run's end. */
    private volatile boolean ended;

    RehydrationSession(DawProject project, int projectSampleRate, List<AssetReference> sources,
                       SourceDecoder decoder, int permits, long maxMemoryBytes,
                       RehydrationListener listener, ThreadFactory decodeThreads) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.projectSampleRate = projectSampleRate;
        this.sources = List.copyOf(sources);
        this.pending = new LinkedHashSet<>(this.sources);
        this.decoder = Objects.requireNonNull(decoder, "decoder must not be null");
        this.gate = new DecodeGate(this.sources.size(), permits, maxMemoryBytes / 4);
        this.tooLargeBytes = maxMemoryBytes / 2;
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
        this.executor = Executors.newThreadPerTaskExecutor(
                Objects.requireNonNull(decodeThreads, "decodeThreads must not be null"));
        this.unfinished = new AtomicInteger(Math.max(1, this.sources.size()));
    }

    /** Submits one job per source, in decode order, then shuts the executor down. */
    void launch() {
        if (sources.isEmpty()) {
            executor.execute(this::finishOne);
        }
        for (int ticket = 0; ticket < sources.size(); ticket++) {
            int jobTicket = ticket;
            AssetReference source = sources.get(ticket);
            executor.execute(() -> decodeAndReport(jobTicket, source));
        }
        executor.shutdown();
    }

    /**
     * Cancels the run: queued and waiting decodes give up, running ones are
     * interrupted, and the session reports nothing more and applies nothing.
     * Never blocks. Idempotent.
     */
    public void cancel() {
        cancelled = true;
        gate.cancel();
        executor.shutdownNow();
    }

    /**
     * Returns whether {@link #cancel()} was called.
     *
     * @return {@code true} once cancelled
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Returns the sources this run decodes, in decode order.
     *
     * @return the sources
     */
    public List<AssetReference> sources() {
        return sources;
    }

    /**
     * Returns how many sources this run decodes.
     *
     * @return the source count
     */
    public int sourceCount() {
        return sources.size();
    }

    /**
     * Returns how many sources the owner thread has not yet applied an
     * outcome for; 0 once cancelled. Owner thread only.
     *
     * @return the pending source count
     */
    public int pendingCount() {
        return cancelled ? 0 : pending.size();
    }

    /**
     * Returns whether the run still owes {@code source}: it is one of this
     * run's sources and its outcome has not been applied. Owner thread only.
     *
     * @param source the asset reference
     * @return {@code true} while the source is loading
     */
    public boolean isPending(AssetReference source) {
        Objects.requireNonNull(source, "source must not be null");
        return !cancelled && pending.contains(source);
    }

    /**
     * Applies one outcome to the live project. Owner thread only.
     * <ul>
     *   <li>{@link RehydrationOutcome.Decoded}: every clip that currently
     *       reads from the source and has no audio gets the decoded array —
     *       the same instance for every clip of one source (and, for an ADM
     *       file, of one bed or object). A recorded take's
     *       {@link SourceRateMetadata} is set before the array, which
     *       publishes both. An ADM clip whose name selects no bed or object
     *       gets nothing and is a silent clip of an
     *       {@link MissingAssetReason#ADM_OBJECT_UNKNOWN} row. When the
     *       clips are an ADM import's ({@link DecodedAudio#isAdmImport}) but
     *       the file decoded without ADM metadata, no clip gets anything —
     *       each would play the same channels — and they are all silent clips
     *       of such a row (D30). When a clip spans more audio than the
     *       source holds ({@link SourceShortfall}: judged at the transport's
     *       tempo, which the render pipeline plays every audio clip at — a
     *       renderer that follows the tempo map must replace this with one
     *       beat-to-source-frame function both call (D37) — and which cannot
     *       tell a shorter file from a tempo slowed after the clip was cut),
     *       the clips keep the audio and the source is a
     *       {@link MissingAssetReason#CHANGED_ON_DISK} row ("Shorter than the
     *       clip") that silences nothing.</li>
     *   <li>{@link RehydrationOutcome.Failed}: one row with the outcome's
     *       reason, whose silent clips are the clips that still read from
     *       the source and have no audio; no row when there are none.</li>
     * </ul>
     * A row names every clip that holds the source as a holder (a relink
     * re-points them all); its detail says which of them the reason
     * concerns when that is not all of them (D26).
     * Does nothing — returns {@link AttachResult#NONE} — once cancelled, for
     * another session's outcome, and for an outcome already applied. Never
     * marks the project dirty.
     *
     * <p>The source stops being pending only once its outcome was applied
     * (decision D39): when applying it throws, the source is still owed, and
     * {@link #settlePending()} turns it into a row over the clips still
     * without audio — they are never left silent without one.</p>
     *
     * @param outcome an outcome this session reported
     * @return the clips that received audio and the rows to add to the report
     */
    public AttachResult apply(RehydrationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        RehydrationSession origin = switch (outcome) {
            case RehydrationOutcome.Decoded decoded -> decoded.origin();
            case RehydrationOutcome.Failed failed -> failed.origin();
        };
        if (cancelled || origin != this || !pending.contains(outcome.source())) {
            return AttachResult.NONE;
        }
        AttachResult result = switch (outcome) {
            case RehydrationOutcome.Decoded decoded -> attach(decoded.source(), decoded.audio());
            case RehydrationOutcome.Failed failed -> fail(failed);
        };
        pending.remove(outcome.source());
        return result;
    }

    /**
     * Waits for every job to end — a test aid; the application never waits.
     */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    /**
     * Settles every source the run still owes once its end was reported
     * (decisions D33, D39): an outcome that never arrived — lost to a failing
     * listener — or one whose applying threw ({@link #apply} leaves such a
     * source pending, and the owner may also have failed before it called
     * {@code apply}) would otherwise leave the source loading for the rest
     * of the session, its clips silent without a row. Each such source
     * stops being pending and becomes an {@link MissingAssetReason#UNREADABLE}
     * row, {@value #LOADING_DID_NOT_COMPLETE}, whose silent clips are the
     * clips that still read from it and have no audio (no row when there are
     * none). Owner thread: call it when the end's notification is handled,
     * after applying every outcome that arrived before it.
     *
     * <p>Does nothing — returns no rows — before the run's end was reported
     * (a source may still be decoding) and once the session is cancelled.
     * Never marks the project dirty.</p>
     *
     * @return the rows to add to the report, in decode order; empty when
     *         nothing was still pending
     */
    public List<MissingAsset> settlePending() {
        if (cancelled || !ended) {
            return List.of();
        }
        List<MissingAsset> rows = new ArrayList<>();
        for (AssetReference source : List.copyOf(pending)) {
            pending.remove(source);
            rows.addAll(fail(source, MissingAssetReason.UNREADABLE, LOADING_DID_NOT_COMPLETE).rows());
        }
        return List.copyOf(rows);
    }

    private AttachResult attach(AssetReference source, DecodedAudio decoded) {
        List<HeldClip> holding = liveHolders(source);
        if (decoded instanceof DecodedAudio.FileAudio file && DecodedAudio.isAdmImport(namesOf(holding))) {
            // D30: a file without ADM metadata would give every bed and object clip the same channels.
            return rowOfSilent(source, MissingAssetReason.ADM_OBJECT_UNKNOWN, file.noAdmMetadataDetail(), holding);
        }
        double bpm = SourceShortfall.playbackTempo(project);
        List<AudioClip> attached = new ArrayList<>();
        int shortClips = 0;
        List<MissingAsset.Holder> unknown = new ArrayList<>();
        double heldSeconds = 0.0;
        double longestSpanSeconds = 0.0;
        for (HeldClip held : holding) {
            AudioClip clip = held.clip();
            if (clip.getAudioData() != null) {
                continue;
            }
            Optional<DecodedAudio.ClipAudio> selected = decoded.forClip(clip.getName());
            if (selected.isEmpty()) {
                unknown.add(held.holder());
                continue;
            }
            selected.get().attachTo(clip);
            attached.add(clip);
            double seconds = selected.get().seconds();
            double spanSeconds = SourceShortfall.usedSeconds(clip, bpm);
            if (SourceShortfall.isShort(seconds, spanSeconds)) {
                shortClips++;
                heldSeconds = seconds;
                longestSpanSeconds = Math.max(longestSpanSeconds, spanSeconds);
            }
        }
        List<MissingAsset.Holder> holders = holdersOf(holding);
        List<MissingAsset> rows = new ArrayList<>(2);
        if (shortClips > 0) {
            rows.add(new MissingAsset(source, MissingAssetReason.CHANGED_ON_DISK,
                    SourceShortfall.shorterDetail(shortClips, holders.size(), heldSeconds, longestSpanSeconds),
                    holders, List.of()));
        }
        if (!unknown.isEmpty() && decoded instanceof DecodedAudio.AdmAudio adm) {
            rows.add(new MissingAsset(source, MissingAssetReason.ADM_OBJECT_UNKNOWN,
                    adm.unknownNamesDetail(unknown.size(), holders.size()), holders, unknown));
        }
        return new AttachResult(attached, rows);
    }

    private AttachResult fail(RehydrationOutcome.Failed failed) {
        return fail(failed.source(), failed.reason(), failed.detail());
    }

    private AttachResult fail(AssetReference source, MissingAssetReason reason, String detail) {
        return rowOfSilent(source, reason, detail, liveHolders(source));
    }

    /**
     * One row naming every clip of {@code holding}, whose silent clips are
     * those without audio; nothing when every one has audio.
     */
    private static AttachResult rowOfSilent(AssetReference source, MissingAssetReason reason, String detail,
                                            List<HeldClip> holding) {
        List<MissingAsset.Holder> silent = new ArrayList<>();
        for (HeldClip held : holding) {
            if (held.clip().getAudioData() == null) {
                silent.add(held.holder());
            }
        }
        if (silent.isEmpty()) {
            return AttachResult.NONE;
        }
        return new AttachResult(List.of(), List.of(
                new MissingAsset(source, reason, detail, holdersOf(holding), silent)));
    }

    /** Every clip of the live project that reads from {@code source}, in project order. */
    private List<HeldClip> liveHolders(AssetReference source) {
        List<HeldClip> holding = new ArrayList<>();
        for (Track track : project.getTracks()) {
            for (AudioClip clip : track.getClips()) {
                if (source.isHeldBy(clip)) {
                    holding.add(new HeldClip(clip, MissingAsset.Holder.of(track, clip)));
                }
            }
        }
        return holding;
    }

    private static List<String> namesOf(List<HeldClip> holding) {
        List<String> names = new ArrayList<>(holding.size());
        for (HeldClip held : holding) {
            names.add(held.clip().getName());
        }
        return names;
    }

    private static List<MissingAsset.Holder> holdersOf(List<HeldClip> holding) {
        List<MissingAsset.Holder> holders = new ArrayList<>(holding.size());
        for (HeldClip held : holding) {
            holders.add(held.holder());
        }
        return holders;
    }

    /** A live clip that reads from a source, with its report holder. */
    private record HeldClip(AudioClip clip, MissingAsset.Holder holder) {
        HeldClip {
            Objects.requireNonNull(clip, "clip must not be null");
            Objects.requireNonNull(holder, "holder must not be null");
        }
    }

    private void decodeAndReport(int ticket, AssetReference source) {
        try {
            RehydrationOutcome outcome = decode(ticket, source);
            if (outcome != null) {
                report(outcome);
            }
        } finally {
            finishOne();
        }
    }

    /** Decodes one source; {@code null} when the session was cancelled. Never throws. */
    private RehydrationOutcome decode(int ticket, AssetReference source) {
        DecodeGate.Lease lease = null;
        try {
            if (cancelled) {
                return null;
            }
            long cost = decoder.estimateCost(source, projectSampleRate);
            if (cost > tooLargeBytes) {
                return failed(source, LoadFailure.tooLarge(cost, tooLargeBytes));
            }
            lease = gate.admit(ticket, cost);
            if (cancelled) {
                return null;
            }
            return new RehydrationOutcome.Decoded(this, source, decoder.decode(source, projectSampleRate));
        } catch (OutOfMemoryError outOfMemory) {
            // The decode's arrays went with its stack frames.
            return cancelled ? null : failed(source, LoadFailure.outOfMemory());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return cancelled ? null : new RehydrationOutcome.Failed(this, source,
                    MissingAssetReason.UNREADABLE, "loading was interrupted");
        } catch (Throwable error) {
            // Contained per source (design book §5.2): one bad file never ends the run.
            return cancelled ? null : failed(source, LoadFailure.of(source, error));
        } finally {
            if (lease != null) {
                gate.release(lease);
            } else {
                gate.skip(ticket);
            }
        }
    }

    private RehydrationOutcome.Failed failed(AssetReference source, LoadFailure failure) {
        return new RehydrationOutcome.Failed(this, source, failure.reason(), failure.detail());
    }

    private void report(RehydrationOutcome outcome) {
        if (cancelled) {
            return;
        }
        if (outcome instanceof RehydrationOutcome.Decoded) {
            decodedCount.incrementAndGet();
        } else {
            failedCount.incrementAndGet();
        }
        try {
            listener.onOutcome(outcome);
        } catch (Throwable listenerFailure) {
            LOG.log(Level.WARNING, "A rehydration listener failed on " + outcome
                    + "; the failure is contained", listenerFailure);
        }
    }

    private void finishOne() {
        if (unfinished.decrementAndGet() != 0) {
            return;
        }
        ended = true;
        if (cancelled) {
            return;
        }
        try {
            listener.onFinished(new RehydrationListener.Summary(
                    sources.size(), decodedCount.get(), failedCount.get()));
        } catch (Throwable listenerFailure) {
            LOG.log(Level.WARNING, "A rehydration listener failed on the run's end;"
                    + " the failure is contained", listenerFailure);
        }
    }

    /**
     * What applying one outcome did.
     *
     * @param attached the clips that received audio, in project order
     * @param rows     the report rows the outcome produced, to add to the
     *                 run's report ({@link MissingAssetsReport#withAll})
     */
    public record AttachResult(List<AudioClip> attached, List<MissingAsset> rows) {

        /** Nothing attached, nothing to report. */
        public static final AttachResult NONE = new AttachResult(List.of(), List.of());

        public AttachResult {
            attached = List.copyOf(Objects.requireNonNull(attached, "attached must not be null"));
            rows = List.copyOf(Objects.requireNonNull(rows, "rows must not be null"));
        }
    }
}
