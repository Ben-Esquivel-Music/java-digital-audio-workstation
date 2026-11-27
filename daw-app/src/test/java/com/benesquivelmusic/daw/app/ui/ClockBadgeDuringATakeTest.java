package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ClockStatusIndicator.State;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.OpenedStream;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.ClockKind;
import com.benesquivelmusic.daw.sdk.audio.ClockLockStatus;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The window's clock badge over a real engine, engine controller, bridge and
 * take (rulings E30, E31, E74, E81 to E85, E107, E111, E119, E135, E143,
 * E146, E216, E221, E222 and E280): built and wired by the window's own
 * {@code ClockStatusIndicator.engineClock},
 * {@code TakeClockMarks.of} and
 * {@code MainController.installClockStatusIndicator}, its source read after
 * each real stream open and each clock-source selection, its lock derived on
 * each pulse from the mock driver's status through the engine and the
 * controller, its device loss from the controller's level only, on a
 * dispatcher whose pulse the test runs. The engine opens the mock's default
 * device while the driver side names the device it resolved, as ASIO does.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClockBadgeDuringATakeTest {

    private static final ClockSource INTERNAL = new ClockSource(0, "Internal", true, new ClockKind.Internal());
    private static final ClockSource INTERNAL_IDLE = new ClockSource(0, "Internal", false, new ClockKind.Internal());
    private static final ClockSource WORD_CLOCK = new ClockSource(1, "Word Clock", true, new ClockKind.WordClock());
    private static final ClockSource WORD_CLOCK_IDLE =
            new ClockSource(1, "Word Clock", false, new ClockKind.WordClock());
    /** The device the driver side resolved the default device to. */
    private static final DeviceId RESOLVED = new DeviceId(MockAudioBackend.NAME, "Interface A");
    private static final String CLOSED_TOOLTIP =
            "Clock source when the audio stream last ran. Lock is not checked while the stream is closed.";
    private static final String STARTING_TOOLTIP =
            "Clock source when the audio stream last ran. Lock is not checked yet on this stream.";
    /** What the refusing driver answers when it refuses a start. */
    private static final String REFUSAL = "The driver refused to start the stream";
    /**
     * How long a refused start stays open once a pulse has seen it open:
     * far longer than a read that nothing makes wait takes to return and
     * land, and short enough for the hold to cost the suite nothing.
     */
    private static final long REFUSED_START_WINDOW_MILLIS = 300;

    @TempDir
    Path projectDirectory;

    private final List<String> notices = Collections.synchronizedList(new ArrayList<>());
    private final List<String> marks = Collections.synchronizedList(new ArrayList<>());
    private final FxDispatcher dispatcher = new FxDispatcher();
    /**
     * The transport controller's dispatcher in a test whose take counts in:
     * the count-in's fire reaches the record coordinator only at its pulse,
     * so the take stays in COUNT_IN until the test ends it, however long the
     * test takes. Kept apart from {@link #dispatcher}, which the badge's
     * waits pulse.
     */
    private final FxDispatcher transportDispatcher = new FxDispatcher();
    private DeviceLossRecordingFixture fixture;
    private ClockStatusIndicator badge;
    private Runnable undo;
    /** Runs on the FX thread right after each change the badge hands the take; nothing unless a test sets it. */
    private volatile Runnable afterAHandOver = () -> { };

    @AfterEach
    void stopEverything() throws Exception {
        if (undo != null) {
            runOnFx(undo);
        }
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void anUnlockDuringATakeIsShownNotifiedAndMarkedTheTakeKeepsRecordingAndARelockClearsIt() throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK));
        status(1, ClockLockStatus.Lock.CHECKING);
        Path take = fixture.recordUntilABlockIsOnDisk();
        awaitState(State.CHECKING, "the take's stream is checked");

        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");
        String unlockedText = onFx(badge::getText);
        RecordState duringTheUnlock = onFx(() -> fixture.coordinator().getState());
        status(1, ClockLockStatus.Lock.LOCKED);
        awaitState(State.QUIET, "the relock reached the badge");
        boolean stillRecording = onFx(() -> fixture.coordinator().getState() == RecordState.RECORDING);
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(unlockedText).isEqualTo("⚠ W/C 44.1k UNLOCKED");
        assertThat(duringTheUnlock).as("a clock event never stops a take (ruling E30)").isEqualTo(RecordState.RECORDING);
        assertThat(stillRecording).isTrue();
        assertThat(notices).as("rulings E31 and E111: the device the driver side names").containsExactly(
                "External clock lost on Interface A (Word Clock). Check the clock cable and the clock master."
                        + " Recording continues; the take is marked from this point.",
                "External clock locked again on Interface A (Word Clock).");
        assertThat(clockEvents(take)).as("the sealed manifest holds both marks")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED, TakeManifest.ClockEvent.LOCKED);
    }

    /**
     * Ruling E173: an unlock declared while the take counts in, still on
     * show when the take starts recording, is marked once at the take's
     * start-frame; its notice stays the plain one.
     */
    @Test
    void anUnlockOnShowWhenTheTakeStartsRecordingIsMarkedOnceAtItsStartFrame() throws Exception {
        Path take = countingInAtBeat(8.0);
        long startFrame = fixture.pipeline().getRecordingStartFrame();
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");
        RecordState whenUnlocked = onFx(() -> fixture.coordinator().getState());

        recordOnPastTheStart();
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(whenUnlocked).as("premise: the unlock was applied before the take recorded")
                .isEqualTo(RecordState.COUNT_IN);
        assertThat(startFrame).as("premise: the take starts past its first frame").isPositive();
        assertThat(notices).as("the plain notice: no take recorded when the unlock was declared").containsExactly(
                "External clock lost on Interface A (Word Clock). Check the clock cable and the clock master.");
        assertThat(marks).containsExactly("unlocked", "unlocked at the take's start");
        assertThat(clockMarks(take)).singleElement().satisfies(mark -> {
            assertThat(mark.event()).isEqualTo(TakeManifest.ClockEvent.UNLOCKED);
            assertThat(mark.frame()).as("at the take's start-frame").isEqualTo(startFrame);
        });
    }

    @Test
    void aRelockBeforeTheTakeStartsRecordingMarksNothing() throws Exception {
        Path take = countingInAtBeat(8.0);
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");
        status(1, ClockLockStatus.Lock.LOCKED);
        awaitState(State.QUIET, "the relock reached the badge");
        RecordState whenRelocked = onFx(() -> fixture.coordinator().getState());

        recordOnPastTheStart();
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(whenRelocked).as("premise: the relock was applied before the take recorded")
                .isEqualTo(RecordState.COUNT_IN);
        assertThat(notices).hasSize(2);
        assertThat(marks).containsExactly("unlocked", "locked");
        assertThat(clockMarks(take)).as("nothing declared before the take is the take's").isEmpty();
    }

    /**
     * Ruling E216 over the window's wiring: an unlock and its relock that
     * came and went between two looks of the badge are one notice and both
     * are marked in the take, the unlock no later than the relock.
     */
    @Test
    void anUnlockAndItsRelockBetweenTwoPulsesAreOneNoticeAndBothMarkedInTheTake() throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK));
        status(1, ClockLockStatus.Lock.CHECKING);
        Path take = fixture.recordUntilABlockIsOnDisk();
        awaitState(State.CHECKING, "the take's stream is checked");
        status(1, ClockLockStatus.Lock.LOCKED);
        awaitState(State.QUIET, "the first measurement reached the badge");

        long e1 = System.nanoTime();
        long e2 = Math.max(System.nanoTime(), e1 + 1);
        fixture.backend().setClockLockStatus(new ClockLockStatus(RESOLVED, WORD_CLOCK.id(), 1,
                ClockLockStatus.Lock.LOCKED, e2, List.of(new ClockLockStatus.Transition(1, false, e1),
                        new ClockLockStatus.Transition(2, true, e2))));
        runOnFx(dispatcher::pulse);
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        List<TakeManifest.ClockMark> clockMarks = clockMarks(take);
        assertThat(clockMarks).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED, TakeManifest.ClockEvent.LOCKED);
        assertThat(clockMarks.get(0).frame()).isLessThanOrEqualTo(clockMarks.get(1).frame());
        assertThat(notices).last().isEqualTo("External clock on Interface A (Word Clock) was lost and has locked"
                + " again. Check the clock cable and the clock master. Recording continues; the take is marked where"
                + " it was lost.");
    }

    /**
     * Ruling E280: an unlock the badge hands over after the capture thread sealed the take early, before the take
     * is stopped for it, is not marked, and its notice ends with no take sentence.
     */
    @Test
    void anUnlockHandedOverAfterTheEarlySealIsNotMarkedAndItsNoticeHasNoTakeSentence() throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK));
        status(1, ClockLockStatus.Lock.CHECKING);
        Path take = fixture.recordUntilABlockIsOnDisk();
        awaitState(State.CHECKING, "the take's stream is checked");
        runOnFx(fixture.coordinator()::readTheCaptureAsSealedForTest);
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");
        RecordState whenUnlocked = onFx(() -> fixture.coordinator().getState());
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(whenUnlocked).as("premise: in the early seal's window").isEqualTo(RecordState.RECORDING);
        assertThat(marks).as("premise: the badge handed the unlock over").containsExactly("unlocked");
        assertThat(notices).as("ruling E280: no take sentence").containsExactly(
                "External clock lost on Interface A (Word Clock). Check the clock cable and the clock master.");
        assertThat(clockEvents(take)).as("not marked").isEmpty();
    }

    /** Ruling E280: E216's notice for an unlock and its relock after the early seal gets no ending. */
    @Test
    void aLostAndRelockedLookAfterTheEarlySealHasNoEnding() throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK));
        status(1, ClockLockStatus.Lock.CHECKING);
        Path take = fixture.recordUntilABlockIsOnDisk();
        awaitState(State.CHECKING, "the take's stream is checked");
        status(1, ClockLockStatus.Lock.LOCKED);
        awaitState(State.QUIET, "the first measurement reached the badge");
        runOnFx(fixture.coordinator()::readTheCaptureAsSealedForTest);

        long e1 = System.nanoTime();
        long e2 = Math.max(System.nanoTime(), e1 + 1);
        fixture.backend().setClockLockStatus(new ClockLockStatus(RESOLVED, WORD_CLOCK.id(), 1,
                ClockLockStatus.Lock.LOCKED, e2, List.of(new ClockLockStatus.Transition(1, false, e1),
                        new ClockLockStatus.Transition(2, true, e2))));
        runOnFx(dispatcher::pulse);
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(marks).as("premise: the badge handed both changes over").containsExactly("unlocked", "locked");
        assertThat(notices).last().isEqualTo("External clock on Interface A (Word Clock) was lost and has locked"
                + " again. Check the clock cable and the clock master.");
        assertThat(clockMarks(take)).as("nothing marked in the window").isEmpty();
    }

    /**
     * Ruling E280: a seal that lands after the badge handed an unlock over and before the notice is worded never
     * makes the notice claim the mark; the line handed over before it still reaches the take.
     */
    @Test
    void aSealLandingBetweenTheHandOverAndTheNoticeNeverClaimsTheMark() throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK));
        status(1, ClockLockStatus.Lock.CHECKING);
        Path take = fixture.recordUntilABlockIsOnDisk();
        awaitState(State.CHECKING, "the take's stream is checked");
        afterAHandOver = () -> fixture.coordinator().readTheCaptureAsSealedForTest();
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(notices).as("the seal landed after the hand-over: the notice claims no mark").containsExactly(
                "External clock lost on Interface A (Word Clock). Check the clock cable and the clock master.");
        assertThat(clockEvents(take)).as("the line handed over before the seal reaches the take (an under-claim)")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    /**
     * Ruling E222: a punch take records its pre-roll before its reach, so an
     * unlock on show when it starts recording and the relock after it, both
     * before the reach, are an empty region at the punch-in.
     */
    @Test
    void aPunchTakesPreRollUnlockAndRelockAreAnEmptyRegionAtThePunchIn() throws Exception {
        long punchIn = 44_100L * 600;
        Path take = countingInAtBeatToAPunchIn(8.0, punchIn);
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");
        awaitOnFx(() -> {
            transportDispatcher.pulse();
            return fixture.coordinator().getState() == RecordState.RECORDING;
        }, "the count-in fired");
        pulses(3);
        status(1, ClockLockStatus.Lock.LOCKED);
        awaitState(State.QUIET, "the relock reached the badge");
        Transport transport = fixture.project().getTransport();
        long frameAtTheEnd = onFx(() -> fixture.pipeline().transportFrameOf(transport.getPositionInBeats()));
        boolean punchAnchored = fixture.pipeline().isRecordingStartAPunchIn();
        long startFrame = fixture.pipeline().getRecordingStartFrame();
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(punchAnchored).as("premise: the punch-in anchors the take").isTrue();
        assertThat(startFrame).as("premise: its start-frame").isEqualTo(punchIn);
        assertThat(frameAtTheEnd).as("premise: still in the pre-roll").isLessThan(punchIn);
        assertThat(clockMarks(take)).extracting(TakeManifest.ClockMark::event, TakeManifest.ClockMark::frame)
                .containsExactly(tuple(TakeManifest.ClockEvent.UNLOCKED, punchIn),
                        tuple(TakeManifest.ClockEvent.LOCKED, punchIn));
        assertThat(notices).containsExactly(
                "External clock lost on Interface A (Word Clock). Check the clock cable and the clock master.",
                "External clock locked again on Interface A (Word Clock).");
        assertThat(marks).containsExactly("unlocked", "unlocked at the take's start", "locked");
    }

    /**
     * Ruling E222: an unlock and its relock whose evidence comes before the
     * take's reach, first handed over at the take's first look, are an empty
     * region at its start-frame.
     */
    @Test
    void anUnlockAndItsRelockBeforeTheReachReplayedAtTheTakesFirstLookAreAnEmptyRegionAtItsStart()
            throws Exception {
        Path take = countingInAtBeat(8.0);
        status(1, ClockLockStatus.Lock.LOCKED);
        awaitState(State.QUIET, "the first measurement reached the badge");
        long e1 = System.nanoTime();
        long e2 = e1 + 1;
        fixture.backend().setClockLockStatus(new ClockLockStatus(RESOLVED, WORD_CLOCK.id(), 1,
                ClockLockStatus.Lock.LOCKED, e2, List.of(new ClockLockStatus.Transition(1, false, e1),
                        new ClockLockStatus.Transition(2, true, e2))));

        recordOnPastTheStart();
        long startFrame = fixture.pipeline().getRecordingStartFrame();
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(clockMarks(take)).extracting(TakeManifest.ClockMark::event, TakeManifest.ClockMark::frame)
                .containsExactly(tuple(TakeManifest.ClockEvent.UNLOCKED, startFrame),
                        tuple(TakeManifest.ClockEvent.LOCKED, startFrame));
        assertThat(notices).containsExactly("External clock on Interface A (Word Clock) was lost and has locked"
                + " again. Check the clock cable and the clock master. Recording continues; the take is marked where"
                + " it was lost.");
        assertThat(marks).containsExactly("unlocked", "locked");
    }

    /**
     * Rulings E173, E221 and E222: an unlock still on show at the take's
     * reach is marked at its start-frame, and its relock at the oldest frame
     * sampled at or after the relock's evidence.
     */
    @Test
    void anUnlockStandingAtTheReachIsMarkedAtTheStartAndItsRelockAtAFrameSampledAfterIt() throws Exception {
        Path take = countingInAtBeat(8.0);
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");
        recordOnPastTheStart();
        status(1, ClockLockStatus.Lock.LOCKED);
        awaitState(State.QUIET, "the relock reached the badge");
        Transport transport = fixture.project().getTransport();
        double beats = transport.getPositionInBeats();
        await(() -> transport.getPositionInBeats() >= beats + 0.5, "the take recorded on past the relock");
        long[] sampled = onFx(() -> {
            long before = fixture.pipeline().transportFrameOf(transport.getPositionInBeats());
            transportDispatcher.pulse();
            return new long[] {before, fixture.pipeline().transportFrameOf(transport.getPositionInBeats())};
        });
        long startFrame = fixture.pipeline().getRecordingStartFrame();
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        List<TakeManifest.ClockMark> clockMarks = clockMarks(take);
        assertThat(clockMarks).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED, TakeManifest.ClockEvent.LOCKED);
        assertThat(clockMarks.get(0).frame()).as("at the take's start-frame").isEqualTo(startFrame);
        assertThat(clockMarks.get(1).frame()).as("at the frame sampled after the relock's evidence")
                .isBetween(sampled[0], sampled[1]).isGreaterThan(startFrame);
        assertThat(notices).containsExactly(
                "External clock lost on Interface A (Word Clock). Check the clock cable and the clock master.",
                "External clock locked again on Interface A (Word Clock).");
        assertThat(marks).containsExactly("unlocked", "unlocked at the take's start", "locked");
    }

    /**
     * Ruling E269 over the window's wiring: Record reopens the stream while
     * the external clock is unlocked; the new stream's first decision is an
     * unlock dated at its start, before the take's reach, so the take is
     * marked unlocked at its start-frame, and the unlock is announced again
     * with the take's ending. The date the test gives stands for the new
     * stream's start: it is read just before Record, earlier still, and both
     * come before the take's reach.
     */
    @Test
    void anUnlockDatedAtTheStartOfTheStreamRecordReopensMarksTheTakeAtItsStartFrame() throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK));
        fixture.bindTheProjectToTheEngine();
        Transport transport = fixture.project().getTransport();
        runOnFx(() -> transport.setPositionInBeats(8.0));
        status(1, ClockLockStatus.Lock.CHECKING);
        runOnFx(fixture.controller()::start);
        awaitState(State.CHECKING, "the playing stream is checked");
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the playing stream's unlock reached the badge");
        long playingSerial = fixture.engine().lastOpenedStream().orElseThrow().serial();
        long beforeTheReopen = System.nanoTime();
        // What the reopened stream's monitor writes at its start: checking, its source not read yet.
        fixture.backend().setClockLockStatus(new ClockLockStatus(RESOLVED, -1, 2, ClockLockStatus.Lock.CHECKING, 0L));
        Path take = fixture.recordUntilABlockIsOnDisk();
        long takeSerial = fixture.engine().lastOpenedStream().orElseThrow().serial();
        awaitState(State.CHECKING, "the reopened stream is checked");
        await(() -> transport.getPositionInBeats() >= fixture.pipeline().getRecordingStartBeat() + 0.5,
                "the take recorded on past its start");
        long frameNow = onFx(() -> fixture.pipeline().transportFrameOf(transport.getPositionInBeats()));
        fixture.backend().setClockLockStatus(new ClockLockStatus(RESOLVED, WORD_CLOCK.id(), 2,
                ClockLockStatus.Lock.UNLOCKED, beforeTheReopen,
                List.of(new ClockLockStatus.Transition(1, false, beforeTheReopen))));
        awaitState(State.UNLOCKED, "the reopened stream's first decision reached the badge");
        long startFrame = fixture.pipeline().getRecordingStartFrame();
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(takeSerial).as("premise: Record reopened the stream").isGreaterThan(playingSerial);
        assertThat(startFrame).as("premise: the take starts past its first frame").isPositive();
        assertThat(frameNow).as("premise: the take recorded on past its start-frame before the unlock")
                .isGreaterThan(startFrame);
        assertThat(clockMarks(take)).extracting(TakeManifest.ClockMark::event, TakeManifest.ClockMark::frame)
                .containsExactly(tuple(TakeManifest.ClockEvent.UNLOCKED, startFrame));
        assertThat(notices).containsExactly(
                "External clock lost on Interface A (Word Clock). Check the clock cable and the clock master.",
                "External clock lost on Interface A (Word Clock). Check the clock cable and the clock master."
                        + " Recording continues; the take is marked from this point.");
        assertThat(marks).containsExactly("unlocked", "unlocked");
    }

    /**
     * Ruling E187: an unlock applied before a take that counts in is marked
     * once at the take's start-frame. A device loss rescues the take; when
     * the device returns the badge reads its stream again and announces the
     * unlock it still measures, with the no-take text, and nothing more is
     * marked in the rescued take, which stopped recording at the loss.
     */
    @Test
    void anUnlockAnnouncedAgainWhenTheLostDeviceReturnsAddsNothingToTheRescuedTake() throws Exception {
        Path take = countingInAtBeat(8.0);
        long startFrame = fixture.pipeline().getRecordingStartFrame();
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");
        recordOnPastTheStart();
        DeviceId device = fixture.activeDevice();

        fixture.unplug();
        fixture.awaitRescued();
        awaitState(State.HIDDEN, "the loss hid the badge");
        fixture.plugIn(device);
        awaitOnFx(() -> {
            transportDispatcher.pulse();
            return fixture.coordinator().getState() == RecordState.IDLE;
        }, "the coordinator settled to IDLE at the device's return");
        awaitState(State.UNLOCKED, "the returned device's stream is read and still unlocked");

        String noTakeText = "External clock lost on Interface A (Word Clock). Check the clock cable and the clock"
                + " master.";
        assertThat(notices).as("before the take, and again after the return: the no-take text")
                .containsExactly(noTakeText, noTakeText);
        assertThat(marks).as("the badge hands the second unlock on; nothing records to take it")
                .containsExactly("unlocked", "unlocked at the take's start", "unlocked");
        assertThat(clockMarks(take)).as("the rescued take: one unlock, at its start-frame").singleElement()
                .satisfies(mark -> {
                    assertThat(mark.event()).isEqualTo(TakeManifest.ClockEvent.UNLOCKED);
                    assertThat(mark.frame()).isEqualTo(startFrame);
                });
    }

    @Test
    void aTakeStoppedWhileUnlockedTurnsDimWithNoRelockAndTheNextOpenChecksAgain() throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK));
        status(1, ClockLockStatus.Lock.CHECKING);
        Path take = fixture.recordUntilABlockIsOnDisk();
        awaitState(State.CHECKING, "the take's stream is checked");
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");

        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();
        await(() -> !fixture.engine().isStreamOpen(), "the audio-only project closed its stream at the Stop");
        runOnFx(dispatcher::pulse);
        State atTheStop = onFx(() -> badge.stateProperty().get());
        String dimText = onFx(badge::getText);
        status(2, ClockLockStatus.Lock.CHECKING);
        runOnFx(fixture.controller()::start);
        awaitState(State.CHECKING, "the next open reads the source again");

        assertThat(atTheStop).as("ruling E81").isEqualTo(State.CLOSED);
        assertThat(dimText).isEqualTo("W/C 44.1k");
        assertThat(notices).as("no \"locked again\" notice at the close").hasSize(1)
                .allMatch(notice -> notice.startsWith("External clock lost on "));
        assertThat(clockEvents(take)).as("no relock mark: the suspect region runs to the seal")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    @Test
    void aStopInAnAudioOnlyProjectKeepsTheInternalSourceDimAndAPauseKeepsItMeasured() throws Exception {
        wire(List.of(INTERNAL));
        runOnFx(fixture.controller()::start);
        awaitState(State.QUIET, "the internal source is read at the open");
        runOnFx(fixture.controller()::pause);
        boolean pausedOpen = fixture.engine().isStreamOpen();
        runOnFx(dispatcher::pulse);
        State paused = onFx(() -> badge.stateProperty().get());

        runOnFx(fixture.controller()::stop);
        await(() -> !fixture.engine().isStreamOpen(), "the audio-only project closed its stream at the Stop");
        runOnFx(dispatcher::pulse);
        String[] closed = onFx(() -> new String[] {String.valueOf(badge.stateProperty().get()),
                String.valueOf(badge.isVisible()), badge.getText(), badge.getAccessibleText(),
                String.valueOf(badge.getStyleClass().contains(ClockStatusIndicator.DIM_CLASS))});

        assertThat(pausedOpen).as("premise: Pause parks the stream open").isTrue();
        assertThat(paused).as("the poll still runs while paused (ruling E85)").isEqualTo(State.QUIET);
        assertThat(closed).containsExactly("CLOSED", "true", "INT 44.1k",
                "Clock: Internal, 44.1 kilohertz. Lock not checked while the audio stream is closed.", "true");
    }

    @Test
    void aDeviceLossHidesTheBadgeWithNoRelockNoticeOrMark() throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK));
        status(1, ClockLockStatus.Lock.CHECKING);
        Path take = fixture.recordUntilABlockIsOnDisk();
        awaitState(State.CHECKING, "the take's stream is checked");
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");

        fixture.unplug();
        fixture.awaitRescued();
        awaitState(State.HIDDEN, "the loss hid the badge");
        runOnFx(dispatcher::pulse);

        assertThat(onFx(badge::isVisible)).as("never a source shown for a lost device (ruling E84)").isFalse();
        assertThat(notices).as("no relock notice at the loss (ruling E74)").hasSize(1);
        assertThat(clockEvents(take)).as("the device loss wins; no relock mark")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    /**
     * The window's model can report DEVICE_LOST after the controller's loss
     * ended and a stream is open and unlocked again: the badge reads the loss
     * as a level only, so that report changes nothing, and the unlock is
     * notified and marked once.
     */
    @Test
    void aLossTheModelReportsLateOverAnOpenUnlockedStreamNotifiesAndMarksOnce() throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK));
        status(1, ClockLockStatus.Lock.CHECKING);
        Path take = fixture.recordUntilABlockIsOnDisk();
        awaitState(State.CHECKING, "the take's stream is checked");
        status(1, ClockLockStatus.Lock.UNLOCKED);
        awaitState(State.UNLOCKED, "the unlock reached the badge");

        State atTheLateReport = onFx(() -> {
            fixture.engineHealth().onEngineStateChange(new EngineStateChange(EngineState.DEVICE_LOST,
                    Optional.of(fixture.activeDevice()), EngineStateChange.LossCause.STALLED));
            dispatcher.pulse();
            return badge.stateProperty().get();
        });
        awaitState(State.UNLOCKED, "the badge shows the open stream's unlock");
        boolean lostByTheController = fixture.engineController().orElseThrow().isDeviceLost();
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(lostByTheController).as("premise: the controller's loss is not in effect").isFalse();
        assertThat(atTheLateReport).as("a late report over an open stream changes nothing").isEqualTo(State.UNLOCKED);
        assertThat(notices).as("one notice per measured change").hasSize(1)
                .allMatch(notice -> notice.startsWith("External clock lost on "));
        assertThat(clockEvents(take)).as("one take mark per measured change")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    /**
     * A clock source chosen in Settings while the stream is closed hides a
     * closed badge (ruling E81): the controller's clock-source-selected
     * callback, wired by the window, reads the source again (review D
     * finding 1).
     */
    @Test
    void aSourceChosenInSettingsWhileTheStreamIsClosedHidesTheClosedBadge() throws Exception {
        wire(List.of(INTERNAL, WORD_CLOCK_IDLE));
        runOnFx(fixture.controller()::start);
        awaitState(State.QUIET, "the internal source is read at the open");
        runOnFx(fixture.controller()::stop);
        await(() -> !fixture.engine().isStreamOpen(), "the audio-only project closed its stream at the Stop");
        awaitState(State.CLOSED, "premise: closed on purpose, the source still on show");

        fixture.engineController().orElseThrow().selectClockSource(MockAudioBackend.NAME,
                fixture.activeDevice().name(), WORD_CLOCK.id());

        awaitState(State.HIDDEN, "the source chosen in Settings hid the closed badge");
        assertThat(fixture.backend().recordedClockSourceSelections()).as("premise: the driver took the source")
                .containsExactly(WORD_CLOCK.id());
    }

    /** The badge reads the level; it follows no clock-lock event (review A finding 2). */
    @Test
    void aClockLockEventAloneChangesNothingOnTheBadge() throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK));
        status(1, ClockLockStatus.Lock.CHECKING);
        runOnFx(fixture.controller()::start);
        awaitState(State.CHECKING, "the stream's source is read");

        fixture.backend().simulateClockLock(RESOLVED, WORD_CLOCK.id(), false);
        long until = System.nanoTime() + 300_000_000L;
        while (System.nanoTime() < until) {
            runOnFx(dispatcher::pulse);
        }

        assertThat(onFx(() -> badge.stateProperty().get())).isEqualTo(State.CHECKING);
        assertThat(notices).isEmpty();
    }

    /**
     * Ruling E135: one open is one new stream. Play, Stop and Play again ask
     * one stream read per open, whatever order the stream-opened callback and
     * the pulses take; a pause and its resume are the same stream and ask
     * none; a callback arriving after the read answered changes nothing.
     */
    @Test
    void eachOpenAsksOneStreamReadAndAResumeOrALateCallbackAsksNone() throws Exception {
        AtomicInteger streamReads = new AtomicInteger();
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK), real -> new CountingStreamReads(real, streamReads));
        status(1, ClockLockStatus.Lock.LOCKED);
        runOnFx(fixture.controller()::start);
        awaitState(State.QUIET, "the first stream is measured");
        long firstSerial = fixture.engine().lastOpenedStream().orElseThrow().serial();
        int readsForTheFirstOpen = streamReads.get();

        badge.streamOpened();
        pulses(5);
        State afterALateCallback = onFx(() -> badge.stateProperty().get());
        int readsAfterALateCallback = streamReads.get();

        runOnFx(fixture.controller()::pause);
        pulses(3);
        runOnFx(fixture.controller()::start);
        pulses(3);
        boolean resumedOpen = fixture.engine().isStreamOpen();
        long resumedSerial = fixture.engine().lastOpenedStream().orElseThrow().serial();
        State resumed = onFx(() -> badge.stateProperty().get());
        int readsAfterTheResume = streamReads.get();

        runOnFx(fixture.controller()::stop);
        await(() -> !fixture.engine().isStreamOpen(), "the audio-only project closed its stream at the Stop");
        awaitState(State.CLOSED, "the Stop shows the closed view");
        status(2, ClockLockStatus.Lock.LOCKED);
        runOnFx(fixture.controller()::start);
        awaitState(State.QUIET, "the second stream is measured");
        pulses(5);
        long secondSerial = fixture.engine().lastOpenedStream().orElseThrow().serial();

        assertThat(readsForTheFirstOpen).as("one stream read for the first open").isEqualTo(1);
        assertThat(afterALateCallback).as("a late callback changes nothing").isEqualTo(State.QUIET);
        assertThat(readsAfterALateCallback).as("and asks no read").isEqualTo(1);
        assertThat(resumedOpen).as("premise: the resume runs the paused stream").isTrue();
        assertThat(resumedSerial).as("premise: the same stream").isEqualTo(firstSerial);
        assertThat(resumed).isEqualTo(State.QUIET);
        assertThat(readsAfterTheResume).as("a pause and its resume ask no read").isEqualTo(1);
        assertThat(secondSerial).as("premise: a new stream").isGreaterThan(firstSerial);
        assertThat(streamReads.get()).as("one stream read for the second open").isEqualTo(2);
        assertThat(notices).isEmpty();
    }

    /**
     * Ruling E143: a Play whose start fails after its open is a stream that
     * opened and closed at once. The engine's record is that stream's, under
     * a greater serial; the badge asks one stream read for it, about the
     * device it opened, and the closed view on show stays; nothing is
     * notified or marked.
     */
    @Test
    void aPlayWhoseStartFailsAfterItsOpenIsReadOnceAndKeepsTheClosedView() throws Exception {
        AtomicReference<CountingStreamReads> window = new AtomicReference<>();
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK), real -> {
            window.set(new CountingStreamReads(real, new AtomicInteger()));
            return window.get();
        });
        AtomicBoolean refuseTheNextStart = new AtomicBoolean();
        StreamingProvision refusing = new StreamingProvision(fixture.backend().name(), List.of(
                new BackendStreamRung(refusingItsNextStart(fixture.backend(), refuseTheNextStart),
                        fixture.activeDevice())));
        runOnFx(() -> fixture.engine().setStreamingProvision(refusing));
        status(1, ClockLockStatus.Lock.LOCKED);
        runOnFx(fixture.controller()::start);
        awaitState(State.QUIET, "the first stream is measured");
        runOnFx(fixture.controller()::stop);
        await(() -> !fixture.engine().isStreamOpen(), "the audio-only project closed its stream at the Stop");
        awaitState(State.CLOSED, "the Stop shows the closed view");
        Shown closed = shown();
        OpenedStream ran = fixture.engine().lastOpenedStream().orElseThrow();
        CountingStreamReads reads = window.get();
        int askedBefore = reads.devicesAsked().size();
        int answeredBefore = reads.readsAnswered().get();

        refuseTheNextStart.set(true);
        runOnFx(fixture.controller()::start);
        boolean openAfterThePlay = fixture.engine().isStreamOpen();
        Optional<OpenedStream> failed = fixture.engine().lastOpenedStream();
        List<Shown> afterThePlay = pulsesUntilAnswered(reads.readsAnswered(), answeredBefore + 1);
        List<DeviceId> asked = List.copyOf(reads.devicesAsked().subList(askedBefore, reads.devicesAsked().size()));

        assertThat(refuseTheNextStart).as("premise: the stream's start was refused after its open").isFalse();
        assertThat(openAfterThePlay).as("premise: the start failed").isFalse();
        assertThat(failed).as("premise: the engine has a record").isPresent();
        assertThat(failed.orElseThrow().serial()).as("E143: the engine's record is the failed stream's")
                .isGreaterThan(ran.serial());
        assertThat(failed.orElseThrow().device()).as("on the device it opened").isEqualTo(fixture.activeDevice());
        assertThat(asked).as("one stream read for that open, about the device it opened")
                .containsExactly(fixture.activeDevice());
        assertThat(closed).as("premise: the dim closed view")
                .isEqualTo(new Shown(State.CLOSED, true, "W/C 44.1k", CLOSED_TOOLTIP));
        assertThat(afterThePlay).as("E143 (S1): the closed view on show stays").isNotEmpty().containsOnly(closed);
        assertThat(notices).isEmpty();
        assertThat(marks).isEmpty();
    }

    /**
     * Rulings E143 and E146 off the FX thread: an Apply that keeps the
     * device, through the controller's real {@code applyConfiguration} on a
     * background thread as Settings runs it, restarts the playing stream, and
     * the restart's start fails after its open while the driver side reports
     * the clock unlocked. While the failing stream reads as open the
     * window's pulses show the starting view and ask its read about the
     * device it opened on; that read waits on the controller's monitor,
     * which the real apply holds across the restart and the real
     * {@code clockSources} takes, so it lands only after the failure, on the
     * closed stream: the dim closed view, no clock notice and no take mark.
     * The refused start stays open until a pulse has seen it open, then for
     * {@value #REFUSED_START_WINDOW_MILLIS} ms or until the read has landed.
     */
    @Test
    void anApplyWhoseRestartFailsAfterItsOpenShowsTheStartingViewThenTheClosedOne() throws Exception {
        AtomicReference<CountingStreamReads> window = new AtomicReference<>();
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK), real -> {
            window.set(new CountingStreamReads(real, new AtomicInteger()));
            return window.get();
        });
        CountingStreamReads reads = window.get();
        DefaultAudioEngineController controller = fixture.engineController().orElseThrow();
        StreamingProvision configured = null;
        try {
            applyOffTheFxThread(controller, mockDefaultDevice(256)).get(10, TimeUnit.SECONDS);
            configured = fixture.engine().getStreamingProvision();
            AtomicBoolean refuseTheNextStart = new AtomicBoolean();
            CountDownLatch aPulseSawTheRestartOpen = new CountDownLatch(1);
            CountDownLatch theReadLanded = new CountDownLatch(1);
            AtomicBoolean heldUntilAPulseSawItOpen = new AtomicBoolean();
            AtomicInteger answeredAtTheRefusal = new AtomicInteger(-1);
            StreamingProvision refusing = new StreamingProvision(fixture.backend().name(), List.of(
                    new BackendStreamRung(refusingItsNextStart(fixture.backend(), refuseTheNextStart, () -> {
                        status(2, ClockLockStatus.Lock.UNLOCKED);
                        heldUntilAPulseSawItOpen.set(aPulseSawTheRestartOpen.await(5, TimeUnit.SECONDS));
                        theReadLanded.await(REFUSED_START_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
                        answeredAtTheRefusal.set(reads.readsAnswered().get());
                    }), fixture.activeDevice())));
            runOnFx(() -> {
                fixture.engine().stopAudioOutput();
                fixture.engine().stop();
                fixture.engine().setStreamingProvision(refusing);
            });
            status(1, ClockLockStatus.Lock.LOCKED);
            runOnFx(fixture.controller()::start);
            awaitState(State.QUIET, "the playing stream is measured");
            Shown playing = shown();
            OpenedStream ran = fixture.engine().lastOpenedStream().orElseThrow();
            int askedBefore = reads.devicesAsked().size();
            int answeredBefore = reads.readsAnswered().get();
            int engineNoticesBefore = fixture.engineNotices().size();

            refuseTheNextStart.set(true);
            FutureTask<Void> apply = applyOffTheFxThread(controller, mockDefaultDevice(512));
            Predicate<Look> theRestartOpen = look -> look.open() && look.lastOpenedSerial() > ran.serial();
            AtomicInteger looksSinceTheAnswer = new AtomicInteger();
            List<Look> looks = looksUntil(() -> reads.readsAnswered().get() > answeredBefore && apply.isDone(),
                    look -> {
                        if (theRestartOpen.test(look)) {
                            aPulseSawTheRestartOpen.countDown();
                        }
                        if (reads.readsAnswered().get() > answeredBefore
                                && looksSinceTheAnswer.incrementAndGet() >= 20) {
                            theReadLanded.countDown();
                        }
                    });
            apply.get(10, TimeUnit.SECONDS);
            List<Shown> fromTheRestartsOpen = looks.stream().dropWhile(theRestartOpen.negate())
                    .map(Look::shown).toList();
            List<Shown> whileTheRestartReadOpen = looks.stream().filter(theRestartOpen).map(Look::shown).toList();
            List<DeviceId> asked = List.copyOf(reads.devicesAsked().subList(askedBefore, reads.devicesAsked().size()));
            List<String> controllerNotices = fixture.engineNotices().subList(engineNoticesBefore,
                    fixture.engineNotices().size());
            Optional<OpenedStream> failed = fixture.engine().lastOpenedStream();
            Shown starting = new Shown(State.STARTING, true, "W/C 44.1k", STARTING_TOOLTIP);
            Shown closed = new Shown(State.CLOSED, true, "W/C 44.1k", CLOSED_TOOLTIP);

            assertThat(playing.text()).as("premise: the playing stream's source").isEqualTo("W/C 44.1k");
            assertThat(heldUntilAPulseSawItOpen).as("premise: a pulse saw the restart's stream open within 5 s")
                    .isTrue();
            assertThat(refuseTheNextStart).as("premise: the restart's start was refused after its open").isFalse();
            assertThat(fixture.engine().isStreamOpen()).as("premise: the start failed").isFalse();
            assertThat(fromTheRestartsOpen).extracting(Shown::state)
                    .as("E143: the failing stream is never measured")
                    .doesNotContain(State.QUIET, State.CHECKING, State.UNLOCKED);
            assertThat(fromTheRestartsOpen).as("from the restart's open, only the starting and the dim closed views")
                    .isNotEmpty().allMatch(shown -> shown.equals(starting) || shown.equals(closed));
            assertThat(whileTheRestartReadOpen).as("the starting view while the restart's stream read as open")
                    .contains(starting);
            assertThat(looks.getLast().shown()).as("the end state: the dim closed view").isEqualTo(closed);
            assertThat(asked).as("one stream read for the restart, about the device it opened")
                    .containsExactly(fixture.activeDevice());
            assertThat(answeredAtTheRefusal.get()).as("the read waited on the controller's monitor")
                    .isEqualTo(answeredBefore);
            assertThat(reads.readsAnswered().get()).as("and returned after the start failed")
                    .isEqualTo(answeredBefore + 1);
            assertThat(notices).as("no clock notice").isEmpty();
            assertThat(marks).as("no take mark").isEmpty();
            assertThat(failed.orElseThrow().serial()).as("E143: the engine's record is the failed stream's")
                    .isGreaterThan(ran.serial());
            assertThat(failed.orElseThrow().device()).as("on the device it opened").isEqualTo(fixture.activeDevice());
            assertThat(controllerNotices).as("the controller's own notice of the failed restart")
                    .containsExactly("Audio output could not be started: " + REFUSAL);
        } finally {
            closeUnlessInstalled(configured);
        }
    }

    /**
     * Ruling E81: playback stopped, the dim closed view on show after a
     * stream that ran on the configured device, then an Apply that changes
     * nothing. The controller builds the
     * same configuration again, the engine is quiesced and given it, and the
     * read a reconfigure asks (a refresh through the window's engineClock,
     * which reads the provision's requested device) keeps the dim view: that
     * device, built anew, and the device the stream opened compare equal.
     */
    @Test
    void anApplyThatChangesNothingAfterAStreamOnTheConfiguredDeviceKeepsTheDimView() throws Exception {
        AtomicReference<CountingStreamReads> window = new AtomicReference<>();
        wire(List.of(INTERNAL), real -> {
            window.set(new CountingStreamReads(real, new AtomicInteger()));
            return window.get();
        });
        DefaultAudioEngineController controller = fixture.engineController().orElseThrow();
        AudioEngineController.Request request = mockDefaultDevice(256);
        StreamingProvision configured = provisionFor(controller, request);
        StreamingProvision applied = null;
        try {
            runOnFx(() -> fixture.engine().setStreamingProvision(configured));
            runOnFx(fixture.controller()::start);
            awaitState(State.QUIET, "the stream on the configured device is measured");
            runOnFx(fixture.controller()::stop);
            await(() -> !fixture.engine().isStreamOpen(), "the audio-only project closed its stream at the Stop");
            awaitState(State.CLOSED, "the Stop shows the dim closed view");
            Shown closed = shown();
            DeviceId opened = fixture.engine().lastOpenedStream().orElseThrow().device();

            applied = provisionFor(controller, request);
            StreamingProvision reapplied = applied;
            runOnFx(() -> {
                fixture.engine().stopAudioOutput();
                fixture.engine().stop();
                fixture.engine().setStreamingProvision(reapplied);
            });
            int answeredBefore = window.get().readsAnswered().get();
            badge.refresh();
            List<Shown> afterTheRefresh = pulsesUntilAnswered(window.get().readsAnswered(), answeredBefore + 1);

            assertThat(reapplied.requestedDevice()).as("premise: the Apply built the requested device anew")
                    .isNotSameAs(opened);
            assertThat(reapplied.requestedDevice()).as("the requested device and the opened rung's device")
                    .isEqualTo(opened);
            assertThat(closed).as("premise: the dim closed view")
                    .isEqualTo(new Shown(State.CLOSED, true, "INT 44.1k", CLOSED_TOOLTIP));
            assertThat(window.get().readsAnswered().get()).as("premise: the refresh's read answered")
                    .isGreaterThan(answeredBefore);
            assertThat(afterTheRefresh).as("E81: an Apply that changes nothing keeps the dim view").isNotEmpty()
                    .containsOnly(closed);
        } finally {
            closeUnlessInstalled(configured);
            closeUnlessInstalled(applied);
        }
    }

    /**
     * Ruling E81 through the controller's real {@code applyConfiguration},
     * run off the FX thread as Settings runs it: after a stream that ran on
     * the configured device, the same request applied again keeps the
     * provision the first apply built, and the read the window asks after a
     * reconfigure ({@code MainController}'s post-reconfigure callback calls
     * the badge's refresh on the FX thread) reads that provision's requested
     * device through the window's engineClock, which is the device the
     * stream opened on: the dim view stays. The apply's own choice to keep
     * the provision is what this pins, beside the rebuilt provision of
     * {@link #anApplyThatChangesNothingAfterAStreamOnTheConfiguredDeviceKeepsTheDimView}.
     */
    @Test
    void theControllersApplyOfAnUnchangedRequestKeepsTheDimView() throws Exception {
        AtomicReference<CountingStreamReads> window = new AtomicReference<>();
        wire(List.of(INTERNAL), real -> {
            window.set(new CountingStreamReads(real, new AtomicInteger()));
            return window.get();
        });
        CountingStreamReads reads = window.get();
        DefaultAudioEngineController controller = fixture.engineController().orElseThrow();
        AudioEngineController.Request request = mockDefaultDevice(256);
        StreamingProvision configured = null;
        try {
            applyOffTheFxThread(controller, request).get(10, TimeUnit.SECONDS);
            configured = fixture.engine().getStreamingProvision();
            configuredMockOf(configured).setClockSources(List.of(INTERNAL));
            runOnFx(fixture.controller()::start);
            awaitState(State.QUIET, "the stream on the configured device is measured");
            runOnFx(fixture.controller()::stop);
            await(() -> !fixture.engine().isStreamOpen(), "the audio-only project closed its stream at the Stop");
            awaitState(State.CLOSED, "the Stop shows the dim closed view");
            Shown closed = shown();
            DeviceId opened = fixture.engine().lastOpenedStream().orElseThrow().device();

            applyOffTheFxThread(controller, request).get(10, TimeUnit.SECONDS);
            StreamingProvision applied = fixture.engine().getStreamingProvision();
            int answeredBefore = reads.readsAnswered().get();
            runOnFx(badge::refresh);
            List<Shown> afterTheRefresh = pulsesUntilAnswered(reads.readsAnswered(), answeredBefore + 1);

            assertThat(applied).as("premise: the apply kept the provision it built").isSameAs(configured);
            assertThat(applied.requestedDevice()).as("premise: its requested device is the one the stream opened on")
                    .isEqualTo(opened);
            assertThat(closed).as("premise: the dim closed view")
                    .isEqualTo(new Shown(State.CLOSED, true, "INT 44.1k", CLOSED_TOOLTIP));
            assertThat(reads.readsAnswered().get()).as("premise: the refresh's read answered")
                    .isGreaterThan(answeredBefore);
            assertThat(afterTheRefresh).as("E81: an Apply that changes nothing keeps the dim view").isNotEmpty()
                    .containsOnly(closed);
            assertThat(notices).isEmpty();
        } finally {
            closeUnlessInstalled(configured);
        }
    }

    /**
     * Wires the badge over a project whose Record counts in one bar at 60
     * bpm from {@code beat}, its transport rolling with the render and its
     * transport controller on {@link #transportDispatcher}, presses Record
     * and waits until the take counts in; returns the take directory.
     */
    private Path countingInAtBeat(double beat) throws Exception {
        return countingIn(beat, _ -> { });
    }

    /** {@link #countingInAtBeat(double)} over a punch region from {@code punchIn}, one second long. */
    private Path countingInAtBeatToAPunchIn(double beat, long punchIn) throws Exception {
        return countingIn(beat, transport -> transport.setPunchRegion(PunchRegion.enabled(punchIn, punchIn + 44_100L)));
    }

    private Path countingIn(double beat, Consumer<Transport> setUp) throws Exception {
        wire(List.of(INTERNAL_IDLE, WORD_CLOCK), real -> real,
                DeviceLossRecordingFixture.withEngineBridge(projectDirectory, CountInMode.ONE_BAR,
                        transportDispatcher));
        fixture.bindTheProjectToTheEngine();
        Transport transport = fixture.project().getTransport();
        runOnFx(() -> {
            transport.setTempo(60);
            transport.setPositionInBeats(beat);
            setUp.accept(transport);
        });
        status(1, ClockLockStatus.Lock.CHECKING);
        runOnFx(fixture.controller()::toggleRecord);
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.COUNT_IN, "the take counts in");
        return fixture.pipeline().getTakeDirectory();
    }

    /**
     * Pulses the transport's dispatcher until the count-in's fire has moved
     * the take to RECORDING, waits for the transport to pass the take's
     * start, then pulses the badge.
     */
    private void recordOnPastTheStart() throws Exception {
        awaitOnFx(() -> {
            transportDispatcher.pulse();
            return fixture.coordinator().getState() == RecordState.RECORDING;
        }, "the count-in fired");
        double start = fixture.pipeline().getRecordingStartBeat();
        Transport transport = fixture.project().getTransport();
        await(() -> transport.getPositionInBeats() >= start + 0.5, "the take recorded on past its start");
        pulses(3);
    }

    private void pulses(int count) throws Exception {
        for (int pulse = 0; pulse < count; pulse++) {
            runOnFx(dispatcher::pulse);
        }
    }

    /**
     * Pulses the window's dispatcher until {@code answered} reads have
     * returned and twenty FX turns have run since, so their answers have
     * landed, or for ten seconds when they never return; returns what each
     * pulse showed.
     */
    private List<Shown> pulsesUntilAnswered(AtomicInteger readsAnswered, int answered) throws Exception {
        return looksUntil(() -> readsAnswered.get() >= answered, _ -> { }).stream().map(Look::shown).toList();
    }

    /**
     * Pulses the window's dispatcher until {@code settled} holds and twenty
     * FX turns have run since, so the answers of the reads it waits for have
     * landed, or for ten seconds when it never holds; hands each look to
     * {@code each} on the test thread as it is taken, and returns them all.
     */
    private List<Look> looksUntil(BooleanSupplier settled, Consumer<Look> each) throws Exception {
        List<Look> seen = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int turnsSinceSettled = 0;
        while (turnsSinceSettled < 20 && System.nanoTime() < deadline) {
            boolean settledBeforeThisTurn = settled.getAsBoolean();
            Look look = onFx(() -> {
                long lastOpened = fixture.engine().lastOpenedStream().map(OpenedStream::serial).orElse(0L);
                boolean open = fixture.engine().isStreamOpen();
                dispatcher.pulse();
                return new Look(lastOpened, open, shownOnFx());
            });
            seen.add(look);
            each.accept(look);
            if (settledBeforeThisTurn) {
                turnsSinceSettled++;
            }
        }
        return seen;
    }

    private Shown shown() throws Exception {
        return onFx(this::shownOnFx);
    }

    private Shown shownOnFx() {
        return new Shown(badge.stateProperty().get(), badge.isVisible(), badge.getText(), badge.getTooltip().getText());
    }

    /** What the badge shows. */
    private record Shown(State state, boolean visible, String text, String tooltip) {
    }

    /**
     * One FX turn of the window: the serial of the stream the engine last
     * opened and whether a stream read as open, both read just before the
     * pulse, in that order, and what the pulse showed. The engine publishes a
     * stream before it reads as open, so an open level read after a record is
     * that stream's or a later one's.
     */
    private record Look(long lastOpenedSerial, boolean open, Shown shown) {
    }

    /** What a refused start does on the starting thread before it throws. */
    @FunctionalInterface
    private interface InsideTheRefusedStart {
        void run() throws InterruptedException;
    }

    private static AudioBackend refusingItsNextStart(AudioBackend device, AtomicBoolean refuse) {
        return refusingItsNextStart(device, refuse, () -> { });
    }

    /**
     * {@code device} as the engine's rung, except that once {@code refuse} is
     * set the next stream start fails after its open: the pump's subscription
     * to the input blocks runs {@code inside} and then throws, as a driver
     * that refuses to start does.
     */
    private static AudioBackend refusingItsNextStart(AudioBackend device, AtomicBoolean refuse,
                                                     InsideTheRefusedStart inside) {
        InvocationHandler calls = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "RefusingItsNextStart[" + device.name() + "]";
                };
            }
            if (method.getName().equals("inputBlocks") && refuse.getAndSet(false)) {
                inside.run();
                throw new AudioBackendException(REFUSAL);
            }
            try {
                return method.invoke(device, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        };
        return (AudioBackend) Proxy.newProxyInstance(AudioBackend.class.getClassLoader(),
                new Class<?>[] {AudioBackend.class}, calls);
    }

    /** A request for the mock backend's default device at 44.1 kHz, 24 bits and {@code bufferFrames}. */
    private static AudioEngineController.Request mockDefaultDevice(int bufferFrames) {
        return new AudioEngineController.Request(MockAudioBackend.NAME, "", "", SampleRate.HZ_44100, bufferFrames,
                24, 1);
    }

    /** The controller's real apply of {@code request}, started on a virtual thread as the Settings dialog runs it. */
    private static FutureTask<Void> applyOffTheFxThread(DefaultAudioEngineController controller,
                                                       AudioEngineController.Request request) {
        FutureTask<Void> apply = new FutureTask<>(() -> controller.applyConfiguration(request), null);
        Thread.ofVirtual().name("settings-audio-reconfigure").start(apply);
        return apply;
    }

    /** The provision the controller builds for {@code request}; its head is the selector's mock, given one source. */
    private static StreamingProvision provisionFor(DefaultAudioEngineController controller,
                                                   AudioEngineController.Request request) {
        StreamingProvision provision = controller.buildStreamingProvision(request);
        try {
            configuredMockOf(provision).setClockSources(List.of(INTERNAL));
        } catch (AssertionError notTheMock) {
            closeRungs(provision);
            throw notTheMock;
        }
        return provision;
    }

    /** The selector's mock, which heads a provision the controller builds for {@link #mockDefaultDevice}. */
    private static MockAudioBackend configuredMockOf(StreamingProvision provision) {
        if (!(provision.firstRung().backend() instanceof MockAudioBackend head)) {
            throw new AssertionError("fixture: the configured mock heads the ladder, not "
                    + provision.firstRung().backend().name());
        }
        return head;
    }

    /** Closes the rungs of {@code built} unless the engine holds it: the fixture's close shuts that one down. */
    private void closeUnlessInstalled(StreamingProvision built) {
        if (built != null && built != fixture.engine().getStreamingProvision()) {
            closeRungs(built);
        }
    }

    private static void closeRungs(StreamingProvision provision) {
        for (BackendStreamRung rung : provision.ladder()) {
            try {
                rung.backend().close();
            } catch (RuntimeException ignored) {
                // A never-opened rung's close is best effort.
            }
        }
    }

    private void wire(List<ClockSource> sources) throws Exception {
        wire(sources, real -> real);
    }

    private void wire(List<ClockSource> sources,
                      UnaryOperator<ClockStatusIndicator.StreamClock> reads) throws Exception {
        wire(sources, reads, DeviceLossRecordingFixture.withEngineBridge(projectDirectory));
    }

    private void wire(List<ClockSource> sources, UnaryOperator<ClockStatusIndicator.StreamClock> reads,
                      DeviceLossRecordingFixture built) throws Exception {
        fixture = built;
        fixture.backend().setClockSources(sources);
        DefaultAudioEngineController controller = fixture.engineController().orElseThrow();
        ClockStatusIndicator.StreamClock window = reads.apply(ClockStatusIndicator.engineClock(fixture.engine(),
                controller));
        TakeClockMarks take = TakeClockMarks.of(fixture::controller);
        TakeClockMarks marked = new TakeClockMarks() {
            @Override
            public TakeClockMarks.TakeRecording takeRecording() {
                return take.takeRecording();
            }

            @Override
            public boolean lockChanged(boolean locked, long evidenceNanos) {
                marks.add(locked ? "locked" : "unlocked");
                boolean marked = take.lockChanged(locked, evidenceNanos);
                afterAHandOver.run();
                return marked;
            }

            @Override
            public boolean takeStartsUnlocked() {
                marks.add("unlocked at the take's start");
                return take.takeStartsUnlocked();
            }
        };
        badge = onFx(() -> new ClockStatusIndicator(notices::add, marked, window, dispatcher));
        undo = onFx(() -> MainController.installClockStatusIndicator(badge, controller, dispatcher));
    }

    /**
     * The window's reads of the engine, counting the stream's own reads
     * (those asked about a device), keeping the devices they asked about, and
     * counting every read that returned.
     */
    private record CountingStreamReads(ClockStatusIndicator.StreamClock real, AtomicInteger streamReads,
                                       List<DeviceId> devicesAsked, AtomicInteger readsAnswered)
            implements ClockStatusIndicator.StreamClock {

        CountingStreamReads(ClockStatusIndicator.StreamClock real, AtomicInteger streamReads) {
            this(real, streamReads, Collections.synchronizedList(new ArrayList<>()), new AtomicInteger());
        }

        @Override
        public boolean streamOpen() {
            return real.streamOpen();
        }

        @Override
        public boolean deviceLost() {
            return real.deviceLost();
        }

        @Override
        public Optional<ClockLockStatus> lockStatus() {
            return real.lockStatus();
        }

        @Override
        public Optional<OpenedStream> lastOpened() {
            return real.lastOpened();
        }

        @Override
        public Optional<ClockStatusIndicator.ClockReading> read() {
            try {
                return real.read();
            } finally {
                readsAnswered.incrementAndGet();
            }
        }

        @Override
        public Optional<ClockStatusIndicator.ClockReading> read(DeviceId device) {
            streamReads.incrementAndGet();
            devicesAsked.add(device);
            try {
                return real.read(device);
            } finally {
                readsAnswered.incrementAndGet();
            }
        }
    }

    /** What the mock's driver side measures now, on Word Clock of the device it resolved. */
    private void status(long serial, ClockLockStatus.Lock lock) {
        long evidence = lock == ClockLockStatus.Lock.LOCKED || lock == ClockLockStatus.Lock.UNLOCKED
                ? System.nanoTime() : 0L;
        fixture.backend().setClockLockStatus(new ClockLockStatus(RESOLVED, WORD_CLOCK.id(), serial, lock, evidence));
    }

    /** Pulses the window's dispatcher until the badge shows {@code expected}. */
    private void awaitState(State expected, String what) throws Exception {
        awaitOnFx(() -> {
            dispatcher.pulse();
            return badge.stateProperty().get() == expected;
        }, what);
    }

    private static List<TakeManifest.ClockEvent> clockEvents(Path take) throws Exception {
        return clockMarks(take).stream().map(TakeManifest.ClockMark::event).toList();
    }

    private static List<TakeManifest.ClockMark> clockMarks(Path take) throws Exception {
        return TakeManifest.read(TakeManifest.manifestPath(take)).clockMarks();
    }
}
