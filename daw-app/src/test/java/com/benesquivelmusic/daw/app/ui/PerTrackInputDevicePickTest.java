package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleArmCommand;
import com.benesquivelmusic.daw.core.audio.AudioDeviceManager;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A per-track input-device pick changes only the track's own input identity.
 * Both production pick paths — the strip I/O label's double-click and "Add
 * Audio Track" — are driven through their real result consumers, with only
 * the modal dialog's answer scripted: neither touches the session input, so
 * neither starts an engine reconfiguration that could race the arm guard. An
 * armed track whose device changes is re-validated against the provision
 * installed now, and a further pick while that check is in flight supersedes
 * it: the arm intent survives and only the latest device is validated. A
 * single-device backend (ASIO) refuses a non-active driver with an error
 * naming the track and the device. A provision swap that lands while a
 * validation is in flight re-validates the pending arm instead of dropping
 * it. Arming a second device is accepted only when the backend reports it
 * shares the first device's hardware clock; otherwise the arm is refused by
 * name. A track that carries only a pre-identity device position arms on the
 * session input, as earlier versions recorded it; the dialog offers the hinted
 * row only as a suggestion to confirm, and confirming stores an identity and
 * clears the hint.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PerTrackInputDevicePickTest {

    private static final com.benesquivelmusic.daw.core.audio.AudioFormat FORMAT =
            new com.benesquivelmusic.daw.core.audio.AudioFormat(48_000, 2, 16, 256);

    private static AudioDeviceInfo device(int index, String name, String hostApi) {
        return new AudioDeviceInfo(index, name, hostApi, 8, 2, 48_000.0,
                List.of(SampleRate.HZ_48000), 0.0, 0.0);
    }

    @Test
    void dialogPreselectsTheTracksOwnDeviceAndFallsBackToTheSessionInput() {
        AudioDeviceInfo session = device(0, "Session In", "WASAPI");
        AudioDeviceInfo own = device(1, "Own In", "WASAPI");
        AudioDeviceManager.Enumeration listing =
                new AudioDeviceManager.Enumeration(Optional.of("Test"), List.of(session, own));
        StubSessionInputSelection selection = new StubSessionInputSelection(session.qualifiedName());
        Track track = new Track("Vox", TrackType.AUDIO);

        assertThat(TrackStripController.preselectedInputIndex(track, selection, listing))
                .as("no input of its own: the session input")
                .isEqualTo(session.index());

        track.setInputDevice(Optional.of(new DeviceId("Other", own.qualifiedName())));
        assertThat(TrackStripController.preselectedInputIndex(track, selection, listing))
                .as("an identity of another backend does not claim the same-labelled row")
                .isEqualTo(session.index());

        track.setInputDevice(Optional.of(new DeviceId("Test", own.qualifiedName())));
        assertThat(TrackStripController.preselectedInputIndex(track, selection, listing))
                .as("the track's own device wins over the session input")
                .isEqualTo(own.index());
    }

    @Test
    void stripDoubleClickPickRevalidatesTheArmedTrackAndLeavesSessionAndEngineAlone() throws Exception {
        AudioDeviceInfo first = device(0, "Interface A", "Test");
        AudioDeviceInfo second = device(1, "Interface B", "Test");
        ScriptedBackend backend = new ScriptedBackend("Test", true, List.of(first, second));
        Rig rig = ArrangementStripFixture.onFx(() -> new Rig(backend, DeviceId.defaultFor(backend.name()), "Session In [Test]"));
        try {
            armAndAwait(rig);
            StreamingProvision installed = rig.provision();

            backend.hold();
            ArrangementStripFixture.onFx(() -> {
                rig.doubleClickPick(second);
                assertThat(rig.offered).as("the dialog was offered the backend's listing")
                        .containsExactly(List.of(first, second));
                assertThat(rig.track.getInputDevice())
                        .contains(new DeviceId(backend.name(), second.qualifiedName()));
                assertThat(rig.fixture.statusBarLabel.getText()).contains("Vox", second.qualifiedName());
                assertThat(rig.track.isArmed()).as("disarmed until the new device is validated").isFalse();
            });
            assertThat(backend.entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(rig.provision()).as("the pick started no engine reconfiguration").isSameAs(installed);
            backend.release();

            await(() -> ArrangementStripFixture.onFx(rig.track::isArmed));
            assertThat(rig.errors).isEmpty();
            assertThat(rig.provision()).isSameAs(installed);
            assertThat(rig.fixture.sessionInputSelection.currentDeviceName())
                    .as("a per-track pick never rewrites the session input").isEqualTo("Session In [Test]");
            ArrangementStripFixture.onFx(() ->
                    assertThat(rig.ioLabel().getTooltip().getText()).isEqualTo("Input: " + second.qualifiedName()));
        } finally {
            backend.release();
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void aHintOnlyTrackArmsOnTheSessionInputAndTheDialogSuggestsTheHintedRowToConfirm() throws Exception {
        AudioDeviceInfo first = device(0, "Interface A", "Test");
        AudioDeviceInfo hinted = device(1, "Interface B", "Test");
        ScriptedBackend backend = new ScriptedBackend("Test", true, List.of(first, hinted));
        Rig rig = ArrangementStripFixture.onFx(() -> new Rig(backend, DeviceId.defaultFor(backend.name()), first.qualifiedName()));
        try {
            // Opened from a project saved before stable identities: only a device position survives.
            // Earlier versions recorded such a track from the session input, and so does this one.
            ArrangementStripFixture.onFx(() -> rig.track.setLegacyInputDeviceIndexHint(hinted.index()));
            armAndAwait(rig);

            ArrangementStripFixture.onFx(() -> rig.doubleClickPick(hinted));
            assertThat(rig.preselected).as("the dialog suggests the hinted row, not the session input's")
                    .containsExactly(hinted.index());
            ArrangementStripFixture.onFx(() -> {
                assertThat(rig.track.getInputDevice()).contains(new DeviceId(backend.name(), hinted.qualifiedName()));
                assertThat(rig.track.getLegacyInputDeviceIndexHint()).isEqualTo(Track.NO_INPUT_DEVICE);
            });
            await(() -> ArrangementStripFixture.onFx(rig.track::isArmed));
            assertThat(rig.errors).isEmpty();
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void addAudioTrackStampsThePickAsTheNewTracksIdentityAndLeavesSessionAndEngineAlone() throws Exception {
        AudioDeviceInfo session = device(0, "Session In", "Test");
        AudioDeviceInfo picked = device(1, "Interface B", "Test");
        ScriptedBackend backend = new ScriptedBackend("Test", true, List.of(session, picked));
        Rig rig = ArrangementStripFixture.onFx(() -> new Rig(backend, DeviceId.defaultFor(backend.name()),
                session.qualifiedName()));
        try {
            StreamingProvision installed = rig.provision();
            AtomicReference<Integer> preselected = new AtomicReference<>();
            Track added = ArrangementStripFixture.onFx(() -> {
                ArrangementStripFixture fixture = rig.fixture;
                TrackCreationController creation = new TrackCreationController(
                        new TrackCreationController.Deps(
                                () -> fixture.project, () -> fixture.undoManager, () -> fixture.controller,
                                () -> fixture.mixerView, () -> fixture.trackListPanel,
                                () -> { }, () -> { }, (_, _) -> { }, (_, _) -> { },
                                fixture.sessionInputSelection),
                        new AudioDeviceManager(fixture.audioEngine),
                        (devices, preselectedIndex) -> {
                            preselected.set(preselectedIndex);
                            return Optional.of(picked);
                        });
                creation.onAddAudioTrack();
                return fixture.project.getTracks().getLast();
            });

            assertThat(added.getName()).isEqualTo("Audio 1");
            assertThat(added.getInputDevice()).contains(new DeviceId(backend.name(), picked.qualifiedName()));
            assertThat(preselected.get()).as("a new track preselects the session input").isEqualTo(session.index());
            assertThat(rig.fixture.sessionInputSelection.currentDeviceName())
                    .as("adding a track never rewrites the session input").isEqualTo(session.qualifiedName());
            assertThat(rig.provision()).as("adding a track started no engine reconfiguration").isSameAs(installed);
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void aSecondPickWhileTheFirstCheckIsHeldSupersedesItAndArmsForTheLatestDevice() throws Exception {
        AudioDeviceInfo first = device(0, "Interface A", "Test");
        AudioDeviceInfo latest = device(1, "Interface B", "Test");
        AudioDeviceInfo ghost = device(7, "Ghost In", "Test");   // not enumerated: its own check would refuse
        ScriptedBackend backend = new ScriptedBackend("Test", true, List.of(first, latest));
        Rig rig = ArrangementStripFixture.onFx(() -> new Rig(backend, DeviceId.defaultFor(backend.name()), ""));
        try {
            ArrangementStripFixture.onFx(() ->
                    rig.track.setInputDevice(Optional.of(new DeviceId(backend.name(), first.qualifiedName()))));
            armAndAwait(rig);

            backend.hold();
            ArrangementStripFixture.onFx(() -> rig.doubleClickPick(ghost));
            assertThat(backend.entered.await(5, TimeUnit.SECONDS)).as("the ghost check is in flight").isTrue();
            ArrangementStripFixture.onFx(() -> rig.doubleClickPick(latest));
            backend.release();

            await(() -> ArrangementStripFixture.onFx(rig.track::isArmed));
            assertThat(rig.errors).as("the superseded check's refusal is stale and never shown").isEmpty();
            ArrangementStripFixture.onFx(() -> assertThat(rig.track.getInputDevice())
                    .contains(new DeviceId(backend.name(), latest.qualifiedName())));
        } finally {
            backend.release();
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void aSecondPickWhileTheFirstCheckIsHeldIsRefusedByNameAndTheStaleSuccessNeverArms() throws Exception {
        AudioDeviceInfo first = device(0, "Interface A", "Test");
        AudioDeviceInfo valid = device(1, "Interface B", "Test");
        AudioDeviceInfo ghost = device(7, "Ghost In", "Test");   // not enumerated: refused
        ScriptedBackend backend = new ScriptedBackend("Test", true, List.of(first, valid));
        Rig rig = ArrangementStripFixture.onFx(() -> new Rig(backend, DeviceId.defaultFor(backend.name()), ""));
        AtomicInteger armsAfterPicks = new AtomicInteger();
        try {
            ArrangementStripFixture.onFx(() ->
                    rig.track.setInputDevice(Optional.of(new DeviceId(backend.name(), first.qualifiedName()))));
            armAndAwait(rig);

            backend.hold();
            ArrangementStripFixture.onFx(() -> {
                rig.doubleClickPick(valid);
                rig.track.addChangeListener(kind -> {
                    if (kind == Track.ChangeKind.ARM && rig.track.isArmed()) armsAfterPicks.incrementAndGet();
                });
            });
            assertThat(backend.entered.await(5, TimeUnit.SECONDS)).as("the valid device's check is in flight").isTrue();
            ArrangementStripFixture.onFx(() -> rig.doubleClickPick(ghost));
            backend.release();

            await(() -> !rig.errors.isEmpty());
            assertThat(rig.errors).singleElement().asString().contains("Vox", ghost.qualifiedName());
            ArrangementStripFixture.onFx(() -> {
                assertThat(rig.track.isArmed()).isFalse();
                assertThat(rig.track.getInputDevice())
                        .contains(new DeviceId(backend.name(), ghost.qualifiedName()));
            });
            assertThat(armsAfterPicks).as("the stale success for the superseded device never armed").hasValue(0);
        } finally {
            backend.release();
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void singleDeviceAsioRefusesAPickOfANonActiveDriverNamingTrackAndDevice() throws Exception {
        AudioDeviceInfo active = device(0, "Driver A", "ASIO");
        AudioDeviceInfo other = device(1, "Driver B", "ASIO");
        ScriptedBackend backend = new ScriptedBackend("ASIO", false, List.of(active, other));
        DeviceId activeId = new DeviceId(backend.name(), active.qualifiedName());
        Rig rig = ArrangementStripFixture.onFx(() -> new Rig(backend, activeId, active.qualifiedName()));
        try {
            ArrangementStripFixture.onFx(() ->
                    rig.track.setInputDevice(Optional.of(activeId)));
            armAndAwait(rig);
            StreamingProvision installed = rig.provision();

            ArrangementStripFixture.onFx(() -> rig.doubleClickPick(other));

            await(() -> !rig.errors.isEmpty());
            assertThat(rig.errors).singleElement().asString()
                    .contains("Vox", other.qualifiedName(), active.qualifiedName());
            ArrangementStripFixture.onFx(() -> assertThat(rig.track.isArmed()).isFalse());
            assertThat(rig.provision()).isSameAs(installed);
            assertThat(rig.fixture.sessionInputSelection.currentDeviceName()).isEqualTo(active.qualifiedName());
        } finally {
            backend.release();
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void aProvisionSwapDuringArmValidationRevalidatesAgainstTheReplacementInsteadOfDroppingTheArm()
            throws Exception {
        ScriptedBackend outgoing = new ScriptedBackend("Test", true, List.of(device(0, "Interface A", "Test")));
        ScriptedBackend replacement = new ScriptedBackend("Other", true, List.of(device(0, "Interface C", "Other")));
        Rig rig = ArrangementStripFixture.onFx(() -> new Rig(outgoing, DeviceId.defaultFor(outgoing.name()), ""));
        try {
            outgoing.hold();
            ArrangementStripFixture.onFx(() -> rig.wiring.commandSink().accept(new ToggleArmCommand(rig.track, true)));
            assertThat(outgoing.entered.await(5, TimeUnit.SECONDS)).isTrue();
            StreamingProvision swapped = new StreamingProvision(replacement.name(),
                    List.of(new BackendStreamRung(replacement, DeviceId.defaultFor(replacement.name()))));
            ArrangementStripFixture.onFx(() -> rig.fixture.audioEngine.setStreamingProvision(swapped));
            outgoing.release();

            await(() -> ArrangementStripFixture.onFx(rig.track::isArmed));
            assertThat(replacement.listings.get()).as("re-validated against the replacement").isPositive();
            assertThat(rig.errors).isEmpty();
        } finally {
            outgoing.release();
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void armingASecondDeviceWithoutASharedClockIsRefusedNamingTrackAndDevice() throws Exception {
        AudioDeviceInfo first = device(0, "Interface A", "Test");
        AudioDeviceInfo second = device(1, "Interface B", "Test");
        ScriptedBackend backend = new ScriptedBackend("Test", true, List.of(first, second));
        Rig rig = ArrangementStripFixture.onFx(() -> new Rig(backend, DeviceId.defaultFor(backend.name()), ""));
        try {
            Track room = armOnSeparateDevices(rig, backend, first, second);

            await(() -> !rig.errors.isEmpty());
            assertThat(rig.errors).singleElement().asString()
                    .contains("Room", second.qualifiedName(), "not clock-synchronized", first.qualifiedName());
            ArrangementStripFixture.onFx(() -> {
                assertThat(room.isArmed()).isFalse();
                assertThat(rig.track.isArmed()).as("the accepted arm is untouched").isTrue();
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void armingASecondDeviceOnTheSameDeclaredClockIsAccepted() throws Exception {
        AudioDeviceInfo first = device(0, "Interface A", "Test");
        AudioDeviceInfo second = device(1, "Interface B", "Test");
        ScriptedBackend backend = new ScriptedBackend("Test", true, List.of(first, second));
        backend.sharedClock = true;
        Rig rig = ArrangementStripFixture.onFx(() -> new Rig(backend, DeviceId.defaultFor(backend.name()), ""));
        try {
            Track room = armOnSeparateDevices(rig, backend, first, second);

            await(() -> ArrangementStripFixture.onFx(room::isArmed));
            assertThat(rig.errors).isEmpty();
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    /** Arms the rig track on {@code first}, then requests an arm of a new "Room" track on {@code second}. */
    private static Track armOnSeparateDevices(Rig rig, ScriptedBackend backend, AudioDeviceInfo first,
                                              AudioDeviceInfo second) throws Exception {
        ArrangementStripFixture.onFx(() ->
                rig.track.setInputDevice(Optional.of(new DeviceId(backend.name(), first.qualifiedName()))));
        armAndAwait(rig);
        Track room = ArrangementStripFixture.onFx(() -> {
            Track created = rig.fixture.project.createAudioTrack("Room");
            created.setInputDevice(Optional.of(new DeviceId(backend.name(), second.qualifiedName())));
            return created;
        });
        ArrangementStripFixture.onFx(() -> rig.wiring.commandSink().accept(new ToggleArmCommand(room, true)));
        return room;
    }

    private static void armAndAwait(Rig rig) throws Exception {
        ArrangementStripFixture.onFx(() -> rig.wiring.commandSink().accept(new ToggleArmCommand(rig.track, true)));
        await(() -> ArrangementStripFixture.onFx(rig.track::isArmed));
        assertThat(rig.errors).isEmpty();
    }

    private interface CheckedCondition { boolean getAsBoolean() throws Exception; }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /**
     * The strip controller with the rig track's strip built, plus the guarded wiring on the same
     * project and engine. The input-port dialog is replaced by a queue of scripted answers that also
     * records each listing it was offered. FX thread only.
     */
    private static final class Rig {
        final ArrangementStripFixture fixture;
        final Track track;
        final TrackControlWiring wiring;
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<List<AudioDeviceInfo>> offered = new CopyOnWriteArrayList<>();
        final List<Integer> preselected = new CopyOnWriteArrayList<>();
        private final Deque<AudioDeviceInfo> answers = new ArrayDeque<>();
        private final javafx.scene.layout.HBox strip;

        Rig(AudioBackend backend, DeviceId rungDevice, String sessionDevice) {
            DawProject project = new DawProject("Pick", FORMAT);
            track = project.createAudioTrack("Vox");
            fixture = new ArrangementStripFixture(project, false, new StubSessionInputSelection(sessionDevice));
            fixture.audioEngine.setStreamingProvision(new StreamingProvision(backend.name(),
                    List.of(new BackendStreamRung(backend, rungDevice))));
            wiring = TrackControlWiring.standalone(project, fixture.dispatcher, null, fixture.audioEngine,
                    message -> errors.add(Objects.requireNonNull(message)));
            fixture.controller.setInputPortChooserForTest((devices, preselectedIndex) -> {
                offered.add(List.copyOf(devices));
                preselected.add(preselectedIndex);
                return Optional.ofNullable(answers.poll());
            });
            strip = fixture.addStrip(track);
        }

        javafx.scene.control.Label ioLabel() {
            return ArrangementStripFixture.controlsOf(strip).ioLabel();
        }

        /** The user double-clicks the strip's I/O label and confirms {@code device} in the dialog. */
        void doubleClickPick(AudioDeviceInfo device) {
            answers.add(device);
            doubleClick(ioLabel());
        }

        StreamingProvision provision() {
            return fixture.audioEngine.getStreamingProvision();
        }

        void close() {
            wiring.dispose();
            fixture.close();
            fixture.audioEngine.shutdown();
        }
    }

    private static void doubleClick(Node node) {
        node.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 2,
                false, false, false, false, true, false, false, false, false, true, null));
    }

    /**
     * A backend with a scripted device list whose enumeration can be held to pin a validation in
     * flight. Only off-FX enumerations are held: the pick gesture's own listing runs on the FX
     * thread and must complete for the dialog to open.
     */
    private static final class ScriptedBackend implements AudioBackend {
        final MockAudioBackend delegate = new MockAudioBackend();
        final String name;
        final boolean multipleInputs;
        final List<AudioDeviceInfo> devices;
        final AtomicInteger listings = new AtomicInteger();
        final AtomicReference<CountDownLatch> gate = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);
        /** Declares every device of this backend to run from one word clock; off by default, like the SDK. */
        volatile boolean sharedClock;

        ScriptedBackend(String name, boolean multipleInputs, List<AudioDeviceInfo> devices) {
            this.name = name;
            this.multipleInputs = multipleInputs;
            this.devices = List.copyOf(devices);
        }

        void hold() { gate.set(new CountDownLatch(1)); }

        void release() {
            CountDownLatch held = gate.getAndSet(null);
            if (held != null) held.countDown();
        }

        @Override public String name() { return name; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public boolean supportsMultipleInputDevices() { return multipleInputs; }
        @Override public boolean sharesClockDomain(DeviceId first, DeviceId second) {
            return sharedClock || AudioBackend.super.sharesClockDomain(first, second);
        }

        @Override public List<AudioDeviceInfo> listDevices() {
            listings.incrementAndGet();
            CountDownLatch held = Platform.isFxApplicationThread() ? null : gate.get();
            if (held != null) {
                entered.countDown();
                try {
                    if (!held.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return devices;
        }

        @Override public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format, int frames) {
            delegate.open(device, format, frames);
        }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public Flow.Publisher<AudioBlock> inputBlocks() { return delegate.inputBlocks(); }
        @Override public void sink(AudioBlock block) { delegate.sink(block); }
        @Override public void close() { delegate.close(); }
    }
}
