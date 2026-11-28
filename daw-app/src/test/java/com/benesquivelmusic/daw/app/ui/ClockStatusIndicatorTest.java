package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ClockStatusIndicator.ClockLabel;
import com.benesquivelmusic.daw.app.ui.ClockStatusIndicator.ClockReading;
import com.benesquivelmusic.daw.app.ui.ClockStatusIndicator.Facts;
import com.benesquivelmusic.daw.app.ui.ClockStatusIndicator.OwnRead;
import com.benesquivelmusic.daw.app.ui.ClockStatusIndicator.State;
import com.benesquivelmusic.daw.app.ui.TakeClockMarks.TakeRecording;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.theme.ThemeContrastValidator;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;
import com.benesquivelmusic.daw.core.audio.OpenedStream;
import com.benesquivelmusic.daw.sdk.audio.ClockKind;
import com.benesquivelmusic.daw.sdk.audio.ClockLockStatus;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import javafx.application.Platform;
import javafx.collections.SetChangeListener;
import javafx.css.PseudoClass;
import javafx.css.TransitionEvent;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.effect.Effect;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.stage.Stage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.prefs.AbstractPreferences;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transport bar's clock badge (rulings E29 to E32, E61, E80 to E85, E100,
 * E101, E108, E110, E111, E119, E135, E136 and E141 to E145): its one decision
 * table, its seven states, the copy of each, and how it derives them on
 * every pulse from the open
 * stream's clock-lock status — a level that also carries its measurement's
 * newest changes, never an event — over a fake engine
 * whose label reads the test runs itself and a fake nanosecond clock.
 * {@code ClockBadgeDuringATakeTest} runs the window's wiring over a real
 * engine and a real take.
 */
@ExtendWith(JavaFxToolkitExtension.class)
@Tag("slow")
class ClockStatusIndicatorTest {

    private static final DeviceId INTERFACE = new DeviceId(MockAudioBackend.NAME, "Interface A");
    private static final DeviceId OTHER_INTERFACE = new DeviceId(MockAudioBackend.NAME, "Interface B");
    /** The device a stream fell back to when the configured one was missing. */
    private static final DeviceId FALLBACK = new DeviceId(MockAudioBackend.NAME, "Interface C");
    private static final DeviceId ASIO_DRIVER = new DeviceId("ASIO", "Interface A ASIO");
    private static final DeviceId ASIO_DEFAULT = DeviceId.defaultFor("ASIO");
    private static final DeviceId PORTAUDIO_DEFAULT = DeviceId.defaultFor("PortAudio");
    private static final DeviceId STUDIO_ASIO = new DeviceId("ASIO", "Studio Interface");
    private static final DeviceId STUDIO_WASAPI = new DeviceId("WASAPI", "Studio Interface");
    private static final ClockSource INTERNAL = new ClockSource(0, "Internal", true, new ClockKind.Internal());
    private static final ClockSource WORD_CLOCK_IDLE = new ClockSource(1, "Word Clock", false, new ClockKind.WordClock());
    private static final ClockSource INTERNAL_IDLE = new ClockSource(0, "Internal", false, new ClockKind.Internal());
    private static final ClockSource WORD_CLOCK = new ClockSource(1, "Word Clock", true, new ClockKind.WordClock());
    private static final ClockSource SPDIF = new ClockSource(2, "S/PDIF", true, new ClockKind.Spdif());
    private static final String QUIET_TOOLTIP = "Hardware clock source. Shows ⚠ UNLOCKED in red when the audio driver"
            + " reports that the external clock is not locked.";
    private static final String UNLOCKED_TOOLTIP = "Hardware clock source. The external clock is not locked."
            + " Check the clock cable and the clock master.";
    private static final String UNLOCK_NOTICE = "External clock lost on Interface A (Word Clock)."
            + " Check the clock cable and the clock master.";
    private static final String RELOCK_NOTICE = "External clock locked again on Interface A (Word Clock).";
    /** An unlock and its relock that came and went between two looks, with no take (ruling E216). */
    private static final String LOST_AND_RELOCKED_NOTICE = "External clock on Interface A (Word Clock) was lost"
            + " and has locked again. Check the clock cable and the clock master.";
    private static final String STARTING_TOOLTIP =
            "Clock source when the audio stream last ran. Lock is not checked yet on this stream.";
    private static final String CLOSED_TOOLTIP =
            "Clock source when the audio stream last ran. Lock is not checked while the stream is closed.";
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    /** What {@link RecordingMarks} records for a take that starts while an unlock is on show (ruling E173). */
    private static final String UNLOCKED_AT_THE_START = "unlocked at the take's start";
    /** The states that show the label while the lock is not checked: the dim view (rulings E81, E100 and E119). */
    private static final List<State> UNCHECKED =
            List.of(State.CHECKING, State.NOT_MEASURABLE, State.CLOSED, State.STARTING);
    /** The role tokens the look tests compare with, each read from a probe the window's theme styles. */
    private static final List<String> PROBED_TOKENS =
            List.of("-danger", "-text-hi", "-text-on-accent", "-text", "-text-mute", "-surface-3");

    private final FakeClock clock = new FakeClock();
    private final ManualReads reads = new ManualReads();
    private final AtomicLong nanos = new AtomicLong(SECOND);
    private final List<String> notices = Collections.synchronizedList(new ArrayList<>());
    private final List<String> informationNotices = Collections.synchronizedList(new ArrayList<>());
    private final NotificationManager sink = new NotificationManager() {
        @Override
        public void notify(String message) {
            notices.add(message);
        }

        @Override
        public void notifyInformation(String message) {
            notices.add(message);
            informationNotices.add(message);
        }
    };
    private final RecordingMarks marks = new RecordingMarks();

