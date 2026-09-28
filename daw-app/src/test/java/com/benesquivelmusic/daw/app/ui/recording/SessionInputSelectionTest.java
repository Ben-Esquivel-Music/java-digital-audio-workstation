package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Story 322 — the ONE session-level input selection (Audio Engine Wiring
 * Design Book §5.6 "Per-track input device"): {@code select} persists the
 * device and applies it to the engine with the chosen device name, and a
 * selection made while an earlier apply is still running wins over it (PR
 * #977 review — latest-selection-wins, a superseded failure never shown);
 * {@code mismatches} lists only the ARMED tracks whose explicit per-track
 * choice disagrees with the session device — including one whose persisted
 * index no longer resolves to any enumerated device, reported as unavailable,
 * while an empty enumeration compares nothing; the warning names the tracks
 * and both devices.
 */
class SessionInputSelectionTest {

    /** Bound on every worker join so a lost lock or a stuck latch fails instead of hanging. */
    private static final Duration JOIN = Duration.ofSeconds(10);

    private static final AudioDeviceInfo MIC = AudioDeviceInfo.unprobed(0, "Mic In", "ASIO");
    private static final AudioDeviceInfo USB = AudioDeviceInfo.unprobed(1, "USB In", "WASAPI");
    private static final AudioDeviceInfo LINE = AudioDeviceInfo.unprobed(2, "Line In", "WASAPI");
    private static final List<AudioDeviceInfo> DEVICES = List.of(MIC, USB, LINE);
    /** The same endpoint as {@link #USB} enumerated under another host API (a new index). */
    private static final AudioDeviceInfo USB_MME = AudioDeviceInfo.unprobed(3, "USB In", "MME");
    private static final List<AudioDeviceInfo> SAME_NAME_UNDER_TWO_HOST_APIS = List.of(MIC, USB, LINE, USB_MME);

    private Preferences prefs;

    @AfterEach
    void dropPreferences() throws BackingStoreException {
        if (prefs != null) {
            prefs.removeNode();
        }
    }

    private SettingsModel newSettings() {
        prefs = Preferences.userRoot().node("sessionInputSelectionTest_" + System.nanoTime());
        return new SettingsModel(prefs);
    }

    @Test
    void selectPersistsTheDeviceAndAppliesTheConfigurationWithItsName() throws Exception {
        SettingsModel settings = newSettings();
        settings.setAudioBackend("ASIO");
        settings.setAudioOutputDevice("Main Out [ASIO]");
        RecordingController controller = new RecordingController();
        List<String> notifications = new CopyOnWriteArrayList<>();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, message, _, _) -> notifications.add(message), () -> { });

        selection.selectAndApply(USB).orElseThrow().join();

        assertThat(settings.getAudioInputDevice()).isEqualTo("USB In [WASAPI]");
        assertThat(selection.currentDeviceName()).isEqualTo("USB In [WASAPI]");
        assertThat(selection.isSessionDevice(USB)).isTrue();
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(USB.index());
        AudioEngineController.Request request = controller.lastRequest.get();
        assertThat(request).isNotNull();
        assertThat(request.inputDeviceName()).isEqualTo("USB In [WASAPI]");
        assertThat(request.backendName()).isEqualTo("ASIO");
        assertThat(request.outputDeviceName()).isEqualTo("Main Out [ASIO]");
        assertThat(request.bufferFrames()).isEqualTo(settings.getBufferSize());
        assertThat(controller.applyThread.get()).isNotSameAs(Thread.currentThread());
        assertThat(notifications).isEmpty();
    }

    @Test
    void selectUsesTheProvisionedBackendWhenNoneIsPersistedAndReportsAnApplyFailure() throws Exception {
        SettingsModel settings = newSettings();
        settings.setAudioBackend("");
        RecordingController controller = new RecordingController();
        controller.failure = new IllegalStateException("device is busy");
        List<String> notifications = new CopyOnWriteArrayList<>();
        AtomicReference<NotificationLevel> level = new AtomicReference<>();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (l, message, _, _) -> {
                    level.set(l);
                    notifications.add(message);
                }, () -> { });

        selection.selectAndApply(MIC).orElseThrow().join();

        assertThat(controller.lastRequest.get().backendName()).isEqualTo(RecordingController.PROVISIONED);
        assertThat(settings.getAudioInputDevice()).as("the choice is persisted even when the apply fails")
                .isEqualTo("Mic In [ASIO]");
        assertThat(level.get()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notifications).singleElement().asString()
                .contains("Mic In [ASIO]").contains("device is busy");
    }

    @Test
    void aLegacyBareSessionNameUpgradesItselfOnTheFirstConfirmAndOnlyTheSameDeviceIsThenANoOp()
            throws Exception {
        // Story 322 fix round 2 (R2-1): the no-op gate is the EXACT qualified
        // name, not isSessionDevice's bare-name tolerance. A legacy bare
        // "USB In" is therefore persisted as "USB In [WASAPI]" and applied
        // ONCE on the first confirm (what every confirm did before the
        // round-1 gate), and only the second confirm of the very same device
        // is the no-op that must not stop the pump and reopen the stream.
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("USB In");   // a legacy bare name that still names USB
        RecordingController controller = new RecordingController();
        List<String> notifications = new CopyOnWriteArrayList<>();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, message, _, _) -> notifications.add(message), () -> { });
        assertThat(selection.isSessionDevice(USB)).as("the bare tolerance still preselects USB").isTrue();

        selection.selectAndApply(USB).orElseThrow().join();

        assertThat(settings.getAudioInputDevice()).as("the bare name upgraded itself").isEqualTo("USB In [WASAPI]");
        assertThat(controller.applies.get()).as("exactly one apply").isEqualTo(1);
        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("USB In [WASAPI]");

        assertThat(selection.selectAndApply(USB)).as("no worker for the very same device").isEmpty();

        assertThat(settings.getAudioInputDevice()).as("settings unchanged").isEqualTo("USB In [WASAPI]");
        assertThat(controller.applies.get()).as("no second apply").isEqualTo(1);
        assertThat(notifications).isEmpty();
    }

    @Test
    void aSameNamedDeviceUnderAnotherHostApiIsPersistedAndAppliedOverALegacyBareSessionName()
            throws Exception {
        // Story 322 fix round 2 (R2-1): PortAudio enumerates "USB In" under
        // MME and under WASAPI, and a persisted bare "USB In" names BOTH by
        // isSessionDevice's tolerance. Picking the [MME] endpoint must persist
        // "USB In [MME]" and apply it — under the round-1 tolerant gate it did
        // neither while the caller still wrote the per-track index, the
        // status bar and the dirty flag: the silent ignore §5.6 forbids.
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("USB In");
        RecordingController controller = new RecordingController();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, _, _, _) -> { }, () -> { });
        assertThat(selection.isSessionDevice(USB)).isTrue();
        assertThat(selection.isSessionDevice(USB_MME)).as("the bare name names both endpoints").isTrue();

        selection.selectAndApply(USB_MME).orElseThrow().join();

        assertThat(settings.getAudioInputDevice()).isEqualTo("USB In [MME]");
        assertThat(controller.applies.get()).as("one apply, targeting the pick").isEqualTo(1);
        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("USB In [MME]");
        assertThat(selection.isSessionDevice(USB_MME)).isTrue();
        assertThat(selection.isSessionDevice(USB)).as("the session name is exact from now on").isFalse();
        assertThat(selection.selectedIndexIn(SAME_NAME_UNDER_TWO_HOST_APIS)).isEqualTo(USB_MME.index());
    }

    @Test
    void selectingTheSessionInputByItsQualifiedNameIsANoOpWhileADifferentDeviceStillApplies()
            throws Exception {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(USB.qualifiedName());
        RecordingController controller = new RecordingController();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, _, _, _) -> { }, () -> { });

        assertThat(selection.selectAndApply(USB)).isEmpty();
        assertThat(controller.lastRequest.get()).isNull();

        selection.selectAndApply(MIC).orElseThrow().join();

        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("Mic In [ASIO]");
        assertThat(settings.getAudioInputDevice()).isEqualTo("Mic In [ASIO]");
    }

    @Test
    void aSelectionMadeWhileAnEarlierApplyIsRunningWinsAndTheOneItSupersededNeverApplies() throws Exception {
        // PR #977 review: an ASIO reopen takes seconds — long enough for a
        // second (and third) input-port dialog to be confirmed. A's apply is
        // parked INSIDE the engine (A holds the apply lock) while B and C are
        // selected and park on the lock; whichever of B/C wins it afterwards,
        // B is superseded and C is the device the engine ends on. No sleeps,
        // no assumption about which waiter the lock hands over to.
        SettingsModel settings = newSettings();
        RecordingController controller = new RecordingController();
        controller.parkFirstApply();
        List<String> notifications = new CopyOnWriteArrayList<>();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, message, _, _) -> notifications.add(message), () -> { });

        Thread a = selection.selectAndApply(USB).orElseThrow();
        assertThat(controller.entered.await(JOIN.toMillis(), TimeUnit.MILLISECONDS))
                .as("A is inside applyConfiguration").isTrue();
        Thread b = selection.selectAndApply(MIC).orElseThrow();
        Thread c = selection.selectAndApply(LINE).orElseThrow();
        assertThat(settings.getAudioInputDevice()).as("the persist is synchronous").isEqualTo("Line In [WASAPI]");
        controller.release.countDown();

        assertThat(a.join(JOIN)).isTrue();
        assertThat(b.join(JOIN)).isTrue();
        assertThat(c.join(JOIN)).isTrue();
        assertThat(controller.applies.get()).as("A and C applied; B was superseded").isEqualTo(2);
        assertThat(controller.requests).extracting(AudioEngineController.Request::inputDeviceName)
                .as("B's device never reached the engine")
                .containsExactly("USB In [WASAPI]", "Line In [WASAPI]");
        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("Line In [WASAPI]");
        assertThat(settings.getAudioInputDevice()).isEqualTo("Line In [WASAPI]");
        assertThat(notifications).isEmpty();
    }

    @Test
    void noApplyEntersTheEngineWhileAnEarlierOneHoldsTheLockSoTheEngineEndsOnTheLatestSelection()
            throws Exception {
        // PR #977 verification rounds 1 and 2: the test above records the
        // engine's ENTRY order and asserts applies == 2. With the apply lock
        // deleted that is a RACE, not a detector — whether B's worker reads its
        // own generation or C's depends on B's virtual thread against this
        // thread's C bump (one fault run stayed green, another failed with
        // `expected: 2 but was: 3`), and it releases A right after selecting C
        // without letting the workers settle, so unlocked even the completion
        // order is a race there. This test lets B and C settle before releasing
        // A, which makes the unlocked outcome determinate — B and C both enter
        // and complete while A is parked and A COMPLETES last, the very outcome
        // the review described (engine on A, settings naming C) — and pins the
        // lock itself: while A is parked inside the engine every later worker
        // settles parked on the lock (a parked virtual thread reports
        // WAITING), never inside the engine, and the engine's last COMPLETED
        // apply is C. Unlocked, B and C run to TERMINATED, applies is 3 and
        // the entered-count assertion fails before any order is compared.
        // Round 2 added the wait for B to park BEFORE C is selected: against a
        // generation check hoisted above the lock, B would otherwise sometimes
        // read C's bump first and skip legitimately; with B already parked the
        // hoisted check has passed, B applies MIC after release whichever way
        // the non-fair lock hands off, and `completed` names MIC.
        SettingsModel settings = newSettings();
        RecordingController controller = new RecordingController();
        controller.parkFirstApply();
        List<String> notifications = new CopyOnWriteArrayList<>();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, message, _, _) -> notifications.add(message), () -> { });

        Thread a = selection.selectAndApply(USB).orElseThrow();
        assertThat(controller.entered.await(JOIN.toMillis(), TimeUnit.MILLISECONDS))
                .as("A is inside applyConfiguration").isTrue();
        Thread b = selection.selectAndApply(MIC).orElseThrow();
        awaitParkedOrFinished(b); // B is parked on the lock before C exists — see the comment above
        Thread c = selection.selectAndApply(LINE).orElseThrow();
        awaitParkedOrFinished(b, c);

        assertThat(controller.applies.get())
                .as("A holds the apply lock: no other apply entered the engine while A was parked")
                .isEqualTo(1);
        assertThat(controller.completed).as("nothing completed while A was parked").isEmpty();
        controller.release.countDown();

        assertThat(a.join(JOIN)).isTrue();
        assertThat(b.join(JOIN)).isTrue();
        assertThat(c.join(JOIN)).isTrue();
        assertThat(controller.completed)
                .as("completion order is what the engine ends on: A, then C — never B, never C before A")
                .containsExactly("USB In [WASAPI]", "Line In [WASAPI]");
        assertThat(settings.getAudioInputDevice()).isEqualTo("Line In [WASAPI]");
        assertThat(notifications).isEmpty();
    }

    /**
     * Polls — bounded by {@link #JOIN}, spinning rather than sleeping — until
     * every worker is parked (a virtual thread parked on the apply lock reports
     * {@code WAITING}) or has finished: the settled state from which "who
     * entered the engine while A held the lock" can be read deterministically —
     * and, called on B alone while the lock is in place, the point after which
     * a later selection is guaranteed to find B already at the lock (without
     * the lock B has simply finished by then).
     */
    private static void awaitParkedOrFinished(Thread... workers) {
        long deadline = System.nanoTime() + JOIN.toNanos();
        while (!allParkedOrFinished(workers)) {
            if (System.nanoTime() - deadline > 0) {
                StringBuilder states = new StringBuilder();
                for (Thread worker : workers) {
                    states.append(worker.getName()).append('=').append(worker.getState()).append(' ');
                }
                fail("workers never settled within " + JOIN + ": " + states);
            }
            Thread.onSpinWait();
        }
    }

    private static boolean allParkedOrFinished(Thread... workers) {
        for (Thread worker : workers) {
            boolean settled = switch (worker.getState()) {
                case WAITING, TIMED_WAITING, TERMINATED -> true;
                case NEW, RUNNABLE, BLOCKED -> false;
            };
            if (!settled) {
                return false;
            }
        }
        return true;
    }

    @Test
    void aSupersededApplyFailureIsLoggedButNeverShown() throws Exception {
        // PR #977 review: A is parked inside the engine, B is selected, then
        // A's apply fails. A's failure is stale — B's own worker reports B's
        // outcome — so no notification may reach the user for A.
        SettingsModel settings = newSettings();
        RecordingController controller = new RecordingController();
        controller.parkFirstApply();
        List<String> notifications = new CopyOnWriteArrayList<>();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, message, _, _) -> notifications.add(message), () -> { });

        Thread a = selection.selectAndApply(USB).orElseThrow();
        assertThat(controller.entered.await(JOIN.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        Thread b = selection.selectAndApply(MIC).orElseThrow();
        controller.failParkedApplyOnRelease = new IllegalStateException("ASIO reopen failed");
        controller.release.countDown();

        assertThat(a.join(JOIN)).isTrue();
        assertThat(b.join(JOIN)).isTrue();
        assertThat(controller.applies.get()).as("A (failed) and B (applied)").isEqualTo(2);
        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("Mic In [ASIO]");
        assertThat(settings.getAudioInputDevice()).isEqualTo("Mic In [ASIO]");
        assertThat(notifications).as("A's failure is stale; nothing is shown for it").isEmpty();
    }

    @Test
    void mismatchesListsOnlyArmedTracksWhoseChoiceDisagreesWithTheSessionDevice() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(MIC.qualifiedName());
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });

        Track agrees = armed("Agrees", MIC.index());
        Track conflicts = armed("Conflicts", USB.index());
        Track unarmedConflict = new Track("Unarmed", TrackType.AUDIO);
        unarmedConflict.setInputDeviceIndex(USB.index());
        Track noChoice = armed("No choice", Track.NO_INPUT_DEVICE);
        Track dangling = armed("Dangling", 42);   // an index no enumerated device has (PR #977: a mismatch)

        assertThat(selection.mismatches(
                List.of(agrees, conflicts, unarmedConflict, noChoice, dangling), DEVICES))
                .containsExactly(conflicts, dangling);
        assertThat(selection.mismatchWarning(List.of(agrees, noChoice), DEVICES)).isEmpty();
    }

    @Test
    void anArmedTrackWhoseIndexNoLongerResolvesIsReportedAsAnUnavailableDevice() {
        // PR #977 review: Track.inputDeviceIndex is persisted (ProjectSerializer
        // "input-device"), so a project reopened after an interface was
        // unplugged or the backend changed holds indices that resolve to
        // nothing. Skipping them let that armed track record from the session
        // input with no warning — the silent ignore §5.6 forbids.
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(MIC.qualifiedName());
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });
        Track drums = armed("Drums", 42);

        assertThat(selection.mismatches(List.of(armed("Agrees", MIC.index()), drums), DEVICES))
                .containsExactly(drums);
        assertThat(selection.mismatchWarning(List.of(drums), DEVICES)).hasValue(
                "Recording uses the session input 'Mic In [ASIO]'; "
                        + "track(s) Drums chose an input device that is no longer available "
                        + "— multi-device capture is story 326");
    }

    @Test
    void anUnarmedTrackWhoseIndexNoLongerResolvesIsSkipped() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(MIC.qualifiedName());
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });
        Track unarmed = new Track("Unarmed", TrackType.AUDIO);
        unarmed.setInputDeviceIndex(42);

        assertThat(selection.mismatches(List.of(unarmed), DEVICES)).isEmpty();
        assertThat(selection.mismatchWarning(List.of(unarmed), DEVICES)).isEmpty();
    }

    @Test
    void anEmptyEnumerationMeansNothingToCompareAndReportsNothing() {
        // Both production callers (TrackStripController / TransportController
        // .listAudioDevices) pass List.of() when there is no backend or the
        // enumeration failed. Under the literal "every unresolved explicit
        // index is a mismatch" rule that would flag EVERY armed track with an
        // explicit index as unavailable — a false warning — so an empty list
        // compares nothing, even with a session device set.
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(MIC.qualifiedName());
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });
        List<Track> tracks = List.of(armed("Vox", USB.index()), armed("Drums", 42));

        assertThat(selection.mismatches(tracks, List.of())).isEmpty();
        assertThat(selection.mismatchWarning(tracks, List.of())).isEmpty();
        assertThat(selection.mismatches(tracks, DEVICES))
                .as("the guard is the empty enumeration, not the tracks: over a real list both are reported")
                .hasSize(2);
    }

    @Test
    void theWarningOrdersResolvedAndUnavailableGroupsAsFirstSeen() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(MIC.qualifiedName());
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });

        assertThat(selection.mismatchWarning(
                List.of(armed("Vox", USB.index()), armed("Drums", 42), armed("Keys", 99), armed("Guitar", USB.index())),
                DEVICES))
                .as("one unavailable group for every unresolved index, placed where its first track appears")
                .hasValue("Recording uses the session input 'Mic In [ASIO]'; "
                        + "track(s) Vox, Guitar chose 'USB In [WASAPI]'; "
                        + "track(s) Drums, Keys chose an input device that is no longer available "
                        + "— multi-device capture is story 326");
        assertThat(selection.mismatchWarning(
                List.of(armed("Drums", 42), armed("Vox", USB.index())), DEVICES))
                .hasValue("Recording uses the session input 'Mic In [ASIO]'; "
                        + "track(s) Drums chose an input device that is no longer available; "
                        + "track(s) Vox chose 'USB In [WASAPI]' "
                        + "— multi-device capture is story 326");
    }

    @Test
    void mismatchWarningNamesTheTracksAndBothDevices() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(MIC.qualifiedName());
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });

        Optional<String> warning = selection.mismatchWarning(
                List.of(armed("Vox", USB.index()), armed("Guitar", USB.index()), armed("Keys", LINE.index())),
                DEVICES);

        assertThat(warning).isPresent();
        assertThat(warning.get()).isEqualTo(
                "Recording uses the session input 'Mic In [ASIO]'; "
                        + "track(s) Vox, Guitar chose 'USB In [WASAPI]'; "
                        + "track(s) Keys chose 'Line In [WASAPI]' "
                        + "— multi-device capture is story 326");
    }

    @Test
    void blankSessionDeviceIsTheDefaultAndEveryExplicitChoiceDisagreesWithIt() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("");
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });

        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(Track.NO_INPUT_DEVICE);
        assertThat(selection.mismatchWarning(List.of(armed("Vox", MIC.index())), DEVICES))
                .hasValueSatisfying(text -> assertThat(text)
                        .startsWith("Recording uses the session input '<default>'; track(s) Vox chose 'Mic In [ASIO]'"));
    }

    @Test
    void legacyBareNameStillNamesTheSessionDevice() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("Mic In");   // saved before qualified names existed
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });

        assertThat(selection.isSessionDevice(MIC)).isTrue();
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(MIC.index());
        assertThat(selection.mismatches(List.of(armed("Vox", MIC.index())), DEVICES)).isEmpty();
    }

    private static Track armed(String name, int inputDeviceIndex) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setArmed(true);
        track.setInputDeviceIndex(inputDeviceIndex);
        return track;
    }

    /**
     * Records every {@link Request} at ENTRY and, separately, the device name
     * of every apply that COMPLETED ({@link #completed}: what the engine ends
     * on — entry order alone cannot tell a serialised apply from a concurrent
     * one); optionally fails every apply; optionally parks the FIRST apply
     * inside the engine ({@link #parkFirstApply()}) so a test can select again
     * while a worker holds the apply lock, then {@link #release} it —
     * optionally into a failure of that first apply only.
     */
    private static final class RecordingController implements AudioEngineController {
        static final String PROVISIONED = "Provisioned Backend";
        final AtomicReference<Request> lastRequest = new AtomicReference<>();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        /** Input device names in COMPLETION order — appended after the park, only when the apply did not throw. */
        final List<String> completed = new CopyOnWriteArrayList<>();
        final AtomicReference<Thread> applyThread = new AtomicReference<>();
        final AtomicInteger applies = new AtomicInteger();
        /** Thrown by every apply while set. */
        volatile RuntimeException failure;
        /** Counted down once the parked first apply is inside {@link #applyConfiguration}. */
        final CountDownLatch entered = new CountDownLatch(1);
        /** Counted down by the test to let the parked first apply return. */
        final CountDownLatch release = new CountDownLatch(1);
        /** Thrown by the parked first apply once released — by it alone. */
        volatile RuntimeException failParkedApplyOnRelease;
        private volatile boolean parkFirstApply;

        void parkFirstApply() {
            parkFirstApply = true;
        }

        @Override public String getActiveBackendName() { return BACKEND_NONE; }
        @Override public String getProvisionedBackendName() { return PROVISIONED; }
        @Override public List<String> getAvailableBackendNames() { return List.of(PROVISIONED); }
        @Override public List<AudioDeviceInfo> listDevices() { return DEVICES; }
        @Override public List<AudioDeviceInfo> listDevices(String backendName) { return DEVICES; }

        @Override
        public BufferSizeRange bufferSizeRange(String backendName, String outputDeviceName) {
            return BufferSizeRange.singleton(256);
        }

        @Override
        public Set<Integer> supportedSampleRates(String backendName, String outputDeviceName) {
            return Set.of(48_000);
        }

        @Override public double getCpuLoadPercent() { return 0; }

        @Override
        public void applyConfiguration(Request request) {
            applyThread.set(Thread.currentThread());
            lastRequest.set(request);
            requests.add(request);
            if (applies.incrementAndGet() == 1 && parkFirstApply) {
                entered.countDown();
                awaitRelease();
                if (failParkedApplyOnRelease != null) {
                    throw failParkedApplyOnRelease;
                }
            }
            if (failure != null) {
                throw failure;
            }
            completed.add(request.inputDeviceName());
        }

        private void awaitRelease() {
            try {
                if (!release.await(JOIN.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("the parked apply was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while parked inside the engine", interrupted);
            }
        }

        @Override public void playTestTone(String outputDeviceName) { }
    }
}
