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
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
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
 * allows it — and one ERROR notice names what the transport was doing; a
 * {@link VirtualMachineError} halt uses the FATAL copy with Open Log Folder. A
 * worker failure every block escalates once instead of toasting per
 * occurrence, a first worker failure in the same frame as a first render
 * failure is one notice, and nothing counted before the drain existed is lost.
 * A render failure in a halt's frame is folded into the halt's notice with a
 * record of its own, a return-bus failure whose sample was lost states the cap
 * without a bus count, and a category latched before a restart no longer keeps
 * the next fact latched.
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

    private final AtomicLong nanos = new AtomicLong(Duration.ofSeconds(100).toNanos());
    private final Instant noon = LocalDate.now(ZONE).atTime(LocalTime.NOON).atZone(ZONE).toInstant();
    private final AtomicReference<Instant> wall = new AtomicReference<>(noon);
    private final AtomicInteger restarts = new AtomicInteger();
    private final AtomicReference<BackendThreadCounters> counters = new AtomicReference<>(BackendThreadCounters.NONE);
    private final AudioHealthRecord record = new AudioHealthRecord();
    private final ScriptedHaltResponse halt = new ScriptedHaltResponse();
    private final IllegalStateException shortBuffer = new IllegalStateException("the driver returned a short buffer");

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
            // count — is lost (story 337, ruling E40).
            for (int slot = 0; slot < LANE_SLOTS; slot++) {
                drive.record(AudioFailureCodes.RENDER, null, null, 0);
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
                assertThat(entry.message()).isEqualTo("Audio device fault — 1 errors from “" + DEVICE + "” since "
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
        record.attachBackendCounters(counters::get);
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
