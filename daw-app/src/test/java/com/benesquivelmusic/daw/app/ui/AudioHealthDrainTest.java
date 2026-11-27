package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.AudioHealthDrain;
import com.benesquivelmusic.daw.app.diagnostics.EngineFault;
import com.benesquivelmusic.daw.app.diagnostics.Fingerprint;
import com.benesquivelmusic.daw.app.diagnostics.HaltContext;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureCodes;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthLane;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.audio.health.LaneRole;
import com.benesquivelmusic.daw.core.automation.PluginParameterTarget;
import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 337, Goals — Tests bullet 8 (rulings E11, E13, E27, E40–E45; Failure
 * Surfacing book §3.2 channel B, §6.2): the audio health drain turns what the
 * real-time threads recorded into notices through the funnel. A first
 * device-callback failure is a WARNING at once, naming the device and the
 * reason, with Restart Audio Engine; repeats inside the 30 s window are
 * counted by their exact delta and re-shown after it with the weighted total;
 * a fingerprint failing in three consecutive one-second buckets escalates once
 * to the ERROR fact, which latches the engine fault and stops the WARNING
 * re-shows until it clears — on a successful restart, or after one fault-free
 * second of render-drive heartbeats on a newer render drive. A halt reacts at once:
 * the transport is stopped, the stream is closed — retried until the take
 * allows it — and one ERROR notice names what the halt's Stop did; a
 * {@link VirtualMachineError} halt uses the FATAL copy with Open Log Folder. A
 * worker failure every block escalates once instead of toasting per
 * occurrence, a first worker failure in the same frame as a first render
 * failure is one notice, and nothing counted before the drain existed is lost.
 * A render failure in a halt's frame is folded into the halt's notice with a
 * record of its own, a return-bus failure whose sample was lost states the cap
 * without a bus count, and a category latched before a restart no longer keeps
 * the next fact latched.
 *
 * <p>Story 337's code review round (rulings E69 to E89): a halt whose stream
 * was already replaced stops and closes nothing, shows the halt copy without
 * a context sentence and clears on the drive already running; a halt latches
 * with the epoch read at the frame's start; a frame is one reading, so a
 * failure sampled before it is counted — recorded while the frame is read, or
 * just before its lane retired — is still named, two parameters are each
 * named once, an input halt is counted once and the backend is read once per
 * frame unless a device is closed or reopened during that read (ruling E98);
 * a backend's failing report costs the frame nothing; an input halt's fact
 * clears only on a stream opened after it; and an effect is named as its
 * insert slot names it, while a name that cannot be read costs nothing.</p>
 *
 * <p>The funnel runs over the test's clocks ({@link FailureSpineFixture#withClock})
 * armed on a dispatcher and a notification bar; the drain reads the same
 * clocks. Device-side failures come from the backend ledger — a counter source
 * attached to the record, which the test advances — and render-side ones from
 * lanes the test claims and writes from its own thread, the lane's one writer.
 * Each frame is the drain's {@link AudioHealthDrain#pulse()} run on the FX
 * thread, followed by one more FX turn for the toasts it posted. The real
 * wiring — the window registering the drain on its dispatcher over its engine's
 * record — is {@code FailureSpineStartupWiringTest}'s.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class AudioHealthDrainTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final String DEVICE = "Test Interface";
    private static final String RESTART = "Restart Audio Engine";
    private static final String OPEN_LOG_FOLDER = "Open Log Folder";
    private static final Fingerprint CALLBACK = Fingerprint.ofSite("audio.callback", "device-callback");
    private static final String CALLBACK_FIRST = "The audio callback for “" + DEVICE + "” failed, so a block of"
            + " output was played as silence: the driver returned a short buffer. Audio continues.";
    /** Story 337, ruling E21: the descriptor ring of one lane holds this many unread records. */
    private static final int LANE_SLOTS = 16;
    /** Story 337, ruling E80: of them, the streaming categories may fill this many. */
    private static final int STREAMING_SLOTS = 12;
    private static final PluginParameterTarget CUTOFF =
            new PluginParameterTarget("insert-7", 3, "Cutoff", 0.0, 1.0, 0.5, "");
    private static final PluginParameterTarget DRIVE =
            new PluginParameterTarget("insert-7", 4, "Drive", 0.0, 1.0, 0.5, "");
    private static final String CUTOFF_NOTICE = "Automation of “Cutoff” stopped because its plugin rejected a value:"
            + " value out of range. The parameter keeps its last value; restarting the audio engine retries its"
            + " automation.";
    private static final String HALT_NOTICE = "The audio engine stopped because audio processing failed for a full"
            + " second without recovering: the mixer lost its master bus. Restart the audio engine to continue.";
    /** The input's lost-block warning with an input halt's reason (story 338, ruling E155). */
    private static final String HALT_AS_LOST_INPUT = "Input from “" + DEVICE + "” was lost for a block: the input line"
            + " closed. If you are recording, the take has a gap there.";
    private static final String LOST_BLOCK_WARNING = "Input from “" + DEVICE + "” was lost for a block: a captured"
            + " block was dropped. If you are recording, the take has a gap there.";

    private final AtomicLong nanos = new AtomicLong(Duration.ofSeconds(100).toNanos());
    private final Instant noon = LocalDate.now(ZONE).atTime(LocalTime.NOON).atZone(ZONE).toInstant();
    private final AtomicReference<Instant> wall = new AtomicReference<>(noon);
    private final AtomicInteger restarts = new AtomicInteger();
    private final AtomicReference<BackendThreadCounters> counters = new AtomicReference<>(BackendThreadCounters.NONE);
    private final AudioHealthRecord record = new AudioHealthRecord();
    private final ScriptedHaltResponse halt = new ScriptedHaltResponse();
    private final IllegalStateException shortBuffer = new IllegalStateException("the driver returned a short buffer");
    private final IllegalStateException lineClosed = new IllegalStateException("the input line closed");
    private final IllegalStateException blockDropped = new IllegalStateException("a captured block was dropped");

    @Test
    void theFirstCallbackFailureSurfacesAWarningAtOnce(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);

            callbackFaults(1);
            pulse(drain);

            assertThat(entries(spine, CALLBACK_FIRST)).singleElement().satisfies(entry -> {
                assertThat(entry.message()).isEqualTo(CALLBACK_FIRST);
                assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                assertThat(entry.actionLabel()).as("a stream restart can cure a callback failure (ruling E13)")
                        .hasValue(RESTART);
                entry.action().orElseThrow().run();
            });
            assertThat(restarts).as("the action is the window's Restart Audio Engine").hasValue(1);
            assertThat(records(spine, CALLBACK)).singleElement().satisfies(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getMessage()).startsWith("Failure audio.callback (WARNING) at \"device-callback\","
                        + " occurrence 1, fingerprint audio.callback|device-callback");
                assertThat(record.getThrown()).isSameAs(shortBuffer);
            });
            assertThat(onFx(drain::engineFault)).as("a first occurrence latches no fault").isEmpty();
        }
    }

    @Test
    void repeatsInsideTheWindowAreCountedSilently(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);

            callbackFaults(2);
            pulse(drain);
            advance(Duration.ofSeconds(10));
            callbackFaults(5);
            pulse(drain);
            advance(Duration.ofSeconds(10));
            callbackFaults(6);
            pulse(drain);

            assertThat(entries(spine, CALLBACK_FIRST)).as("inside the window: counted, not shown").hasSize(1);
            assertThat(records(spine, CALLBACK)).hasSize(1);
            assertThat(spine.funnel().occurrenceTotals()).as("by the exact counter deltas (ruling E39)")
                    .containsEntry(CALLBACK.key(), 6L);
        }
    }

    @Test
    void aRepeatAfterTheWindowResurfacesWithTheAccumulatedCount(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);

            callbackFaults(2);
            pulse(drain);
            advance(Duration.ofSeconds(10));
            callbackFaults(5);
            pulse(drain);
            advance(Duration.ofSeconds(21));
            callbackFaults(9);
            pulse(drain);

            assertThat(entries(spine, CALLBACK_FIRST)).hasSize(2).last().satisfies(entry -> {
                assertThat(entry.message()).isEqualTo(CALLBACK_FIRST + " It has happened 9 times since "
                        + clock(noon) + ".");
                assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                assertThat(entry.actionLabel()).hasValue(RESTART);
            });
        }
    }

    @Test
    void sustainedFailureLatchesTheEngineFaultWithARestartAction(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);

            sustainCallbackFaults(drain);

            String fault = "Audio engine fault — 3 callback errors since " + clock(noon) + ": the driver returned a"
                    + " short buffer. Audio may stay silent or broken until you restart the audio engine.";
            assertThat(entries(spine, "Audio engine fault")).singleElement().satisfies(entry -> {
                assertThat(entry.message()).isEqualTo(fault);
                assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                assertThat(entry.actionLabel()).hasValue(RESTART);
                entry.action().orElseThrow().run();
            });
            assertThat(restarts).as("the fact's action runs the window's restart").hasValue(1);
            assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact -> {
                assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK);
                assertThat(fact.count()).isEqualTo(3);
                assertThat(fact.firstSeen()).isEqualTo(noon);
                assertThat(fact.lastSeen()).isEqualTo(noon.plusSeconds(2));
                assertThat(fact.action().label()).isEqualTo(RESTART);
            });
            assertThat(spine.funnel().isEscalated(CALLBACK)).isTrue();

            advance(Duration.ofSeconds(31));
            callbackFaults(7);
            pulse(drain);

            assertThat(entries(spine, CALLBACK_FIRST)).as("latched: no WARNING re-show (ruling E44)").hasSize(1);
            assertThat(entries(spine, "Audio engine fault")).as("and no second escalation").hasSize(1);
            assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact -> {
                assertThat(fact.count()).as("the fact follows its fingerprint's count").isEqualTo(7);
                assertThat(fact.lastSeen()).isEqualTo(noon.plusSeconds(33));
            });
            assertThat(spine.funnel().occurrenceTotals()).containsEntry(CALLBACK.key(), 7L);
        }
    }

    @Test
    void aNegativeMonotonicClockEscalatesAtItsThirdConsecutiveSecond(@TempDir Path settings) throws Exception {
        nanos.set(Duration.ofSeconds(-4).toNanos());
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);

            sustainCallbackFaults(drain);

            assertThat(entries(spine, "Audio engine fault")).as("buckets -4, -3 and -2 are consecutive")
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.message()).startsWith("Audio engine fault — 3 callback errors since "
                                + clock(noon) + ":");
                        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                    });
            assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact ->
                    assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));
            assertThat(spine.funnel().isEscalated(CALLBACK)).isTrue();
        }
    }

    @Test
    void aSuccessfulRestartClearsTheFault(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            List<Optional<EngineFault>> published = new CopyOnWriteArrayList<>();
            runOnFx(() -> drain.engineFaultProperty().addListener((property, before, after) -> published.add(after)));
            sustainCallbackFaults(drain);
            assertThat(onFx(drain::engineFault)).isPresent();

            runOnFx(drain::clearEngineFault);

            assertThat(onFx(drain::engineFault)).isEmpty();
            assertThat(published).as("the property published the fact, then its clearing").last()
                    .isEqualTo(Optional.empty());
            assertThat(spine.funnel().isEscalated(CALLBACK)).as("its fingerprint follows the window rule again")
                    .isFalse();
            advance(Duration.ofSeconds(31));
            callbackFaults(4);
            pulse(drain);
            assertThat(entries(spine, CALLBACK_FIRST)).as("a WARNING re-show is possible again").hasSize(2);
            assertThat(onFx(drain::engineFault)).as("one bucket is not sustained").isEmpty();
        }
    }

    @Test
    void aFaultFreeSecondOnANewStreamClearsTheFault(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane faultedStream = record.claimLane(LaneRole.RENDER_DRIVE);
            sustainCallbackFaults(drain);

            for (int frame = 0; frame < 4; frame++) {
                faultedStream.beat();
                advance(Duration.ofMillis(500));
                pulse(drain);
            }
            assertThat(onFx(drain::engineFault)).as("beats of the stream that was open at the latch do not clear it")
                    .isPresent();

            AudioHealthLane newerStream = record.claimLane(LaneRole.RENDER_DRIVE);
            newerStream.beat();
            pulse(drain);
            newerStream.beat();
            advance(Duration.ofMillis(600));
            pulse(drain);
            assertThat(onFx(drain::engineFault)).as("not a full second yet").isPresent();
            newerStream.beat();
            advance(Duration.ofMillis(400));
            pulse(drain);

            assertThat(onFx(drain::engineFault)).as("one fault-free second of heartbeats on a newer render drive (E41)")
                    .isEmpty();
            assertThat(spine.funnel().isEscalated(CALLBACK)).isFalse();
        }
    }

    @Test
    void aHaltReactsAtOnceStopsTheTransportAndClosesTheStream(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            halt.context = HaltContext.RECORDING;
            halt.closeAnswers.add(false);
            halt.closeAnswers.add(true);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            drive.record(AudioFailureCodes.RENDER_HALTED, new IllegalStateException("the mixer lost its master bus"),
                    new Object(), 375);
            pulse(drain);

            assertThat(halt.stops).as("the transport stopped at once").hasValue(1);
            assertThat(halt.closes).as("the stream's close attempted at once").hasValue(1);
            assertThat(halt.retries).as("the halt frame's own attempt is not a retry").hasValue(0);
            String notice = "The audio engine stopped because audio processing failed for a full second without"
                    + " recovering: the mixer lost its master bus. Recording was stopped; the take keeps what was"
                    + " recorded before the audio failed. Restart the audio engine to continue.";
            assertThat(entries(spine, "The audio engine stopped")).singleElement().satisfies(entry -> {
                assertThat(entry.message()).isEqualTo(notice);
                assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                assertThat(entry.actionLabel()).hasValue(RESTART);
            });
            assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact -> {
                assertThat(fact.category()).isEqualTo(AudioFailureCategory.RENDER_HALTED);
                assertThat(fact.action().label()).isEqualTo(RESTART);
            });

            pulse(drain);
            assertThat(halt.retries).as("the take deferred the close: retried on the next frame, through the"
                    + " side-effect-free retry (ruling E66)").hasValue(1);
            assertThat(halt.closes).as("only the halt frame's attempt acts").hasValue(1);
            pulse(drain);
            assertThat(halt.retries).as("closed: nothing more owed").hasValue(1);
            assertThat(halt.closes).hasValue(1);
            assertThat(halt.stops).as("the Stop ran once").hasValue(1);
            assertThat(entries(spine, "The audio engine stopped")).as("one notice").hasSize(1);
        }
    }

    @Test
    void aRenderFailureInTheFrameOfAHaltIsFoldedIntoTheHaltNoticeWithARecordOfItsOwn(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            IllegalStateException renderFailure = new IllegalStateException("the chain threw again");

            drive.record(AudioFailureCodes.RENDER, renderFailure, null, 1);
            drive.record(AudioFailureCodes.RENDER, renderFailure, null, 1);
            drive.record(AudioFailureCodes.RENDER_HALTED, new IllegalStateException("the mixer lost its master bus"),
                    null, 375);
            pulse(drain);

            assertThat(entries(spine, "")).as("one notice, the halt's: the render failure is folded into it (ruling"
                            + " E27), and no notice says playback continues over a dead drive")
                    .extracting(NotificationEntry::message).containsExactly("The audio engine stopped because audio"
                            + " processing failed for a full second without recovering: the mixer lost its master"
                            + " bus. Restart the audio engine to continue.");
            assertThat(records(spine, Fingerprint.ofSite("audio.render", "render-drive")))
                    .as("the folded render failure writes its own record, without a toast (ruling E65)")
                    .singleElement().satisfies(logged -> {
                        assertThat(logged.getLevel()).isEqualTo(Level.WARNING);
                        assertThat(logged.getMessage()).startsWith("Failure audio.render (WARNING) at"
                                        + " \"render-drive\", occurrence 2, fingerprint audio.render|render-drive")
                                .contains("folded into the notice of the same frame; no toast of its own");
                        assertThat(logged.getThrown()).isSameAs(renderFailure);
                    });
            assertThat(spine.funnel().occurrenceTotals()).as("both counted, by their exact deltas")
                    .containsEntry("audio.render|render-drive", 2L)
                    .containsEntry("audio.render.halted|render-drive", 1L);
        }
    }

    @Test
    void aHaltCausedByAVirtualMachineErrorUsesTheFatalCopyWithoutRestart(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            halt.context = HaltContext.PLAYBACK;
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            drive.record(AudioFailureCodes.RENDER_HALTED, new StackOverflowError("the render graph recursed"), null, 1);
            pulse(drain);

            String notice = "Serious internal error: the application may not work correctly from now on. When you"
                    + " can, save a copy with File ▸ Save As… and restart. Reason: the render graph recursed."
                    + " Playback was stopped.";
            assertThat(entries(spine, "Serious internal error")).singleElement().satisfies(entry -> {
                assertThat(entry.message()).isEqualTo(notice);
                assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                assertThat(entry.actionLabel()).as("no restart for a JVM error (ruling E13)")
                        .hasValue(OPEN_LOG_FOLDER);
            });
            assertThat(records(spine, Fingerprint.ofSite("audio.render.halted", "render-drive")))
                    .singleElement().satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
                        assertThat(record.getMessage()).startsWith("Failure audio.render.halted (FATAL)");
                    });
            assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact ->
                    assertThat(fact.action().label()).isEqualTo(OPEN_LOG_FOLDER));
            assertThat(halt.stops).hasValue(1);
        }
    }

    @Test
    void aWorkerFailureEveryBlockEscalatesOnceInsteadOfToastingPerOccurrence(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
            IllegalStateException chain = new IllegalStateException("an insert chain threw");
            String first = "The inserts of a track failed during multi-core processing and were run again for that"
                    + " block: an insert chain threw. If it keeps happening, check the inserts on your tracks.";
            String sustained = "Multi-core processing keeps failing — 5 errors since " + clock(noon) + ": an insert"
                    + " chain threw. The affected tracks are processed again on one core, which costs processor"
                    + " time; check the inserts on your tracks.";

            for (int frame = 0; frame < 5; frame++) {
                worker.record(AudioFailureCodes.WORKER, chain, null, frame);
                pulse(drain);
                if (frame == 0) {
                    assertThat(entries(spine, first)).as("the first occurrence: one WARNING").hasSize(1);
                }
                advance(Duration.ofMillis(500));
            }

            assertThat(entries(spine, first)).as("none per occurrence inside the window").hasSize(1);
            assertThat(entries(spine, "Multi-core processing keeps failing"))
                    .as("ERROR at the third one-second bucket, with the worker's own sentence (ruling E64)")
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.message()).isEqualTo(sustained);
                        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                        assertThat(entry.actionLabel()).as("a render-pipeline category keeps Open Log Folder (E13)")
                                .hasValue(OPEN_LOG_FOLDER);
                    });

            for (int frame = 0; frame < 6; frame++) {
                worker.record(AudioFailureCodes.WORKER, chain, null, frame);
                pulse(drain);
                advance(Duration.ofMillis(500));
            }
            assertThat(entries(spine, first)).as("still one WARNING").hasSize(1);
            assertThat(entries(spine, "Multi-core processing keeps failing")).as("escalated once").hasSize(1);
            assertThat(spine.funnel().occurrenceTotals()).containsEntry("audio.worker|worker-pool", 11L);
        }
    }

    @Test
    void aFirstWorkerFailureCoincidingWithAFirstRenderFailureIsOneNotice(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
            IllegalStateException renderFailure = renderFailure();
            IllegalStateException workerFailure = workerFailure();

            worker.record(AudioFailureCodes.WORKER, workerFailure, null, 2);
            drive.record(AudioFailureCodes.RENDER, renderFailure, null, 1);
            pulse(drain);

            String renderNotice = "Audio processing failed, so a block was played as silence: the chain was re-run"
                    + " and threw again. Playback continues.";
            assertThat(entries(spine, "")).as("one notice, the render one (ruling E27)")
                    .extracting(NotificationEntry::message).containsExactly(renderNotice);
            String renderAnchor = Fingerprint.of("audio.render", Optional.empty(), renderFailure).anchor();
            String workerAnchor = Fingerprint.of("audio.worker", Optional.empty(), workerFailure).anchor();
            assertThat(records(spine, Fingerprint.ofSite("audio.render", "render-drive"))).singleElement()
                    .satisfies(record -> assertThat(record.getMessage())
                            .endsWith(", sampled anchors " + renderAnchor + "; " + workerAnchor));
            assertThat(records(spine, Fingerprint.ofSite("audio.worker", "worker-pool")))
                    .as("the worker failure writes its own record, without a toast (ruling E65)")
                    .singleElement().satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                        assertThat(record.getMessage()).startsWith("Failure audio.worker (WARNING) at \"worker-pool\","
                                + " occurrence 1, fingerprint audio.worker|worker-pool")
                                .contains("folded into the notice of the same frame; no toast of its own");
                    });
            assertThat(spine.funnel().occurrenceTotals()).as("both counted")
                    .containsEntry("audio.render|render-drive", 1L).containsEntry("audio.worker|worker-pool", 1L);
        }
    }

    @Test
    void nothingCountedBeforeTheDrainExistedIsLost(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            callbackFaults(4);
            drive.record(AudioFailureCodes.RENDER, new IllegalStateException("a block failed early"), null, 1);
            drive.record(AudioFailureCodes.RENDER, new IllegalStateException("a block failed early"), null, 2);

            AudioHealthDrain drain = drain(spine);
            pulse(drain);

            assertThat(entries(spine, CALLBACK_FIRST)).hasSize(1);
            assertThat(entries(spine, "Audio processing failed, so a block was played as silence: a block failed"
                    + " early.")).hasSize(1);
            assertThat(spine.funnel().occurrenceTotals()).as("the baselines start at zero (ruling E42)")
                    .containsEntry(CALLBACK.key(), 4L).containsEntry("audio.render|render-drive", 2L);
        }
    }

    @Test
    void aRenderFailureAnchoredInAPluginNamesThePlugin(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            IllegalStateException inPlugin = new IllegalStateException("the filter state went NaN");
            inPlugin.setStackTrace(new StackTraceElement[] {
                    new StackTraceElement("com.example.fuzz.FuzzPlugin$Voice", "render", "FuzzPlugin.java", 42)});

            drive.record(AudioFailureCodes.RENDER, inPlugin, null, 1);
            pulse(drain);

            assertThat(entries(spine, "Audio processing failed in")).extracting(NotificationEntry::message)
                    .containsExactly("Audio processing failed in “FuzzPlugin”, so a block was played as silence: the"
                            + " filter state went NaN. Playback continues; if it happens again, bypass “FuzzPlugin”.");
        }
    }

    @Test
    void anAutomationFailureIsReportedOncePerParameterWithItsName(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            PluginParameterTarget cutoff = new PluginParameterTarget("insert-7", 3, "Cutoff", 0.0, 1.0, 0.5, "");
            PluginParameterTarget drive2 = new PluginParameterTarget("insert-7", 4, "Drive", 0.0, 1.0, 0.5, "");

            drive.record(AudioFailureCodes.AUTOMATION, new IllegalArgumentException("value out of range"), cutoff, 3);
            drive.record(AudioFailureCodes.AUTOMATION, new IllegalArgumentException("not now"), drive2, 4);
            pulse(drain);

            assertThat(entries(spine, "Automation of")).extracting(NotificationEntry::message).containsExactly(
                    "Automation of “Cutoff” stopped because its plugin rejected a value: value out of range. The"
                            + " parameter keeps its last value; restarting the audio engine retries its automation.",
                    "Automation of “Drive” stopped because its plugin rejected a value: not now. The parameter"
                            + " keeps its last value; restarting the audio engine retries its automation.");
            assertThat(entries(spine, "Automation of")).allSatisfy(entry -> {
                assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                assertThat(entry.actionLabel()).hasValue(OPEN_LOG_FOLDER);
            });
            assertThat(spine.funnel().occurrenceTotals()).as("one fingerprint per parameter (ruling E40)")
                    .containsEntry("audio.automation|insert-7#3", 1L)
                    .containsEntry("audio.automation|insert-7#4", 1L);
        }
    }

    @Test
    void theReturnBusCapIsOneWarningNamingTheBusCount(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            drive.record(AudioFailureCodes.CONFIG, null, "returnBusCap", 18);
            pulse(drain);

            assertThat(entries(spine, "This mix has")).singleElement().satisfies(entry -> {
                assertThat(entry.message()).isEqualTo("This mix has 18 return buses but only 16 can receive sends,"
                        + " so sends to the others are silent. Remove return buses you do not need.");
                assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                assertThat(entry.actionLabel()).hasValue(OPEN_LOG_FOLDER);
            });
            assertThat(records(spine, Fingerprint.ofSite("audio.config", "returnBusCap"))).singleElement()
                    .satisfies(record -> assertThat(record.getThrown()).as("no throwable to show").isNull());
        }
    }

    @Test
    void aReturnBusFailureWithNoSampleStatesTheCapWithoutABusCount(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            // A full ring: the return-bus record is counted, but its sample — the only carrier of the bus
            // count — is lost (story 337, ruling E40). Streaming records fill at most STREAMING_SLOTS slots and
            // one-shot records the rest (story 337, ruling E80), so both are needed to fill all of them.
            for (int slot = 0; slot < STREAMING_SLOTS; slot++) {
                drive.record(AudioFailureCodes.RENDER, null, null, 0);
            }
            for (int slot = STREAMING_SLOTS; slot < LANE_SLOTS; slot++) {
                drive.record(AudioFailureCodes.AUTOMATION, null, null, 0);
            }
            drive.record(AudioFailureCodes.CONFIG, null, "returnBusCap", 18);
            pulse(drain);

            assertThat(entries(spine, "This mix has")).as("no sample stated a bus count, so the notice states"
                    + " none (ruling E64)").singleElement().satisfies(entry -> {
                        assertThat(entry.message()).isEqualTo("This mix has more return buses than the 16 that can"
                                + " receive sends, so sends to the others are silent. Remove return buses you do"
                                + " not need.");
                        assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    });
        }
    }

    @Test
    void anInputHaltEscalatesAtOnceWithTheDeviceFaultCopyAndRestart(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);

            counters.set(new BackendThreadCounters(0, 0, 1, 0, OptionalLong.empty(), Optional.empty(),
                    Optional.of(new IllegalStateException("the input line closed")), Optional.empty()));
            pulse(drain);

            assertThat(entries(spine, "Audio device fault")).singleElement().satisfies(entry -> {
                assertThat(entry.message()).isEqualTo("Audio device fault — 1 error from “" + DEVICE + "” since "
                        + clock(noon) + ": the input line closed. Audio may stay silent or broken until you restart"
                        + " the audio engine.");
                assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                assertThat(entry.actionLabel()).hasValue(RESTART);
            });
            assertThat(entries(spine, "Input from")).as("escalated at once, not a first-occurrence WARNING").isEmpty();
            assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact ->
                    assertThat(fact.category()).isEqualTo(AudioFailureCategory.INPUT));
        }
    }

    /**
     * Story 337, ruling E94: an input halt's notice — its reason and its log
     * record's throwable — names the input that stopped, also when an input
     * device opened after it lost a block in the same frame.
     */
    @Test
    void anInputHaltNoticeQuotesTheHaltedInputWhenAnotherInputLostABlockInTheSameFrame(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AtomicReference<BackendThreadCounters> secondInput = new AtomicReference<>(BackendThreadCounters.NONE);
            record.attachInputCounters(new Object(), () -> "Second Interface", secondInput::get);
            IllegalStateException halt = new IllegalStateException("the input line closed");

            counters.set(new BackendThreadCounters(0, 0, 1, 0, OptionalLong.empty(), Optional.empty(),
                    Optional.of(halt), Optional.empty()));
            secondInput.set(new BackendThreadCounters(0, 1, 0, 0, OptionalLong.empty(), Optional.empty(),
                    Optional.of(new IllegalStateException("a captured block was dropped")), Optional.empty()));
            pulse(drain);

            assertThat(entries(spine, "Audio device fault")).singleElement().extracting(NotificationEntry::message)
                    .isEqualTo("Audio device fault — 2 errors from “" + DEVICE + "” since " + clock(noon) + ": the"
                            + " input line closed. Audio may stay silent or broken until you restart the audio"
                            + " engine.");
            assertThat(records(spine, Fingerprint.ofSite("audio.input", "input-capture"))).singleElement()
                    .satisfies(logged -> assertThat(logged.getThrown()).isSameAs(halt));
        }
    }

    /**
     * Story 337, rulings E94 and E99: an input halt whose input kept no fault
     * gets the site reason, never the fault of an input opened after it that
     * only lost a block in the same frame.
     */
    @Test
    void anInputHaltWithoutAFaultGetsTheSiteReasonNotAnotherInputsLostBlock(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AtomicReference<BackendThreadCounters> secondInput = new AtomicReference<>(BackendThreadCounters.NONE);
            record.attachInputCounters(new Object(), () -> "Second Interface", secondInput::get);

            counters.set(new BackendThreadCounters(0, 0, 1, 0, OptionalLong.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty()));
            secondInput.set(new BackendThreadCounters(0, 1, 0, 0, OptionalLong.empty(), Optional.empty(),
                    Optional.of(new IllegalStateException("a captured block was dropped")), Optional.empty()));
            pulse(drain);

            assertThat(entries(spine, "Audio device fault")).singleElement().extracting(NotificationEntry::message)
                    .isEqualTo("Audio device fault — 2 errors from “" + DEVICE + "” since " + clock(noon) + ": "
                            + "input-capture. Audio may stay silent or broken until you restart the audio engine.");
            assertThat(records(spine, Fingerprint.ofSite("audio.input", "input-capture"))).singleElement()
                    .satisfies(logged -> assertThat(logged.getThrown()).as("never the other input's lost block")
                            .isNull());
        }
    }

    @Test
    void aHaltResponseThatThrowsCostsTheFrameNothingAndIsRethrownAfterIt(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            IllegalStateException stopFailed = new IllegalStateException("the transport refused to stop");
            halt.stopFailure = Optional.of(stopFailed);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            drive.record(AudioFailureCodes.RENDER_HALTED, new IllegalStateException("the mixer lost its master bus"),
                    null, 375);
            callbackFaults(1);

            assertThatThrownBy(() -> runOnFx(drain::pulse)).as("rethrown at the end of the frame, for the"
                    + " dispatcher's guard").isSameAs(stopFailed);
            runOnFx(() -> { });

            assertThat(halt.closes).as("the close was still attempted").hasValue(1);
            assertThat(entries(spine, "The audio engine stopped")).as("the halt notice was still posted")
                    .singleElement().satisfies(entry -> assertThat(entry.message()).endsWith(
                            "the mixer lost its master bus. Restart the audio engine to continue."));
            assertThat(entries(spine, CALLBACK_FIRST)).as("and the frame's other reports too").hasSize(1);
        }
    }

    /**
     * Story 337, rulings E33 and E66: a halt's close that throws — an input
     * device beside the stream failed to close — costs the frame nothing: the
     * throwable is rethrown after the frame, the notice is posted and the fact
     * latched, and the close stays owed, so the next frame retries it once.
     */
    @Test
    void aHaltCloseThatThrowsCostsTheFrameNothingAndIsRetriedOnTheNextFrame(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            IllegalStateException closeFailed = new IllegalStateException("the second input refused to close");
            halt.closeFailure = Optional.of(closeFailed);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            drive.record(AudioFailureCodes.RENDER_HALTED, new IllegalStateException("the mixer lost its master bus"),
                    null, 375);
            assertThatThrownBy(() -> runOnFx(drain::pulse)).as("rethrown at the end of the frame")
                    .isSameAs(closeFailed);
            runOnFx(() -> { });

            assertThat(halt.closes).as("fixture: the halt frame's close was attempted").hasValue(1);
            assertThat(entries(spine, "The audio engine stopped")).as("the halt notice was still posted")
                    .extracting(NotificationEntry::message).containsExactly(HALT_NOTICE);
            assertThat(onFx(drain::engineFault)).as("and the fact latched").hasValueSatisfying(fact ->
                    assertThat(fact.category()).isEqualTo(AudioFailureCategory.RENDER_HALTED));

            pulse(drain);

            assertThat(halt.retries).as("the close stays owed: the next frame retries it once").hasValue(1);
            assertThat(halt.closes).as("through the retry, not the halt frame's attempt").hasValue(1);
        }
    }

    @Test
    void aLatchedProcessingFaultThatKeepsFailingOnANewerDriveStaysLatched(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane faultedDrive = record.claimLane(LaneRole.RENDER_DRIVE);
            IllegalStateException segment = new IllegalStateException("a late segment overran its ring");
            String sustained = "Part of the sound of an effect keeps failing";

            for (int bucket = 0; bucket < 3; bucket++) {
                faultedDrive.record(AudioFailureCodes.DSP, segment, null, 2);
                pulse(drain);
                advance(Duration.ofSeconds(1));
            }
            assertThat(onFx(drain::engineFault)).as("fixture: three consecutive buckets latched the fact")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.DSP));

            AudioHealthLane newerDrive = record.claimLane(LaneRole.RENDER_DRIVE);
            for (int frame = 0; frame < 6; frame++) {
                newerDrive.beat();
                newerDrive.record(AudioFailureCodes.DSP, segment, null, 2);
                pulse(drain);
                advance(Duration.ofMillis(500));
            }

            assertThat(onFx(drain::engineFault)).as("a newer drive beats, but the latched category keeps failing"
                    + " (ruling E63)").isPresent();
            assertThat(entries(spine, sustained)).as("exactly one ERROR escalation: no clear, no re-escalation")
                    .singleElement().satisfies(entry -> assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR));

            newerDrive.beat();
            pulse(drain);
            newerDrive.beat();
            advance(Duration.ofMillis(600));
            pulse(drain);
            assertThat(onFx(drain::engineFault)).as("not a full fault-free second yet").isPresent();
            newerDrive.beat();
            advance(Duration.ofMillis(400));
            pulse(drain);

            assertThat(onFx(drain::engineFault)).as("one fault-free second once the category stopped failing")
                    .isEmpty();
            assertThat(entries(spine, sustained)).hasSize(1);
        }
    }

    @Test
    void aCategoryLatchedBeforeARestartNoLongerKeepsTheNextFactLatched(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane faultedDrive = record.claimLane(LaneRole.RENDER_DRIVE);
            IllegalStateException segment = new IllegalStateException("a late segment overran its ring");
            for (int bucket = 0; bucket < 3; bucket++) {
                faultedDrive.record(AudioFailureCodes.DSP, segment, null, 2);
                pulse(drain);
                advance(Duration.ofSeconds(1));
            }
            assertThat(onFx(drain::engineFault)).as("fixture: the DSP fault latched")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.DSP));
            runOnFx(drain::clearEngineFault);
            assertThat(onFx(drain::engineFault)).as("fixture: a successful restart cleared it").isEmpty();

            sustainCallbackFaults(drain);
            assertThat(onFx(drain::engineFault)).as("fixture: a callback fault latched next")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));

            AudioHealthLane newerDrive = record.claimLane(LaneRole.RENDER_DRIVE);
            newerDrive.beat();
            newerDrive.record(AudioFailureCodes.DSP, segment, null, 2);
            pulse(drain);
            advance(Duration.ofMillis(600));
            newerDrive.beat();
            pulse(drain);
            assertThat(onFx(drain::engineFault)).as("not a full fault-free second yet").isPresent();
            advance(Duration.ofMillis(400));
            newerDrive.beat();
            pulse(drain);

            assertThat(onFx(drain::engineFault)).as("one fault-free second of the callback and every category latched"
                    + " since the restart; the DSP fingerprint was released by the restart, so its one failure no"
                    + " longer keeps the callback fact (ruling E63)").isEmpty();
        }
    }

    @Test
    void aPluginRecordWithNoSubjectUsesTheUnnamedPluginSentence(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            drive.record(AudioFailureCodes.PLUGIN, new IllegalStateException("the event queue was full"), null, 0);
            pulse(drain);

            assertThat(entries(spine, "")).as("a plugin's pass-through, never the render sentence (ruling E64)")
                    .extracting(NotificationEntry::message).containsExactly("A plugin failed while processing, so a"
                            + " block passed through it unprocessed or without its parameter changes: the event queue"
                            + " was full. Audio continues.");
            assertThat(entries(spine, "Audio processing failed")).isEmpty();
        }
    }

    // ── the halt fence (story 337, rulings E69 to E72) ──────────────────────

    @Test
    void aHaltWhoseStreamWasAlreadyReplacedStopsAndClosesNothingAndShowsTheHaltWithoutAContextSentence(
            @TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            halt.context = HaltContext.STREAM_REPLACED;
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            drive.record(AudioFailureCodes.RENDER_HALTED, new IllegalStateException("the mixer lost its master bus"),
                    null, 375);
            pulse(drain);
            pulse(drain);

            assertThat(halt.stops).as("the Stop was asked, and answered that the halted stream is gone").hasValue(1);
            assertThat(halt.closes).as("no close is owed for a halt whose stream was replaced (ruling E70)")
                    .hasValue(0);
            assertThat(halt.retries).as("nor retried on the next frame").hasValue(0);
            assertThat(entries(spine, "The audio engine stopped")).singleElement().satisfies(entry -> {
                assertThat(entry.message()).as("no context sentence: this halt stopped nothing").isEqualTo(HALT_NOTICE);
                assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                assertThat(entry.actionLabel()).hasValue(RESTART);
            });
            assertThat(onFx(drain::engineFault)).as("the fact is latched").hasValueSatisfying(fact ->
                    assertThat(fact.category()).isEqualTo(AudioFailureCategory.RENDER_HALTED));

            AudioHealthLane nextDrive = record.claimLane(LaneRole.RENDER_DRIVE);
            nextDrive.record(AudioFailureCodes.RENDER_HALTED, new StackOverflowError("the render graph recursed"),
                    null, 1);
            pulse(drain);

            assertThat(entries(spine, "Serious internal error")).as("the FATAL copy has no context sentence either")
                    .singleElement().extracting(NotificationEntry::message).isEqualTo("Serious internal error: the"
                            + " application may not work correctly from now on. When you can, save a copy with File ▸"
                            + " Save As… and restart. Reason: the render graph recursed.");
            assertThat(halt.closes).as("still nothing closed").hasValue(0);
        }
    }

    @Test
    void aReplacedHaltsFactClearsAfterOneFaultFreeSecondOfTheDriveAlreadyRunning(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            halt.context = HaltContext.STREAM_REPLACED;
            AudioHealthLane halted = record.claimLane(LaneRole.RENDER_DRIVE);
            halted.record(AudioFailureCodes.RENDER_HALTED, new IllegalStateException("the mixer lost its master bus"),
                    null, 375);
            // Play reaped the halted stream and reopened it before the drain's frame (ruling E10).
            AudioHealthLane running = record.claimLane(LaneRole.RENDER_DRIVE);
            pulse(drain);
            assertThat(onFx(drain::engineFault)).as("fixture: the halt latched the fact").isPresent();

            running.beat();
            pulse(drain);
            running.beat();
            advance(Duration.ofMillis(600));
            pulse(drain);
            assertThat(onFx(drain::engineFault)).as("not a full second yet").isPresent();
            running.beat();
            advance(Duration.ofMillis(400));
            pulse(drain);

            assertThat(onFx(drain::engineFault)).as("one fault-free second of a drive started after the halted one,"
                    + " already running when the halt was seen (ruling E71)").isEmpty();
        }
    }

    @Test
    void aHaltLatchesWithTheEpochReadAtTheFrameStart(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AtomicReference<AudioHealthLane> reopened = new AtomicReference<>();
            // The halt's close is followed by a reopen inside the response: a new drive claims its lane then.
            halt.onClose = () -> reopened.set(record.claimLane(LaneRole.RENDER_DRIVE));
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            drive.record(AudioFailureCodes.RENDER_HALTED, new IllegalStateException("the mixer lost its master bus"),
                    null, 375);
            pulse(drain);
            assertThat(onFx(drain::engineFault)).as("fixture: the halt latched the fact").isPresent();
            AudioHealthLane running = reopened.get();
            assertThat(running).as("fixture: the response reopened").isNotNull();

            running.beat();
            pulse(drain);
            running.beat();
            advance(Duration.ofMillis(600));
            pulse(drain);
            assertThat(onFx(drain::engineFault)).as("not a full second yet").isPresent();
            running.beat();
            advance(Duration.ofMillis(400));
            pulse(drain);

            assertThat(onFx(drain::engineFault)).as("the fact latched with the epoch read at the frame's start, so"
                    + " the drive opened during the response is a newer one (ruling E79)").isEmpty();
        }
    }

    // ── a backend's failing report (story 337, ruling E78) ──────────────────

    @Test
    void aBackendWhoseReportFailsCostsTheFrameNoneOfItsLaneReports(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AtomicBoolean reportFails = new AtomicBoolean();
            record.attachBackendCounters(() -> "Failing Backend", () -> {
                if (reportFails.get()) {
                    throw new IllegalStateException("the driver's counters are gone");
                }
                return BackendThreadCounters.NONE;
            });
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            reportFails.set(true);

            drive.record(AudioFailureCodes.RENDER, new IllegalStateException("a block failed"), null, 1);
            assertThatCode(() -> pulse(drain)).as("the failing report costs the frame nothing")
                    .doesNotThrowAnyException();

            assertThat(entries(spine, "Audio processing failed")).extracting(NotificationEntry::message)
                    .containsExactly("Audio processing failed, so a block was played as silence: a block failed."
                            + " Playback continues.");
        }
    }

    // ── one reading per frame; samples kept until reported (story 337, rulings E79 and E82) ──

    @Test
    void anAutomationFailureRecordedWhileTheFrameIsBeingReadStillNamesItsParameter(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
            // The drive lane is read first and is empty; while the drain takes the worker's sample, the drive
            // records the automation failure, after its own lane was read.
            worker.record(AudioFailureCodes.WORKER, failureThatRecordsWhenSampled(() -> drive.record(
                    AudioFailureCodes.AUTOMATION, new IllegalArgumentException("value out of range"), CUTOFF, 3)),
                    null, 1);

            pulse(drain);
            pulse(drain);

            assertThat(entries(spine, "Automation of")).extracting(NotificationEntry::message)
                    .containsExactly(CUTOFF_NOTICE);
            assertThat(spine.funnel().occurrenceTotals()).containsEntry("audio.automation|insert-7#3", 1L)
                    .as("never counted without its sample").doesNotContainKey("audio.automation|unknown");
        }
    }

    @Test
    void aSampleTakenAFrameBeforeItsCountIsReportedWithItsSubject(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            // The worker lane is read first; the failure its sample records lands in the drive lane, read later in
            // the same reading: its sample is in this frame, its count is not.
            worker.record(AudioFailureCodes.WORKER, failureThatRecordsWhenSampled(() -> drive.record(
                    AudioFailureCodes.AUTOMATION, new IllegalArgumentException("value out of range"), CUTOFF, 3)),
                    null, 1);

            pulse(drain);
            assertThat(entries(spine, "Automation of")).as("frame 1: the sample, no count yet").isEmpty();
            pulse(drain);

            assertThat(entries(spine, "Automation of")).extracting(NotificationEntry::message)
                    .as("frame 2: the count, named by the sample kept since frame 1").containsExactly(CUTOFF_NOTICE);
            assertThat(spine.funnel().occurrenceTotals()).containsEntry("audio.automation|insert-7#3", 1L)
                    .doesNotContainKey("audio.automation|unknown");
        }
    }

    @Test
    void twoParametersFailingAcrossTwoFramesAreEachNamedOnce(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            drive.record(AudioFailureCodes.AUTOMATION, new IllegalArgumentException("value out of range"), CUTOFF, 3);
            // Frame 1 reads Cutoff's count and both samples: Drive's failure is sampled before it is counted.
            worker.record(AudioFailureCodes.WORKER, failureThatRecordsWhenSampled(() -> drive.record(
                    AudioFailureCodes.AUTOMATION, new IllegalArgumentException("not now"), DRIVE, 4)), null, 1);

            pulse(drain);
            pulse(drain);

            assertThat(entries(spine, "Automation of")).extracting(NotificationEntry::message).containsExactly(
                    CUTOFF_NOTICE, "Automation of “Drive” stopped because its plugin rejected a value: not now. The"
                            + " parameter keeps its last value; restarting the audio engine retries its automation.");
            assertThat(spine.funnel().occurrenceTotals()).as("each parameter named once, by its own sample")
                    .containsEntry("audio.automation|insert-7#3", 1L)
                    .containsEntry("audio.automation|insert-7#4", 1L)
                    .doesNotContainKey("audio.automation|unknown");
        }
    }

    @Test
    void aPluginFailureSampledAFrameBeforeItsCountNamesThePlugin(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            worker.record(AudioFailureCodes.WORKER, failureThatRecordsWhenSampled(() -> drive.record(
                    AudioFailureCodes.PLUGIN, new IllegalStateException("the event queue was full"),
                    pluginHost("Tape Echo"), 0)), null, 1);

            pulse(drain);
            assertThat(entries(spine, "The plugin")).as("frame 1: the sample, no count yet").isEmpty();
            pulse(drain);

            assertThat(entries(spine, "The plugin")).extracting(NotificationEntry::message).containsExactly(
                    "The plugin “Tape Echo” failed while processing, so its input was passed through unprocessed:"
                            + " the event queue was full.");
            assertThat(entries(spine, "A plugin failed")).as("never the unnamed sentence").isEmpty();
        }
    }

    @Test
    void anInputHaltThatLandsMidFrameIsCountedOnce(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            BackendThreadCounters oneHalt = new BackendThreadCounters(0, 0, 1, 0, OptionalLong.empty(),
                    Optional.empty(), Optional.of(new IllegalStateException("the input line closed")),
                    Optional.empty());
            AtomicBoolean attached = new AtomicBoolean();
            AtomicBoolean firstCallOfThePulse = new AtomicBoolean();
            // The halt lands right after the first read of the first frame.
            record.attachBackendCounters(() -> DEVICE, () -> (!attached.get() || firstCallOfThePulse.getAndSet(false))
                    ? BackendThreadCounters.NONE : oneHalt);
            attached.set(true);
            firstCallOfThePulse.set(true);

            pulse(drain);
            pulse(drain);

            assertThat(entries(spine, "Audio device fault")).singleElement().extracting(NotificationEntry::message)
                    .isEqualTo("Audio device fault — 1 error from “" + DEVICE + "” since " + clock(noon) + ": the"
                            + " input line closed. Audio may stay silent or broken until you restart the audio"
                            + " engine.");
            assertThat(onFx(drain::engineFault)).as("one input halt is one count (ruling E82)")
                    .hasValueSatisfying(fact -> assertThat(fact.count()).isEqualTo(1));
        }
    }

    @Test
    void oneFrameReadsTheBackendOnce(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AtomicInteger reads = new AtomicInteger();
            AtomicReference<BackendThreadCounters> report = new AtomicReference<>(BackendThreadCounters.NONE);
            record.attachBackendCounters(() -> DEVICE, () -> {
                reads.incrementAndGet();
                return report.get();
            });
            report.set(new BackendThreadCounters(1, 0, 1, 0, OptionalLong.of(10), Optional.of(shortBuffer),
                    Optional.of(new IllegalStateException("the input line closed")), Optional.empty()));
            reads.set(0);

            pulse(drain);

            assertThat(reads).as("one backend report per frame, with no lane sample to name either fault (ruling"
                    + " E82)").hasValue(1);
            assertThat(entries(spine, CALLBACK_FIRST)).as("fixture: the callback fault was reported").hasSize(1);
            assertThat(entries(spine, "Audio device fault")).as("fixture: and the input halt").hasSize(1);
            pulse(drain);
            assertThat(reads).hasValue(2);
        }
    }

    // ── a retired lane's undrained samples (story 337, ruling E81) ──────────

    @Test
    void anAutomationFailureRecordedJustBeforeItsLaneRetiredStillNamesItsParameter(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            drive.record(AudioFailureCodes.AUTOMATION, new IllegalArgumentException("value out of range"), CUTOFF, 3);
            record.retireLane(drive);
            pulse(drain);

            assertThat(entries(spine, "Automation of")).extracting(NotificationEntry::message)
                    .containsExactly(CUTOFF_NOTICE);
            assertThat(spine.funnel().occurrenceTotals()).containsEntry("audio.automation|insert-7#3", 1L)
                    .doesNotContainKey("audio.automation|unknown");
        }
    }

    @Test
    void aHaltWhoseLaneRetiredBeforeTheNextFrameStillListsItsAnchors(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            IllegalStateException renderFailure = renderFailure();
            IllegalStateException haltFailure = new IllegalStateException("the mixer lost its master bus");

            drive.record(AudioFailureCodes.RENDER, renderFailure, null, 1);
            drive.record(AudioFailureCodes.RENDER_HALTED, haltFailure, null, 375);
            // The halt's close retired the drive's lane before the drain's frame.
            record.retireLane(drive);
            pulse(drain);

            String haltAnchor = Fingerprint.of("audio.render.halted", Optional.empty(), haltFailure).anchor();
            String renderAnchor = Fingerprint.of("audio.render", Optional.empty(), renderFailure).anchor();
            assertThat(haltAnchor).as("fixture: two anchors").isNotEqualTo(renderAnchor);
            assertThat(records(spine, Fingerprint.ofSite("audio.render.halted", "render-drive"))).singleElement()
                    .satisfies(logged -> assertThat(logged.getMessage())
                            .endsWith(", sampled anchors " + haltAnchor + "; " + renderAnchor));
            assertThat(entries(spine, "The audio engine stopped")).extracting(NotificationEntry::message)
                    .containsExactly(HALT_NOTICE);
        }
    }

    // ── an input halt's fact (story 337, ruling E87) ────────────────────────

    @Test
    void anInputHaltsFactClearsOnlyOnAStreamOpenedAfterIt(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            counters.set(new BackendThreadCounters(0, 0, 1, 0, OptionalLong.empty(), Optional.empty(),
                    Optional.of(new IllegalStateException("the input line closed")), Optional.empty()));
            pulse(drain);
            assertThat(onFx(drain::engineFault)).as("fixture: the input halt latched the fact")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.INPUT));

            // A resume: a newer render drive on the same stream, whose input is still dead.
            beatOneFaultFreeSecond(drain, record.claimLane(LaneRole.RENDER_DRIVE));
            assertThat(onFx(drain::engineFault)).as("a resume restarts the drive, not the input").isPresent();

            // A stream open: the backend attached again, then its render drive.
            record.attachBackendCounters(() -> DEVICE, counters::get);
            beatOneFaultFreeSecond(drain, record.claimLane(LaneRole.RENDER_DRIVE));

            assertThat(onFx(drain::engineFault)).as("one fault-free second on a stream opened after the input halt")
                    .isEmpty();
        }
    }

    /**
     * Story 337, ruling E87: the fact of a second input halt, on a stream
     * opened after the first, waits for a stream opened after that second
     * halt: a resume's drive does not clear it while that input is still
     * dead.
     */
    @Test
    void aSecondInputHaltOnAReopenedStreamKeepsItsFactUntilAStreamOpenedAfterIt(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            counters.set(new BackendThreadCounters(0, 0, 1, 0, OptionalLong.empty(), Optional.empty(),
                    Optional.of(new IllegalStateException("the input line closed")), Optional.empty()));
            pulse(drain);
            assertThat(onFx(drain::engineFault)).as("fixture: the first input halt latched the fact").isPresent();

            // Stop, then Play: a second stream opens and its drive starts; its input fails again at once.
            record.attachBackendCounters(() -> DEVICE, counters::get);
            record.claimLane(LaneRole.RENDER_DRIVE);
            counters.set(new BackendThreadCounters(0, 0, 2, 0, OptionalLong.empty(), Optional.empty(),
                    Optional.of(new IllegalStateException("the input line closed again")), Optional.empty()));
            pulse(drain);
            assertThat(record.inputHalts()).as("fixture: two input halts").isEqualTo(2);

            // Pause, then Play: a newer drive on the same stream, whose input is still dead.
            beatOneFaultFreeSecond(drain, record.claimLane(LaneRole.RENDER_DRIVE));
            assertThat(onFx(drain::engineFault)).as("a resume after the second halt restarts no input")
                    .isPresent();

            record.attachBackendCounters(() -> DEVICE, counters::get);
            beatOneFaultFreeSecond(drain, record.claimLane(LaneRole.RENDER_DRIVE));

            assertThat(onFx(drain::engineFault)).as("one fault-free second on a stream opened after the second halt")
                    .isEmpty();
        }
    }

    // ── names (story 337, ruling E89) ───────────────────────────────────────

    @Test
    void aDspNoticeNamesTheEffectAsItsInsertSlotDoes(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            drive.record(AudioFailureCodes.DSP, new IllegalStateException("a band's coefficients went NaN"),
                    new ParametricEqProcessor(2, 48_000.0), 0);
            pulse(drain);

            assertThat(entries(spine, "Part of the sound of")).extracting(NotificationEntry::message)
                    .containsExactly("Part of the sound of “Parametric EQ” failed and was left out of a block: a"
                            + " band's coefficients went NaN. Audio continues.");
        }
    }

    @Test
    void aSubjectWhoseNameCannotBeReadCostsTheFrameNothing(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);

            drive.record(AudioFailureCodes.DSP, new IllegalStateException("a late segment overran its ring"),
                    effectWhoseClassCannotBeRead(), 2);
            drive.record(AudioFailureCodes.PLUGIN, new IllegalStateException("the event queue was full"), null, 0);
            assertThatCode(() -> pulse(drain)).as("reading the name never costs the frame")
                    .doesNotThrowAnyException();

            assertThat(entries(spine, "")).extracting(NotificationEntry::message).containsExactly(
                    "Part of the sound of an effect failed and was left out of a block: a late segment overran its"
                            + " ring. Audio continues.",
                    "A plugin failed while processing, so a block passed through it unprocessed or without its"
                            + " parameter changes: the event queue was full. Audio continues.");
        }
    }

    // ── a stalled or lost stream (story 338, amendment A14; rulings E68 to E70) ──

    @Test
    void aDeviceSideFaultOnEveryBlockWithAStandingHeartbeatNeverEscalatesNorLatchesBeforeTheLoss(
            @TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);

            for (long second = 1; second <= 5; second++) {
                callbackFaultsWithAStandingHeartbeat(second);
                pulse(drain);
                advance(Duration.ofSeconds(1));
            }

            assertThat(entries(spine, CALLBACK_FIRST)).as("the first WARNING arrives and is true (ruling E8)")
                    .hasSize(1);
            assertThat(entries(spine, "Audio engine fault")).as("held: the stall is the likelier cause (ruling E68)")
                    .isEmpty();
            assertThat(onFx(drain::engineFault)).isEmpty();

            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            for (long second = 6; second <= 9; second++) {
                callbackFaultsWithAStandingHeartbeat(second);
                pulse(drain);
                advance(Duration.ofSeconds(1));
            }

            assertThat(entries(spine, "Audio engine fault")).as("never after the loss either (ruling E8)").isEmpty();
            assertThat(onFx(drain::engineFault)).isEmpty();
        }
    }

    @Test
    void aHeldRunEscalatesAsBeforeOnceTheHeartbeatAdvancesAgain(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            for (long second = 1; second <= 3; second++) {
                callbackFaultsWithAStandingHeartbeat(second);
                pulse(drain);
                advance(Duration.ofSeconds(1));
            }
            assertThat(entries(spine, "Audio engine fault")).as("fixture: held while the heartbeat stood").isEmpty();

            drive.beat();
            callbackFaultsWithAStandingHeartbeat(4);
            pulse(drain);

            assertThat(entries(spine, "Audio engine fault")).as("the drive beat again: the held run proceeds (E68)")
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR));
            assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact ->
                    assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));
        }
    }

    @Test
    void theLossDropsTheHeldRunSoALaterDriveCountsAfresh(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            for (long second = 1; second <= 3; second++) {
                callbackFaultsWithAStandingHeartbeat(second);
                pulse(drain);
                advance(Duration.ofSeconds(1));
            }
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));

            AudioHealthLane reopened = record.claimLane(LaneRole.RENDER_DRIVE);
            reopened.beat();
            callbackFaults(4);
            pulse(drain);

            assertThat(entries(spine, "Audio engine fault")).as("the lost stream's held counts were discarded (E68)")
                    .isEmpty();
            for (long total = 5; total <= 6; total++) {
                advance(Duration.ofSeconds(1));
                reopened.beat();
                callbackFaults(total);
                pulse(drain);
            }
            assertThat(entries(spine, "Audio engine fault")).as("three buckets of the reopened stream's own")
                    .hasSize(1);
        }
    }

    @Test
    void theLossClearsADeviceSideFactLatchedOnTheLostDrive(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            sustainCallbackFaults(drain);
            assertThat(onFx(drain::engineFault)).as("fixture: latched on the drive the loss closes").isPresent();

            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));

            assertThat(onFx(drain::engineFault)).as("the cell shows only the loss (ruling E69)").isEmpty();
            assertThat(spine.funnel().isEscalated(CALLBACK)).isFalse();
            assertThat(entries(spine, "Audio engine fault")).as("the notice already shown stays").hasSize(1);
        }
    }

    /**
     * The window's device-loss reactions hand the drain the epoch the engine
     * controller read as it declared the loss (before the close, for a
     * removal or a stall), at the DEVICE_LOST delivery only, even once a
     * newer drive has started (story 338, amendment A14).
     */
    @Test
    void theDeviceLossReactionsHandTheDrainTheEpochTheControllerReadBeforeTheClose(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            sustainCallbackFaults(drain);
            long lostDrive = record.renderDriveEpoch();
            record.claimLane(LaneRole.RENDER_DRIVE);
            DeviceLossReactions reactions = new DeviceLossReactions(() -> null, () -> lostDrive,
                    () -> Optional.of(drain), null);
            assertThat(onFx(drain::engineFault)).as("fixture: latched on the drive the loss closed").isPresent();

            runOnFx(() -> reactions.accept(new EngineStateChange(EngineState.STOPPED, Optional.empty())));
            assertThat(onFx(drain::engineFault)).as("negative control: no loss, no clear").isPresent();

            runOnFx(() -> reactions.accept(new EngineStateChange(EngineState.DEVICE_LOST, Optional.empty(),
                    EngineStateChange.LossCause.STALLED)));

            assertThat(onFx(drain::engineFault)).as("the cell shows only the loss (ruling E69)").isEmpty();
        }
    }

    @Test
    void theLossClearsNeitherAHaltNorANewerDrivesFact(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane halted = record.claimLane(LaneRole.RENDER_DRIVE);
            halted.record(AudioFailureCodes.RENDER_HALTED, new IllegalStateException("the mixer lost its master bus"),
                    new Object(), 375);
            pulse(drain);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            assertThat(onFx(drain::engineFault)).as("a halt latch is never the loss's (ruling E69)")
                    .hasValueSatisfying(fact -> assertThat(fact.category())
                            .isEqualTo(AudioFailureCategory.RENDER_HALTED));
            runOnFx(drain::clearEngineFault);

            long lost = record.renderDriveEpoch();
            record.claimLane(LaneRole.RENDER_DRIVE);
            sustainCallbackFaults(drain);
            assertThat(onFx(drain::engineFault)).as("fixture: latched on a drive newer than the lost one")
                    .isPresent();
            runOnFx(() -> drain.streamLost(lost));

            assertThat(onFx(drain::engineFault)).as("a fault from a stream opened after the loss stays (ruling E69)")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));
        }
    }

    @Test
    void readingsOfTheLostDriveNeverEscalateAndANewerDriveEscalatesAgain(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));

            sustainCallbackFaults(drain);
            assertThat(entries(spine, "Audio engine fault")).as("the lost drive's counts, heartbeat advancing")
                    .isEmpty();

            record.claimLane(LaneRole.RENDER_DRIVE);
            advance(Duration.ofSeconds(1));
            for (long total = 4; total <= 6; total++) {
                callbackFaults(total);
                pulse(drain);
                advance(Duration.ofSeconds(1));
            }

            assertThat(entries(spine, "Audio engine fault")).as("a drive started after the loss escalates as ever")
                    .hasSize(1);
            assertThat(onFx(drain::engineFault)).isPresent();
        }
    }

    @Test
    void anInputHaltOfTheLostStreamRaisesNothingAndOneOfALaterStreamDoes(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));

            counters.set(new BackendThreadCounters(0, 0, 1, 0, OptionalLong.empty(), Optional.empty(),
                    Optional.of(new IllegalStateException("the input line closed")), Optional.empty()));
            pulse(drain);

            assertThat(entries(spine, "Audio device fault")).as("part of the loss: no ERROR (ruling E70)").isEmpty();
            assertThat(onFx(drain::engineFault)).as("and no latch").isEmpty();

            record.claimLane(LaneRole.RENDER_DRIVE);
            advance(Duration.ofSeconds(31));
            counters.set(new BackendThreadCounters(0, 0, 2, 0, OptionalLong.empty(), Optional.empty(),
                    Optional.of(new IllegalStateException("the input line closed again")), Optional.empty()));
            pulse(drain);

            assertThat(entries(spine, "Audio device fault")).as("negative control: a later stream's input halt")
                    .hasSize(1);
            assertThat(onFx(drain::engineFault)).isPresent();
        }
    }

    /**
     * The record of an input halt folded at the loss says what happened: it is part of the device loss, with no
     * toast of its own (story 338, ruling E156, case (i)).
     */
    @Test
    void anInputHaltFoldedAtTheLossWritesARecordThatSaysItIsPartOfTheDeviceLoss(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));

            inputFailures(0, 1, lineClosed);
            pulse(drain);

            assertThat(records(spine, Fingerprint.ofSite("audio.input", "input-capture"))).singleElement()
                    .satisfies(logged -> {
                        assertThat(logged.getLevel()).isEqualTo(Level.WARNING);
                        assertThat(logged.getMessage()).isEqualTo("Failure audio.input (WARNING) at \"input-capture\","
                                        + " occurrence 1, fingerprint audio.input|input-capture, part of the device"
                                        + " loss; no toast of its own (story 338, ruling E70)")
                                .doesNotContain("notice of the same frame", "ruling E65");
                        assertThat(logged.getThrown()).isSameAs(lineClosed);
                    });
            assertThat(spine.history().getEntries()).as("no toast of its own").isEmpty();
        }
    }

    /**
     * An input halt folded at the loss, with no input notice before it, is summed up with the lost block counted
     * after it in the words of the input's lost-block warning, never as an audio device fault (story 338, ruling
     * E155, case (i)).
     */
    @Test
    void aFoldedHaltAndALostBlockOfTheLostDriveAreSummedUpAsLostInput(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            inputFailures(0, 1, lineClosed);
            pulse(drain);

            advance(Duration.ofSeconds(5));
            Instant lostBlock = wall.get();
            inputFailures(1, 1, blockDropped);
            pulse(drain);
            advance(Duration.ofSeconds(30));
            spine.pulse();
            runOnFx(() -> { });

            assertTheFoldIsSummedUpAsLostInput(spine, drain, lostBlock);
        }
    }

    /**
     * As the case above, with the lost block read on a stream opened after the loss (story 338, ruling E155, case
     * (ii)).
     */
    @Test
    void aFoldedHaltAndALostBlockOfALaterStreamAreSummedUpAsLostInput(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            inputFailures(0, 1, lineClosed);
            pulse(drain);

            reopenTheStream();
            advance(Duration.ofSeconds(5));
            Instant lostBlock = wall.get();
            inputFailures(1, 1, blockDropped);
            pulse(drain);
            advance(Duration.ofSeconds(30));
            spine.pulse();
            runOnFx(() -> { });

            assertTheFoldIsSummedUpAsLostInput(spine, drain, lostBlock);
        }
    }

    /**
     * Control: a halt folded at the loss inside the window of a lost-block warning the person was shown is summed
     * up in that warning's words, with no new toast and no latch (story 338, ruling E155, case (iii)).
     */
    @Test
    void aHaltFoldedInsideTheWindowOfAShownWarningIsSummedUpInThatWarningsWords(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            inputFailures(1, 0, blockDropped);
            pulse(drain);
            assertThat(spine.history().getEntries()).as("fixture: the lost-block warning's toast")
                    .extracting(NotificationEntry::message).containsExactly(LOST_BLOCK_WARNING);

            advance(Duration.ofSeconds(2));
            Instant folded = wall.get();
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            inputFailures(1, 1, lineClosed);
            pulse(drain);
            advance(Duration.ofSeconds(30));
            spine.pulse();
            runOnFx(() -> { });

            String summary = LOST_BLOCK_WARNING + " It happened once more after that notice, the last time at "
                    + clock(folded) + ".";
            assertThat(spine.history().getEntries()).as("no new toast: the warning, then its tail")
                    .extracting(NotificationEntry::message).containsExactly(LOST_BLOCK_WARNING, summary);
            assertThat(spine.history().getEntries()).last().satisfies(tail -> {
                assertThat(tail.level()).isEqualTo(NotificationLevel.WARNING);
                assertThat(tail.actionLabel()).hasValue(RESTART);
            });
            assertThat(onFx(drain::engineFault)).as("no latch").isEmpty();
        }
    }

    /**
     * A halt folded at the loss starts no sustained run: a stream opened after the loss escalates on its own three
     * consecutive seconds of failures, never one second sooner (story 338, ruling E157, case (i)).
     */
    @Test
    void anInputHaltFoldedAtTheLossStartsNoRunThatALaterStreamCompletes(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            inputFailures(0, 1, lineClosed);
            pulse(drain);

            AudioHealthLane reopened = reopenTheStream();
            oneSecondLater(drain, reopened, () -> inputFailures(1, 1, blockDropped));
            oneSecondLater(drain, reopened, () -> inputFailures(2, 1, blockDropped));

            assertThat(entries(spine, "Audio device fault")).as("two seconds of the later stream's own").isEmpty();
            assertThat(onFx(drain::engineFault)).as("and no latch").isEmpty();

            oneSecondLater(drain, reopened, () -> inputFailures(3, 1, blockDropped));

            assertTheInputEscalatedAtItsThirdSecond(spine, drain);
        }
    }

    /**
     * As the case above, with a lost input block of the lost drive in place of the halt (story 338, ruling E157, case
     * (ii)).
     */
    @Test
    void aLostBlockOfTheLostDriveStartsNoRunThatALaterStreamCompletes(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            inputFailures(1, 0, blockDropped);
            pulse(drain);

            AudioHealthLane reopened = reopenTheStream();
            oneSecondLater(drain, reopened, () -> inputFailures(2, 0, blockDropped));
            oneSecondLater(drain, reopened, () -> inputFailures(3, 0, blockDropped));

            assertThat(entries(spine, "Audio device fault")).as("two seconds of the later stream's own").isEmpty();
            assertThat(onFx(drain::engineFault)).as("and no latch").isEmpty();

            oneSecondLater(drain, reopened, () -> inputFailures(4, 0, blockDropped));

            assertTheInputEscalatedAtItsThirdSecond(spine, drain);
        }
    }

    /**
     * Pins the newer-drive Known limit as built: a lost input block of the lost stream that the drain first reads on
     * a stream opened after the loss begins that drive's run, so the new stream's own lost blocks in the next two
     * seconds raise its ERROR, one second sooner than its own three (story 338, rulings E151 and E159; future story
     * 473 reverses it).
     */
    @Test
    void aLostBlockOfTheLostStreamFirstReadOnANewerDriveJoinsThatDrivesRun(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            inputFailures(1, 0, blockDropped);
            AudioHealthLane reopened = reopenTheStream();
            Instant firstFrame = wall.get();
            pulse(drain);

            assertThat(spine.history().getEntries()).as("the lost stream's block, first read on the newer drive")
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.message()).isEqualTo(LOST_BLOCK_WARNING);
                        assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                        assertThat(entry.actionLabel()).hasValue(RESTART);
                    });
            assertThat(onFx(() -> spine.bar().getMessage())).as("the input's warning toast")
                    .isEqualTo(LOST_BLOCK_WARNING);
            assertThat(entries(spine, "Audio device fault")).as("with no ERROR").isEmpty();

            oneSecondLater(drain, reopened, () -> inputFailures(2, 0, blockDropped));
            assertThat(entries(spine, "Audio device fault")).as("nor at the first second after it").isEmpty();
            oneSecondLater(drain, reopened, () -> inputFailures(3, 0, blockDropped));

            assertThat(entries(spine, "Audio device fault")).as("the block began the newer drive's run")
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.message()).isEqualTo("Audio device fault — 3 errors from “" + DEVICE + "”"
                                + " since " + clock(firstFrame) + ": a captured block was dropped. Audio may stay"
                                + " silent or broken until you restart the audio engine.");
                        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                        assertThat(entry.actionLabel()).hasValue(RESTART);
                    });
            assertThat(onFx(drain::engineFault)).as("and the input's engine fault latched")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.INPUT));
        }
    }

    /** As the case above, with callback faults in place of input failures (story 338, ruling E157, case (iii)). */
    @Test
    void aCallbackFaultOfTheLostDriveStartsNoRunThatALaterStreamCompletes(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            callbackFaults(1);
            pulse(drain);

            AudioHealthLane reopened = reopenTheStream();
            oneSecondLater(drain, reopened, () -> callbackFaults(2));
            oneSecondLater(drain, reopened, () -> callbackFaults(3));

            assertThat(entries(spine, "Audio engine fault")).as("two seconds of the later stream's own").isEmpty();
            assertThat(onFx(drain::engineFault)).as("and no latch").isEmpty();

            oneSecondLater(drain, reopened, () -> callbackFaults(4));

            assertThat(entries(spine, "Audio engine fault")).as("negative control: its third second escalates")
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.message()).isEqualTo("Audio engine fault — 4 callback errors since "
                                + clock(noon) + ": the driver returned a short buffer. Audio may stay silent or"
                                + " broken until you restart the audio engine.");
                        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                    });
            assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact ->
                    assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));
        }
    }

    /**
     * Pins the before-delivery Known limit's warning as built: a callback fault read on the lost drive after the loss
     * reached the drain, its kind never shown before, still shows its kind's warning with Restart Audio Engine, with
     * no ERROR, no latch and no escalation (story 338, rulings E159 and E160).
     */
    @Test
    void aDeviceSideCountReadOnTheLostDriveAfterTheLossStillShowsItsWarning(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));

            callbackFaults(1);
            pulse(drain);

            assertThat(spine.history().getEntries()).as("one history entry: the callback's first-occurrence warning")
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.message()).isEqualTo(CALLBACK_FIRST);
                        assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                        assertThat(entry.actionLabel()).hasValue(RESTART);
                    });
            assertThat(onFx(() -> spine.bar().getMessage())).as("and its toast").isEqualTo(CALLBACK_FIRST);
            assertThat(records(spine, CALLBACK)).as("and its WARNING record").singleElement()
                    .satisfies(logged -> assertThat(logged.getLevel()).isEqualTo(Level.WARNING));
            assertThat(entries(spine, "Audio engine fault")).as("no ERROR").isEmpty();
            assertThat(onFx(drain::engineFault)).as("no engine fault").isEmpty();
            assertThat(spine.funnel().isEscalated(CALLBACK)).as("the callback fingerprint is not escalated").isFalse();
        }
    }

    /**
     * Control: a category that is not device-side keeps its run across a loss that clears no latched fault, so a
     * processing fault that keeps failing on the lost drive escalates as before (story 338, rulings E157 and E158).
     */
    @Test
    void aProcessingFaultKeepsItsRunAcrossTheLoss(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
            IllegalStateException chain = new IllegalStateException("an insert chain threw");
            worker.record(AudioFailureCodes.WORKER, chain, null, 0);
            pulse(drain);
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));

            for (int second = 1; second <= 2; second++) {
                advance(Duration.ofSeconds(1));
                worker.record(AudioFailureCodes.WORKER, chain, null, second);
                pulse(drain);
            }

            assertThat(entries(spine, "Multi-core processing keeps failing")).as("its third second escalates")
                    .singleElement().satisfies(entry -> assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR));
            assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact ->
                    assertThat(fact.category()).isEqualTo(AudioFailureCategory.WORKER));
        }
    }

    /**
     * A loss that clears a device-side fault latched on the lost drive restarts a processing failure's run too: a
     * worker that keeps failing escalates on its third consecutive second read after the loss, its count and "since"
     * time including its failures from before the loss (story 338, ruling E158).
     */
    @Test
    void aLossThatClearsALatchedDeviceFaultRestartsAProcessingRun(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            record.claimLane(LaneRole.RENDER_DRIVE);
            AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
            IllegalStateException chain = new IllegalStateException("an insert chain threw");
            String workerFault = "Multi-core processing keeps failing";
            sustainCallbackFaults(drain);
            assertThat(onFx(drain::engineFault)).as("fixture: a callback fault latched on the drive the loss closes")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));

            advance(Duration.ofSeconds(1));
            Instant firstWorkerFailure = wall.get();
            worker.record(AudioFailureCodes.WORKER, chain, null, 1);
            pulse(drain);
            advance(Duration.ofSeconds(1));
            worker.record(AudioFailureCodes.WORKER, chain, null, 2);
            pulse(drain);
            assertThat(entries(spine, workerFault)).as("fixture: two seconds of the worker's, no ERROR yet").isEmpty();
            assertThat(onFx(drain::engineFault)).as("fixture: the callback's latch stands until the loss")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));

            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            assertThat(onFx(drain::engineFault)).as("the loss clears the callback's latch (ruling E69)").isEmpty();

            advance(Duration.ofSeconds(1));
            worker.record(AudioFailureCodes.WORKER, chain, null, 3);
            pulse(drain);

            assertThat(entries(spine, workerFault))
                    .as("the clear restarted the worker's run: no ERROR at its first second after the loss").isEmpty();
            assertThat(onFx(drain::engineFault)).as("and the cell has no fact").isEmpty();

            advance(Duration.ofSeconds(1));
            worker.record(AudioFailureCodes.WORKER, chain, null, 4);
            pulse(drain);
            assertThat(entries(spine, workerFault)).as("nor at its second").isEmpty();

            advance(Duration.ofSeconds(1));
            worker.record(AudioFailureCodes.WORKER, chain, null, 5);
            pulse(drain);

            String sustained = workerFault + " — 5 errors since " + clock(firstWorkerFailure) + ": an insert chain"
                    + " threw. The affected tracks are processed again on one core, which costs processor time; check"
                    + " the inserts on your tracks.";
            assertThat(entries(spine, workerFault)).as("its third consecutive second read after the loss escalates")
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.message()).as("5 errors (two seconds before the loss, three after it), since"
                                + " the first one, before the loss").isEqualTo(sustained);
                        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                    });
            assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact ->
                    assertThat(fact.category()).isEqualTo(AudioFailureCategory.WORKER));
        }
    }

    /**
     * Pins the clear sentence of E114's amended Known limit as built: a loss clears nothing while a processing fault
     * is latched, so a callback fault latched on the lost drive after a worker's stays on the cell, escalated, until
     * one fault-free second on a drive started after the latch (story 338, rulings E69, E154 and E161).
     */
    @Test
    void theLossClearsNoFactWhileAProcessingFaultIsLatched(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
            IllegalStateException chain = new IllegalStateException("an insert chain threw");
            oneSecondLater(drain, drive, () -> worker.record(AudioFailureCodes.WORKER, chain, null, 1));
            oneSecondLater(drain, drive, () -> worker.record(AudioFailureCodes.WORKER, chain, null, 2));
            oneSecondLater(drain, drive, () -> worker.record(AudioFailureCodes.WORKER, chain, null, 3));
            assertThat(entries(spine, "Multi-core processing keeps failing")).as("fixture: the worker's ERROR")
                    .singleElement().satisfies(entry -> assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR));
            assertThat(onFx(drain::engineFault)).as("fixture: and its latch")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.WORKER));
            oneSecondLater(drain, drive, () -> callbackFaults(1));
            oneSecondLater(drain, drive, () -> callbackFaults(2));
            oneSecondLater(drain, drive, () -> callbackFaults(3));
            assertThat(entries(spine, "Audio engine fault")).as("fixture: the callback's ERROR")
                    .singleElement().satisfies(entry -> assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR));
            assertThat(onFx(drain::engineFault)).as("fixture: after which the latch is the callback's")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));

            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));

            assertThat(onFx(drain::engineFault)).as("the loss clears nothing while a processing fault is latched")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));
            assertThat(spine.funnel().isEscalated(CALLBACK)).as("and the callback fingerprint stays escalated")
                    .isTrue();

            beatOneFaultFreeSecond(drain, reopenTheStream());

            assertThat(onFx(drain::engineFault)).as("one fault-free second on a newer render drive clears it")
                    .isEmpty();
        }
    }

    /**
     * As the case above, with a worker failure in each frame of that second: the processing fault that keeps failing
     * keeps the fact the loss did not clear (story 338, rulings E69, E154 and E161).
     */
    @Test
    void aProcessingFaultThatKeepsFailingKeepsTheFactTheLossDidNotClear(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            AudioHealthDrain drain = drain(spine);
            AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
            AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
            IllegalStateException chain = new IllegalStateException("an insert chain threw");
            oneSecondLater(drain, drive, () -> worker.record(AudioFailureCodes.WORKER, chain, null, 1));
            oneSecondLater(drain, drive, () -> worker.record(AudioFailureCodes.WORKER, chain, null, 2));
            oneSecondLater(drain, drive, () -> worker.record(AudioFailureCodes.WORKER, chain, null, 3));
            oneSecondLater(drain, drive, () -> callbackFaults(1));
            oneSecondLater(drain, drive, () -> callbackFaults(2));
            oneSecondLater(drain, drive, () -> callbackFaults(3));
            runOnFx(() -> drain.streamLost(record.renderDriveEpoch()));
            assertThat(onFx(drain::engineFault)).as("fixture: the loss cleared nothing")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));

            AudioHealthLane reopened = reopenTheStream();
            reopened.beat();
            worker.record(AudioFailureCodes.WORKER, chain, null, 4);
            pulse(drain);
            reopened.beat();
            advance(Duration.ofMillis(600));
            worker.record(AudioFailureCodes.WORKER, chain, null, 5);
            pulse(drain);
            reopened.beat();
            advance(Duration.ofMillis(400));
            worker.record(AudioFailureCodes.WORKER, chain, null, 6);
            pulse(drain);

            assertThat(onFx(drain::engineFault)).as("a worker failure in each frame of that second keeps the fact")
                    .hasValueSatisfying(fact -> assertThat(fact.category()).isEqualTo(AudioFailureCategory.CALLBACK));
            assertThat(spine.funnel().isEscalated(CALLBACK)).as("and the callback fingerprint stays escalated")
                    .isTrue();
        }
    }

    /**
     * The history of a halt folded at the loss and one lost block counted after it: one tail entry, history only, at
     * the warning level with Restart, in the words of the input's lost-block warning with the halt's reason; no
     * entry reads as an audio device fault, and no fault is latched (story 338, ruling E155).
     */
    private static void assertTheFoldIsSummedUpAsLostInput(FailureSpineFixture spine, AudioHealthDrain drain,
                                                           Instant lostBlock) throws Exception {
        String summary = HALT_AS_LOST_INPUT + " It happened once more after that notice, the last time at "
                + clock(lostBlock) + ".";
        assertThat(spine.history().getEntries()).as("every toast has its history entry: no toast, one tail")
                .singleElement().satisfies(entry -> {
                    assertThat(entry.message()).isEqualTo(summary);
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.actionLabel()).hasValue(RESTART);
                });
        assertThat(spine.history().getEntries()).extracting(NotificationEntry::message)
                .noneMatch(message -> message.contains("Audio device fault")
                        || message.contains("until you restart the audio engine"));
        assertThat(onFx(drain::engineFault)).as("no engine fault latched").isEmpty();
    }

    /** The one ERROR of the later stream's third second: the device-fault sentence, Restart, and the latch. */
    private void assertTheInputEscalatedAtItsThirdSecond(FailureSpineFixture spine, AudioHealthDrain drain)
            throws Exception {
        assertThat(entries(spine, "Audio device fault")).as("negative control: its third second escalates")
                .singleElement().satisfies(entry -> {
                    assertThat(entry.message()).isEqualTo("Audio device fault — 4 errors from “" + DEVICE + "”"
                            + " since " + clock(noon) + ": a captured block was dropped. Audio may stay silent or"
                            + " broken until you restart the audio engine.");
                    assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                    assertThat(entry.actionLabel()).hasValue(RESTART);
                });
        assertThat(onFx(drain::engineFault)).hasValueSatisfying(fact ->
                assertThat(fact.category()).isEqualTo(AudioFailureCategory.INPUT));
    }

    /** Sets the backend's input counts, {@code fault} the newest input fault; it reports no device periods. */
    private void inputFailures(long blocksLost, long halts, IllegalStateException fault) {
        counters.set(new BackendThreadCounters(0, blocksLost, halts, 0, OptionalLong.empty(), Optional.empty(),
                Optional.of(fault), Optional.empty()));
    }

    /** A stream opened after the loss: the backend attached again, then its render drive, which is returned. */
    private AudioHealthLane reopenTheStream() {
        record.attachBackendCounters(() -> DEVICE, counters::get);
        return record.claimLane(LaneRole.RENDER_DRIVE);
    }

    /** One second later: {@code drive} beats, {@code counts} sets the backend's counts, and one drain frame runs. */
    private void oneSecondLater(AudioHealthDrain drain, AudioHealthLane drive, Runnable counts) throws Exception {
        advance(Duration.ofSeconds(1));
        drive.beat();
        counts.run();
        pulse(drain);
    }

    /** Sets the backend's callback fault count while its device periods stand still and the drive does not beat. */
    private void callbackFaultsWithAStandingHeartbeat(long total) {
        counters.set(new BackendThreadCounters(total, 0, 0, 0, OptionalLong.of(10), Optional.of(shortBuffer),
                Optional.empty(), Optional.empty()));
    }

    /** Beats {@code lane} for one fault-free second of frames: a stamp, 600 ms later another, 400 ms later a third. */
    private void beatOneFaultFreeSecond(AudioHealthDrain drain, AudioHealthLane lane) throws Exception {
        lane.beat();
        pulse(drain);
        lane.beat();
        advance(Duration.ofMillis(600));
        pulse(drain);
        lane.beat();
        advance(Duration.ofMillis(400));
        pulse(drain);
    }

    /**
     * A worker failure whose stack trace, read when the drain takes its sample to compute the anchor, runs
     * {@code record} once: a failure recorded while the frame is being read.
     */
    private static IllegalStateException failureThatRecordsWhenSampled(Runnable record) {
        AtomicBoolean recorded = new AtomicBoolean();
        return new IllegalStateException("an insert threw on a worker") {
            @Override
            public StackTraceElement[] getStackTrace() {
                if (recorded.compareAndSet(false, true)) {
                    record.run();
                }
                return super.getStackTrace();
            }
        };
    }

    /** A plugin host whose descriptor names it {@code name}; nothing else of it is called. */
    private static ExternalPluginHost pluginHost(String name) {
        PluginDescriptor descriptor = new PluginDescriptor("test.host", name, "1.0", "Test", PluginType.EFFECT);
        return (ExternalPluginHost) Proxy.newProxyInstance(ExternalPluginHost.class.getClassLoader(),
                new Class<?>[] {ExternalPluginHost.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDescriptor" -> descriptor;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * An effect whose class cannot be read, as after its plugin's class loader was closed: a member class
     * generated with the Class-File API (JEP 484, as {@code JarOnlyExceptions} does) and defined by a loader that
     * cannot find the class it is a member of, so reading its simple name throws {@link NoClassDefFoundError}.
     */
    private static Object effectWhoseClassCannotBeRead() throws ReflectiveOperationException {
        ClassDesc outer = ClassDesc.of("com.example.fuzz.FuzzBox");
        ClassDesc member = ClassDesc.of("com.example.fuzz.FuzzBox$Dsp");
        byte[] bytes = ClassFile.of().build(member, type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(ConstantDescs.CD_Object)
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .with(InnerClassesAttribute.of(InnerClassInfo.of(member, Optional.of(outer), Optional.of("Dsp"),
                        AccessFlag.PUBLIC, AccessFlag.STATIC))));
        Class<?> effect = new OneClassLoader(AudioHealthDrainTest.class.getClassLoader())
                .define("com.example.fuzz.FuzzBox$Dsp", bytes);
        return effect.getConstructor().newInstance();
    }

    /** Defines one class from its bytes and finds no other class of its own. */
    private static final class OneClassLoader extends ClassLoader {
        OneClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Callback faults in three consecutive one-second buckets: a WARNING, a counted repeat, the escalation. */
    private void sustainCallbackFaults(AudioHealthDrain drain) throws Exception {
        callbackFaults(1);
        pulse(drain);
        advance(Duration.ofSeconds(1));
        callbackFaults(2);
        pulse(drain);
        advance(Duration.ofSeconds(1));
        callbackFaults(3);
        pulse(drain);
    }

    private static IllegalStateException renderFailure() {
        return new IllegalStateException("the chain was re-run and threw again");
    }

    private static IllegalStateException workerFailure() {
        return new IllegalStateException("an insert threw on a worker");
    }

    private FailureSpineFixture armed(Path settings) throws Exception {
        record.attachBackendCounters(() -> DEVICE, counters::get);
        FailureSpineFixture spine = FailureSpineFixture.withClock(settings, nanos::get, wall::get, folder -> { });
        try {
            spine.armOnFx();
            return spine;
        } catch (Throwable failure) {
            spine.close();
            throw failure;
        }
    }

    private AudioHealthDrain drain(FailureSpineFixture spine) {
        return new AudioHealthDrain(new AudioHealthDrain.Collaborators(record, spine.funnel(), nanos::get,
                wall::get, () -> DEVICE, restarts::incrementAndGet, halt));
    }

    /** Sets the backend's callback fault count; the newest fault is always {@link #shortBuffer}. */
    private void callbackFaults(long total) {
        counters.set(new BackendThreadCounters(total, 0, 0, 0, OptionalLong.of(total * 10), Optional.of(shortBuffer),
                Optional.empty(), Optional.empty()));
    }

    /** One drain frame on the FX thread, then one more FX turn for the toasts it posted. */
    private static void pulse(AudioHealthDrain drain) throws Exception {
        runOnFx(drain::pulse);
        runOnFx(() -> { });
    }

    private void advance(Duration by) {
        nanos.addAndGet(by.toNanos());
        wall.updateAndGet(now -> now.plus(by));
    }

    private static String clock(Instant at) {
        return CLOCK.format(at.atZone(ZONE));
    }

    private static List<NotificationEntry> entries(FailureSpineFixture spine, String prefix) {
        return spine.history().getEntries().stream().filter(entry -> entry.message().startsWith(prefix)).toList();
    }

    private static List<LogRecord> records(FailureSpineFixture spine, Fingerprint fingerprint) {
        return spine.funnelRecords().stream()
                .filter(record -> record.getMessage() != null
                        && record.getMessage().contains("fingerprint " + fingerprint.key())
                        && record.getMessage().startsWith("Failure " + fingerprint.category() + " ("))
                .toList();
    }

    /** A halt response that answers what the test scripts and counts its calls. */
    private static final class ScriptedHaltResponse implements AudioHealthDrain.HaltResponse {
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger retries = new AtomicInteger();
        final Deque<Boolean> closeAnswers = new ArrayDeque<>();
        volatile HaltContext context = HaltContext.IDLE;
        volatile Optional<RuntimeException> stopFailure = Optional.empty();
        /** What the halt frame's close does besides answering — a reopen during the response, for one test. */
        volatile Runnable onClose = () -> { };
        /** What the next halt frame's close throws instead of answering, once. */
        volatile Optional<RuntimeException> closeFailure = Optional.empty();

        @Override
        public HaltContext stopTransportForHalt() {
            stops.incrementAndGet();
            if (stopFailure.isPresent()) {
                throw stopFailure.get();
            }
            return context;
        }

        @Override
        public boolean closeStreamForHalt() {
            closes.incrementAndGet();
            onClose.run();
            Optional<RuntimeException> failure = closeFailure;
            closeFailure = Optional.empty();
            if (failure.isPresent()) {
                throw failure.get();
            }
            Boolean answer = closeAnswers.pollFirst();
            return answer == null || answer;
        }

        @Override
        public boolean retryCloseStreamForHalt() {
            retries.incrementAndGet();
            Boolean answer = closeAnswers.pollFirst();
            return answer == null || answer;
        }
    }
}