    @Test
    void theBadgeIsHiddenUntilASourceIsReadAndWhileTheDeviceReportsNone() throws Exception {
        ClockStatusIndicator badge = badge();
        View before = view(badge);

        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 48_000));
        opened(badge);
        View noSources = view(badge);
        int readsAfterTheOpen = reads.requested.get();
        nanos.addAndGet(5 * SECOND);
        look(badge);

        assertThat(before.state()).as("the list was never read (ruling E80)").isEqualTo(State.HIDDEN);
        assertThat(before.visible()).isFalse();
        assertThat(before.managed()).isFalse();
        assertThat(noSources.state()).as("no clock sources (ruling E32)").isEqualTo(State.HIDDEN);
        assertThat(noSources.visible()).isFalse();
        assertThat(reads.requested.get()).as("no status and no list: the device reports none, nothing to retry")
                .isEqualTo(readsAfterTheOpen);
    }

    @Test
    void anInternalSourceIsQuietAndAnExternalOneIsCheckedUntilThisStreamAnswers() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL, WORD_CLOCK_IDLE), 48_000));
        clock.status = Optional.of(status(INTERNAL.id(), 1, ClockLockStatus.Lock.NOT_MEASURABLE, 0L));
        opened(badge);
        View internal = view(badge);

        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 96_000));
        clock.status = Optional.of(checking(2));
        refresh(badge);
        View external = view(badge);

        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, SPDIF), 44_100));
        clock.status = Optional.of(status(SPDIF.id(), 3, ClockLockStatus.Lock.CHECKING, 0L));
        refresh(badge);
        View spdif = view(badge);

        assertThat(internal.state()).as("internal reads locked (ruling E29)").isEqualTo(State.QUIET);
        assertThat(internal.text()).isEqualTo("INT 48k");
        assertThat(internal.styleClasses()).contains(ClockStatusIndicator.LOCKED_CLASS, "numeric-mono")
                .doesNotContain(ClockStatusIndicator.DIM_CLASS, ClockStatusIndicator.UNLOCKED_CLASS);
        assertThat(external.state()).as("not measured until this stream answers (ruling E82)")
                .isEqualTo(State.CHECKING);
        assertThat(external.text()).isEqualTo("W/C 96k");
        assertThat(external.styleClasses()).contains(ClockStatusIndicator.DIM_CLASS)
                .doesNotContain(ClockStatusIndicator.LOCKED_CLASS);
        assertThat(spdif.text()).as("the engineer's shorthand").isEqualTo("SPDIF 44.1k");
    }

    @Test
    void eachStateSpeaksAndExplainsItselfAsRuled() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 44_100));
        clock.status = Optional.of(checking(1));
        opened(badge);
        View checking = view(badge);
        statusNow(badge, unlocked(1, 7L));
        View unlocked = view(badge);
        statusNow(badge, locked(1, 9L));
        View quiet = view(badge);
        clock.closeStream();
        look(badge);
        View closed = view(badge);
        clock.status = Optional.empty();
        opened(badge);
        View notMeasurable = view(badge);

        assertThat(checking.spoken()).isEqualTo("Clock: Word Clock, 44.1 kilohertz. Checking lock.");
        assertThat(checking.tooltip()).isEqualTo("Checking the external clock lock.");
        assertThat(unlocked.text()).isEqualTo("⚠ W/C 44.1k UNLOCKED");
        assertThat(unlocked.spoken()).isEqualTo("Clock: Word Clock, 44.1 kilohertz. Not locked.");
        assertThat(unlocked.tooltip()).as("ruling E101").isEqualTo(UNLOCKED_TOOLTIP);
        assertThat(quiet.spoken()).isEqualTo("Clock: Word Clock, 44.1 kilohertz.");
        assertThat(quiet.tooltip()).as("ruling E101").isEqualTo(QUIET_TOOLTIP);
        assertThat(closed.spoken())
                .isEqualTo("Clock: Word Clock, 44.1 kilohertz. Lock not checked while the audio stream is closed.");
        assertThat(closed.tooltip()).isEqualTo(
                "Clock source when the audio stream last ran. Lock is not checked while the stream is closed.");
        assertThat(notMeasurable.spoken()).isEqualTo("Clock: Word Clock, 44.1 kilohertz. Lock not checked on this device.");
        assertThat(List.of(checking, unlocked, quiet, closed, notMeasurable))
                .as("never a motion word in a tooltip or a spoken text (ruling E101)")
                .allSatisfy(view -> assertThat(view.tooltip() + " " + view.spoken())
                        .doesNotContainIgnoringCase("flash").doesNotContainIgnoringCase("blink"));
    }

    @Test
    void aStopKeepsTheSourceVisibleDimAndNotMeasured() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL), 48_000));
        opened(badge);

        clock.closeStream();
        look(badge);
        View closed = view(badge);

        assertThat(closed.state()).isEqualTo(State.CLOSED);
        assertThat(closed.visible()).as("never a badge that vanishes at an ordinary Stop (ruling E81)").isTrue();
        assertThat(closed.managed()).isTrue();
        assertThat(closed.text()).isEqualTo("INT 48k");
        assertThat(closed.styleClasses()).contains(ClockStatusIndicator.DIM_CLASS)
                .doesNotContain(ClockStatusIndicator.LOCKED_CLASS, ClockStatusIndicator.UNLOCKED_CLASS);
        assertThat(closed.spoken())
                .isEqualTo("Clock: Internal, 48 kilohertz. Lock not checked while the audio stream is closed.");
    }

    @Test
    void anUnlockShowingAtACloseTurnsDimWithNoRelockNoticeOrMark() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));

        clock.closeStream();
        look(badge);
        statusNow(badge, locked(1, 9L));
        View closed = view(badge);

        assertThat(closed.state()).isEqualTo(State.CLOSED);
        assertThat(closed.text()).as("no ⚠ and no UNLOCKED (ruling E81)").isEqualTo("W/C 48k");
        assertThat(notices).as("only the unlock's notice; none at the close").containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).as("no relock mark at an on-purpose close").containsExactly("unlocked@7");
    }

    @Test
    void theNextOpenChecksAgainAndOnlyAnUnlockOfThisStreamIsNotified() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));
        clock.closeStream();
        look(badge);
        clock.status = Optional.of(checking(2));
        opened(badge);
        View reopened = view(badge);
        look(badge);
        View afterOneBadAnswer = view(badge);
        statusNow(badge, locked(2, 8L));
        View firstGoodAnswer = view(badge);
        List<String> noticesAfterTheFirstAnswer = List.copyOf(notices);

        statusNow(badge, unlocked(2, 9L));
        View unlockedAgain = view(badge);
        look(badge);
        statusNow(badge, locked(2, 10L));
        View relocked = view(badge);

        assertThat(reopened.state()).as("nothing carried from the closed stream (ruling E82)")
                .isEqualTo(State.CHECKING);
        assertThat(afterOneBadAnswer.state()).as("one bad answer publishes nothing: still checking")
                .isEqualTo(State.CHECKING);
        assertThat(firstGoodAnswer.state()).isEqualTo(State.QUIET);
        assertThat(noticesAfterTheFirstAnswer).as("the first good answer of a stream is not a relock (ruling E83)")
                .containsExactly(UNLOCK_NOTICE);
        assertThat(unlockedAgain.state()).isEqualTo(State.UNLOCKED);
        assertThat(relocked.state()).isEqualTo(State.QUIET);
        assertThat(notices).as("a new unlock, once, and its relock (ruling E31)")
                .containsExactly(UNLOCK_NOTICE, UNLOCK_NOTICE, RELOCK_NOTICE);
        assertThat(informationNotices).as("the relock at the information level (ruling E110)")
                .containsExactly(RELOCK_NOTICE);
        assertThat(marks.marks).as("each at its status's evidence time").containsExactly("unlocked@7", "unlocked@9",
                "locked@10");
    }

    @Test
    void anUnlockDuringATakeSaysTheTakeContinuesAndIsMarked() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        marks.recording = TakeRecording.AUDIO;

        statusNow(badge, unlocked(1, 7L));

        assertThat(notices).containsExactly(UNLOCK_NOTICE
                + " Recording continues; the take is marked from this point.");
        assertThat(marks.marks).containsExactly("unlocked@7");
    }

    /** Ruling E108: a take that writes no audio manifest is not promised a mark. */
    @Test
    void anUnlockDuringAMidiOnlyTakePromisesNoMark() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        marks.recording = TakeRecording.WITHOUT_AUDIO;

        statusNow(badge, unlocked(1, 7L));

        assertThat(notices).containsExactly(UNLOCK_NOTICE + " Recording continues.");
    }

    /**
     * Ruling E173: an unlock applied before the take was first seen, still
     * on show when it is, is marked once at that take's start, with no
     * notice; the next take is judged afresh.
     */
    @Test
    void anUnlockOnShowWhenAnAudioTakeIsFirstSeenIsMarkedOnceAtEachTakesStart() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));

        marks.recording = TakeRecording.AUDIO;
        look(badge);
        look(badge);
        List<String> marksOfTheFirstTake = List.copyOf(marks.marks);
        marks.recording = TakeRecording.NONE;
        look(badge);
        marks.recording = TakeRecording.AUDIO;
        look(badge);

        assertThat(marksOfTheFirstTake).as("once for the take, however many looks see it")
                .containsExactly("unlocked@7", UNLOCKED_AT_THE_START);
        assertThat(marks.marks).as("the next take starts on the same unlock")
                .containsExactly("unlocked@7", UNLOCKED_AT_THE_START, UNLOCKED_AT_THE_START);
        assertThat(notices).as("no notice is added").containsExactly(UNLOCK_NOTICE);
    }

    @Test
    void aRelockBeforeTheTakeOrATakeWithoutAudioMarksNothingAtTheTakesStart() throws Exception {
        ClockStatusIndicator relocked = externalBadge();
        statusNow(relocked, unlocked(1, 7L));
        statusNow(relocked, locked(1, 9L));
        marks.recording = TakeRecording.AUDIO;
        look(relocked);
        List<String> afterTheRelock = List.copyOf(marks.marks);

        marks.marks.clear();
        marks.recording = TakeRecording.NONE;
        ClockStatusIndicator midiOnly = externalBadge();
        statusNow(midiOnly, unlocked(1, 7L));
        marks.recording = TakeRecording.WITHOUT_AUDIO;
        look(midiOnly);

        assertThat(afterTheRelock).as("a relock declared before the take writes nothing")
                .containsExactly("unlocked@7", "locked@9");
        assertThat(marks.marks).as("a take without audio is not marked").containsExactly("unlocked@7");
    }

    /**
     * Ruling E173 marks a take at its start for the unlock applied on the
     * stream it records on: an unlock applied on one serial marks nothing at
     * the start of an audio take first seen on the next stream's serial,
     * whose own unlock is announced and marked as any unlock is.
     */
    @Test
    void anUnlockAppliedOnOneSerialMarksNothingAtTheStartOfATakeFirstSeenOnAnother() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));

        clock.closeStream();
        clock.status = Optional.of(unlocked(2, 8L));
        clock.openStream();
        marks.recording = TakeRecording.AUDIO;
        look(badge);
        look(badge);

        assertThat(view(badge).state()).as("premise: the next stream is measured unlocked").isEqualTo(State.UNLOCKED);
        assertThat(marks.marks).as("no mark at the take's start; the next stream's own unlock")
                .containsExactly("unlocked@7", "unlocked@8");
    }

    /**
     * Ruling E173: the audio take the badge saw is remembered only while
     * every look sees it. A look that sees no take while the stream is not
     * measured, here while its status names another source, forgets it too,
     * so the next take on the same unlock is marked at its start.
     */
    @Test
    void aLookThatSeesNoTakeWhileTheStreamIsNotMeasuredForgetsTheTakeItSaw() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));
        marks.recording = TakeRecording.AUDIO;
        look(badge);

        marks.recording = TakeRecording.NONE;
        statusNow(badge, status(SPDIF.id(), 1, ClockLockStatus.Lock.UNLOCKED, 7L));
        State notMeasured = view(badge).state();
        marks.recording = TakeRecording.AUDIO;
        statusNow(badge, unlocked(1, 7L));

        assertThat(notMeasured).as("premise: the status names another source").isEqualTo(State.CHECKING);
        assertThat(marks.marks).as("each take marked at its start once")
                .containsExactly("unlocked@7", UNLOCKED_AT_THE_START, UNLOCKED_AT_THE_START);
    }

    /**
     * The same across a device loss, whose look sees no take: the loss also
     * forgets the status applied, so the unlock the returned stream still
     * measures is announced and marked again, and the take recorded after
     * the return is marked at its start.
     */
    @Test
    void aTakeRecordedAfterADeviceLossOnTheSameUnlockIsMarkedAtItsStart() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));
        marks.recording = TakeRecording.AUDIO;
        look(badge);

        marks.recording = TakeRecording.NONE;
        clock.lost = true;
        look(badge);
        State duringTheLoss = view(badge).state();
        clock.lost = false;
        look(badge);
        marks.recording = TakeRecording.AUDIO;
        look(badge);

        assertThat(duringTheLoss).as("premise: the loss hides the badge").isEqualTo(State.HIDDEN);
        assertThat(notices).as("the unlock announced again after the loss, with no take")
                .containsExactly(UNLOCK_NOTICE, UNLOCK_NOTICE);
        assertThat(marks.marks).as("the take after the return marked at its start")
                .containsExactly("unlocked@7", UNLOCKED_AT_THE_START, "unlocked@7", UNLOCKED_AT_THE_START);
    }

    @Test
    void anInternalSourceMarksNothingAtTheTakesStart() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL, WORD_CLOCK_IDLE), 48_000));
        clock.status = Optional.of(status(INTERNAL.id(), 1, ClockLockStatus.Lock.UNLOCKED, 7L));
        opened(badge);
        look(badge);

        marks.recording = TakeRecording.AUDIO;
        look(badge);

        assertThat(view(badge).state()).as("premise: the internal source is measured").isEqualTo(State.QUIET);
        assertThat(marks.marks).as("an internal clock has no lock to mark").isEmpty();
    }

    /** Ruling E216: what came and went between two looks is one warning and every mark, in order. */
    @Test
    void anUnlockAndItsRelockBetweenTwoLooksAreOneWarningAndTwoMarksInOrder() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));

        statusNow(badge, history(1, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));

        assertThat(view(badge).state()).isEqualTo(State.QUIET);
        assertThat(notices).containsExactly(LOST_AND_RELOCKED_NOTICE);
        assertThat(informationNotices).as("a warning, not information").isEmpty();
        assertThat(marks.marks).as("each at its own evidence, in order").containsExactly("unlocked@7", "locked@9");
    }

    @Test
    void severalUnlocksBetweenTwoLooksSayHowMany() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));

        statusNow(badge, history(1, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L), change(3, 11L),
                change(4, 13L)));

        assertThat(notices).containsExactly("External clock on Interface A (Word Clock) was lost 2 times and has"
                + " locked again. Check the clock cable and the clock master.");
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9", "unlocked@11", "locked@13");
    }

    /** Ruling E223: the take sentence only when one of the look's unlocks left an audio take marked. */
    @Test
    void theCombinedNoticeEndsAsTheTakeAndItsMarksAllow() throws Exception {
        String audio = lostAndRelockedWhile(TakeRecording.AUDIO, true);
        String withoutAudio = lostAndRelockedWhile(TakeRecording.WITHOUT_AUDIO, true);
        String audioNotMarked = lostAndRelockedWhile(TakeRecording.AUDIO, false);

        assertThat(audio).isEqualTo(LOST_AND_RELOCKED_NOTICE
                + " Recording continues; the take is marked where it was lost.");
        assertThat(withoutAudio).as("ruling E108").isEqualTo(LOST_AND_RELOCKED_NOTICE + " Recording continues.");
        assertThat(audioNotMarked).as("the audio take holds no unlock after the look")
                .isEqualTo(LOST_AND_RELOCKED_NOTICE + " Recording continues.");
    }

    @Test
    void aRelockAndAnUnlockWhileUnlockedIsShownAreMarkedWithNoNotice() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, history(1, ClockLockStatus.Lock.UNLOCKED, change(1, 7L)));

        statusNow(badge, history(1, ClockLockStatus.Lock.UNLOCKED, change(1, 7L), change(2, 9L), change(3, 11L)));

        assertThat(view(badge).state()).isEqualTo(State.UNLOCKED);
        assertThat(notices).as("the unlock on show is not announced again").containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9", "unlocked@11");
    }

    @Test
    void aTransitionAlreadyAppliedIsNeverReplayed() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        statusNow(badge, history(1, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));

        look(badge);
        look(badge);

        assertThat(notices).containsExactly(LOST_AND_RELOCKED_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9");
    }

    /** Rulings E220 and E252: a loss consumes the serial in play; only a change after it is replayed. */
    @Test
    void aLossConsumesItsSerialsTransitions() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.lost = true;
        look(badge);

        clock.lost = false;
        statusNow(badge, history(1, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));
        List<String> noticesAtTheReturn = List.copyOf(notices);
        List<String> marksAtTheReturn = List.copyOf(marks.marks);
        statusNow(badge, history(1, ClockLockStatus.Lock.UNLOCKED, change(1, 7L), change(2, 9L), change(3, 11L)));

        assertThat(noticesAtTheReturn).as("nothing of the consumed serial is replayed").isEmpty();
        assertThat(marksAtTheReturn).isEmpty();
        assertThat(notices).as("a change after the return").containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@11");
    }

    /** Rulings E220 and E259: a close consumes the serial last applied. */
    @Test
    void aCloseConsumesItsSerialsTransitions() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.closeStream();
        look(badge);

        clock.status = Optional.of(history(1, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));
        opened(badge);
        List<String> noticesAtTheReopen = List.copyOf(notices);
        List<String> marksAtTheReopen = List.copyOf(marks.marks);
        statusNow(badge, history(1, ClockLockStatus.Lock.UNLOCKED, change(1, 7L), change(2, 9L), change(3, 11L)));

        assertThat(view(badge).state()).as("premise: the reopened stream is measured").isEqualTo(State.UNLOCKED);
        assertThat(noticesAtTheReopen).as("nothing of the consumed serial is replayed").isEmpty();
        assertThat(marksAtTheReopen).isEmpty();
        assertThat(notices).as("a change after the reopen").containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@11");
    }

    /**
     * Rulings E252 and E268: the first look of a loss consumes the serial last applied, never the one it read, so
     * a serial first read at a loss has its own changes replayed at its first measuring look.
     */
    @Test
    void aSerialFirstReadAtALossIsReplayedOnceTheBadgeMeasuresIt() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));

        clock.status = Optional.of(history(2, ClockLockStatus.Lock.UNLOCKED, change(1, 7L)));
        clock.lost = true;
        look(badge);
        clock.lost = false;
        clock.status = Optional.of(history(2, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));
        look(badge);

        assertThat(view(badge).state()).as("premise: measured after the loss").isEqualTo(State.QUIET);
        assertThat(notices).containsExactly(LOST_AND_RELOCKED_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9");
    }

    /**
     * Ruling E268: the first look of a loss consumes the serial last applied, never the one it read, which can be
     * that of a stream opened after the loss began; that stream's changes are replayed at its first measuring look.
     */
    @Test
    void aLossWhoseFirstLookReadsANewStreamConsumesOnlyTheSerialLastApplied() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));

        clock.lost = true;
        clock.closeStream();
        clock.status = Optional.of(checking(2));
        clock.openStream();
        look(badge);
        clock.lost = false;
        statusNow(badge, history(2, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));

        assertThat(view(badge).state()).as("premise: measured after the loss").isEqualTo(State.QUIET);
        assertThat(notices).containsExactly(LOST_AND_RELOCKED_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9");
    }

    /**
     * Ruling E268: a look reads the lock status before the loss, so a stream opened as the loss begins, between
     * that look's two reads, is never measured before the loss is seen and has its changes replayed once it ends.
     */
    @Test
    void aStreamOpenedAsALossBeginsInsideALookHasItsChangesReplayedOnceTheLossEnds() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));

        clock.afterTheNextLossOrStatusRead = () -> {
            clock.lost = true;
            clock.closeStream();
            clock.status = Optional.of(checking(2));
            clock.openStream();
        };
        look(badge);
        look(badge);
        clock.lost = false;
        statusNow(badge, history(2, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));

        assertThat(clock.afterTheNextLossOrStatusRead).as("premise: the loss began inside the first look").isNull();
        assertThat(notices).containsExactly(LOST_AND_RELOCKED_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9");
    }

    /** Ruling E252: later looks of a loss consume nothing, so a stream reopened during it is replayed. */
    @Test
    void aStreamReopenedDuringALossHasItsOwnChangesReplayedOnceTheLossEnds() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));

        clock.lost = true;
        look(badge);
        clock.status = Optional.of(checking(2));
        look(badge);
        clock.lost = false;
        clock.status = Optional.of(history(2, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));
        look(badge);

        assertThat(notices).containsExactly(LOST_AND_RELOCKED_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9");
    }

    /**
     * Rulings E252 and E259: a later look of a close that reads a stream
     * opened after it consumes nothing; that stream's standing unlock is
     * announced and marked.
     */
    @Test
    void aLaterLookOfACloseThatReadsANewStreamForgetsNothing() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.closeStream();
        look(badge);

        clock.status = Optional.of(history(2, ClockLockStatus.Lock.UNLOCKED, change(1, 7L)));
        clock.afterTheNextEngineRead = clock::openStream;
        look(badge);

        assertThat(notices).containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7");
    }

    /** Rulings E216, E219 and E259: as above, with changes that only a replay marks. */
    @Test
    void aLaterLookOfACloseThatReadsANewStreamKeepsItsChangesForTheReplay() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.closeStream();
        look(badge);

        clock.status = Optional.of(history(2, ClockLockStatus.Lock.UNLOCKED, change(1, 7L), change(2, 9L),
                change(3, 11L)));
        clock.afterTheNextEngineRead = clock::openStream;
        look(badge);

        assertThat(notices).containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9", "unlocked@11");
    }

    /**
     * Ruling E259: the first look of a close can read a stream that opened
     * during that look; the close consumes the serial last applied, never
     * the one it read, whose changes are replayed.
     */
    @Test
    void aCloseWhoseFirstLookReadsANewStreamConsumesOnlyTheSerialLastApplied() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.closeStream();

        clock.status = Optional.of(history(2, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));
        clock.afterTheNextEngineRead = clock::openStream;
        look(badge);

        assertThat(notices).containsExactly(LOST_AND_RELOCKED_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9");
    }

    /** Ruling E219: the first measuring look of a serial replays it from its start. */
    @Test
    void aFirstMeasuringLookReplaysItsSerialFromItsStart() throws Exception {
        ClockStatusIndicator badge = externalBadge();

        statusNow(badge, history(2, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));

        assertThat(notices).containsExactly(LOST_AND_RELOCKED_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9");
    }

    @Test
    void aLookThatDoesNotMeasureAppliesNothing() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, historyOn(SPDIF.id(), 1, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));
        State notMeasured = view(badge).state();
        List<String> noticesNotMeasured = List.copyOf(notices);
        List<String> marksNotMeasured = List.copyOf(marks.marks);

        statusNow(badge, history(1, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));

        assertThat(notMeasured).as("premise: the status names another source").isEqualTo(State.CHECKING);
        assertThat(noticesNotMeasured).isEmpty();
        assertThat(marksNotMeasured).isEmpty();
        assertThat(notices).containsExactly(LOST_AND_RELOCKED_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9");
    }

    /**
     * Ruling E218: more changes than the status keeps are a gap, marked
     * unlocked from three seconds before the look that applied the serial
     * last, and counted "or more" with one unlock for the gap.
     */
    @Test
    void aGapIsMarkedFromThreeSecondsBeforeTheLastLookAndCountedAsOrMore() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        nanos.set(20 * SECOND);
        statusNow(badge, locked(1, 5 * SECOND));

        statusNow(badge, history(1, ClockLockStatus.Lock.LOCKED, changes(3, 34, 21 * SECOND)));

        assertThat(marks.marks).hasSize(33)
                .startsWith("unlocked@17000000000", "unlocked@21000000003", "locked@21000000004");
        assertThat(notices).containsExactly("External clock on Interface A (Word Clock) was lost 17 or more times"
                + " and has locked again. Check the clock cable and the clock master.");
    }

    /** Ruling E218: the gap's mark is never dated before the evidence of the status last applied. */
    @Test
    void aGapIsNeverMarkedBeforeTheEvidenceOfTheStatusLastApplied() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        nanos.set(20 * SECOND);
        statusNow(badge, locked(1, 19 * SECOND));

        statusNow(badge, history(1, ClockLockStatus.Lock.LOCKED, changes(3, 34, 21 * SECOND)));

        assertThat(marks.marks).first().isEqualTo("unlocked@19000000000");
    }

    @Test
    void aGapWhileUnlockedIsShownGivesNoNotice() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        nanos.set(20 * SECOND);
        statusNow(badge, history(1, ClockLockStatus.Lock.UNLOCKED, change(1, 15 * SECOND)));

        statusNow(badge, history(1, ClockLockStatus.Lock.UNLOCKED, changes(4, 35, 21 * SECOND)));

        assertThat(notices).containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).startsWith("unlocked@15000000000", "unlocked@17000000000", "locked@21000000004",
                "unlocked@21000000005");
    }

    /** Ruling E218: a gap at a serial's first measuring look is marked from the take's start. */
    @Test
    void aGapAtAFirstMeasuringLookIsMarkedFromTheTakesStart() throws Exception {
        nanos.set(20 * SECOND);
        ClockStatusIndicator badge = externalBadge();
        marks.recording = TakeRecording.AUDIO;

        statusNow(badge, history(2, ClockLockStatus.Lock.LOCKED, changes(3, 34, 21 * SECOND)));

        assertThat(marks.marks).startsWith(UNLOCKED_AT_THE_START, "unlocked@21000000003");
        assertThat(notices).containsExactly("External clock on Interface A (Word Clock) was lost 17 or more times"
                + " and has locked again. Check the clock cable and the clock master. Recording continues; the take"
                + " is marked where it was lost.");
    }

    @Test
    void anInternalSourceReplaysNothing() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL, WORD_CLOCK_IDLE), 48_000));
        clock.status = Optional.of(historyOn(INTERNAL.id(), 1, ClockLockStatus.Lock.LOCKED, change(1, 7L),
                change(2, 9L)));
        opened(badge);
        look(badge);

        assertThat(view(badge).state()).as("premise: the internal source is measured").isEqualTo(State.QUIET);
        assertThat(notices).isEmpty();
        assertThat(marks.marks).as("an internal clock has no lock to mark").isEmpty();
    }

    /** Rulings E173 and E216: the take's start mark comes before the changes the same look hands over. */
    @Test
    void theStartMarkComesBeforeTheTransitionsItMissed() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, history(1, ClockLockStatus.Lock.UNLOCKED, change(1, 7L)));
        marks.recording = TakeRecording.AUDIO;

        statusNow(badge, history(1, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));

        assertThat(marks.marks).containsExactly("unlocked@7", UNLOCKED_AT_THE_START, "locked@9");
        assertThat(notices).containsExactly(UNLOCK_NOTICE, RELOCK_NOTICE);
    }

    @Test
    void oneLookGivesOneNotice() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));

        statusNow(badge, history(1, ClockLockStatus.Lock.UNLOCKED, change(1, 7L), change(2, 9L), change(3, 11L)));

        assertThat(notices).containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7", "locked@9", "unlocked@11");
    }

    @Test
    void aDeviceLossHidesTheBadgeAndPublishesNothing() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));

        clock.lost = true;
        clock.closeStream();
        look(badge);
        statusNow(badge, locked(1, 9L));
        View lost = view(badge);

        assertThat(lost.state()).as("never a source shown for a lost device (ruling E84)").isEqualTo(State.HIDDEN);
        assertThat(lost.visible()).isFalse();
        assertThat(notices).as("no relock notice at the loss").containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).as("no relock mark: the suspect region runs to the seal").containsExactly("unlocked@7");
    }

    @Test
    void aReadThatLandsWhileTheDeviceIsLostOrTheStreamClosedAgainShowsNothing() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(OTHER_INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(1));
        clock.openStream();
        clock.lost = true;
        refresh(badge);
        View stillLost = view(badge);

        clock.lost = false;
        badge.refresh();
        clock.closeStream();
        reads.runAll();
        runOnFx(() -> { });
        View closedBeforeTheAnswer = view(badge);

        assertThat(stillLost.state())
                .as("a Reconnect that opened another device stays lost: no source of another device")
                .isEqualTo(State.HIDDEN);
        assertThat(closedBeforeTheAnswer.state()).as("never a source read for a stream already closed")
                .isEqualTo(State.HIDDEN);
    }

    @Test
    void aChangeInSettingsWhileTheStreamIsClosedHidesTheBadgeAndNoChangeKeepsIt() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        clock.closeStream();
        look(badge);

        refresh(badge);
        View unchanged = view(badge);
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 96_000));
        refresh(badge);
        View rateChanged = view(badge);

        ClockStatusIndicator other = externalBadge();
        clock.closeStream();
        look(other);
        clock.reading = Optional.of(new ClockReading(OTHER_INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        refresh(other);
        View deviceChanged = view(other);

        ClockStatusIndicator third = externalBadge();
        clock.closeStream();
        look(third);
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL, WORD_CLOCK_IDLE), 48_000));
        refresh(third);
        View sourceChanged = view(third);

        assertThat(unchanged.state()).as("device, rate and source provably unchanged").isEqualTo(State.CLOSED);
        assertThat(rateChanged.state()).as("the rate changed while closed (ruling E81)").isEqualTo(State.HIDDEN);
        assertThat(deviceChanged.state()).as("the device changed while closed (ruling E81)").isEqualTo(State.HIDDEN);
        assertThat(sourceChanged.state()).as("the clock source changed while closed (ruling E81)")
                .isEqualTo(State.HIDDEN);
    }

    @Test
    void aStreamLeftOpenByAPauseOrADeferredStopKeepsMeasuring() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));

        look(badge);
        look(badge);

        assertThat(view(badge).state()).as("the poll still runs (ruling E85)").isEqualTo(State.UNLOCKED);
        assertThat(notices).as("one unlock, one notice").containsExactly(UNLOCK_NOTICE);
    }

    /** A lock decided before the badge first looked is shown: the level is read, never awaited as an event. */
    @Test
    void aStreamAlreadyLockedBeforeTheBadgeFirstLookedShowsQuiet() throws Exception {
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(locked(1, 5L));
        ClockStatusIndicator badge = badge();

        opened(badge);

        assertThat(view(badge).state()).as("never \"Checking lock.\" for a stream already measured (ruling E85)")
                .isEqualTo(State.QUIET);
        assertThat(notices).as("a first measurement is no relock (ruling E83)").isEmpty();
        assertThat(marks.marks).isEmpty();
    }

    /** An unlock that happened before the badge first looked is still notified, once. */
    @Test
    void anUnlockDecidedBeforeTheBadgeFirstLookedIsNotifiedOnce() throws Exception {
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(unlocked(1, 6L));
        ClockStatusIndicator badge = badge();

        opened(badge);
        look(badge);

        assertThat(view(badge).state()).isEqualTo(State.UNLOCKED);
        assertThat(notices).containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).as("marked at the status's evidence time").containsExactly("unlocked@6");
    }

    /** A read of the same open stream's label decides nothing by itself (review D finding 10). */
    @Test
    void aRefreshInPlaceKeepsAnUnlockAndItsRelockIsNotifiedAndMarked() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));

        refresh(badge);
        View afterTheRefresh = view(badge);
        statusNow(badge, locked(1, 9L));

        assertThat(afterTheRefresh.state()).as("the refresh in place keeps the measured state")
                .isEqualTo(State.UNLOCKED);
        assertThat(notices).containsExactly(UNLOCK_NOTICE, RELOCK_NOTICE);
        assertThat(marks.marks).as("the take's suspect region closes").containsExactly("unlocked@7", "locked@9");
    }

    /**
     * A reopen whose label read fails keeps the label that was on show, dim,
     * with the starting texts — never the closed texts on an open stream
     * (rulings E85, E119) — and is read again one second of the badge's clock
     * later, not before; the first good read then applies the stream's
     * status, so its unlock is notified once.
     */
    @Test
    void aFailedReadAtTheNextOpenKeepsTheLabelStartingAndIsAskedAgainAfterOneSecond() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        clock.closeStream();
        look(badge);
        assertThat(view(badge).state()).as("premise: closed on purpose").isEqualTo(State.CLOSED);

        clock.status = Optional.of(checking(2));
        clock.failure = new IllegalStateException("the driver's panel is open");
        clock.openStream();
        look(badge);
        View rightAfterTheFailedRead = view(badge);
        look(badge);
        View afterAPulse = view(badge);
        int readsAfterTheOpen = reads.requested.get();
        nanos.addAndGet(SECOND / 2);
        look(badge);
        int readsHalfASecondLater = reads.requested.get();
        nanos.addAndGet(SECOND / 2);
        look(badge);
        int readsASecondLater = reads.requested.get();

        clock.failure = null;
        clock.status = Optional.of(unlocked(2, 11L));
        nanos.addAndGet(SECOND);
        look(badge);
        look(badge);

        assertThat(rightAfterTheFailedRead.state()).as("never CLOSED on an open stream, never hidden (E85, E119)")
                .isEqualTo(State.STARTING);
        assertStarting(rightAfterTheFailedRead, "W/C 48k", "Word Clock", "48");
        assertThat(afterAPulse).isEqualTo(rightAfterTheFailedRead);
        assertThat(readsHalfASecondLater).as("not asked again within the second").isEqualTo(readsAfterTheOpen);
        assertThat(readsASecondLater).as("asked again after one second").isEqualTo(readsAfterTheOpen + 1);
        assertThat(view(badge).state()).isEqualTo(State.UNLOCKED);
        assertThat(notices).as("the stream's unlock, once").containsExactly(UNLOCK_NOTICE);
    }

    /**
     * A stream unlocked, then a new stream the badge never saw closed
     * between (closed and reopened inside one frame) whose first read fails
     * and whose status is LOCKED under a new serial: no "locked again", no
     * LOCKED mark (rulings E82, E83).
     */
    @Test
    void aNewStreamLockedUnderANewSerialIsNoRelockEvenWhenItsFirstReadFailed() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));

        clock.status = Optional.of(locked(2, 12L));
        clock.failure = new IllegalStateException("the driver's panel is open");
        clock.openStream();
        look(badge);
        look(badge);
        View whileUnread = view(badge);
        clock.failure = null;
        nanos.addAndGet(SECOND);
        look(badge);
        look(badge);

        assertThat(whileUnread.state()).as("never the previous stream's UNLOCKED; its label kept (ruling E119)")
                .isEqualTo(State.STARTING);
        assertThat(whileUnread.text()).as("no ⚠ and no UNLOCKED").isEqualTo("W/C 48k");
        assertThat(view(badge).state()).isEqualTo(State.QUIET);
        assertThat(notices).containsExactly(UNLOCK_NOTICE);
        assertThat(marks.marks).containsExactly("unlocked@7");
    }

    /**
     * A stream that opens with no stream-opened callback is seen by the
     * pulse, which reads the stream the engine last opened: the label on show
     * belonged to the closed stream, so until the new one's read answers it
     * keeps that label with the starting texts (ruling E119), never the
     * closed ones.
     */
    @Test
    void aStreamThatOpensWithoutTheCallbackIsANewStreamAtTheNextPulse() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        clock.closeStream();
        look(badge);
        clock.status = Optional.of(checking(2));
        clock.failure = new IllegalStateException("the driver's panel is open");

        clock.openStream();
        runOnFx(badge::pulse);
        View whileItsReadIsInFlight = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        look(badge);

        assertThat(whileItsReadIsInFlight.state())
                .as("starting until this stream's label is read: never CLOSED on an open stream (E85, E119)")
                .isEqualTo(State.STARTING);
        assertThat(view(badge).state()).as("no label read for this stream yet").isEqualTo(State.STARTING);
        assertThat(reads.requested.get()).as("the pulse asked for the new stream's label").isEqualTo(2);
    }

    /** Ruling E80: an unreadable list keeps the label and the lock this stream has on show. */
    @Test
    void anEmptySourceListOnAnOpenStreamKeepsTheLabelAndTheState() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));

        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 48_000));
        refresh(badge);
        View afterTheEmptyList = view(badge);

        assertThat(afterTheEmptyList.state()).isEqualTo(State.UNLOCKED);
        assertThat(afterTheEmptyList.text()).isEqualTo("⚠ W/C 48k UNLOCKED");
    }

    @Test
    void aReadThatFailsKeepsWhatTheBadgeShows() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, unlocked(1, 7L));

        clock.failure = new IllegalStateException("the driver's panel is open");
        refresh(badge);
        View afterTheFailure = view(badge);
        statusNow(badge, locked(1, 9L));

        assertThat(afterTheFailure.state()).as("a failed read decides nothing (ruling E80)")
                .isEqualTo(State.UNLOCKED);
        assertThat(view(badge).state()).isEqualTo(State.QUIET);
    }

    /**
     * The engine opened ASIO's default device and the driver side measures the
     * driver it resolved: the unlock is shown, notified with that driver's name
     * and marked (review D finding 3, ruling E111).
     */
    @Test
    void theAsioDefaultDeviceShowsNotifiesAndMarksTheResolvedDriversUnlock() throws Exception {
        clock.reading = Optional.of(new ClockReading(ASIO_DEFAULT, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(new ClockLockStatus(ASIO_DRIVER, WORD_CLOCK.id(), 1, ClockLockStatus.Lock.CHECKING,
                0L));
        ClockStatusIndicator badge = badge();
        opened(badge);

        statusNow(badge, new ClockLockStatus(ASIO_DRIVER, WORD_CLOCK.id(), 1, ClockLockStatus.Lock.UNLOCKED, 7L));

        assertThat(view(badge).state()).isEqualTo(State.UNLOCKED);
        assertThat(notices).containsExactly("External clock lost on Interface A ASIO (Word Clock)."
                + " Check the clock cable and the clock master.");
        assertThat(marks.marks).containsExactly("unlocked@7");
    }

    /** Ruling E111: a device the driver side names only by the placeholder is "the audio device". */
    @Test
    void aClockNoticeNeverNamesThePlaceholder() throws Exception {
        clock.reading = Optional.of(new ClockReading(ASIO_DEFAULT, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(new ClockLockStatus(ASIO_DEFAULT, WORD_CLOCK.id(), 1, ClockLockStatus.Lock.CHECKING,
                0L));
        ClockStatusIndicator badge = badge();
        opened(badge);

        statusNow(badge, new ClockLockStatus(ASIO_DEFAULT, WORD_CLOCK.id(), 1, ClockLockStatus.Lock.UNLOCKED, 7L));
        statusNow(badge, new ClockLockStatus(ASIO_DEFAULT, WORD_CLOCK.id(), 1, ClockLockStatus.Lock.LOCKED, 9L));

        assertThat(notices).containsExactly(
                "External clock lost on the audio device (Word Clock). Check the clock cable and the clock master.",
                "External clock locked again on the audio device (Word Clock).");
    }

    /**
     * A source chosen while the stream is open: the status moves to the new
     * source under a new serial, so the label on show is stale — the badge
     * checks, reads the label again, shows the new source and follows its
     * status (review D finding 1).
     */
    @Test
    void aSourceChosenWhileTheStreamIsOpenIsReadAgainAndFollowsItsStatus() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));

        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, SPDIF),
                48_000));
        clock.status = Optional.of(status(SPDIF.id(), 2, ClockLockStatus.Lock.CHECKING, 0L));
        runOnFx(badge::pulse);
        View stale = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        View reRead = view(badge);
        statusNow(badge, status(SPDIF.id(), 2, ClockLockStatus.Lock.LOCKED, 8L));

        assertThat(stale.state()).as("a status of another source: the label is stale").isEqualTo(State.CHECKING);
        assertThat(reRead.text()).as("the new source is read again").isEqualTo("SPDIF 48k");
        assertThat(reRead.state()).as("checking on the new source (ruling E82)").isEqualTo(State.CHECKING);
        assertThat(view(badge).state()).isEqualTo(State.QUIET);
        assertThat(notices).as("a new source's first lock is no relock").isEmpty();
    }

    /**
     * Ruling E100: an external source whose lock nothing measures — no
     * status, or a status that says it cannot be measured — gets the dim
     * treatment with the quiet label and its own words, never "Checking
     * lock."; closed, E81's texts apply.
     */
    @Test
    void anExternalSourceWhoseLockCannotBeMeasuredIsDimAndSaysSo() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.empty();
        opened(badge);
        View noStatus = view(badge);
        statusNow(badge, status(WORD_CLOCK.id(), 2, ClockLockStatus.Lock.NOT_MEASURABLE, 0L));
        View notMeasurable = view(badge);
        clock.closeStream();
        look(badge);
        View closed = view(badge);

        for (View shown : List.of(noStatus, notMeasurable)) {
            assertThat(shown.state()).isEqualTo(State.NOT_MEASURABLE);
            assertThat(shown.text()).isEqualTo("W/C 48k");
            assertThat(shown.styleClasses()).contains(ClockStatusIndicator.DIM_CLASS)
                    .doesNotContain(ClockStatusIndicator.LOCKED_CLASS, ClockStatusIndicator.UNLOCKED_CLASS);
            assertThat(shown.tooltip()).isEqualTo("Clock source. Lock cannot be checked on this device, so the badge"
                    + " will not warn if the external clock is lost.");
            assertThat(shown.spoken()).isEqualTo("Clock: Word Clock, 48 kilohertz. Lock not checked on this device.");
        }
        assertThat(closed.state()).isEqualTo(State.CLOSED);
        assertThat(closed.spoken())
                .isEqualTo("Clock: Word Clock, 48 kilohertz. Lock not checked while the audio stream is closed.");
        assertThat(notices).isEmpty();
    }

    /** Ruling E61: with no source on show, an unlock is never notified. */
    @Test
    void anUnlockWithNoSourceOnShowIsNeverNotified() throws Exception {
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 48_000));
        clock.status = Optional.of(unlocked(1, 7L));
        ClockStatusIndicator badge = badge();

        opened(badge);
        look(badge);

        assertThat(view(badge).state()).isEqualTo(State.HIDDEN);
        assertThat(notices).isEmpty();
        assertThat(marks.marks).isEmpty();
    }

    /**
     * Ruling E119 (a) and (d), the case of the confirming review: a quick
     * Play then Stop before the new stream's read answers. The label on show
     * stays on show throughout — starting texts while the stream is open,
     * the closed texts after the Stop — and the read that lands after the
     * Stop leaves the closed view.
     */
    @Test
    void aQuickPlayThenStopBeforeTheLabelReadLandsNeverHidesTheBadge() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.closeStream();
        look(badge);
        assertThat(view(badge).state()).as("premise: closed on purpose").isEqualTo(State.CLOSED);

        clock.status = Optional.of(locked(2, 9L));
        clock.openStream();
        runOnFx(badge::pulse);
        View playing = view(badge);
        clock.closeStream();
        runOnFx(badge::pulse);
        View stoppedBeforeTheRead = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        runOnFx(badge::pulse);
        View afterTheRead = view(badge);

        assertThat(playing.state()).as("the label on show stays while the stream starts").isEqualTo(State.STARTING);
        assertStarting(playing, "W/C 48k", "Word Clock", "48");
        assertClosed(stoppedBeforeTheRead, "W/C 48k", "Word Clock", "48");
        assertClosed(afterTheRead, "W/C 48k", "Word Clock", "48");
        assertThat(notices).as("nothing measured on the new stream").isEmpty();
        assertThat(marks.marks).isEmpty();
    }

    /** Ruling E119 (a): with nothing on show at the open, the first stream shows nothing until its read answers. */
    @Test
    void theFirstStreamOfASessionIsHiddenUntilItsReadAnswers() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(1));

        clock.openStream();
        runOnFx(badge::pulse);
        View beforeTheRead = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        View afterTheRead = view(badge);

        assertThat(beforeTheRead.state()).isEqualTo(State.HIDDEN);
        assertThat(beforeTheRead.visible()).isFalse();
        assertThat(beforeTheRead.managed()).isFalse();
        assertThat(afterTheRead.state()).as("the answer applies E82: checking").isEqualTo(State.CHECKING);
    }

    /**
     * Ruling E119 (d): the first stream of a session stopped before its read
     * answers; the read it asked for lands after the Stop, with nothing
     * changed in Settings since, and shows the closed view with its label.
     */
    @Test
    void theFirstStreamStoppedBeforeItsReadAnswersEndsInTheClosedView() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL), 48_000));

        clock.openStream();
        runOnFx(badge::pulse);
        clock.closeStream();
        runOnFx(badge::pulse);
        View stoppedBeforeTheRead = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        runOnFx(badge::pulse);
        View afterTheRead = view(badge);

        assertThat(stoppedBeforeTheRead.state()).as("nothing was on show").isEqualTo(State.HIDDEN);
        assertClosed(afterTheRead, "INT 48k", "Internal", "48");
    }

    /**
     * Ruling E81 still holds for a read Settings asks for while the stream is
     * closed: after a quick Play and Stop, a source chosen in Settings before
     * the stream's own read answered hides the badge, and the stream's read,
     * now void, does not bring the old label back.
     */
    @Test
    void aSourceChosenInSettingsAfterAQuickPlayAndStopStillHides() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        clock.closeStream();
        look(badge);
        clock.openStream();
        runOnFx(badge::pulse);
        clock.closeStream();
        runOnFx(badge::pulse);
        assertThat(view(badge).state()).as("premise: the closed view after the quick Stop").isEqualTo(State.CLOSED);

        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL, WORD_CLOCK_IDLE), 48_000));
        badge.refresh();
        reads.runAll();
        runOnFx(() -> { });
        runOnFx(badge::pulse);

        assertThat(view(badge).state()).as("the source changed while closed (ruling E81)").isEqualTo(State.HIDDEN);
        assertThat(view(badge).visible()).isFalse();
    }

    /**
     * A label read that dies with an Error, not an exception, settles its
     * read all the same: the badge keeps what it shows and a later pulse
     * that needs the label asks for it again.
     */
    @Test
    void aLabelReadThatDiesWithAnErrorDoesNotStrandTheBadge() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        clock.error = new NoClassDefFoundError("a driver binding class failed to load");
        refresh(badge);
        View afterTheError = view(badge);
        int readsAfterTheError = reads.requested.get();

        clock.error = null;
        nanos.addAndGet(SECOND);
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, SPDIF), 48_000));
        clock.status = Optional.of(status(SPDIF.id(), 2, ClockLockStatus.Lock.CHECKING, 0L));
        look(badge);

        assertThat(reads.escaped).as("the Error is logged, not rethrown").isEmpty();
        assertThat(afterTheError.state()).as("a failed read decides nothing (ruling E80)").isEqualTo(State.CHECKING);
        assertThat(reads.requested.get()).as("the next label read is asked for").isEqualTo(readsAfterTheError + 1);
        assertThat(view(badge).text()).isEqualTo("SPDIF 48k");
    }

    /** A {@link VirtualMachineError} in the label read is rethrown on the read's thread after the read is settled. */
    @Test
    void aVirtualMachineErrorInTheLabelReadIsRethrownAfterItIsSettled() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        OutOfMemoryError fatal = new OutOfMemoryError("the read ran out of memory");
        clock.error = fatal;
        refresh(badge);
        int readsAfterTheError = reads.requested.get();

        clock.error = null;
        nanos.addAndGet(SECOND);
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, SPDIF), 48_000));
        clock.status = Optional.of(status(SPDIF.id(), 2, ClockLockStatus.Lock.CHECKING, 0L));
        look(badge);

        assertThat(reads.escaped).as("rethrown on the read's thread").containsExactly(fatal);
        assertThat(reads.requested.get()).as("settled first: the next label read is asked for")
                .isEqualTo(readsAfterTheError + 1);
        assertThat(view(badge).text()).isEqualTo("SPDIF 48k");
    }

    @Test
    void aDisposedBadgeDerivesNothing() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        runOnFx(badge::dispose);

        statusNow(badge, unlocked(1, 7L));

        assertThat(view(badge).state()).isEqualTo(State.CHECKING);
        assertThat(notices).isEmpty();
    }

    /** Reduce Motion: the unlocked treatment is steady and the flash's timeline does not run. */
    @Test
    void underReduceMotionTheUnlockedTreatmentIsSteady() throws Exception {
        MotionManager motion = new MotionManager(new MemoryPreferences(), Optional::empty);
        motion.setReduceMotion(false);
        MotionManager.setDefaultForTest(motion);
        try {
            ClockStatusIndicator badge = externalBadge();
            statusNow(badge, unlocked(1, 7L));
            boolean flashingWithMotion = onFx(badge::isFlashing);

            runOnFx(() -> motion.setReduceMotion(true));
            boolean flashingUnderReduceMotion = onFx(badge::isFlashing);
            boolean flashClass = onFx(() -> badge.getPseudoClassStates().contains(ClockStatusIndicator.FLASH));
            runOnFx(badge::dispose);

            assertThat(flashingWithMotion).as("premise: the flash runs while motion is allowed").isTrue();
            assertThat(flashingUnderReduceMotion).as("no timeline under Reduce Motion").isFalse();
            assertThat(flashClass).as("and no flash state left on").isFalse();
            assertThat(view(badge).text()).as("the words carry the state").isEqualTo("⚠ W/C 48k UNLOCKED");
        } finally {
            MotionManager.setDefaultForTest(null);
        }
    }

    /**
     * Ruling E232: the unlocked flash is a two-tone ring stepped on over the
     * badge's outer 2 px, so the words and the red under them are the same in
     * both phases and keep their contrast in every theme. Opacity, an effect
     * or a size change would dim or move the words exactly when they matter.
     */
    @Test
    void theUnlockedTextAndItsFillStaySteadyWhileTheBadgeFlashesInEveryTheme() throws Exception {
        List<Phases> themes = onAThemedBar((badge, themed) -> {
            statusNow(badge, unlocked(1, 7L));
            assertThat(view(badge).state()).as("premise: unlocked").isEqualTo(State.UNLOCKED);
            assertThat(onFx(badge::isFlashing)).as("premise: no timeline steps the flash behind the test")
                    .isFalse();
            return onFx(() -> {
                List<Phases> phases = new ArrayList<>();
                for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
                    themed.apply(theme, UiScale.P100);
                    badge.pseudoClassStateChanged(ClockStatusIndicator.FLASH, false);
                    Look rest = look(badge, themed, theme);
                    badge.pseudoClassStateChanged(ClockStatusIndicator.FLASH, true);
                    Look flash = look(badge, themed, theme);
                    badge.pseudoClassStateChanged(ClockStatusIndicator.FLASH, false);
                    phases.add(new Phases(themed.palette(), rest, flash));
                }
                return phases;
            });
        });

        assertThat(themes.stream().map(phases -> phases.palette().token("-danger")).distinct())
                .as("non-vacuity: each theme resolves its own -danger").hasSize(3);
        for (Phases phases : themes) {
            assertTheFlashKeepsTheWordsSteady(phases);
        }
    }

    /**
     * Rulings E233 and E234: in every theme the words of each other shown
     * state read at 4.5:1 or more against what is under them, the fill or,
     * where there is none, both stops of the transport bar's gradient.
     */
    @Test
    void eachShownStatesTextIsReadableAgainstWhatIsUnderItInEveryTheme() throws Exception {
        Shown shown = eachCheckedAndUncheckedStateInEveryTheme();

        assertThat(shown.looks()).as("non-vacuity: five states in three themes").hasSize(15);
        for (Look look : shown.looks()) {
            Color words = colour(look.text());
            Optional<Color> fill = opaqueLastFill(look);
            List<Color> under = fill.isPresent() ? List.of(fill.get()) : shown.palettes().get(look.theme()).barStops();
            for (Color surface : under) {
                assertThat(contrast(words, surface))
                        .as("%s in %s: the words against %s", look.state(), look.theme(),
                                fill.isPresent() ? "the fill" : "the transport bar")
                        .isGreaterThanOrEqualTo(ThemeContrastValidator.AA_NORMAL);
            }
        }
    }

    /**
     * Ruling E234: while the lock is not checked the badge is an outlined chip
     * with no fill, -text words in a -text-mute outline, so it never reads as
     * the filled locked chip; and every state is the locked chip's size, so a
     * change of state never moves the transport status beside it.
     */
    @Test
    void theUncheckedStatesAreAnOutlinedChipTheSizeOfTheFilledLockedChip() throws Exception {
        Shown shown = eachCheckedAndUncheckedStateInEveryTheme();

        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            Palette palette = shown.palettes().get(theme);
            Look quiet = shown.in(State.QUIET, theme);
            assertThat(opaqueLastFill(quiet)).as("%s: the locked chip is filled with -surface-3", theme)
                    .contains(palette.token("-surface-3"));
            assertThat(strokePaints(quiet.border())).as("%s: the locked chip shows no outline", theme)
                    .allSatisfy(paint -> assertThat(colour(paint).getOpacity()).isZero());
            assertThat(quiet.text()).as("%s: the locked chip's words are -text", theme)
                    .isEqualTo(palette.token("-text"));
            for (State state : UNCHECKED) {
                assertAnOutlinedChipTheSizeOf(shown.in(state, theme), quiet, palette);
            }
        }
    }

    /**
     * Ruling E233: at every UI scale the flash's ring, unscaled 2 px, lies
     * inside the 1 px border plus the scaled padding, so it never reaches the
     * words. The words' layout box is measured, not their glyphs' ink.
     */
    @Test
    void theFlashRingStaysInsideTheBorderAndPaddingAtEveryUiScale() throws Exception {
        List<Ring> rings = onAThemedBar((badge, themed) -> {
            statusNow(badge, unlocked(1, 7L));
            assertThat(view(badge).state()).as("premise: unlocked").isEqualTo(State.UNLOCKED);
            List<Ring> measured = new ArrayList<>();
            for (UiScale scale : UiScale.values()) {
                measured.add(onFx(() -> {
                    themed.apply(ThemeManager.Theme.ONYX_REFINED, scale);
                    badge.pseudoClassStateChanged(ClockStatusIndicator.FLASH, true);
                    themed.root().applyCss();
                    themed.root().layout();
                    List<BackgroundFill> fills = badge.getBackground().getFills();
                    Node words = Objects.requireNonNull(badge.lookup(".text"), "the label's text node");
                    return new Ring(scale, fills.size(), badge.getInsets(), fills.getLast().getInsets(),
                            words.getBoundsInParent(), badge.getHeight());
                }));
            }
            return measured;
        });

        assertThat(rings).as("non-vacuity: every UI scale").hasSize(UiScale.values().length);
        for (Ring ring : rings) {
            String step = ring.scale().label();
            double padding = 2 * ring.scale().factor();
            double sides = 8 * ring.scale().factor();
            assertThat(ring.layers()).as("%s: premise: the ring is on", step).isEqualTo(3);
            assertThat(List.of(ring.insets().getTop(), ring.insets().getRight(), ring.insets().getBottom(),
                    ring.insets().getLeft()))
                    .as("%s: the 1 px border plus the scaled padding", step)
                    .usingElementComparator((a, b) -> Math.abs(a - b) <= 1e-6 ? 0 : Double.compare(a, b))
                    .containsExactly(1 + padding, 1 + sides, 1 + padding, 1 + sides);
            assertThat(ring.fill().getTop()).as("%s: the ring within the border and padding, top", step)
                    .isLessThanOrEqualTo(ring.insets().getTop() + 1e-6);
            assertThat(ring.fill().getRight()).as("%s: right", step)
                    .isLessThanOrEqualTo(ring.insets().getRight() + 1e-6);
            assertThat(ring.fill().getBottom()).as("%s: bottom", step)
                    .isLessThanOrEqualTo(ring.insets().getBottom() + 1e-6);
            assertThat(ring.fill().getLeft()).as("%s: left", step)
                    .isLessThanOrEqualTo(ring.insets().getLeft() + 1e-6);
            assertThat(ring.words().getMinY()).as("%s: the words start below the ring", step)
                    .isGreaterThanOrEqualTo(ring.fill().getTop() - 1e-6);
            assertThat(ring.words().getMaxY()).as("%s: and end above it", step)
                    .isLessThanOrEqualTo(ring.height() - ring.fill().getBottom() + 1e-6);
        }
    }

    /**
     * Ruling E232: the flash is a step, not a pulse: each look is complete in
     * the CSS pass that applies it, and no CSS transition runs on the badge.
     * The badge is in a shown window, where a transition would run.
     */
    @Test
    void theFlashStepsBetweenItsTwoLooksWithNoTransition() throws Exception {
        List<TransitionEvent> transitions = new CopyOnWriteArrayList<>();
        List<Paint> ring = new ArrayList<>();
        List<Paint> rest = new ArrayList<>();
        List<List<Paint>> steps = onAThemedBar((badge, themed) -> {
            statusNow(badge, unlocked(1, 7L));
            assertThat(view(badge).state()).as("premise: unlocked").isEqualTo(State.UNLOCKED);
            return onFx(() -> {
                assertThat(themed.stage().isShowing()).as("premise: the window is showing").isTrue();
                Palette palette = themed.palette();
                ring.addAll(List.of(palette.token("-text-hi"), palette.token("-text-on-accent"),
                        palette.token("-danger")));
                rest.add(palette.token("-danger"));
                badge.addEventHandler(TransitionEvent.ANY, transitions::add);
                List<List<Paint>> seen = new ArrayList<>();
                for (boolean on : List.of(true, false, true, false)) {
                    badge.pseudoClassStateChanged(ClockStatusIndicator.FLASH, on);
                    themed.root().applyCss();
                    seen.add(badge.getBackground().getFills().stream().map(BackgroundFill::getFill).toList());
                }
                return seen;
            });
        });

        assertThat(transitions).as("no CSS transition on the badge").isEmpty();
        assertThat(steps).as("each look complete in the pass that applies it: on, off, on, off")
                .containsExactly(ring, rest, ring, rest);
    }

    /**
     * Ruling E232: the flash's timeline steps a pseudo-class; it animates no
     * property of the badge and dims nothing. Read with comments and string
     * literals stripped.
     */
    @Test
    void theFlashTimelineAnimatesNoPropertyOfTheBadge() throws Exception {
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/ClockStatusIndicator.java");
        String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(
                Files.readString(file, StandardCharsets.UTF_8))).replaceAll("\\s+", " ");

        assertThat(code.length()).as("non-vacuity: the badge was read").isGreaterThan(10_000);
        assertThat(Stream.of("KeyValue", "Interpolator", "Transition", "setOpacity", "opacityProperty")
                .filter(code::contains)).as("no interpolated value, no transition and no opacity").isEmpty();
        assertThat(occurrences(code, "new KeyFrame(")).as("one key frame: the step").isEqualTo(1);
    }

    /**
     * The real wiring behind the seam the look tests use: while motion is
     * allowed, the unlocked badge's own timeline adds and then removes
     * {@link ClockStatusIndicator#FLASH}, the pseudo-class the stylesheet's
     * ring is keyed on. Each change is recorded on the FX thread as it happens.
     */
    @Test
    void theRunningFlashTogglesThePseudoClassTheStylesheetKeysOn() throws Exception {
        MotionManager motion = new MotionManager(new MemoryPreferences(), Optional::empty);
        motion.setReduceMotion(false);
        MotionManager.setDefaultForTest(motion);
        List<Runnable> cleanUp = new ArrayList<>();
        try {
            ClockStatusIndicator badge = externalBadge();
            cleanUp.add(badge::dispose);
            List<Boolean> changes = new CopyOnWriteArrayList<>();
            CountDownLatch twoChanges = new CountDownLatch(2);
            runOnFx(() -> badge.getPseudoClassStates().addListener((SetChangeListener<PseudoClass>) change -> {
                if (ClockStatusIndicator.FLASH.equals(change.getElementAdded())) {
                    changes.add(true);
                    twoChanges.countDown();
                }
                if (ClockStatusIndicator.FLASH.equals(change.getElementRemoved())) {
                    changes.add(false);
                    twoChanges.countDown();
                }
            }));

            statusNow(badge, unlocked(1, 7L));
            assertThat(view(badge).state()).as("premise: unlocked").isEqualTo(State.UNLOCKED);

            assertThat(twoChanges.await(5, TimeUnit.SECONDS))
                    .as("two steps of the flash within 5 s; seen (true when it came on): %s", changes).isTrue();
            assertThat(changes).as("the timeline adds the flash, then removes it").startsWith(true, false);
        } finally {
            try {
                runOnFx(() -> cleanUp.forEach(Runnable::run));
            } finally {
                MotionManager.setDefaultForTest(null);
            }
        }
    }

    /**
     * Ruling E232 over the running timeline: each step it takes leaves the
     * badge exactly as the look tests' toggle of {@link ClockStatusIndicator#FLASH}
     * by hand leaves it in that phase, its padding and border included, so the
     * timeline changes nothing else on the badge. Each step is read once the
     * key frame's handler has returned.
     */
    @Test
    void eachStepOfTheRunningFlashIsTheLookOfItsPhaseToggledByHand() throws Exception {
        MotionManager motion = new MotionManager(new MemoryPreferences(), Optional::empty);
        motion.setReduceMotion(true);
        MotionManager.setDefaultForTest(motion);
        List<Runnable> cleanUp = new ArrayList<>();
        try {
            ClockStatusIndicator badge = externalBadge();
            cleanUp.add(badge::dispose);
            ThemedBar themed = onFx(() -> themedBar(badge));
            cleanUp.add(themed.stage()::hide);
            statusNow(badge, unlocked(1, 7L));
            assertThat(onFx(badge::isFlashing)).as("premise: no timeline while motion is reduced").isFalse();
            Map<Boolean, FlashStep> byHand = onFx(() -> {
                badge.pseudoClassStateChanged(ClockStatusIndicator.FLASH, false);
                FlashStep rest = flashStep(badge, themed);
                badge.pseudoClassStateChanged(ClockStatusIndicator.FLASH, true);
                FlashStep flash = flashStep(badge, themed);
                badge.pseudoClassStateChanged(ClockStatusIndicator.FLASH, false);
                return Map.of(false, rest, true, flash);
            });
            List<FlashStep> steps = new CopyOnWriteArrayList<>();
            runOnFx(() -> badge.getPseudoClassStates().addListener((SetChangeListener<PseudoClass>) change -> {
                if (ClockStatusIndicator.FLASH.equals(change.getElementAdded())
                        || ClockStatusIndicator.FLASH.equals(change.getElementRemoved())) {
                    // Read once the key frame's handler has returned, so whatever else it set is seen too.
                    Platform.runLater(() -> steps.add(flashStep(badge, themed)));
                }
            }));
            runOnFx(() -> motion.setReduceMotion(false));
            DeviceLossRecordingFixture.await(() -> steps.stream().anyMatch(FlashStep::flashing)
                    && steps.stream().anyMatch(step -> !step.flashing()), "both phases of the running flash");

            assertThat(byHand.get(true).fills()).as("non-vacuity: the two phases differ")
                    .isNotEqualTo(byHand.get(false).fills());
            assertThat(List.copyOf(steps)).as("each step of the running flash is its phase's look toggled by hand")
                    .allSatisfy(step -> assertThat(step).isEqualTo(byHand.get(step.flashing())));
        } finally {
            try {
                runOnFx(() -> cleanUp.forEach(Runnable::run));
            } finally {
                MotionManager.setDefaultForTest(null);
            }
        }
    }

    /** The badge's one decision, row by row (rulings E32, E81 to E85, E100, E107, E119, E135 and E142). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("theDecisionTable")
    void eachRowOfTheDecisionTableShowsItsState(String row, Facts facts, State expected) {
        assertThat(ClockStatusIndicator.decide(facts)).as(row).isEqualTo(expected);
    }

    static Stream<Arguments> theDecisionTable() {
        ClockLabel internal = new ClockLabel(INTERFACE, INTERNAL, 48_000);
        ClockLabel external = new ClockLabel(INTERFACE, WORD_CLOCK, 48_000);
        return Stream.of(
                row("1: lost, open and measured", true, true, external, OwnRead.LABELLED, locked(1, 5L),
                        State.HIDDEN),
                row("1: lost, closed, a label", true, false, external, OwnRead.UNANSWERED, null, State.HIDDEN),
                row("1: lost, open, unanswered", true, true, internal, OwnRead.UNANSWERED, unlocked(1, 7L),
                        State.HIDDEN),
                row("2: closed, no label, unanswered", false, false, null, OwnRead.UNANSWERED, null, State.HIDDEN),
                row("2: closed, no label, no sources", false, false, null, OwnRead.NO_SOURCES, checking(1),
                        State.HIDDEN),
                row("2: closed, no label, labelled", false, false, null, OwnRead.LABELLED, locked(1, 5L),
                        State.HIDDEN),
                row("3: closed, an external label, unanswered", false, false, external, OwnRead.UNANSWERED, null,
                        State.CLOSED),
                row("3: closed, an internal label, labelled", false, false, internal, OwnRead.LABELLED,
                        unlocked(1, 7L), State.CLOSED),
                row("3: closed, a label, no sources", false, false, external, OwnRead.NO_SOURCES, checking(2),
                        State.CLOSED),
                row("4: open, no label, unanswered", false, true, null, OwnRead.UNANSWERED, checking(1),
                        State.HIDDEN),
                row("4: open, no label, no sources", false, true, null, OwnRead.NO_SOURCES, null, State.HIDDEN),
                row("5: open, a label, unanswered", false, true, external, OwnRead.UNANSWERED, unlocked(2, 7L),
                        State.STARTING),
                row("6: open, labelled, the status names another source", false, true, external,
                        OwnRead.LABELLED, status(SPDIF.id(), 2, ClockLockStatus.Lock.LOCKED, 5L), State.CHECKING),
                row("6: an internal label, the status names word clock", false, true, internal, OwnRead.LABELLED,
                        unlocked(2, 7L), State.CHECKING),
                row("7: internal, no status", false, true, internal, OwnRead.LABELLED, null, State.QUIET),
                row("7: internal, a status naming no source", false, true, internal, OwnRead.LABELLED,
                        status(-1, 2, ClockLockStatus.Lock.CHECKING, 0L), State.QUIET),
                row("8: external, no status", false, true, external, OwnRead.LABELLED, null, State.NOT_MEASURABLE),
                row("8: external, not measurable", false, true, external, OwnRead.LABELLED,
                        status(WORD_CLOCK.id(), 2, ClockLockStatus.Lock.NOT_MEASURABLE, 0L), State.NOT_MEASURABLE),
                row("9: external, checking", false, true, external, OwnRead.LABELLED, checking(2), State.CHECKING),
                row("10: external, locked", false, true, external, OwnRead.LABELLED, locked(2, 5L), State.QUIET),
                row("11: external, unlocked", false, true, external, OwnRead.LABELLED, unlocked(2, 7L),
                        State.UNLOCKED));
    }

    /** Over every combination of the facts, the invariants every row keeps. */
    @Test
    void theDecisionKeepsItsInvariantsOverEveryCombinationOfTheFacts() {
        List<ClockLabel> labels = Arrays.asList(null,
                new ClockLabel(INTERFACE, INTERNAL, 48_000), new ClockLabel(INTERFACE, WORD_CLOCK, 48_000));
        List<ClockLockStatus> statuses = Arrays.asList(null,
                status(-1, 1, ClockLockStatus.Lock.CHECKING, 0L), checking(1), locked(1, 5L), unlocked(1, 7L),
                status(WORD_CLOCK.id(), 1, ClockLockStatus.Lock.NOT_MEASURABLE, 0L),
                status(INTERNAL.id(), 1, ClockLockStatus.Lock.LOCKED, 5L),
                status(SPDIF.id(), 1, ClockLockStatus.Lock.CHECKING, 0L));
        int combinations = 0;
        for (boolean lost : List.of(true, false)) {
            for (boolean open : List.of(true, false)) {
                for (ClockLabel label : labels) {
                    for (OwnRead ownRead : OwnRead.values()) {
                        for (ClockLockStatus status : statuses) {
                            Facts facts = new Facts(lost, open, label, ownRead, status);
                            State state = ClockStatusIndicator.decide(facts);
                            combinations++;
                            if (lost) {
                                assertThat(state).as("hidden whenever lost (E84): %s", facts).isEqualTo(State.HIDDEN);
                            }
                            if (label == null) {
                                assertThat(state).as("hidden whenever no label: %s", facts).isEqualTo(State.HIDDEN);
                            }
                            if (open) {
                                assertThat(state).as("never CLOSED on an open stream (E85): %s", facts)
                                        .isNotEqualTo(State.CLOSED);
                            } else {
                                assertThat(state).as("closed: CLOSED or HIDDEN only: %s", facts)
                                        .isIn(State.CLOSED, State.HIDDEN);
                            }
                            if (state == State.STARTING) {
                                assertThat(ownRead).as("STARTING only before an answer (E135): %s", facts)
                                        .isEqualTo(OwnRead.UNANSWERED);
                            }
                        }
                    }
                }
            }
        }
        assertThat(combinations).as("non-vacuity").isEqualTo(2 * 2 * 3 * 3 * 8);
    }

    /**
     * The state on show is never an input: {@code state.get()} occurs only
     * where the state is written and rendered, and one call site shows it.
     * Read with comments and string literals stripped.
     */
    @Test
    void theStateOnShowIsDecidedInOnePlaceAndNeverRead() throws Exception {
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/ClockStatusIndicator.java");
        String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(
                Files.readString(file, StandardCharsets.UTF_8))).replaceAll("\\s+", " ");
        int inTheWriters = occurrences(body(code, "private void show(State next)"), "state.get()")
                + occurrences(body(code, "private void showState()"), "state.get()")
                + occurrences(body(code, "ReadOnlyObjectProperty<State> stateProperty()"), "state.get()");

        assertThat(code.length()).as("non-vacuity: the badge was read").isGreaterThan(10_000);
        assertThat(inTheWriters).as("non-vacuity: show and showState read what they render").isPositive();
        assertThat(occurrences(code, "state.get()")).as("state.get() only in show, showState and stateProperty")
                .isEqualTo(inTheWriters);
        assertThat(Pattern.compile("\\bshow\\(").matcher(code).results().count())
                .as("show( declared once and called once").isEqualTo(2);
        assertThat(body(code, "private void showTheDecision(Facts facts)"))
                .as("the one call shows the decision").contains("State next = decide(facts);").contains("show(next);");
    }

    /**
     * Ruling E135: a label on show, a new stream on the same device and rate
     * whose read answers that the device has no clock source, and no lock
     * status: the starting view lasts until that answer, then the badge
     * hides, reads no more, and shows nothing after a Stop; the next stream
     * whose read names a source is hidden until that read answers.
     */
    @Test
    void aNewStreamWhoseDeviceAnswersNoClockSourcesHidesTheCarriedLabelForGood() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 48_000));
        clock.status = Optional.empty();

        clock.openStream();
        runOnFx(badge::pulse);
        View beforeTheAnswer = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        View atTheAnswer = view(badge);
        int readsAtTheAnswer = reads.requested.get();
        for (int second = 0; second < 5; second++) {
            nanos.addAndGet(SECOND);
            look(badge);
        }
        View fiveSecondsLater = view(badge);
        int readsAfter = reads.requested.get() - readsAtTheAnswer;
        clock.closeStream();
        look(badge);
        View afterTheStop = view(badge);
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(3));
        clock.openStream();
        runOnFx(badge::pulse);
        View nextStreamBeforeItsRead = view(badge);
        reads.runAll();
        runOnFx(() -> { });

        assertThat(beforeTheAnswer.state()).as("the starting view until the first answer (E119)")
                .isEqualTo(State.STARTING);
        assertThat(atTheAnswer.state()).as("E135: hidden at the answer, never STARTING after it")
                .isEqualTo(State.HIDDEN);
        assertThat(atTheAnswer.visible()).isFalse();
        assertThat(fiveSecondsLater.state()).as("E135: still hidden").isEqualTo(State.HIDDEN);
        assertThat(readsAfter).as("E32: no further read while nothing says the device has a source").isZero();
        assertThat(afterTheStop.state()).as("E135: never the closed view after that answer").isEqualTo(State.HIDDEN);
        assertThat(nextStreamBeforeItsRead.state()).as("nothing carried into the next stream (E119 (a))")
                .isEqualTo(State.HIDDEN);
        assertThat(view(badge).state()).as("its answer applies E82").isEqualTo(State.CHECKING);
        assertThat(view(badge).text()).isEqualTo("W/C 48k");
        assertThat(notices).as("no notice from the hidden stream").isEmpty();
    }

    /** Ruling E135: a status that names no source (an id below zero) is no source either. */
    @Test
    void aNoSourcesAnswerWithAStatusNamingNoSourceHidesTheCarriedLabel() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 48_000));
        clock.status = Optional.of(status(-1, 2, ClockLockStatus.Lock.CHECKING, 0L));

        opened(badge);
        int readsAtTheAnswer = reads.requested.get();
        for (int second = 0; second < 5; second++) {
            nanos.addAndGet(SECOND);
            look(badge);
        }

        assertThat(view(badge).state()).as("E135: hidden after the answer").isEqualTo(State.HIDDEN);
        assertThat(reads.requested.get()).as("E32: no further read").isEqualTo(readsAtTheAnswer);
    }

    /**
     * Ruling E141: an empty list while the lock status names a source is
     * contradicted: the starting view stays, and the list is read again once
     * a second.
     */
    @Test
    void aNoSourcesAnswerTheStatusContradictsKeepsTheStartingViewAndIsReadEachSecond() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 48_000));
        clock.status = Optional.of(checking(2));

        opened(badge);
        View atTheAnswer = view(badge);
        int readsAtTheAnswer = reads.requested.get();
        nanos.addAndGet(SECOND / 2);
        look(badge);
        int halfASecondLater = reads.requested.get();
        nanos.addAndGet(SECOND / 2);
        look(badge);
        int aSecondLater = reads.requested.get();
        nanos.addAndGet(SECOND);
        look(badge);
        int twoSecondsLater = reads.requested.get();

        assertThat(atTheAnswer.state()).as("E141: the list is contradicted, not E32's answer")
                .isEqualTo(State.STARTING);
        assertStarting(view(badge), "W/C 48k", "Word Clock", "48");
        assertThat(halfASecondLater).as("not within the second").isEqualTo(readsAtTheAnswer);
        assertThat(aSecondLater).as("read again after one second (E141)").isEqualTo(readsAtTheAnswer + 1);
        assertThat(twoSecondsLater).as("and each second after").isEqualTo(readsAtTheAnswer + 2);
    }

    /** Ruling E136: a quick Play and Stop whose read then answers "no clock sources" hides the badge. */
    @Test
    void aQuickPlayThenStopWhoseReadAnswersNoClockSourcesHidesTheBadge() throws Exception {
        ClockStatusIndicator badge = quicklyPlayedAndStopped();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 48_000));

        reads.runAll();
        runOnFx(() -> { });
        View atTheAnswer = view(badge);
        look(badge);

        assertThat(atTheAnswer.state()).as("E136: never the older label after this stream's answer")
                .isEqualTo(State.HIDDEN);
        assertThat(view(badge).state()).as("and at the next look").isEqualTo(State.HIDDEN);
    }

    /** Ruling E136 and E119 (d): a quick Play and Stop whose read then fails keeps the closed view. */
    @Test
    void aQuickPlayThenStopWhoseReadFailsKeepsTheClosedView() throws Exception {
        ClockStatusIndicator badge = quicklyPlayedAndStopped();
        clock.failure = new IllegalStateException("the driver's panel is open");

        reads.runAll();
        runOnFx(() -> { });
        look(badge);

        assertClosed(view(badge), "W/C 48k", "Word Clock", "48");
    }

    /**
     * Ruling E136: the stream opened device B (a fallback), was stopped
     * before its read answered, and that read is given device B; an answer
     * about device A, the requested one, is not that stream's and hides the
     * badge.
     */
    @Test
    void aStreamsReadIsAboutTheDeviceItOpenedAndAnAnswerAboutAnotherHidesTheBadge() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL), 48_000));

        clock.openStream(OTHER_INTERFACE, 48_000);
        runOnFx(badge::pulse);
        clock.closeStream();
        runOnFx(badge::pulse);
        reads.runAll();
        runOnFx(() -> { });
        runOnFx(badge::pulse);

        assertThat(clock.askedAbout).as("the stream's own read asks about the device the stream opened, after it closed")
                .containsExactly(OTHER_INTERFACE.name());
        assertThat(view(badge).state()).as("E136: never a closed view read from a device the stream did not open")
                .isEqualTo(State.HIDDEN);
    }

    /** Ruling E136: a look while the stream is open asks about the device the stream opened. */
    @Test
    void aLookWhileTheStreamIsOpenAsksAboutTheStreamsDevice() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(OTHER_INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.failure = new IllegalStateException("the driver's panel is open");
        opened(badge);
        clock.failure = null;
        nanos.addAndGet(SECOND);
        look(badge);

        assertThat(clock.askedAbout).as("the open's read and the look's both ask about the stream's device (E136)")
                .containsExactly(OTHER_INTERFACE.name(), OTHER_INTERFACE.name());
        assertThat(view(badge).text()).isEqualTo("W/C 48k");
    }

    /** Ruling E141: nothing carried and a read that throws: hidden, read each second, shown once one answers. */
    @Test
    void nothingCarriedAndAFailingReadStaysHiddenAndIsReadEachSecondUntilOneAnswers() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(1));
        clock.failure = new IllegalStateException("the driver's panel is open");

        opened(badge);
        View afterTheFailure = view(badge);
        int readsAfterTheOpen = reads.requested.get();
        nanos.addAndGet(SECOND);
        look(badge);
        int aSecondLater = reads.requested.get();
        View stillFailing = view(badge);
        clock.failure = null;
        nanos.addAndGet(SECOND);
        look(badge);

        assertThat(afterTheFailure.state()).as("E141: nothing carried, nothing shows").isEqualTo(State.HIDDEN);
        assertThat(aSecondLater).as("read again after a second").isEqualTo(readsAfterTheOpen + 1);
        assertThat(stillFailing.state()).isEqualTo(State.HIDDEN);
        assertThat(view(badge).state()).as("shown once a read answers (E82)").isEqualTo(State.CHECKING);
    }

    /** Ruling E141: a read that hangs is never joined by a second one. */
    @Test
    void aReadThatHangsIsNeverJoinedByASecondOne() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(1));

        clock.openStream();
        for (int second = 0; second < 5; second++) {
            nanos.addAndGet(SECOND);
            runOnFx(badge::pulse);
        }
        int whileItHangs = reads.requested.get();
        reads.runAll();
        runOnFx(() -> { });

        assertThat(whileItHangs).as("one read in flight, never a second").isEqualTo(1);
        assertThat(view(badge).state()).as("the read that settled applies").isEqualTo(State.CHECKING);
    }

    /**
     * Ruling E142: "W/C 48k" read on device A; a stream opens on device B
     * (Settings changed the device during playback): hidden from the open,
     * then B's answer.
     */
    @Test
    void aStreamOnAnotherDeviceCarriesNoLabelAndShowsItsOwnAnswer() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.reading = Optional.of(new ClockReading(OTHER_INTERFACE, List.of(INTERNAL, WORD_CLOCK_IDLE), 48_000));
        clock.status = Optional.of(status(INTERNAL.id(), 2, ClockLockStatus.Lock.NOT_MEASURABLE, 0L));

        clock.openStream();
        runOnFx(badge::pulse);
        View fromTheOpen = view(badge);
        reads.runAll();
        runOnFx(() -> { });

        assertThat(fromTheOpen.state()).as("E142: never device A's label on device B's stream")
                .isEqualTo(State.HIDDEN);
        assertThat(view(badge).state()).isEqualTo(State.QUIET);
        assertThat(view(badge).text()).as("B's own answer").isEqualTo("INT 48k");
    }

    /** Ruling E142: device B answering that it has no clock sources stays hidden. */
    @Test
    void aStreamOnAnotherDeviceWithNoClockSourcesStaysHidden() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.reading = Optional.of(new ClockReading(OTHER_INTERFACE, List.of(), 48_000));
        clock.status = Optional.empty();

        clock.openStream();
        runOnFx(badge::pulse);
        View fromTheOpen = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        look(badge);

        assertThat(fromTheOpen.state()).as("E142: nothing carried onto B").isEqualTo(State.HIDDEN);
        assertThat(view(badge).state()).as("E135: B has no clock sources").isEqualTo(State.HIDDEN);
    }

    /** Ruling E142: the rate changed from 48 to 44.1 kHz on the same device: hidden from the open, then its own label. */
    @Test
    void aStreamAtAnotherRateCarriesNoLabelAndShowsItsOwnRate() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 44_100));
        clock.status = Optional.of(checking(2));

        clock.openStream();
        runOnFx(badge::pulse);
        View fromTheOpen = view(badge);
        reads.runAll();
        runOnFx(() -> { });

        assertThat(fromTheOpen.state()).as("E142: never the 48 kHz label at 44.1 kHz").isEqualTo(State.HIDDEN);
        assertThat(view(badge).text()).isEqualTo("W/C 44.1k");
        assertThat(view(badge).state()).isEqualTo(State.CHECKING);
    }

    /** Ruling E142 with E141: a stream on device B whose first read fails stays hidden and is read each second. */
    @Test
    void aStreamOnAnotherDeviceWhoseFirstReadFailsStaysHiddenAndIsReadEachSecond() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.reading = Optional.of(new ClockReading(OTHER_INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(2));
        clock.failure = new IllegalStateException("the driver's panel is open");

        opened(badge);
        View afterTheFailure = view(badge);
        int readsAfterTheOpen = reads.requested.get();
        nanos.addAndGet(SECOND);
        look(badge);
        nanos.addAndGet(SECOND);
        look(badge);

        assertThat(afterTheFailure.state()).as("E142: nothing carried onto B").isEqualTo(State.HIDDEN);
        assertThat(view(badge).state()).isEqualTo(State.HIDDEN);
        assertThat(reads.requested.get()).as("read each second (E141)").isEqualTo(readsAfterTheOpen + 2);
    }

    /**
     * Ruling E136: a stream that opened and closed between two looks is still
     * noticed at the next look, and its own read, asked about the device it
     * opened, settles the closed view: a current source is that stream's
     * label; no sources hides the badge; a failed read leaves what was there.
     */
    @Test
    void aStreamThatOpenedAndClosedBetweenTwoLooksIsReadAboutItsDeviceAndSettlesTheClosedView() throws Exception {
        ClockStatusIndicator answered = closedExternalBadge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, SPDIF), 48_000));
        clock.openStream();
        clock.closeStream();
        int readsBefore = reads.requested.get();
        look(answered);
        View withASource = view(answered);
        int readsByTheLook = reads.requested.get() - readsBefore;
        DeviceId askedAbout = clock.devicesAsked.getLast();

        ClockStatusIndicator none = closedExternalBadge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 48_000));
        clock.openStream();
        clock.closeStream();
        look(none);
        View withNoSources = view(none);

        ClockStatusIndicator failing = closedExternalBadge();
        clock.failure = new IllegalStateException("the driver's panel is open");
        clock.openStream();
        clock.closeStream();
        look(failing);
        View withAFailedRead = view(failing);

        assertThat(readsByTheLook).as("the look noticed the stream and asked its read once").isEqualTo(1);
        assertThat(askedAbout).as("about the device that stream opened").isEqualTo(INTERFACE);
        assertClosed(withASource, "SPDIF 48k", "S/PDIF", "48");
        assertThat(withNoSources.state()).as("E136: no clock sources hides the badge").isEqualTo(State.HIDDEN);
        assertClosed(withAFailedRead, "W/C 48k", "Word Clock", "48");
    }

    /**
     * Ruling E135: one open is one new stream. A look sees it open (the
     * starting view), its read answers (measured), and the stream-opened
     * callback arrives only then: nothing changes and nothing is read again.
     */
    @Test
    void aStreamOpenedCallbackArrivingAfterTheStreamWasMeasuredChangesNothing() throws Exception {
        ClockStatusIndicator badge = closedExternalBadge();
        clock.status = Optional.of(locked(2, 9L));
        clock.openStream();
        runOnFx(badge::pulse);
        View seenOpen = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        View measured = view(badge);
        int readsWhenMeasured = reads.requested.get();

        badge.streamOpened();
        runOnFx(() -> { });
        View atTheLateCallback = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        look(badge);

        assertStarting(seenOpen, "W/C 48k", "Word Clock", "48");
        assertThat(measured.state()).as("premise: measured").isEqualTo(State.QUIET);
        assertThat(atTheLateCallback.state()).as("E135: never STARTING after the stream's first answered read")
                .isEqualTo(State.QUIET);
        assertThat(atTheLateCallback.tooltip()).isEqualTo(QUIET_TOOLTIP);
        assertThat(atTheLateCallback.spoken()).isEqualTo("Clock: Word Clock, 48 kilohertz.");
        assertThat(view(badge)).isEqualTo(measured);
        assertThat(reads.requested.get()).as("the late callback asks no read").isEqualTo(readsWhenMeasured);
    }

    /** The callback that arrives before any look starts the new stream's read, once. */
    @Test
    void theStreamOpenedCallbackBeforeTheFirstLookStartsExactlyOneRead() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(1));
        clock.openStream();

        badge.streamOpened();
        runOnFx(() -> { });
        int readsAfterTheCallback = reads.requested.get();
        runOnFx(badge::pulse);
        reads.runAll();
        runOnFx(() -> { });
        look(badge);

        assertThat(readsAfterTheCallback).as("the callback's look started the read").isEqualTo(1);
        assertThat(reads.requested.get()).as("and no look after it started another").isEqualTo(1);
        assertThat(view(badge).state()).isEqualTo(State.CHECKING);
    }

    /** Several callbacks and looks, in any order, for one open start exactly one read. */
    @Test
    void severalCallbacksAndLooksForOneOpenStartExactlyOneRead() throws Exception {
        ClockStatusIndicator badge = closedExternalBadge();
        int readsBefore = reads.requested.get();
        clock.status = Optional.of(locked(2, 9L));
        clock.openStream();

        runOnFx(badge::pulse);
        badge.streamOpened();
        runOnFx(badge::pulse);
        badge.streamOpened();
        badge.streamOpened();
        runOnFx(() -> { });
        int whileInFlight = reads.requested.get() - readsBefore;
        reads.runAll();
        runOnFx(() -> { });
        badge.streamOpened();
        runOnFx(badge::pulse);
        badge.streamOpened();
        runOnFx(() -> { });

        assertThat(whileInFlight).as("one read for the open").isEqualTo(1);
        assertThat(reads.requested.get() - readsBefore).as("and none after it answered").isEqualTo(1);
        assertThat(view(badge).state()).isEqualTo(State.QUIET);
    }

    /**
     * Ruling E142: a label read on one backend's automatic device is not
     * carried onto another backend's automatic device, though both are named
     * "&lt;default&gt;": hidden from the first look at that stream, and
     * hidden after it answers that it has no clock sources.
     */
    @Test
    void aLabelOnOneBackendsAutomaticDeviceIsNeverCarriedOntoAnothers() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(ASIO_DEFAULT, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(1));
        opened(badge);
        clock.closeStream();
        look(badge);
        View asioClosed = view(badge);

        clock.reading = Optional.of(new ClockReading(PORTAUDIO_DEFAULT, List.of(), 48_000));
        clock.status = Optional.empty();
        clock.openStream();
        runOnFx(badge::pulse);
        View firstLook = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        View afterItsAnswer = view(badge);

        assertClosed(asioClosed, "W/C 48k", "Word Clock", "48");
        assertThat(firstLook.state()).as("E142: never the ASIO label on the PortAudio stream, not even starting")
                .isEqualTo(State.HIDDEN);
        assertThat(afterItsAnswer.state()).as("E135: PortAudio has no clock sources").isEqualTo(State.HIDDEN);
    }

    /**
     * Ruling E142 with E141: a label read on one backend's "Studio Interface"
     * is not carried onto another backend's device of the same name; with
     * that stream's read failing, the badge stays hidden and reads once a
     * second.
     */
    @Test
    void aLabelIsNeverCarriedOntoAnotherBackendsDeviceOfTheSameNameWhoseReadFails() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(STUDIO_ASIO, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(1));
        opened(badge);
        assertThat(view(badge).text()).as("premise: the ASIO label").isEqualTo("W/C 48k");

        clock.reading = Optional.of(new ClockReading(STUDIO_WASAPI, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.failure = new IllegalStateException("the driver's panel is open");
        clock.openStream();
        look(badge);
        int readsAfterTheOpen = reads.requested.get();
        List<State> eachSecond = new ArrayList<>();
        eachSecond.add(view(badge).state());
        for (int second = 0; second < 5; second++) {
            nanos.addAndGet(SECOND);
            look(badge);
            eachSecond.add(view(badge).state());
        }

        assertThat(eachSecond).as("E142: hidden throughout, never STARTING with the ASIO label")
                .containsOnly(State.HIDDEN).hasSize(6);
        assertThat(reads.requested.get()).as("one read a second (E141)").isEqualTo(readsAfterTheOpen + 5);
    }

    /**
     * Ruling E81: a closed view on one backend's device, and a Settings read
     * that names the same source and rate on another backend's device of the
     * same name: not provably the same clock, so the label is forgotten.
     */
    @Test
    void aSettingsReadOnAnotherBackendsDeviceOfTheSameNameHidesTheClosedView() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(STUDIO_ASIO, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(1));
        opened(badge);
        clock.closeStream();
        look(badge);
        View closed = view(badge);

        clock.reading = Optional.of(new ClockReading(STUDIO_WASAPI, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        refresh(badge);

        assertClosed(closed, "W/C 48k", "Word Clock", "48");
        assertThat(view(badge).state()).as("E81: the device changed while closed").isEqualTo(State.HIDDEN);
    }

    /**
     * Ruling E136: a stream's read that answers about another backend's
     * device of the same name is not that stream's answer and never builds
     * the closed view.
     */
    @Test
    void aStreamsReadAnsweredAboutAnotherBackendsDeviceOfTheSameNameNeverBuildsTheClosedView() throws Exception {
        ClockStatusIndicator badge = badge();
        clock.reading = Optional.of(new ClockReading(STUDIO_WASAPI, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));

        clock.openStream(STUDIO_ASIO, 48_000);
        runOnFx(badge::pulse);
        clock.closeStream();
        runOnFx(badge::pulse);
        reads.runAll();
        runOnFx(() -> { });
        runOnFx(badge::pulse);

        assertThat(clock.devicesAsked).as("premise: the read asked about the stream's device")
                .containsExactly(STUDIO_ASIO);
        assertThat(view(badge).state()).as("E136: never a closed view read from a device the stream did not open")
                .isEqualTo(State.HIDDEN);
    }

    /** A badge built while a stream is open notices that stream at its first look and reads it. */
    @Test
    void theFirstLookOfABadgeBuiltWhileAStreamIsOpenReadsThatStream() throws Exception {
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(1));
        clock.openStream();
        ClockStatusIndicator badge = badge();
        View built = view(badge);

        look(badge);

        assertThat(built.state()).isEqualTo(State.HIDDEN);
        assertThat(reads.requested.get()).as("one read, asked by the first look").isEqualTo(1);
        assertThat(view(badge).state()).isEqualTo(State.CHECKING);
        assertThat(view(badge).text()).isEqualTo("W/C 48k");
    }

    /**
     * A badge built after a stream ran and closed notices that stream at its
     * first look; its read, about that stream's device, settles the closed
     * view (E136).
     */
    @Test
    void theFirstLookOfABadgeBuiltAfterAStreamRanAndClosedSettlesThatStreamsClosedView() throws Exception {
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL), 48_000));
        clock.openStream();
        clock.closeStream();
        ClockStatusIndicator badge = badge();

        look(badge);

        assertThat(clock.devicesAsked).containsExactly(INTERFACE);
        assertClosed(view(badge), "INT 48k", "Internal", "48");
    }

    /**
     * Ruling E144: a measured "W/C 48k" stream whose status names S/PDIF
     * (checking, its re-read in flight) is stopped. A label the stream's own
     * status had replaced is not the closed view: hidden whether the re-read
     * answers S/PDIF, answers no sources or throws, and whether a pulse runs
     * between the Stop and the landing or none does.
     */
    @Test
    void aStopWhileTheStatusNamesAnotherSourceHidesTheLabelWhateverTheReReadAnswersInEitherOrder()
            throws Exception {
        for (String answer : List.of("S/PDIF", "no sources", "a failure")) {
            for (boolean aPulseFirst : List.of(true, false)) {
                String variant = answer + (aPulseFirst ? ", a pulse before the landing" : ", the landing first");
                ClockStatusIndicator badge = measuredWithAReReadInFlightThenStopped();
                switch (answer) {
                    case "no sources" -> clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 48_000));
                    case "a failure" -> clock.failure = new IllegalStateException("the driver's panel is open");
                    default -> { }
                }
                if (aPulseFirst) {
                    runOnFx(badge::pulse);
                }
                reads.runAll();
                runOnFx(() -> { });
                View atTheLanding = view(badge);
                runOnFx(badge::pulse);

                assertThat(atTheLanding.text()).as("E144, %s: no label on show after the Stop", variant).isNull();
                assertThat(atTheLanding.state()).as(variant).isEqualTo(State.HIDDEN);
                assertThat(atTheLanding.visible()).as(variant).isFalse();
                assertThat(view(badge)).as("and at the next look, %s", variant).isEqualTo(atTheLanding);
            }
        }
    }

    /** Ruling E144: the re-read answered S/PDIF before the Stop, so S/PDIF is the label at the close. */
    @Test
    void aStopAfterTheReReadAnsweredTheNewSourceShowsThatSourceClosed() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, SPDIF),
                48_000));
        clock.status = Optional.of(status(SPDIF.id(), 2, ClockLockStatus.Lock.CHECKING, 0L));
        runOnFx(badge::pulse);
        View whileTheReReadIsInFlight = view(badge);
        reads.runAll();
        runOnFx(() -> { });
        View answered = view(badge);

        clock.closeStream();
        look(badge);

        assertThat(whileTheReReadIsInFlight.text()).as("premise: the status named S/PDIF over the W/C label")
                .isEqualTo("W/C 48k");
        assertThat(answered.text()).as("premise: the re-read answered S/PDIF before the Stop").isEqualTo("SPDIF 48k");
        assertClosed(view(badge), "SPDIF 48k", "S/PDIF", "48");
    }

    /**
     * Ruling E144 with E136: a "W/C 48k" carried into the starting view of a
     * stream whose status names S/PDIF, then a Stop before the stream's first
     * read answers. A failed read leaves nothing (the label the status had
     * replaced is not kept); a read answering S/PDIF about the stream's device
     * gives that label's closed view.
     */
    @Test
    void aCarriedLabelTheStatusReplacedGivesWayAtTheStopToWhatTheFirstReadAnswers() throws Exception {
        ClockStatusIndicator failing = carriedLabelWhoseStatusNamesSpdifThenStopped();
        clock.failure = new IllegalStateException("the driver's panel is open");
        look(failing);

        ClockStatusIndicator answered = carriedLabelWhoseStatusNamesSpdifThenStopped();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, SPDIF),
                48_000));
        look(answered);

        assertThat(view(failing).state()).as("E144: a failed first read keeps no label the status had replaced")
                .isEqualTo(State.HIDDEN);
        assertThat(view(failing).visible()).isFalse();
        assertClosed(view(answered), "SPDIF 48k", "S/PDIF", "48");
    }

    /**
     * Ruling E144: a measured stream whose status named S/PDIF closes, and
     * another stream opens on the same device and rate before any look saw
     * the close. The replaced label is not carried into the new stream's
     * starting view: hidden from its first look, then its own read's answer.
     */
    @Test
    void aLabelTheStatusReplacedIsNotCarriedIntoAStreamThatOpensBeforeAnyLookSawTheClose() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, SPDIF),
                48_000));
        clock.status = Optional.of(status(SPDIF.id(), 2, ClockLockStatus.Lock.CHECKING, 0L));
        runOnFx(badge::pulse);
        View contradicted = view(badge);

        clock.closeStream();
        clock.status = Optional.of(status(SPDIF.id(), 3, ClockLockStatus.Lock.LOCKED, 9L));
        clock.openStream();
        runOnFx(badge::pulse);
        View theNewStreamsFirstLook = view(badge);
        reads.runAll();
        runOnFx(() -> { });

        assertThat(contradicted.state()).as("premise: the status named S/PDIF over the W/C label")
                .isEqualTo(State.CHECKING);
        assertThat(contradicted.text()).isEqualTo("W/C 48k");
        assertThat(theNewStreamsFirstLook.state()).as("E144: never the starting view with the replaced label")
                .isEqualTo(State.HIDDEN);
        assertThat(view(badge).text()).as("the new stream's own answer").isEqualTo("SPDIF 48k");
        assertThat(view(badge).state()).isEqualTo(State.QUIET);
        assertThat(notices).isEmpty();
    }

    /**
     * Rulings E144 and E138: the driver refused the switch, and the status
     * names the label's source again at a look before the Stop: the closed
     * view keeps the label.
     */
    @Test
    void aRefusedSwitchWhoseStatusNamesTheLabelsSourceAgainBeforeTheStopKeepsTheClosedView() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        statusNow(badge, status(SPDIF.id(), 2, ClockLockStatus.Lock.CHECKING, 0L));
        View switching = view(badge);
        statusNow(badge, locked(3, 9L));
        View refused = view(badge);

        clock.closeStream();
        look(badge);

        assertThat(switching.state()).as("premise: the status named S/PDIF").isEqualTo(State.CHECKING);
        assertThat(switching.text()).as("premise: the driver still answers Word Clock").isEqualTo("W/C 48k");
        assertThat(refused.state()).as("premise: the status names Word Clock again").isEqualTo(State.QUIET);
        assertClosed(view(badge), "W/C 48k", "Word Clock", "48");
    }

    /**
     * When a measured stream whose status agrees with its label closes, a
     * Settings read in flight is void, whether a pulse runs between the close
     * and the landing or none does: the closed view is that stream's label.
     */
    @Test
    void aSettingsReadInFlightWhenAMeasuredStreamClosesIsVoidInEitherOrder() throws Exception {
        for (boolean aPulseFirst : List.of(true, false)) {
            String order = aPulseFirst ? "a pulse before the landing" : "the landing first";
            ClockStatusIndicator badge = externalBadge();
            statusNow(badge, locked(1, 5L));
            clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL, WORD_CLOCK_IDLE), 48_000));
            badge.refresh();
            clock.closeStream();
            if (aPulseFirst) {
                runOnFx(badge::pulse);
            }
            reads.runAll();
            runOnFx(() -> { });
            runOnFx(badge::pulse);

            assertThat(view(badge).state()).as("the Settings read is void, %s", order).isEqualTo(State.CLOSED);
            assertClosed(view(badge), "W/C 48k", "Word Clock", "48");
        }
    }

    /**
     * Ruling E145: a stream ran on the fallback device C and closed ("INT
     * 44.1k"); a Settings read of the configured device A hides the closed
     * view whether it answers A's clock (the same source and rate on another
     * device), an empty list, or fails. The next stream on A is hidden until
     * A's read answers; a next stream that opens C again shows C's view once
     * its read answers.
     */
    @Test
    void aSettingsReadOfTheConfiguredDeviceAfterAStreamOnAFallbackHidesTheClosedView() throws Exception {
        for (String answer : List.of("A's clock", "an empty list", "a failure")) {
            ClockStatusIndicator badge = closedOnTheFallback();
            switch (answer) {
                case "A's clock" -> clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL), 44_100));
                case "an empty list" -> clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 44_100));
                default -> clock.failure = new IllegalStateException("the driver's panel is open");
            }
            refresh(badge);

            assertThat(view(badge).state()).as("E145, %s", answer).isEqualTo(State.HIDDEN);
            assertThat(view(badge).visible()).as(answer).isFalse();
        }

        ClockStatusIndicator onA = closedOnTheFallback();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 44_100));
        refresh(onA);
        clock.status = Optional.of(checking(2));
        clock.openStream();
        runOnFx(onA::pulse);
        View aBeforeItsRead = view(onA);
        reads.runAll();
        runOnFx(() -> { });

        ClockStatusIndicator onC = closedOnTheFallback();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL), 44_100));
        refresh(onC);
        View cAfterTheApply = view(onC);
        clock.reading = Optional.of(new ClockReading(FALLBACK, List.of(INTERNAL), 44_100));
        clock.openStream();
        runOnFx(onC::pulse);
        View cBeforeItsRead = view(onC);
        reads.runAll();
        runOnFx(() -> { });

        assertThat(aBeforeItsRead.state()).as("the next stream on A: hidden until A's read answers")
                .isEqualTo(State.HIDDEN);
        assertThat(view(onA).text()).as("then A's view").isEqualTo("W/C 44.1k");
        assertThat(view(onA).state()).isEqualTo(State.CHECKING);
        assertThat(cAfterTheApply.state()).as("premise: the Apply hid C's closed view").isEqualTo(State.HIDDEN);
        assertThat(cBeforeItsRead.state()).isEqualTo(State.HIDDEN);
        assertThat(view(onC).text()).as("C's view after its read").isEqualTo("INT 44.1k");
        assertThat(view(onC).state()).isEqualTo(State.QUIET);
    }

    /**
     * Ruling E143 with E142: playing "W/C 48k" on A, a Settings change of
     * device reopens on B, and B's start fails, so B opened and closed at
     * once. Hidden from the reopen; A's label never comes back at any look,
     * no stream read is asked about A, nothing is notified or marked. The end
     * depends on which read answers first: B's own read alone gives B's
     * closed view; a Settings refresh asked after it voids it and hides.
     */
    @Test
    void aReopenOnAnotherDeviceWhoseStartFailsDuringPlaybackNeverBringsBackTheEarlierLabel() throws Exception {
        Map<String, View> ends = new HashMap<>();
        for (String order : List.of("no Settings refresh", "a pulse then the posted refresh",
                "the posted refresh then a pulse")) {
            ClockStatusIndicator badge = playingOnA();
            int askedBefore = clock.devicesAsked.size();
            List<View> afterTheReopen = new ArrayList<>();

            clock.closeStream();
            clock.openStream(OTHER_INTERFACE, 48_000);
            runOnFx(badge::pulse);
            View reopened = view(badge);
            afterTheReopen.add(reopened);
            clock.closeStream();
            clock.reading = Optional.of(new ClockReading(OTHER_INTERFACE, List.of(INTERNAL), 48_000));
            if (order.equals("the posted refresh then a pulse")) {
                runOnFx(badge::refresh);
            }
            runOnFx(badge::pulse);
            if (order.equals("a pulse then the posted refresh")) {
                runOnFx(badge::refresh);
            }
            afterTheReopen.add(view(badge));
            reads.runAll();
            runOnFx(() -> { });
            afterTheReopen.add(view(badge));
            look(badge);
            afterTheReopen.add(view(badge));
            nanos.addAndGet(5 * SECOND);
            look(badge);
            afterTheReopen.add(view(badge));
            ends.put(order, view(badge));

            assertThat(reopened.state()).as("E142, %s: hidden from the reopen", order).isEqualTo(State.HIDDEN);
            assertThat(afterTheReopen).as("E143, %s: never A's label after the reopen", order)
                    .extracting(View::text).doesNotContain("W/C 48k");
            assertThat(clock.devicesAsked.subList(askedBefore, clock.devicesAsked.size()))
                    .as("%s: no stream read about A after the reopen", order).containsExactly(OTHER_INTERFACE);
        }

        assertClosed(ends.get("no Settings refresh"), "INT 48k", "Internal", "48");
        assertThat(ends.get("a pulse then the posted refresh").state()).as("a pulse then the posted refresh")
                .isEqualTo(State.HIDDEN);
        assertThat(ends.get("the posted refresh then a pulse").state()).as("the posted refresh then a pulse")
                .isEqualTo(State.HIDDEN);
        assertThat(notices).isEmpty();
        assertThat(marks.marks).isEmpty();
    }

    /**
     * Ruling E143 with E81: the closed view of A on show, an Apply of B while
     * stopped hides it, then a Play on B whose start fails. A's label never
     * comes back; the end is B's closed view when B's own read answers last,
     * hidden when a Settings refresh asked after it voids it.
     */
    @Test
    void anApplyOfAnotherDeviceThenAPlayWhoseStartFailsNeverBringsBackTheEarlierLabel() throws Exception {
        Map<String, View> ends = new HashMap<>();
        for (String order : List.of("a pulse inside the failed start", "no pulse inside the failed start",
                "a refresh then a pulse")) {
            ClockStatusIndicator badge = playingOnA();
            clock.closeStream();
            look(badge);
            clock.reading = Optional.of(new ClockReading(OTHER_INTERFACE, List.of(INTERNAL), 48_000));
            refresh(badge);
            look(badge);
            View afterTheApply = view(badge);
            int askedBefore = clock.devicesAsked.size();

            clock.openStream(OTHER_INTERFACE, 48_000);
            if (!order.equals("no pulse inside the failed start")) {
                runOnFx(badge::pulse);
            }
            clock.closeStream();
            if (order.equals("a refresh then a pulse")) {
                runOnFx(badge::refresh);
            }
            runOnFx(badge::pulse);
            reads.runAll();
            runOnFx(() -> { });
            nanos.addAndGet(5 * SECOND);
            look(badge);
            ends.put(order, view(badge));

            assertThat(afterTheApply.state()).as("premise, %s: the Apply of B hid A's closed view (E81)", order)
                    .isEqualTo(State.HIDDEN);
            assertThat(view(badge).text()).as("E143, %s: never A's label", order).isNotEqualTo("W/C 48k");
            assertThat(clock.devicesAsked.subList(askedBefore, clock.devicesAsked.size()))
                    .as("%s: the failed stream is read once, about B", order).containsExactly(OTHER_INTERFACE);
        }

        assertClosed(ends.get("a pulse inside the failed start"), "INT 48k", "Internal", "48");
        assertClosed(ends.get("no pulse inside the failed start"), "INT 48k", "Internal", "48");
        assertThat(ends.get("a refresh then a pulse").state()).as("a refresh then a pulse").isEqualTo(State.HIDDEN);
        assertThat(notices).isEmpty();
        assertThat(marks.marks).isEmpty();
    }

    /**
     * Ruling E143 (S1): the closed view of A on show, a Play on A whose start
     * fails. The view stays, with the starting texts only on a look that sees
     * the stream open; the stream is read once, about A, and that read
     * settles the closed view as after a quick Play and Stop: a current
     * source keeps it, no sources hides it, a failed read keeps it. The
     * failed stream's unlocked status is never shown, notified or marked.
     * The same with no look seeing the stream open:
     * {@link #aStreamThatOpenedAndClosedBetweenTwoLooksIsReadAboutItsDeviceAndSettlesTheClosedView}.
     */
    @Test
    void aPlayWhoseStartFailsOnTheSameDeviceKeepsTheClosedViewUntilItsReadSettlesIt() throws Exception {
        Map<String, View> afterTheRead = new HashMap<>();
        for (String answer : List.of("Word Clock", "no sources", "a failure")) {
            ClockStatusIndicator badge = closedExternalBadge();
            int askedBefore = clock.devicesAsked.size();
            switch (answer) {
                case "no sources" -> clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(), 48_000));
                case "a failure" -> clock.failure = new IllegalStateException("the driver's panel is open");
                default -> { }
            }
            clock.status = Optional.of(unlocked(2, 9L));

            clock.openStream();
            runOnFx(badge::pulse);
            View whileOpen = view(badge);
            clock.closeStream();
            runOnFx(badge::pulse);
            View afterTheFailure = view(badge);
            reads.runAll();
            runOnFx(() -> { });
            look(badge);
            afterTheRead.put(answer, view(badge));

            assertStarting(whileOpen, "W/C 48k", "Word Clock", "48");
            assertClosed(afterTheFailure, "W/C 48k", "Word Clock", "48");
            assertThat(clock.devicesAsked.subList(askedBefore, clock.devicesAsked.size()))
                    .as("%s: one stream read for that open, about A", answer).containsExactly(INTERFACE);
        }

        assertClosed(afterTheRead.get("Word Clock"), "W/C 48k", "Word Clock", "48");
        assertThat(afterTheRead.get("no sources").state()).as("E136: no clock sources hides the badge")
                .isEqualTo(State.HIDDEN);
        assertClosed(afterTheRead.get("a failure"), "W/C 48k", "Word Clock", "48");
        assertThat(notices).as("never a notice from a stream that never started").isEmpty();
        assertThat(marks.marks).as("nor a mark").isEmpty();
    }

    /**
     * Ruling E143 (S2): the first open of the session fails to start. Nothing
     * was on show, so the badge stays hidden until that stream's read about
     * its device answers: a current source gives the closed view, a failed
     * read leaves it hidden.
     */
    @Test
    void theFirstOpenOfASessionWhoseStartFailsShowsTheClosedViewOnlyWhenItsReadAnswersASource() throws Exception {
        ClockStatusIndicator answered = badge();
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.openStream();
        clock.closeStream();
        runOnFx(answered::pulse);
        View beforeItsRead = view(answered);
        reads.runAll();
        runOnFx(() -> { });
        look(answered);

        ClockStatusIndicator failing = badge();
        clock.failure = new IllegalStateException("the driver's panel is open");
        clock.openStream();
        clock.closeStream();
        look(failing);
        nanos.addAndGet(5 * SECOND);
        look(failing);

        assertThat(beforeItsRead.state()).as("nothing was on show").isEqualTo(State.HIDDEN);
        assertClosed(view(answered), "W/C 48k", "Word Clock", "48");
        assertThat(view(failing).state()).as("a failed read shows nothing").isEqualTo(State.HIDDEN);
        assertThat(view(failing).visible()).isFalse();
    }

    /**
     * A look reads whether a stream is open before it reads the stream the
     * engine last opened: a stream that opens on another device between the
     * two reads is never shown with the label of the stream before it (E142).
     */
    @Test
    void aStreamOpeningBetweenTheBadgesTwoReadsOfTheEngineNeverShowsTheLastStreamsLabel() throws Exception {
        ClockStatusIndicator badge = closedExternalBadge();
        clock.reading = Optional.of(new ClockReading(OTHER_INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.empty();
        clock.afterTheNextEngineRead = clock::openStream;

        runOnFx(badge::pulse);
        View theRacingLook = view(badge);
        reads.runAll();
        List<DeviceId> asked = List.copyOf(clock.devicesAsked);

        assertThat(theRacingLook.state()).as("E142: never device A's label on device B's stream, not for one look")
                .isEqualTo(State.HIDDEN);
        assertThat(asked).as("the first stream's read, then the new stream's, about its device")
                .containsExactly(INTERFACE, OTHER_INTERFACE);
    }

    /** {@link #externalBadge()}, its status locked, then a Stop the badge has looked at: the closed view. */
    private ClockStatusIndicator closedExternalBadge() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.closeStream();
        look(badge);
        assertClosed(view(badge), "W/C 48k", "Word Clock", "48");
        return badge;
    }

    /** A measured "W/C 48k" stream whose status names S/PDIF; the re-read is in flight when the stream stops. */
    private ClockStatusIndicator measuredWithAReReadInFlightThenStopped() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, SPDIF),
                48_000));
        clock.status = Optional.of(status(SPDIF.id(), 2, ClockLockStatus.Lock.CHECKING, 0L));
        int readsBefore = reads.requested.get();
        runOnFx(badge::pulse);
        assertThat(reads.requested.get()).as("premise: the re-read was asked").isEqualTo(readsBefore + 1);
        clock.closeStream();
        return badge;
    }

    /** {@link #closedExternalBadge()}, then a stream on the same device whose status names S/PDIF, stopped unread. */
    private ClockStatusIndicator carriedLabelWhoseStatusNamesSpdifThenStopped() throws Exception {
        ClockStatusIndicator badge = closedExternalBadge();
        clock.status = Optional.of(status(SPDIF.id(), 2, ClockLockStatus.Lock.CHECKING, 0L));
        clock.openStream();
        runOnFx(badge::pulse);
        assertStarting(view(badge), "W/C 48k", "Word Clock", "48");
        clock.closeStream();
        return badge;
    }

    /** Playing on A: "W/C 48k" measured and locked; a read about A or about B answers that device's own clock. */
    private ClockStatusIndicator playingOnA() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.byDevice.put(INTERFACE, new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.byDevice.put(OTHER_INTERFACE, new ClockReading(OTHER_INTERFACE, List.of(INTERNAL), 48_000));
        assertThat(view(badge).state()).as("premise: measured on A").isEqualTo(State.QUIET);
        return badge;
    }

    /** A stream ran on {@link #FALLBACK} at 44.1 kHz with its internal clock, and closed: its closed view. */
    private ClockStatusIndicator closedOnTheFallback() throws Exception {
        clock.lost = false;
        clock.failure = null;
        clock.status = Optional.empty();
        clock.reading = Optional.of(new ClockReading(FALLBACK, List.of(INTERNAL), 44_100));
        ClockStatusIndicator badge = badge();
        opened(badge);
        clock.closeStream();
        look(badge);
        assertClosed(view(badge), "INT 44.1k", "Internal", "44.1");
        return badge;
    }

    /**
     * Rulings E74 and E84: a loss keeps nothing the badge knew. A loss that
     * ends with the stream closed shows nothing, never the lost device's
     * label in the closed view; one that ends with the stream still open
     * shows nothing until that stream's read answers.
     */
    @Test
    void aDeviceLossForgetsTheLabelSoNothingShowsWhenTheLossEnds() throws Exception {
        ClockStatusIndicator closedAfter = externalBadge();
        statusNow(closedAfter, locked(1, 5L));
        clock.lost = true;
        clock.closeStream();
        look(closedAfter);
        clock.lost = false;
        look(closedAfter);
        View endedClosed = view(closedAfter);

        ClockStatusIndicator openAfter = externalBadge();
        statusNow(openAfter, locked(1, 5L));
        clock.lost = true;
        look(openAfter);
        clock.lost = false;
        runOnFx(openAfter::pulse);
        View endedOpenBeforeItsRead = view(openAfter);
        reads.runAll();
        runOnFx(() -> { });

        assertThat(endedClosed.state()).as("never the lost device's label after the loss (E84)")
                .isEqualTo(State.HIDDEN);
        assertThat(endedOpenBeforeItsRead.state()).as("nothing carried through a loss").isEqualTo(State.HIDDEN);
        assertThat(view(openAfter).state()).as("the stream is read again").isEqualTo(State.QUIET);
    }

    /** A label on show, closed; a Play whose read has not answered yet, then a Stop. */
    private ClockStatusIndicator quicklyPlayedAndStopped() throws Exception {
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        clock.closeStream();
        look(badge);
        clock.status = Optional.of(locked(2, 9L));
        clock.openStream();
        runOnFx(badge::pulse);
        assertThat(view(badge).state()).as("premise: starting").isEqualTo(State.STARTING);
        clock.closeStream();
        runOnFx(badge::pulse);
        assertClosed(view(badge), "W/C 48k", "Word Clock", "48");
        return badge;
    }

    private static Arguments row(String name, boolean lost, boolean open, ClockLabel label, OwnRead ownRead,
                                 ClockLockStatus status, State expected) {
        return Arguments.of(name, new Facts(lost, open, label, ownRead, status), expected);
    }

    private static int occurrences(String code, String fragment) {
        int count = 0;
        for (int at = code.indexOf(fragment); at >= 0; at = code.indexOf(fragment, at + 1)) {
            count++;
        }
        return count;
    }

    private static String body(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("declares %s", signature).isNotNegative();
        int open = code.indexOf('{', declaration);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    private ClockStatusIndicator badge() throws Exception {
        return onFx(() -> new ClockStatusIndicator(sink, marks, clock, null, reads, nanos::get));
    }

    /** A badge checking Word Clock at 48 kHz on {@link #INTERFACE}, its stream open, status serial 1. */
    private ClockStatusIndicator externalBadge() throws Exception {
        clock.lost = false;
        clock.failure = null;
        clock.reading = Optional.of(new ClockReading(INTERFACE, List.of(INTERNAL_IDLE, WORD_CLOCK), 48_000));
        clock.status = Optional.of(checking(1));
        ClockStatusIndicator badge = badge();
        opened(badge);
        assertThat(view(badge).state()).as("premise: checking the external clock").isEqualTo(State.CHECKING);
        return badge;
    }

    /** A stream opens on the reading's device and rate; the badge looks, its read answers, and it looks again. */
    private void opened(ClockStatusIndicator badge) throws Exception {
        clock.openStream();
        look(badge);
        look(badge);
    }

    /** A pulse, any read it asked for, and the read's answer applied. */
    private void look(ClockStatusIndicator badge) throws Exception {
        runOnFx(badge::pulse);
        reads.runAll();
        runOnFx(() -> { });
    }

    private void refresh(ClockStatusIndicator badge) throws Exception {
        badge.refresh();
        reads.runAll();
        runOnFx(() -> { });
    }

    /** The driver side's status changes, and the badge looks. */
    private void statusNow(ClockStatusIndicator badge, ClockLockStatus status) throws Exception {
        clock.status = Optional.of(status);
        look(badge);
    }

    /** Ruling E119 (a): the label on show, dim, with the starting texts. */
    private static void assertStarting(View shown, String text, String name, String rate) {
        assertThat(shown.visible()).as("never hidden at an open (ruling E119)").isTrue();
        assertThat(shown.managed()).as("the transport bar does not shift").isTrue();
        assertThat(shown.text()).as("the last-read label, no ⚠, no UNLOCKED").isEqualTo(text);
        assertThat(shown.styleClasses()).contains(ClockStatusIndicator.DIM_CLASS)
                .doesNotContain(ClockStatusIndicator.LOCKED_CLASS, ClockStatusIndicator.UNLOCKED_CLASS);
        assertThat(shown.tooltip()).isEqualTo(STARTING_TOOLTIP);
        assertThat(shown.spoken()).isEqualTo("Clock: " + name + ", " + rate + " kilohertz. Lock not checked yet.");
    }

    /** Ruling E83: the closed view. */
    private static void assertClosed(View shown, String text, String name, String rate) {
        assertThat(shown.state()).isEqualTo(State.CLOSED);
        assertThat(shown.visible()).as("never a badge that vanishes at an ordinary Stop (ruling E81)").isTrue();
        assertThat(shown.managed()).isTrue();
        assertThat(shown.text()).isEqualTo(text);
        assertThat(shown.styleClasses()).contains(ClockStatusIndicator.DIM_CLASS)
                .doesNotContain(ClockStatusIndicator.LOCKED_CLASS, ClockStatusIndicator.UNLOCKED_CLASS);
        assertThat(shown.tooltip()).isEqualTo(CLOSED_TOOLTIP);
        assertThat(shown.spoken()).isEqualTo("Clock: " + name + ", " + rate
                + " kilohertz. Lock not checked while the audio stream is closed.");
    }

    private static ClockLockStatus checking(long serial) {
        return status(WORD_CLOCK.id(), serial, ClockLockStatus.Lock.CHECKING, 0L);
    }

    private static ClockLockStatus locked(long serial, long evidenceNanos) {
        return status(WORD_CLOCK.id(), serial, ClockLockStatus.Lock.LOCKED, evidenceNanos);
    }

    private static ClockLockStatus unlocked(long serial, long evidenceNanos) {
        return status(WORD_CLOCK.id(), serial, ClockLockStatus.Lock.UNLOCKED, evidenceNanos);
    }

    private static ClockLockStatus status(int sourceId, long serial, ClockLockStatus.Lock lock, long evidenceNanos) {
        return new ClockLockStatus(INTERFACE, sourceId, serial, lock, evidenceNanos);
    }

    /** A status on Word Clock that carries {@code kept}, its evidence the newest's. */
    private static ClockLockStatus history(long serial, ClockLockStatus.Lock lock, ClockLockStatus.Transition... kept) {
        return historyOn(WORD_CLOCK.id(), serial, lock, kept);
    }

    private static ClockLockStatus historyOn(int sourceId, long serial, ClockLockStatus.Lock lock,
                                             ClockLockStatus.Transition... kept) {
        return new ClockLockStatus(INTERFACE, sourceId, serial, lock, kept[kept.length - 1].evidenceNanos(),
                List.of(kept));
    }

    /** The change {@code sequence}: odd an unlock, even a relock. */
    private static ClockLockStatus.Transition change(long sequence, long evidenceNanos) {
        return new ClockLockStatus.Transition(sequence, sequence % 2 == 0, evidenceNanos);
    }

    /** Transitions first..last, each at base plus its sequence. */
    private static ClockLockStatus.Transition[] changes(long first, long last, long base) {
        return LongStream.rangeClosed(first, last).mapToObj(sequence -> change(sequence, base + sequence))
                .toArray(ClockLockStatus.Transition[]::new);
    }

    /**
     * The one notice of an unlock and its relock between two looks of a new badge, the second look made while
     * {@code recording} records, with an audio take that holds the unlock after it or not.
     */
    private String lostAndRelockedWhile(TakeRecording recording, boolean takeHoldsTheUnlock) throws Exception {
        marks.recording = TakeRecording.NONE;
        marks.takeHoldsTheUnlock = takeHoldsTheUnlock;
        ClockStatusIndicator badge = externalBadge();
        statusNow(badge, locked(1, 5L));
        notices.clear();
        marks.recording = recording;
        statusNow(badge, history(1, ClockLockStatus.Lock.LOCKED, change(1, 7L), change(2, 9L)));
        assertThat(notices).as("premise: one notice").hasSize(1);
        return notices.get(0);
    }

    private static View view(ClockStatusIndicator badge) throws Exception {
        return onFx(() -> new View(badge.stateProperty().get(), badge.isVisible(), badge.isManaged(), badge.getText(),
                List.copyOf(badge.getStyleClass()), badge.getAccessibleText(), badge.getTooltip().getText()));
    }

    private record View(State state, boolean visible, boolean managed, String text, List<String> styleClasses,
                        String spoken, String tooltip) {
    }

    /** Ruling E232, per theme: rest and flash differ only in the ring, and the words keep their contrast. */
    private static void assertTheFlashKeepsTheWordsSteady(Phases phases) {
        Palette palette = phases.palette();
        Look rest = phases.rest();
        Look flash = phases.flash();
        ThemeManager.Theme theme = rest.theme();
        Color danger = palette.token("-danger");
        assertThat(rest.fills()).extracting(BackgroundFill::getFill).as("%s at rest: one fill, -danger", theme)
                .containsExactly(danger);
        assertThat(rest.text()).as("%s: the words are -text-on-accent", theme)
                .isEqualTo(palette.token("-text-on-accent"));
        assertThat(strokePaints(rest.border())).as("%s at rest: no visible outline", theme)
                .allSatisfy(paint -> assertThat(colour(paint).getOpacity()).isZero());
        assertThat(List.of(rest.opacity(), flash.opacity())).as("%s: nothing dims the badge", theme)
                .containsOnly(1.0);
        assertThat(rest.effect()).as("%s at rest: no effect", theme).isNull();
        assertThat(flash.effect()).as("%s flashing: no effect", theme).isNull();
        assertThat(flash.text()).as("%s: the words' colour in both phases", theme).isEqualTo(rest.text());
        assertThat(flash.words()).as("%s: the words in both phases", theme).isEqualTo(rest.words());

        assertThat(flash.fills()).as("%s flashing: the two ring layers over the fill", theme).hasSize(3);
        Color outer = colour(flash.fills().get(0).getFill());
        Color inner = colour(flash.fills().get(1).getFill());
        Color under = colour(flash.fills().get(2).getFill());
        for (Color stop : palette.barStops()) {
            assertThat(contrast(outer, stop)).as("%s: the ring's outer layer against the transport bar", theme)
                    .isGreaterThanOrEqualTo(3.0);
        }
        assertThat(contrast(inner, danger)).as("%s: the ring's inner layer against -danger", theme)
                .isGreaterThanOrEqualTo(3.0);
        assertThat(List.of(outer, inner)).as("%s: the ring is -text-hi outside, -text-on-accent inside", theme)
                .containsExactly(palette.token("-text-hi"), palette.token("-text-on-accent"));
        assertThat(flash.fills()).extracting(BackgroundFill::getInsets)
                .as("%s: the ring's layers each 1 px wide, on every side", theme)
                .containsExactly(new Insets(0), new Insets(1), new Insets(2));
        assertThat(under).as("%s flashing: the words stay on -danger", theme).isEqualTo(danger);
        Insets fill = flash.fills().getLast().getInsets();
        assertThat(List.of(fill.getTop(), fill.getRight(), fill.getBottom(), fill.getLeft()))
                .as("%s: the fill under the words inside the border and padding", theme)
                .zipSatisfy(List.of(flash.insets().getTop(), flash.insets().getRight(), flash.insets().getBottom(),
                        flash.insets().getLeft()), (side, content) -> assertThat(side).isLessThanOrEqualTo(content));
        assertThat(contrast(colour(flash.text()), under)).as("%s flashing: the words against -danger", theme)
                .isGreaterThanOrEqualTo(ThemeContrastValidator.AA_NORMAL);

        assertThat(flash.insets()).as("%s: nothing moves: the insets", theme).isEqualTo(rest.insets());
        assertThat(List.of(flash.width(), flash.height())).as("%s: nor the size", theme)
                .isEqualTo(List.of(rest.width(), rest.height()));
        assertThat(flash.border()).as("%s: nor the border", theme).isEqualTo(rest.border());
        assertThat(List.of(rest.outsets(), flash.outsets())).as("%s: nothing paints outside the badge", theme)
                .containsOnly(Insets.EMPTY);
    }

    /** Ruling E234: {@code dim} is an outlined chip with no fill, the size of the filled {@code quiet}. */
    private static void assertAnOutlinedChipTheSizeOf(Look dim, Look quiet, Palette palette) {
        String state = dim.state() + " in " + dim.theme();
        assertThat(dim.fills()).as("%s: no fill, unlike the locked chip", state)
                .allSatisfy(fill -> assertThat(colour(fill.getFill()).getOpacity()).isZero());
        assertThat(dim.border()).as("%s: an outline", state).isNotNull();
        assertThat(dim.border().getStrokes()).as("%s: one stroke", state).hasSize(1);
        BorderStroke stroke = dim.border().getStrokes().getFirst();
        assertThat(strokePaints(dim.border())).as("%s: -text-mute on every side", state)
                .containsOnly(palette.token("-text-mute"));
        BorderWidths widths = stroke.getWidths();
        assertThat(List.of(widths.getTop(), widths.getRight(), widths.getBottom(), widths.getLeft()))
                .as("%s: 1 px on every side", state).containsOnly(1.0);
        for (Color stop : palette.barStops()) {
            assertThat(contrast(colour(stroke.getTopStroke()), stop))
                    .as("%s: the outline against the transport bar", state).isGreaterThanOrEqualTo(3.0);
        }
        assertThat(dim.text()).as("%s: the words are -text", state).isEqualTo(palette.token("-text"));
        assertThat(List.of(dim.width(), dim.height())).as("%s: the locked chip's size", state)
                .isEqualTo(List.of(quiet.width(), quiet.height()));
        assertThat(dim.insets()).as("%s: and its insets", state).isEqualTo(quiet.insets());
    }

    /**
     * One badge, on a themed bar, driven CHECKING, NOT_MEASURABLE, QUIET,
     * CLOSED and STARTING; in each, its look in every theme.
     */
    private Shown eachCheckedAndUncheckedStateInEveryTheme() throws Exception {
        return onAThemedBar((badge, themed) -> {
            Map<ThemeManager.Theme, Palette> palettes = new EnumMap<>(ThemeManager.Theme.class);
            List<Look> looks = new ArrayList<>(inEveryTheme(badge, themed, State.CHECKING, palettes));
            statusNow(badge, status(WORD_CLOCK.id(), 1, ClockLockStatus.Lock.NOT_MEASURABLE, 0L));
            looks.addAll(inEveryTheme(badge, themed, State.NOT_MEASURABLE, palettes));
            statusNow(badge, locked(1, 5L));
            looks.addAll(inEveryTheme(badge, themed, State.QUIET, palettes));
            clock.closeStream();
            look(badge);
            looks.addAll(inEveryTheme(badge, themed, State.CLOSED, palettes));
            clock.openStream();
            // The new stream's read is left unrun: the label is carried, not checked yet.
            runOnFx(badge::pulse);
            looks.addAll(inEveryTheme(badge, themed, State.STARTING, palettes));
            return new Shown(Map.copyOf(palettes), List.copyOf(looks));
        });
    }

    /** After asserting the badge shows {@code expected}: its look in each theme, in one FX turn. */
    private static List<Look> inEveryTheme(ClockStatusIndicator badge, ThemedBar themed, State expected,
                                          Map<ThemeManager.Theme, Palette> palettes) throws Exception {
        assertThat(view(badge).state()).as("premise: %s", expected).isEqualTo(expected);
        return onFx(() -> {
            List<Look> looks = new ArrayList<>();
            for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
                themed.apply(theme, UiScale.P100);
                palettes.put(theme, themed.palette());
                looks.add(look(badge, themed, theme));
            }
            return looks;
        });
    }

    /**
     * Runs {@code test} on {@link #externalBadge()} built under Reduce Motion,
     * so its own timeline never steps the flash behind the test, on a
     * transport bar in a shown window; then disposes the badge and hides the
     * window.
     */
    private <T> T onAThemedBar(OnABar<T> test) throws Exception {
        MotionManager motion = new MotionManager(new MemoryPreferences(), Optional::empty);
        motion.setReduceMotion(true);
        MotionManager.setDefaultForTest(motion);
        List<Runnable> cleanUp = new ArrayList<>();
        try {
            ClockStatusIndicator badge = externalBadge();
            cleanUp.add(badge::dispose);
            ThemedBar themed = onFx(() -> themedBar(badge));
            cleanUp.add(themed.stage()::hide);
            return test.run(badge, themed);
        } finally {
            try {
                runOnFx(() -> cleanUp.forEach(Runnable::run));
            } finally {
                MotionManager.setDefaultForTest(null);
            }
        }
    }

    /**
     * {@code badge} on a transport bar in a shown window styled by a theme
     * manager of its own, with a probe off the bar for each of
     * {@link #PROBED_TOKENS}. FX thread.
     */
    private static ThemedBar themedBar(ClockStatusIndicator badge) {
        HBox bar = new HBox(badge);
        bar.getStyleClass().add("transport-bar");
        VBox root = new VBox(bar);
        root.getStyleClass().add("root-pane");
        Map<String, Region> probes = new LinkedHashMap<>();
        for (String token : PROBED_TOKENS) {
            Region probe = new Region();
            probe.setStyle("-fx-background-color: " + token + ";");
            root.getChildren().add(probe);
            probes.put(token, probe);
        }
        ThemeManager appearance = new ThemeManager(new MemoryPreferences());
        Scene scene = new Scene(root, 640, 80);
        appearance.applyTo(scene);
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        return new ThemedBar(stage, root, bar, appearance, Map.copyOf(probes));
    }

    /** The badge's look as the window lays it out now. FX thread. */
    private static Look look(ClockStatusIndicator badge, ThemedBar themed, ThemeManager.Theme theme) {
        themed.root().applyCss();
        themed.root().layout();
        Background background = badge.getBackground();
        return new Look(badge.stateProperty().get(), theme, badge.getWidth(), badge.getHeight(), badge.getInsets(),
                badge.getTextFill(), background == null ? List.of() : List.copyOf(background.getFills()),
                badge.getBorder(), background == null ? Insets.EMPTY : background.getOutsets(),
                badge.getOpacity(), badge.getEffect(), badge.getText());
    }

    /** The badge's {@link FlashStep} as the window lays it out now. FX thread. */
    private static FlashStep flashStep(ClockStatusIndicator badge, ThemedBar themed) {
        themed.root().applyCss();
        themed.root().layout();
        Background background = badge.getBackground();
        return new FlashStep(badge.getPseudoClassStates().contains(ClockStatusIndicator.FLASH), badge.getOpacity(),
                badge.getEffect(), badge.getStyle(), badge.getTextFill(),
                background == null ? List.of() : List.copyOf(background.getFills()), badge.getPadding(),
                badge.getBorder());
    }

    /** @return the badge's last fill when it is opaque: the surface its words are drawn on */
    private static Optional<Color> opaqueLastFill(Look look) {
        if (look.fills().isEmpty()) {
            return Optional.empty();
        }
        Color last = colour(look.fills().getLast().getFill());
        return last.getOpacity() == 1.0 ? Optional.of(last) : Optional.empty();
    }

    /** @return each side's paint of each stroke of {@code border}; none when there is no border */
    private static List<Paint> strokePaints(Border border) {
        if (border == null) {
            return List.of();
        }
        return border.getStrokes().stream().flatMap(stroke -> Stream.of(stroke.getTopStroke(),
                stroke.getRightStroke(), stroke.getBottomStroke(), stroke.getLeftStroke())).toList();
    }

    private static Color colour(Paint paint) {
        assertThat(paint).as("a solid colour").isInstanceOf(Color.class);
        return (Color) paint;
    }

    private static double contrast(Color first, Color second) {
        return ThemeContrastValidator.contrastRatio(hex(first), hex(second));
    }

    private static String hex(Color colour) {
        assertThat(colour.getOpacity()).as("an opaque colour: %s", colour).isEqualTo(1.0);
        return String.format(Locale.ROOT, "#%02X%02X%02X", Math.round(colour.getRed() * 255),
                Math.round(colour.getGreen() * 255), Math.round(colour.getBlue() * 255));
    }

    @FunctionalInterface
    private interface OnABar<T> {
        T run(ClockStatusIndicator badge, ThemedBar themed) throws Exception;
    }

    private record ThemedBar(Stage stage, VBox root, HBox bar, ThemeManager appearance, Map<String, Region> probes) {

        /** Applies {@code theme} at {@code scale} and lays the window out. FX thread. */
        void apply(ThemeManager.Theme theme, UiScale scale) {
            appearance.setActiveTheme(theme);
            appearance.setUiScale(scale);
            root.applyCss();
            root.layout();
        }

        /** @return what each probed token and the transport bar resolve to in the window's theme now; FX thread */
        Palette palette() {
            Map<String, Color> tokens = new HashMap<>();
            probes.forEach((token, probe) -> tokens.put(token,
                    colour(probe.getBackground().getFills().getFirst().getFill())));
            Paint gradient = bar.getBackground().getFills().getFirst().getFill();
            assertThat(gradient).as("the transport bar paints a gradient").isInstanceOf(LinearGradient.class);
            List<Stop> stops = ((LinearGradient) gradient).getStops();
            assertThat(stops).as("the transport bar's gradient, -surface-1 to -surface-bg").hasSize(2);
            return new Palette(Map.copyOf(tokens), stops.stream().map(Stop::getColor).toList());
        }
    }

    /** What the role tokens and the transport bar's two gradient stops resolve to in one theme. */
    private record Palette(Map<String, Color> tokens, List<Color> barStops) {

        Color token(String token) {
            return Objects.requireNonNull(tokens.get(token), token);
        }
    }

    /**
     * The badge's look: its state, the theme, its size and insets, its text
     * colour, fills, border and background outsets, its opacity, its effect
     * and its words.
     */
    private record Look(State state, ThemeManager.Theme theme, double width, double height, Insets insets,
                        Paint text, List<BackgroundFill> fills, Border border, Insets outsets, double opacity,
                        Effect effect, String words) {
    }

    /** The unlocked badge in one theme, at rest and flashing. */
    private record Phases(Palette palette, Look rest, Look flash) {
    }

    /** What the running flash may touch on the badge, and whether the flash pseudo-class is on. */
    private record FlashStep(boolean flashing, double opacity, Effect effect, String style, Paint text,
                             List<BackgroundFill> fills, Insets padding, Border border) {
    }

    /** Each look, and each theme's palette. */
    private record Shown(Map<ThemeManager.Theme, Palette> palettes, List<Look> looks) {

        Look in(State state, ThemeManager.Theme theme) {
            return looks.stream().filter(look -> look.state() == state && look.theme() == theme).findFirst()
                    .orElseThrow(() -> new AssertionError("no look of " + state + " in " + theme));
        }
    }

    /** The flashing badge at one UI scale: its layers, insets, its last fill's insets, its words and height. */
    private record Ring(UiScale scale, int layers, Insets insets, Insets fill, Bounds words, double height) {
    }

    /**
     * The engine as the badge reads it, on the engine's contract: opening a
     * stream publishes a new {@link OpenedStream} (the next serial, its
     * device and rate) before the stream reads as open, and closing keeps it;
     * a start that fails is an open followed by a close, and no record is ever
     * put back. A read about a device answers that device's own reading when
     * {@link #byDevice} holds one and {@link #reading} otherwise, and keeps
     * what it was asked about.
     */
    private static final class FakeClock implements ClockStatusIndicator.StreamClock {
        static final String OPEN_OR_CONFIGURED = "the open or configured device";

        volatile boolean lost;
        volatile RuntimeException failure;
        volatile Error error;
        volatile Optional<ClockReading> reading = Optional.empty();
        volatile Optional<ClockLockStatus> status = Optional.empty();
        final Map<DeviceId, ClockReading> byDevice = new ConcurrentHashMap<>();
        /** Runs once, right after the badge's next read of the engine's open level or last opened stream. */
        volatile Runnable afterTheNextEngineRead;
        /** Runs once, right after the badge's next read of the loss or of the lock status, whichever comes first. */
        volatile Runnable afterTheNextLossOrStatusRead;
        final List<String> askedAbout = Collections.synchronizedList(new ArrayList<>());
        final List<DeviceId> devicesAsked = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean open;
        private volatile OpenedStream last;
        private long serial;

        /** A stream opens on the device the reading names, at its rate. */
        void openStream() {
            ClockReading now = reading.orElseThrow(() -> new AssertionError("fixture: no reading to open on"));
            openStream(now.device(), now.sampleRate());
        }

        synchronized void openStream(DeviceId device, int rate) {
            last = new OpenedStream(++serial, device, rate);
            open = true;
        }

        void closeStream() {
            open = false;
        }

        @Override
        public boolean streamOpen() {
            boolean now = open;
            afterARead();
            return now;
        }

        @Override
        public Optional<OpenedStream> lastOpened() {
            Optional<OpenedStream> now = Optional.ofNullable(last);
            afterARead();
            return now;
        }

        private void afterARead() {
            Runnable once = afterTheNextEngineRead;
            if (once != null) {
                afterTheNextEngineRead = null;
                once.run();
            }
        }

        @Override
        public boolean deviceLost() {
            boolean now = lost;
            afterALossOrStatusRead();
            return now;
        }

        @Override
        public Optional<ClockLockStatus> lockStatus() {
            Optional<ClockLockStatus> now = open ? status : Optional.empty();
            afterALossOrStatusRead();
            return now;
        }

        private void afterALossOrStatusRead() {
            Runnable once = afterTheNextLossOrStatusRead;
            if (once != null) {
                afterTheNextLossOrStatusRead = null;
                once.run();
            }
        }

        @Override
        public Optional<ClockReading> read() {
            askedAbout.add(OPEN_OR_CONFIGURED);
            return answer();
        }

        @Override
        public Optional<ClockReading> read(DeviceId asked) {
            askedAbout.add(asked.name());
            devicesAsked.add(asked);
            Optional<ClockReading> answer = answer();
            ClockReading own = byDevice.get(asked);
            return own != null ? Optional.of(own) : answer;
        }

        private Optional<ClockReading> answer() {
            RuntimeException thrown = failure;
            if (thrown != null) {
                throw thrown;
            }
            Error died = error;
            if (died != null) {
                throw died;
            }
            return reading;
        }
    }

    /**
     * The badge's reads, run when the test says; counts how many were asked
     * for and keeps what escaped a read, as a thread's uncaught-exception
     * handler would.
     */
    private static final class ManualReads implements Executor {
        final AtomicInteger requested = new AtomicInteger();
        final List<Throwable> escaped = Collections.synchronizedList(new ArrayList<>());
        private final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable read) {
            requested.incrementAndGet();
            queued.add(read);
        }

        void runAll() {
            for (Runnable read = queued.poll(); read != null; read = queued.poll()) {
                try {
                    read.run();
                } catch (Throwable uncaught) {
                    escaped.add(uncaught);
                }
            }
        }
    }

    private static final class RecordingMarks implements TakeClockMarks {
        final List<String> marks = Collections.synchronizedList(new ArrayList<>());
        volatile TakeRecording recording = TakeRecording.NONE;
        /** Whether an audio take, when one records, holds an unlock mark after an unlock is handed to it. */
        volatile boolean takeHoldsTheUnlock = true;

        @Override
        public TakeRecording takeRecording() {
            return recording;
        }

        @Override
        public boolean lockChanged(boolean locked, long evidenceNanos) {
            marks.add((locked ? "locked@" : "unlocked@") + evidenceNanos);
            return !locked && recording == TakeRecording.AUDIO && takeHoldsTheUnlock;
        }

        @Override
        public boolean takeStartsUnlocked() {
            marks.add(UNLOCKED_AT_THE_START);
            return recording == TakeRecording.AUDIO && takeHoldsTheUnlock;
        }
    }

    /** Preferences held in memory, so the test's Reduce Motion never reaches the user's own. */
    private static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, MemoryPreferences> children = new HashMap<>();

        MemoryPreferences() {
            this(null, "");
        }

        private MemoryPreferences(MemoryPreferences parent, String name) {
            super(parent, name);
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            values.clear();
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(String[]::new);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, child -> new MemoryPreferences(this, child));
        }

        @Override
        protected void syncSpi() {
        }

        @Override
        protected void flushSpi() {
        }
    }
}
