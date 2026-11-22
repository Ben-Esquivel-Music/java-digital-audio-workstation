package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.EngineHealthText;
import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.OpenedStream;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.sdk.audio.ClockKind;
import com.benesquivelmusic.daw.sdk.audio.ClockLockStatus;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The transport bar's clock badge (rulings E29 to E32, E61, E80 to E85,
 * E100, E101, E108, E110, E111, E119, E135, E136 and E141 to E144): the
 * hardware clock source of the open stream and whether an external one is
 * locked ("INT 48k", "⚠ W/C 48k UNLOCKED").
 *
 * <p><strong>The model.</strong> The badge keeps a few facts on the FX
 * thread: the label (the clock last read: device, source and rate), what the
 * stream it is looking at learnt from its own reads ({@link OwnRead}), and
 * the device and rate that stream opened on. Every look reads three levels
 * without a lock: whether the device is lost or being declared lost, whether
 * a stream is open, and the open stream's {@link ClockLockStatus}. What is on
 * show is never an input: {@link #decide(Facts)}, a pure function of these
 * facts, chooses the {@link State}, and one call site shows it.</p>
 *
 * <p><strong>The events.</strong> Each changes the model only, then the
 * decision runs.</p>
 * <ul>
 *   <li>A new stream: every look, a pulse or a landing, reads whether a
 *       stream is open and then the stream the engine last opened
 *       ({@link OpenedStream}); a serial the badge has not noticed yet is a
 *       new stream, however many looks and callbacks see it, so one open
 *       never counts twice (E135); a stream whose start failed is a stream
 *       that opened and closed at once (E143). Its reads start unanswered; a
 *       label read on another device, by exact identity, or at another rate
 *       is forgotten (E142); its own read is asked about the device it opened
 *       on, even when it has closed again by then (E136).</li>
 *   <li>A look at a closed stream ends what its reads learnt; a look at a
 *       lost device forgets the label (E74, E84). A label whose source the
 *       stream's own lock status had replaced by another does not outlive
 *       that stream: it is forgotten at the close, or at the next stream when
 *       no look saw the close (E144).</li>
 *   <li>A read that lands on an open stream: a failure changes nothing and is
 *       asked again after {@link #READ_RETRY_NANOS} (E80, E141); a current
 *       source is the label; a list with no current source, while the status
 *       names no source either, means the device has none (E32, E135).</li>
 *   <li>A read that lands after its stream closed settles the closed view
 *       only when it answered about the device that stream opened (E119 (d),
 *       E136); a read asked for by Settings or a reconfigure while closed
 *       keeps a label only when it names the same clock (E81).</li>
 * </ul>
 * <p>Reads run off the FX thread, at most one at a time; a newer read, a close
 * of a measured stream, or a loss makes the answer of a read in flight void.
 * A landing first looks as a pulse does, so whether a pulse ran before it
 * never changes what it decides.</p>
 *
 * <p><strong>Notices.</strong> They come from the difference between two
 * applied statuses of one measurement: an {@code UNLOCKED} status raises E31's
 * unlock notice and mark unless the last status applied for the same serial
 * was already unlocked; a {@code LOCKED} status after an {@code UNLOCKED} one
 * of the same serial raises the relock notice, at the information level
 * (E110), and mark. A new serial is never a relock. A close on purpose or a
 * loss forgets the last applied status with no notice and no mark (E81, E84).
 * A clock event never stops a take (E30).</p>
 */
public final class ClockStatusIndicator extends Label {

    private static final Logger LOG = Logger.getLogger(ClockStatusIndicator.class.getName());

    /** What the badge shows. */
    enum State { HIDDEN, CLOSED, STARTING, CHECKING, NOT_MEASURABLE, QUIET, UNLOCKED }

    /** What the reads of the stream the badge is looking at have answered. */
    enum OwnRead {
        /** No read of this stream has answered, or the last one failed. */
        UNANSWERED,
        /** A read answered that the device has no clock source. */
        NO_SOURCES,
        /** A read answered with a current source: the label. */
        LABELLED
    }

    /** How long the badge waits after a read that settled nothing before it reads again. */
    static final long READ_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);

    static final String STYLE_CLASS = "clock-status-indicator";
    static final String DIM_CLASS = "clock-dim";
    static final String LOCKED_CLASS = "clock-locked";
    static final String UNLOCKED_CLASS = "clock-unlocked";
    private static final PseudoClass FLASH = PseudoClass.getPseudoClass("flash");

    /** What the badge reads from the engine. */
    interface StreamClock {

        /** @return whether a stream is open (running or paused); lock-free, any thread */
        boolean streamOpen();

        /** @return whether the device is lost or a loss is being declared; lock-free, any thread */
        boolean deviceLost();

        /** @return the open stream's clock-lock status; lock-free, no driver call, any thread */
        Optional<ClockLockStatus> lockStatus();

        /**
         * The stream the engine last opened, whether or not its start succeeded. The engine publishes it before
         * the stream reads as open and replaces it only with a later open's, so it stays after the close and
         * after a failed start, and after {@link #streamOpen()} answered {@code true} this answers that stream
         * or a later one, never an earlier one.
         *
         * @return the last opened stream, empty before the first; lock-free, any thread
         */
        Optional<OpenedStream> lastOpened();

        /**
         * The open stream's device, or with no stream open the configured
         * one, with its clock sources and the engine's rate: what Settings
         * and a reconfigure read. Blocking; never on the FX thread.
         *
         * @return the reading; empty when there is no device to read
         */
        Optional<ClockReading> read();

        /**
         * {@code device}'s clock sources and the engine's rate, whether or
         * not a stream is still open on it. Blocking; never on the FX thread.
         *
         * @param device the device to read
         * @return the reading
         */
        Optional<ClockReading> read(DeviceId device);
    }

    /**
     * One reading of the clock.
     *
     * @param device     the device read
     * @param sources    its clock sources, as it reports them
     * @param sampleRate the engine's rate, in Hz
     */
    record ClockReading(DeviceId device, List<ClockSource> sources, int sampleRate) {

        ClockReading {
            Objects.requireNonNull(device, "device must not be null");
            sources = List.copyOf(sources);
        }

        Optional<ClockSource> current() {
            return sources.stream().filter(ClockSource::current).findFirst();
        }
    }

    /**
     * The clock last read: what the closed and starting views show.
     *
     * @param device     the device it was read on
     * @param source     its current source
     * @param sampleRate the engine's rate when it was read, in Hz
     */
    record ClockLabel(DeviceId device, ClockSource source, int sampleRate) {

        ClockLabel {
            Objects.requireNonNull(device, "device must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }
    }

    /**
     * What the decision reads.
     *
     * @param lost    whether the device is lost or a loss is being declared
     * @param open    whether a stream is open
     * @param label   the label, or {@code null} when none is known
     * @param ownRead what the open stream's reads answered
     * @param status  the open stream's lock status, or {@code null} when none
     */
    record Facts(boolean lost, boolean open, ClockLabel label, OwnRead ownRead, ClockLockStatus status) {

        Facts {
            Objects.requireNonNull(ownRead, "ownRead must not be null");
        }
    }

    /** What records now, as the clock notices word it (ruling E108). */
    enum TakeRecording {
        /** No take records. */
        NONE,
        /** A take with an audio manifest records: a clock change is marked in it. */
        AUDIO,
        /** A take records with no audio manifest (MIDI only): nothing is marked. */
        WITHOUT_AUDIO
    }

    /** The take the badge marks, as the record coordinator has it. FX thread. */
    interface TakeClockMarks {

        /** @return what records now */
        TakeRecording takeRecording();

        /**
         * The clock was declared unlocked or locked again on the source on show.
         *
         * @param locked        {@code false} for an unlock, {@code true} for a relock
         * @param evidenceNanos the {@link System#nanoTime()} of the evidence, or {@code 0} when unknown
         */
        void lockChanged(boolean locked, long evidenceNanos);
    }

    /** Who asked for a read. */
    private enum Asker {
        /** The stream: at its open, or a look while it is open. */
        STREAM,
        /** Settings, a reconfigure or a clock-source selection. */
        SETTINGS
    }

    private final NotificationManager notifications;
    private final TakeClockMarks takeMarks;
    private final StreamClock streamClock;
    private final FxDispatcher dispatcher;
    private final Executor reads;
    private final LongSupplier nanos;
    private final MotionManager motionManager = MotionManager.getDefault();
    private final Timeline flashTimeline;
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state", State.HIDDEN);
    /** Bumped by every read requested, close and loss: a read answers only the generation it was started for. */
    private final AtomicLong generation = new AtomicLong();
    /** Held strongly; registered weakly on the process-lifetime {@link MotionManager}. */
    private final ChangeListener<Boolean> reduceMotionListener = (_, _, _) -> postToFx(this::showState);
    private long settledGeneration;
    private boolean disposed;
    /** The stream the badge last noticed, or {@code null} before the first. */
    private OpenedStream noticed;
    /** Whether the reads in flight were voided for the loss in effect. */
    private boolean hiddenForTheLoss;
    private ClockLabel label;
    private OwnRead ownRead = OwnRead.UNANSWERED;
    /** The device the stream the badge last noticed opened on, or {@code null} before the first. */
    private DeviceId streamDevice;
    private int streamRate;
    private boolean retryArmed;
    private long retryAtNanos;
    /** The status the badge last applied; forgotten at a close or a loss; {@code null} when none. */
    private ClockLockStatus lastApplied;
    /** The lock status at the last look that found the stream open, its device not lost; {@code null} when none. */
    private ClockLockStatus openStatus;
    private boolean labelChanged;

    /**
     * A badge that reads the clock on a virtual thread per read.
     *
     * @param notifications where the unlock and relock notices go
     * @param takeMarks     the take the badge marks
     * @param streamClock   what the badge reads from the engine
     * @param dispatcher    the window's dispatcher, or {@code null} for the default
     */
    ClockStatusIndicator(NotificationManager notifications, TakeClockMarks takeMarks, StreamClock streamClock,
                         FxDispatcher dispatcher) {
        this(notifications, takeMarks, streamClock, dispatcher,
                read -> Thread.ofVirtual().name("daw-clock-source-read").start(read));
    }

    /**
     * A badge whose reads run on {@code reads}.
     *
     * @param notifications where the unlock and relock notices go
     * @param takeMarks     the take the badge marks
     * @param streamClock   what the badge reads from the engine
     * @param dispatcher    the window's dispatcher, or {@code null} for the default
     * @param reads         where each read runs
     */
    ClockStatusIndicator(NotificationManager notifications, TakeClockMarks takeMarks, StreamClock streamClock,
                         FxDispatcher dispatcher, Executor reads) {
        this(notifications, takeMarks, streamClock, dispatcher, reads, System::nanoTime);
    }

    /**
     * A badge whose reads run on {@code reads} and whose read retries are
     * timed by {@code nanos}. FX thread.
     *
     * @param notifications where the unlock and relock notices go
     * @param takeMarks     the take the badge marks
     * @param streamClock   what the badge reads from the engine
     * @param dispatcher    the window's dispatcher, or {@code null} for the default
     * @param reads         where each read runs
     * @param nanos         a monotonic nanosecond clock
     */
    ClockStatusIndicator(NotificationManager notifications, TakeClockMarks takeMarks, StreamClock streamClock,
                         FxDispatcher dispatcher, Executor reads, LongSupplier nanos) {
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.takeMarks = Objects.requireNonNull(takeMarks, "takeMarks must not be null");
        this.streamClock = Objects.requireNonNull(streamClock, "streamClock must not be null");
        this.dispatcher = dispatcher;
        this.reads = Objects.requireNonNull(reads, "reads must not be null");
        this.nanos = Objects.requireNonNull(nanos, "nanos must not be null");
        getStyleClass().addAll(STYLE_CLASS, "numeric-mono");
        setTooltip(new Tooltip());
        // The 0.5 s cadence of an interface's own clock-lock LED.
        flashTimeline = new Timeline(new KeyFrame(Duration.seconds(0.5),
                _ -> pseudoClassStateChanged(FLASH, !getPseudoClassStates().contains(FLASH))));
        flashTimeline.setCycleCount(Animation.INDEFINITE);
        motionManager.reduceMotionProperty().addListener(new WeakChangeListener<>(reduceMotionListener));
        showState();
    }

    /**
     * What the window's badge reads: whether the engine's stream is open,
     * the stream it last opened, the open stream's clock-lock status, the
     * controller's loss, and — for the label — a named device, or the open
     * stream's device, or the configured one while none is open, with the
     * controller's clock sources for it.
     *
     * @param engine     the window's audio engine
     * @param controller the window's engine controller
     * @return the reader
     */
    static StreamClock engineClock(AudioEngine engine, AudioEngineController controller) {
        Objects.requireNonNull(engine, "engine must not be null");
        Objects.requireNonNull(controller, "controller must not be null");
        return new StreamClock() {
            @Override
            public boolean streamOpen() {
                return engine.isStreamOpen();
            }

            @Override
            public boolean deviceLost() {
                return controller.isDeviceLost();
            }

            @Override
            public Optional<ClockLockStatus> lockStatus() {
                return controller.clockLockStatus();
            }

            @Override
            public Optional<OpenedStream> lastOpened() {
                return engine.lastOpenedStream();
            }

            @Override
            public Optional<ClockReading> read() {
                Optional<DeviceId> open = engine.openStreamDevice();
                StreamingProvision provision = engine.getStreamingProvision();
                DeviceId device = open.orElse(provision == null ? null : provision.requestedDevice());
                if (device == null) {
                    return Optional.empty();
                }
                String backend = open.isPresent() ? device.backend() : provision.requestedBackendName();
                return Optional.of(readOn(backend, device));
            }

            @Override
            public Optional<ClockReading> read(DeviceId device) {
                return Optional.of(readOn(device.backend(), device));
            }

            private ClockReading readOn(String backend, DeviceId device) {
                List<ClockSource> sources = controller.clockSources(backend, device.name());
                return new ClockReading(device, sources, sampleRate());
            }

            private int sampleRate() {
                return (int) Math.round(engine.getFormat().sampleRate());
            }
        };
    }

    /**
     * The window's take marks: the current transport's record coordinator.
     *
     * @param transport the window's current transport controller, or a supplier of {@code null} while none
     * @return the marks
     */
    static TakeClockMarks takeMarksOf(Supplier<TransportController> transport) {
        Objects.requireNonNull(transport, "transport must not be null");
        return new TakeClockMarks() {
            @Override
            public TakeRecording takeRecording() {
                TransportController current = transport.get();
                if (current == null || current.recordCoordinator().getState() != RecordState.RECORDING) {
                    return TakeRecording.NONE;
                }
                return current.recordCoordinator().isAudioTakeRecording()
                        ? TakeRecording.AUDIO : TakeRecording.WITHOUT_AUDIO;
            }

            @Override
            public void lockChanged(boolean locked, long evidenceNanos) {
                TransportController current = transport.get();
                if (current != null) {
                    current.recordCoordinator().noteClockEvent(
                            locked ? TakeManifest.ClockEvent.LOCKED : TakeManifest.ClockEvent.UNLOCKED, evidenceNanos);
                }
            }
        };
    }

    /**
     * The controller's stream-opened callback: asks the FX thread for a look
     * now, so the new stream's read starts without waiting for the next
     * pulse. It has no effect of its own: a late, early or repeated call
     * changes nothing, because the stream's identity is read from the engine.
     * Any thread; never blocks.
     */
    void streamOpened() {
        postToFx(this::pulse);
    }

    /**
     * Reads the source and rate again, off the FX thread: after a
     * reconfigure or a clock-source selection. A newer read, a new stream, a
     * close or a loss makes this one's answer void. Any thread; never blocks.
     */
    void refresh() {
        read(Asker.SETTINGS, null);
    }

    /**
     * @param device the device the stream asking opened on, or {@code null}
     *               for Settings' read of the open stream's device, else the configured one
     */
    private void read(Asker asker, DeviceId device) {
        long requested = generation.incrementAndGet();
        reads.execute(() -> {
            Optional<ClockReading> reading;
            try {
                reading = device == null ? streamClock.read() : streamClock.read(device);
            } catch (Throwable unreadable) {
                LOG.log(Level.WARNING, "The clock source could not be read", unreadable);
                // Applying settles the read's generation; without it no look would ever ask again.
                postToFx(() -> apply(requested, asker, Optional.empty(), true));
                if (unreadable instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                return;
            }
            postToFx(() -> apply(requested, asker, reading, false));
        });
    }

    /**
     * One pulse of the window's dispatcher: a look at the engine's levels,
     * the decision shown, and a label read asked for when one is due.
     * Lock-free reads only. FX thread.
     */
    void pulse() {
        if (disposed) {
            return;
        }
        boolean open = look();
        Facts facts = observe(open);
        showTheDecision(facts);
        if (!facts.lost() && open) {
            readIfDue(facts.status());
        }
    }

    /** Stops the flash and ignores every read still in flight. FX thread. */
    void dispose() {
        disposed = true;
        generation.incrementAndGet();
        flashTimeline.stop();
    }

    /** @return what the badge shows */
    ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** @return whether the unlocked flash is running */
    boolean isFlashing() {
        return flashTimeline.getStatus() == Animation.Status.RUNNING;
    }

    /**
     * The short label of a clock kind ("INT", "W/C").
     *
     * @param kind the clock kind; must not be null
     * @return the short label
     */
    public static String labelFor(ClockKind kind) {
        return Objects.requireNonNull(kind, "kind must not be null").shortLabel();
    }

    /**
     * What the badge shows for {@code facts}; nothing else chooses a state.
     *
     * @param facts what the badge knows and reads now
     * @return the state
     */
    static State decide(Facts facts) {
        if (facts.lost() || facts.label() == null) {
            return State.HIDDEN;
        }
        if (!facts.open()) {
            return State.CLOSED;
        }
        return switch (facts.ownRead()) {
            case UNANSWERED -> State.STARTING;
            case NO_SOURCES -> State.HIDDEN;
            case LABELLED -> measured(facts.label().source(), facts.status());
        };
    }

    /** The state of a labelled open stream: E82, E100 and E107. */
    private static State measured(ClockSource source, ClockLockStatus status) {
        if (namesAnotherSource(status, source)) {
            return State.CHECKING;
        }
        if (source.kind() instanceof ClockKind.Internal) {
            return State.QUIET;
        }
        if (status == null || status.lock() == ClockLockStatus.Lock.NOT_MEASURABLE) {
            return State.NOT_MEASURABLE;
        }
        return switch (status.lock()) {
            case LOCKED -> State.QUIET;
            case UNLOCKED -> State.UNLOCKED;
            default -> State.CHECKING;
        };
    }

    private static boolean namesASource(ClockLockStatus status) {
        return status != null && status.sourceId() >= 0;
    }

    private static boolean namesAnotherSource(ClockLockStatus status, ClockSource source) {
        return namesASource(status) && status.sourceId() != source.id();
    }

    /**
     * Reads whether a stream is open, then the stream the engine last opened, in that order: the engine
     * publishes a stream before it reads as open and replaces it only with a later open's, so the record read
     * after an open level is that stream's or a later one, never an earlier one.
     *
     * @return whether a stream is open
     */
    private boolean look() {
        boolean open = streamClock.streamOpen();
        streamClock.lastOpened().ifPresent(this::noticeIfNew);
        return open;
    }

    /**
     * The one place a stream becomes new: a serial the badge has not noticed.
     * The last applied status is kept: a new stream's status has a new serial,
     * so it cannot read as a relock.
     */
    private void noticeIfNew(OpenedStream last) {
        if (noticed != null && noticed.serial() == last.serial()) {
            return;
        }
        noticed = last;
        ownRead = OwnRead.UNANSWERED;
        retryArmed = false;
        streamDevice = last.device();
        streamRate = last.sampleRate();
        forgetALabelTheStatusReplaced();
        if (label != null && (!label.device().equals(streamDevice) || label.sampleRate() != streamRate)) {
            // E142: a label is carried only onto the device and rate it was read at; a name shared by
            // another backend's device, such as every backend's automatic device, is another device.
            forgetLabel();
        }
        read(Asker.STREAM, streamDevice);
    }

    /** A look at a lost device, a closed stream or the open stream's status changes the model; levels read once. */
    private Facts observe(boolean open) {
        boolean lost = streamClock.deviceLost();
        ClockLockStatus status = streamClock.lockStatus().orElse(null);
        if (lost) {
            if (!hiddenForTheLoss) {
                hiddenForTheLoss = true;
                settle();
            }
            forgetLabel();
            lastApplied = null;
            // A declaration that ends with this stream still open has it read again rather than stay hidden.
            ownRead = OwnRead.UNANSWERED;
        } else {
            hiddenForTheLoss = false;
            if (open) {
                openStatus = status;
            } else {
                forgetALabelTheStatusReplaced();
                lastApplied = null;
                if (ownRead == OwnRead.LABELLED) {
                    settle();
                    ownRead = OwnRead.UNANSWERED;
                }
            }
        }
        return new Facts(lost, open, label, ownRead, status);
    }

    /** E144, judged on the label as it is now: a label a re-read already took from the new source stays. */
    private void forgetALabelTheStatusReplaced() {
        if (label != null && namesAnotherSource(openStatus, label.source())) {
            forgetLabel();
        }
        openStatus = null;
    }

    /** The one place a state is shown; notices and marks only for a measured stream (rows 7 to 11). */
    private void showTheDecision(Facts facts) {
        State next = decide(facts);
        boolean measuring = !facts.lost() && facts.open() && facts.ownRead() == OwnRead.LABELLED
                && facts.label() != null && !namesAnotherSource(facts.status(), facts.label().source());
        if (measuring) {
            announce(facts.status(), facts.label().source());
            lastApplied = facts.status();
        }
        show(next);
    }

    /** Asks for the stream's label read when none is in flight, the retry wait is over, and a read can help. */
    private void readIfDue(ClockLockStatus status) {
        if (streamDevice == null || settledGeneration != generation.get()) {
            return;
        }
        if (retryArmed && nanos.getAsLong() - retryAtNanos < 0) {
            return;
        }
        boolean due = switch (ownRead) {
            case UNANSWERED -> true;
            // E32, E135: the device answered that it has none; only a status that names a source says otherwise.
            case NO_SOURCES -> namesASource(status);
            case LABELLED -> label != null && namesAnotherSource(status, label.source());
        };
        if (due) {
            read(Asker.STREAM, streamDevice);
        }
    }

    private void apply(long requested, Asker asker, Optional<ClockReading> reading, boolean failed) {
        if (disposed) {
            return;
        }
        boolean open = look();
        // Looked at before the generation check, as a pulse would have: a close or a loss it has not seen yet
        // voids this read just as it would after a pulse.
        Facts looked = observe(open);
        if (requested == generation.get()) {
            settledGeneration = requested;
            if (!looked.lost()) {
                if (open) {
                    answeredOnAnOpenStream(reading, failed, looked.status());
                } else {
                    answeredOnAClosedStream(asker, reading, failed);
                }
            }
        }
        showTheDecision(new Facts(looked.lost(), open, label, ownRead, looked.status()));
    }

    /** E32, E80, E135 and E141, whoever asked. */
    private void answeredOnAnOpenStream(Optional<ClockReading> reading, boolean failed, ClockLockStatus status) {
        if (failed) {
            armRetry();
            return;
        }
        Optional<ClockSource> current = reading.flatMap(ClockReading::current);
        if (current.isPresent()) {
            takeLabel(reading.get(), current.get());
            ownRead = OwnRead.LABELLED;
            if (namesAnotherSource(status, current.get())) {
                // The list and the measurement disagree; a read every frame would not settle it sooner.
                armRetry();
            } else {
                retryArmed = false;
            }
            return;
        }
        if (ownRead == OwnRead.LABELLED || namesASource(status)) {
            // E80 keeps a measured stream's label; E141: a list the status contradicts is read again.
            armRetry();
            return;
        }
        forgetLabel();
        ownRead = OwnRead.NO_SOURCES;
        retryArmed = false;
    }

    /**
     * A read that answers with no stream open. The stream's own read settles
     * the closed view when it is about the device that stream opened (E119
     * (d), E136); a read asked for by Settings or a reconfigure keeps the
     * label only when it provably names the clock on show (E81).
     */
    private void answeredOnAClosedStream(Asker asker, Optional<ClockReading> reading, boolean failed) {
        lastApplied = null;
        Optional<ClockSource> current = reading.flatMap(ClockReading::current);
        if (asker == Asker.STREAM) {
            ownRead = OwnRead.UNANSWERED;
            if (failed) {
                return;
            }
            boolean aboutTheStream = reading.isPresent() && reading.get().device().equals(streamDevice);
            if (aboutTheStream && current.isPresent()) {
                takeLabel(reading.get(), current.get());
            } else {
                forgetLabel();
            }
            return;
        }
        if (current.isEmpty() || !showsTheSameClock(reading.get())) {
            forgetLabel();
        }
    }

    private void takeLabel(ClockReading read, ClockSource current) {
        ClockLabel next = new ClockLabel(read.device(), current, read.sampleRate());
        labelChanged |= !next.equals(label);
        label = next;
    }

    private void forgetLabel() {
        labelChanged |= label != null;
        label = null;
    }

    private void armRetry() {
        retryArmed = true;
        retryAtNanos = nanos.getAsLong() + READ_RETRY_NANOS;
    }

    /** E31, E108, E110 and E111: notices and marks from the difference of two applied statuses. */
    private void announce(ClockLockStatus current, ClockSource source) {
        if (current == null || source.kind() instanceof ClockKind.Internal) {
            return;
        }
        boolean wasUnlocked = lastApplied != null && lastApplied.serial() == current.serial()
                && lastApplied.lock() == ClockLockStatus.Lock.UNLOCKED;
        String deviceName = deviceName(current.device());
        if (current.lock() == ClockLockStatus.Lock.UNLOCKED && !wasUnlocked) {
            String key = switch (takeMarks.takeRecording()) {
                case AUDIO -> "clockBadge.notice.unlocked.take";
                case WITHOUT_AUDIO -> "clockBadge.notice.unlocked.takeWithoutAudio";
                case NONE -> "clockBadge.notice.unlocked";
            };
            notifications.notify(EngineHealthText.text(key, deviceName, source.name()));
            takeMarks.lockChanged(false, current.evidenceNanos());
        } else if (current.lock() == ClockLockStatus.Lock.LOCKED && wasUnlocked) {
            notifications.notifyInformation(
                    EngineHealthText.text("clockBadge.notice.relocked", deviceName, source.name()));
            takeMarks.lockChanged(true, current.evidenceNanos());
        }
    }

    /** The device the driver side names (E111), never the default placeholder; a DeviceId name is never blank. */
    private static String deviceName(DeviceId measured) {
        return measured.isDefault() ? EngineHealthText.text("clockBadge.notice.unnamedDevice") : measured.name();
    }

    /** A close of a measured stream or a loss: whatever a read in flight answers is void. */
    private void settle() {
        settledGeneration = generation.incrementAndGet();
    }

    /** Whether the read device, rate and selected source are the ones on show. */
    private boolean showsTheSameClock(ClockReading read) {
        Optional<ClockSource> current = read.current();
        return label != null && current.isPresent()
                && label.device().equals(read.device())
                && read.sampleRate() == label.sampleRate()
                && current.get().id() == label.source().id() && current.get().kind().equals(label.source().kind());
    }

    private void show(State next) {
        if (next == state.get() && !labelChanged) {
            return;
        }
        labelChanged = false;
        state.set(next);
        showState();
    }

    private void showState() {
        State shown = state.get();
        ClockLabel onShow = label;
        boolean visible = shown != State.HIDDEN && onShow != null;
        setVisible(visible);
        setManaged(visible);
        getStyleClass().removeAll(DIM_CLASS, LOCKED_CLASS, UNLOCKED_CLASS);
        if (!visible) {
            stopFlash();
            setText(null);
            setAccessibleText(null);
            getTooltip().setText(null);
            return;
        }
        String text = onShow.source().kind().shortLabel() + " " + kilohertz(onShow.sampleRate()) + "k";
        String name = onShow.source().name();
        String rate = kilohertz(onShow.sampleRate());
        String tooltip;
        switch (shown) {
            case CLOSED -> {
                getStyleClass().add(DIM_CLASS);
                setAccessibleText(EngineHealthText.text("clockBadge.spoken.closed", name, rate));
                tooltip = EngineHealthText.text("clockBadge.tooltip.closed");
            }
            case STARTING -> {
                getStyleClass().add(DIM_CLASS);
                setAccessibleText(EngineHealthText.text("clockBadge.spoken.starting", name, rate));
                tooltip = EngineHealthText.text("clockBadge.tooltip.starting");
            }
            case CHECKING -> {
                getStyleClass().add(DIM_CLASS);
                setAccessibleText(EngineHealthText.text("clockBadge.spoken.checking", name, rate));
                tooltip = EngineHealthText.text("clockBadge.tooltip.checking");
            }
            case NOT_MEASURABLE -> {
                getStyleClass().add(DIM_CLASS);
                setAccessibleText(EngineHealthText.text("clockBadge.spoken.notMeasurable", name, rate));
                tooltip = EngineHealthText.text("clockBadge.tooltip.notMeasurable");
            }
            case QUIET -> {
                getStyleClass().add(LOCKED_CLASS);
                setAccessibleText(EngineHealthText.text("clockBadge.spoken.quiet", name, rate));
                tooltip = EngineHealthText.text("clockBadge.tooltip");
            }
            default -> {
                getStyleClass().add(UNLOCKED_CLASS);
                setAccessibleText(EngineHealthText.text("clockBadge.spoken.unlocked", name, rate));
                tooltip = EngineHealthText.text("clockBadge.tooltip.unlocked");
                text = EngineHealthText.text("clockBadge.unlocked.text", text);
            }
        }
        setText(text);
        getTooltip().setText(tooltip);
        if (shown == State.UNLOCKED) {
            startFlash();
        } else {
            stopFlash();
        }
    }

    /** The rate as the label writes it without its "k": "48", "44.1". */
    private static String kilohertz(int rate) {
        return rate % 1000 == 0
                ? Integer.toString(rate / 1000)
                : String.format(Locale.ROOT, "%.1f", rate / 1000.0);
    }

    private void startFlash() {
        if (!motionManager.isAnimationAllowed() || disposed) {
            // Reduce Motion: the solid unlocked treatment and the "⚠ … UNLOCKED" text carry the state.
            stopFlash();
            return;
        }
        if (flashTimeline.getStatus() != Animation.Status.RUNNING) {
            flashTimeline.playFromStart();
        }
    }

    private void stopFlash() {
        flashTimeline.stop();
        pseudoClassStateChanged(FLASH, false);
    }

    private void postToFx(Runnable work) {
        boolean onFx = dispatcher != null ? dispatcher.isFxThread() : Platform.isFxApplicationThread();
        if (onFx) {
            work.run();
        } else {
            FxDispatcher.runOnFx(dispatcher, work);
        }
    }
}
