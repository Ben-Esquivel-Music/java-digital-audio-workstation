package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHubView;
import com.benesquivelmusic.daw.app.ui.plugin.PluginEditorSession;
import com.benesquivelmusic.daw.app.ui.vm.ChannelVM;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlBinder;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.app.ui.vm.TrackVM;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.plugin.ConvolutionReverbPlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.event.TrackEvent;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;

import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review's regression (PR #989 review 5711460812; story 333, rulings R2
 * and R12 #4): while a close waits on disk work the window is inert, and an
 * assistive technology's action — which reaches a control with no input
 * event, so the {@link WindowInputFence} never sees it — edits nothing.
 *
 * <p>The rig is the {@link ExitFixture}'s real close on a real main window,
 * with the window's real controls mounted in it before the close starts: a
 * lane of Bass from a real {@link TrackStripController}, a skinned
 * {@link MixerView} strip, the pre/post-roll box of a real
 * {@link TransportController}, an {@link InsertEffectRack} row and a
 * Convolution Reverb insert's editor session, its plug-in's own body included,
 * the lane and the strip bound through the production track-control wiring,
 * and all five reading one gate composed as
 * {@code MainController.projectActionsAllowed()} is. Every action is
 * {@code executeAccessibleAction} on the FX thread. While the close waits,
 * each action changes nothing in the model, publishes nothing, adds no
 * history entry and leaves its control showing the model; a Save, a named
 * snapshot or the Project Hub asked then does nothing but its FINE refusal;
 * and a window shown then takes no click or key. That holds before the
 * progress surface, after it, and after the application-stop fallback, whose
 * window stays inert for good. Save and close then saves the project as it
 * was; after Cancel the same actions on the same controls edit again (the
 * javafx skill §14: accessible activation stays live outside the fence).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class AccessibleActionsWhileACloseWaitsEditNothingTest {

    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final String REFUSAL = "closes the open project";
    /** The Convolution Reverb's Mix, by parameter id. */
    private static final int REVERB_MIX = 5;
    private static final Logger LIFECYCLE_LOG = Logger.getLogger(ProjectLifecycleController.class.getName());

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private EventBus previousBus;
    private Rig rig;

    @BeforeEach
    void songAWithBassMountedOnTheMainWindow() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.save.save();
        fixture.save.settle();
        fixture.edit("Bass");
        previousBus = EventBusPublisher.getDefault();
        EventBusPublisher.setDefault(fixture.bus);
        rig = onFx(() -> new Rig(fixture));
    }

    @AfterEach
    void close() throws Exception {
        try {
            if (rig != null) {
                runOnFx(rig::close);
            }
        } finally {
            EventBusPublisher.setDefault(previousBus);
            fixture.close();
        }
    }

    // ── the variants ─────────────────────────────────────────────────────────

    /** Variant (a): the 500 ms before any surface, then Cancel and the same actions edit. */
    @Test
    void everyActionBeforeTheSurfaceEditsNothingAndCancelLeavesThemLive() throws Exception {
        List<ProjectHubView> hubs = new CopyOnWriteArrayList<>();
        runOnFx(() -> fixture.lifecycle().setProjectHubPresenter(hubs::add));
        int headsBefore = fixture.save.publishes.done(SaveFlowFixture::isHead).size();
        AtomicReference<Observation> observed = new AtomicReference<>();
        fixture.save.promptAnswer = "Cancel";
        fixture.beforeTheUnsavedPrompt = () -> {
            fixture.beforeTheUnsavedPrompt = () -> { };
            observed.set(everyCase(rig, Set.of()));
        };

        fixture.closeBox();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertThat(fixture.unsavedPrompts).as("the hook returned and the prompt asked as usual").hasSize(1);
        assertNothingWasEdited(observed.get(), true);
        assertThat(hubs).as("no Hub opened while the close waited").isEmpty();

        theSameActionsEditOnceTheWindowIsLive(hubs);
        fixture.save.settle();
        assertThat(fixture.save.publishes.done(SaveFlowFixture::isHead))
                .as("nothing asked during the close saved the project").hasSize(headsBefore);
    }

    /** Variant (b): the same once the modal progress surface shows, then Cancel. */
    @Test
    void everyActionAfterTheProgressSurfaceEditsNothingAndCancelLeavesThemLive() throws Exception {
        List<ProjectHubView> hubs = new CopyOnWriteArrayList<>();
        runOnFx(() -> fixture.lifecycle().setProjectHubPresenter(hubs::add));
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        Observation observation;
        try {
            fixture.save.save();
            head.awaitHeld();
            // An edit after the held save's capture keeps the project dirty, so the prompt asks after the wait.
            runOnFx(() -> rig.project.markDirty());
            fixture.save.promptAnswer = "Cancel";
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface() != null && fixture.surface().isShowing()))
                    .as("the progress surface shows").isTrue();
            observation = onFx(() -> everyCase(rig, Set.of()));
        } finally {
            head.release();
        }
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.GUARD), "GUARD's end");

        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertNothingWasEdited(observation, true);
        theSameActionsEditOnceTheWindowIsLive(hubs);
    }

    /** Variant (c): during and after the application-stop fallback, whose window stays inert for good. */
    @Test
    void afterTheApplicationStopFallbackTheWindowEditsNothingForGood() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        CompletableFuture<Void> stopped;
        Observation whileItWaits;
        try {
            fixture.save.save();
            head.awaitHeld();
            stopped = fixture.stopTheApplication();
            fixture.await(() -> fixture.fence().isRaised() && !fixture.nestedLoopsEntered.isEmpty(),
                    "the fallback waits for every writer, the window inert");
            whileItWaits = onFx(() -> everyCase(rig, Set.of("1 lane mute FIRE")));
        } finally {
            head.release();
        }
        ExitFixture.awaitStopped(stopped);

        assertNothingWasEdited(whileItWaits, false);
        assertThat(onFx(() -> fixture.fence().isRaised())).as("the fallback never lowers the fence").isTrue();
        assertThat(onFx(rig.gate::getAsBoolean)).as("so the gate says no for the rest of the process").isFalse();
        assertNothingWasEdited(onFx(() -> everyCase(rig, Set.of())), true);
    }

    /** Save and close after refused actions saves the project as it was. */
    @Test
    void saveAndCloseAfterRefusedActionsSavesTheProjectAsItWas() throws Exception {
        AtomicReference<Observation> observed = new AtomicReference<>();
        fixture.save.promptAnswer = "Save and close";
        fixture.beforeTheUnsavedPrompt = () -> {
            fixture.beforeTheUnsavedPrompt = () -> { };
            observed.set(everyCase(rig, Set.of("1 lane mute FIRE", "2 strip mute FIRE", "4 phase FIRE")));
        };

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        Observation observation = observed.get();
        assertThat(observation).as("the hook ran").isNotNull();
        assertThat(observation.failure()).isEmpty();
        assertThat(observation.cases()).hasSize(3).allSatisfy(result ->
                assertThat(result.differences()).as(result.name()).isEmpty());
        DawProject saved = new ProjectDeserializer().deserialize(
                Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8));
        Track savedBass = saved.getTracks().stream().filter(track -> track.getName().equals("Bass"))
                .findFirst().orElseThrow();
        assertThat(savedBass.isMuted()).as("the saved Bass is not muted").isFalse();
        assertThat(savedBass.isPhaseInverted()).as("nor phase-inverted").isFalse();
        assertThat(onFx(() -> rig.bass.isMuted() || rig.channel.isMuted() || rig.bass.isPhaseInverted()))
                .as("and the live model never changed, so nothing was silently lost").isFalse();
    }

    /**
     * Case 8 (ruling R-6.5): the wiring {@code MainController} builds for the
     * window — not a seam — refuses a mute while the close waits, and mutes
     * once the close was cancelled. The controller is partial (it is never
     * FXML-loaded in tests): its fields are set reflectively and its private
     * {@code rebuildTrackControlWiring()} invoked.
     */
    @Test
    void theWiringTheWindowBuildsRefusesAMuteWhileTheCloseWaits() throws Exception {
        AtomicReference<TrackControlWiring> built = new AtomicReference<>();
        AtomicReference<TrackControlBinder> binder = new AtomicReference<>();
        Button mute = onFx(() -> {
            MainController main = new MainController();
            setField(main, "project", rig.project);
            setField(main, "fxDispatcher", fixture.save.dispatcher);
            setField(main, "notificationBar", new NotificationBar());
            setField(main, "audioEngine", rig.engine);
            setField(main, "exitCoordinator", fixture.coordinator());
            setField(main, "projectLifecycleController", fixture.lifecycle());
            invoke(main, "rebuildTrackControlWiring");
            TrackControlWiring wiring = main.trackControlWiring();
            built.set(wiring);
            TrackVM trackVm = wiring.registry().trackVm(UUID.fromString(rig.bass.getId()));
            ChannelVM channelVm = wiring.registry().channelVm(rig.channel.getId());
            Button button = new Button("M");
            TrackControlBinder bound = new TrackControlBinder(rig.bass, trackVm, rig.channel, channelVm,
                    wiring.commandSink());
            bound.bindMute(button);
            binder.set(bound);
            return button;
        });
        try {
            AtomicReference<Boolean> mutedInTheHook = new AtomicReference<>();
            fixture.save.promptAnswer = "Cancel";
            fixture.beforeTheUnsavedPrompt = () -> {
                fixture.beforeTheUnsavedPrompt = () -> { };
                mute.executeAccessibleAction(AccessibleAction.FIRE);
                mutedInTheHook.set(rig.bass.isMuted() || rig.channel.isMuted());
            };

            fixture.closeBox();
            fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

            assertThat(mutedInTheHook.get()).as("the production wiring refused the mute while the close waited")
                    .isFalse();
            runOnFx(() -> mute.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> rig.bass.isMuted() && rig.channel.isMuted()))
                    .as("after Cancel the same action mutes").isTrue();
        } finally {
            runOnFx(() -> {
                if (binder.get() != null) {
                    binder.get().dispose();
                }
                if (built.get() != null) {
                    built.get().dispose();
                }
            });
        }
    }

    // ── the cases ────────────────────────────────────────────────────────────

    /**
     * Runs every case (ruling R-6.2, ruling-2 F7) — or only those named in
     * {@code only}, when it is not empty — on the FX thread inside the close,
     * and returns what each changed. Never throws: a failure is captured, so
     * the close's own hook is never broken by an assertion.
     */
    private Observation everyCase(Rig rig, Set<String> only) {
        List<CaseResult> results = new ArrayList<>();
        List<DawEvent> published = new CopyOnWriteArrayList<>();
        long revision = rig.project.getEditRevision();
        int history = rig.undo.undoSize();
        Optional<Throwable> failure = Optional.empty();
        RefusalLog refusals = new RefusalLog();
        EventBus.Subscription observer = fixture.bus.onPublication(DawEvent.class, published::add);
        try {
            for (NamedCase named : cases(rig)) {
                if (only.isEmpty() || only.contains(named.name())) {
                    results.add(named.run(rig, refusals));
                }
            }
        } catch (Throwable thrown) {
            failure = Optional.of(thrown);
        } finally {
            observer.close();
            refusals.close();
        }
        return new Observation(results, List.copyOf(published), rig.project.getEditRevision() - revision,
                rig.undo.undoSize() - history, failure);
    }

    private List<NamedCase> cases(Rig rig) {
        return List.of(
                new NamedCase("1 lane mute FIRE", 0, true,
                        () -> rig.laneControls.muteBtn().executeAccessibleAction(AccessibleAction.FIRE)),
                new NamedCase("2 strip mute FIRE", 0, true,
                        () -> rig.skin.muteButton().executeAccessibleAction(AccessibleAction.FIRE)),
                new NamedCase("3 lane volume SET_VALUE and INCREMENT", 0, true, () -> {
                    rig.laneControls.volumeSlider().executeAccessibleAction(AccessibleAction.SET_VALUE, 0.25);
                    rig.laneControls.volumeSlider().executeAccessibleAction(AccessibleAction.INCREMENT);
                }),
                new NamedCase("4 phase FIRE", 0, true, () -> rig.phase.executeAccessibleAction(AccessibleAction.FIRE)),
                // Two cases, not one: the spinner's refused commit syncs the toggle too, which would hide a toggle
                // left flipped by its own press.
                new NamedCase("5 pre-roll toggle FIRE", 0, true,
                        () -> rig.transport.preRollToggleForTest().executeAccessibleAction(AccessibleAction.FIRE)),
                new NamedCase("5 pre-roll spinner INCREMENT", 0, true,
                        () -> rig.transport.preRollSpinnerForTest().executeAccessibleAction(AccessibleAction.INCREMENT)),
                new NamedCase("6 rack bypass FIRE", 0, true,
                        () -> rig.bypass.executeAccessibleAction(AccessibleAction.FIRE)),
                new NamedCase("7 Save asked outside the menus", 1, true, () -> fixture.lifecycle().onSaveProject()),
                new NamedCase("7b Project Hub asked outside the menus", 1, true,
                        () -> fixture.lifecycle().onRecentProjects()),
                new NamedCase("7c named snapshot asked outside the menus", 1, true,
                        () -> fixture.lifecycle().onCreateNamedSnapshot()),
                // Case 9 shows its own two windows — the late lane and the combo's list — and checks them itself.
                new NamedCase("9 a window shown while the close waits", 0, false,
                        () -> aWindowShownWhileTheCloseWaits(rig)),
                // The plug-in's own body: its slider writes the slot's store, which the session's gate refuses.
                new NamedCase("10 plug-in body Mix SET_VALUE", 0, true,
                        () -> rig.reverbMix.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8)));
    }

    /**
     * One case: the facts before and after {@code act}, every one that
     * changed, a FINE refusal count other than {@code refusalsExpected}, and,
     * when {@code countsWindows}, every window shown — counted by a listener
     * that records each window added and hides it on a later turn, never
     * inside its own add notification (ruling-4 H2), and that is removed
     * before what it recorded is hidden.
     */
    private record NamedCase(String name, int refusalsExpected, boolean countsWindows, Runnable act) {
        NamedCase {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(act, "act must not be null");
        }

        CaseResult run(Rig rig, RefusalLog refusals) {
            Map<String, Object> before = rig.facts();
            int refusedBefore = refusals.count();
            List<Window> shown = new ArrayList<>();
            ListChangeListener<Window> windowCount = change -> {
                while (change.next()) {
                    for (Window window : change.getAddedSubList()) {
                        shown.add(window);
                        javafx.application.Platform.runLater(window::hide);
                    }
                }
            };
            if (countsWindows) {
                Window.getWindows().addListener(windowCount);
            }
            List<String> differences = new ArrayList<>();
            try {
                act.run();
            } finally {
                Window.getWindows().removeListener(windowCount);
                for (Window window : shown) {
                    if (window.isShowing()) {
                        window.hide();
                    }
                }
            }
            Map<String, Object> after = rig.facts();
            for (Map.Entry<String, Object> fact : before.entrySet()) {
                Object now = after.get(fact.getKey());
                if (!Objects.equals(fact.getValue(), now)) {
                    differences.add(fact.getKey() + ": " + fact.getValue() + " -> " + now);
                }
            }
            int refused = refusals.count() - refusedBefore;
            if (refused != refusalsExpected) {
                differences.add("FINE refusals: " + refused + ", expected " + refusalsExpected);
            }
            for (Window window : shown) {
                differences.add("a window was shown: " + window);
            }
            differences.addAll(rig.lateWindowDifferences());
            return new CaseResult(name, List.copyOf(differences));
        }
    }

    /**
     * Case 9 (ruling-2 F7): a window shown while the close waits — owned by
     * the main window, holding a second lane of Bass from the same
     * controller — takes no click and no Space at its mute button; and the
     * Bass column's input-routing combo, expanded by an assistive
     * technology's action, takes no Down and no Enter. The late window stays
     * showing for the positive control.
     */
    private static void aWindowShownWhileTheCloseWaits(Rig rig) {
        rig.showALateLane();
        WindowInputFenceTest.click(rig.lateMute);
        WindowInputFenceTest.type(rig.lateMute, KeyCode.SPACE);
        Object route = rig.bass.getInputRouting();
        Object selection = rig.inputRouting.getSelectionModel().getSelectedItem();
        rig.inputRouting.executeAccessibleAction(AccessibleAction.EXPAND);
        rig.comboShowingAfterTheKeys = false;
        rig.comboActions.set(0);
        WindowInputFenceTest.type(rig.inputRouting, KeyCode.DOWN);
        WindowInputFenceTest.type(rig.inputRouting, KeyCode.ENTER);
        rig.comboShowingAfterTheKeys = rig.inputRouting.isShowing();
        rig.comboKeptItsRoute = Objects.equals(route, rig.bass.getInputRouting())
                && Objects.equals(selection, rig.inputRouting.getSelectionModel().getSelectedItem());
        rig.inputRouting.hide();
    }

    // ── what the cases must show ─────────────────────────────────────────────

    private static void assertNothingWasEdited(Observation observation, boolean everyCase) {
        assertThat(observation).as("the cases ran").isNotNull();
        assertThat(observation.failure()).as("no case threw").isEmpty();
        assertThat(observation.cases()).as("cases run").hasSize(everyCase ? 12 : 1);
        assertThat(observation.cases().stream().filter(result -> !result.differences().isEmpty()).toList())
                .as("every case changed nothing and left its control on the model; these did not").isEmpty();
        assertThat(observation.published()).as("nothing but the transport's own events was published")
                .allMatch(TransportEvent.class::isInstance);
        assertThat(observation.revisionDelta()).as("no edit revision").isZero();
        assertThat(observation.undoDelta()).as("no history entry").isZero();
    }

    /**
     * After Cancel (ruling R-6.4): the fence is down, and the same
     * accessible action on the same control edits — the model changes, the
     * control shows it and the bus and the journal carry it.
     */
    private void theSameActionsEditOnceTheWindowIsLive(List<ProjectHubView> hubs) throws Exception {
        assertThat(onFx(() -> fixture.fence().isRaised())).as("the fence is lowered").isFalse();
        List<DawEvent> published = new CopyOnWriteArrayList<>();
        EventBus.Subscription observer = fixture.bus.onPublication(DawEvent.class, published::add);
        try {
            runOnFx(() -> rig.skin.muteButton().executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> rig.bass.isMuted() && rig.channel.isMuted() && rig.strip.isMuted()
                    && rig.skin.muteButton().isSelected() && isActive(rig.laneControls.muteBtn())))
                    .as("case 2: the strip's M mutes Bass on both surfaces").isTrue();
            runOnFx(() -> rig.laneControls.muteBtn().executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> rig.bass.isMuted() || rig.channel.isMuted()
                    || rig.skin.muteButton().isSelected() || isActive(rig.laneControls.muteBtn())))
                    .as("case 1: the lane's mute unmutes it").isFalse();
            assertThat(published.stream().filter(MixerEvent.MuteChanged.class::isInstance)
                    .map(event -> ((MixerEvent.MuteChanged) event).muted()).toList())
                    .as("both mute changes were published, in order").containsExactly(true, false);
            assertThat(published).anyMatch(TrackEvent.Muted.class::isInstance);

            runOnFx(() -> rig.laneControls.volumeSlider().executeAccessibleAction(AccessibleAction.SET_VALUE, 0.25));
            assertThat(onFx(() -> rig.channel.getVolume())).as("case 3").isEqualTo(0.25);
            assertThat(onFx(() -> rig.laneControls.volumeSlider().getValue())).isEqualTo(0.25);

            runOnFx(() -> rig.phase.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> rig.bass.isPhaseInverted() && isActive(rig.phase))).as("case 4").isTrue();

            runOnFx(() -> rig.transport.preRollToggleForTest().executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> rig.project.getTransport().getPreRollPostRoll().preBars() > 0
                    && rig.project.getTransport().getPreRollPostRoll().enabled()
                    && rig.transport.preRollToggleForTest().isSelected())).as("case 5").isTrue();
            assertThat(onFx(rig.transportStatus::getText)).isEqualTo("Pre-roll: ON");

            runOnFx(() -> rig.bypass.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> rig.slot.isBypassed() && !rig.bypass.isSelected()))
                    .as("case 6: the same node bypasses the slot").isTrue();

            runOnFx(() -> fixture.lifecycle().onRecentProjects());
            assertThat(hubs).as("case 7b: the Hub opens").hasSize(1);

            runOnFx(() -> WindowInputFenceTest.click(rig.lateMute));
            assertThat(onFx(() -> rig.lateActions.get())).as("case 9: the late window's mute takes the click")
                    .isEqualTo(1);
            assertThat(onFx(() -> rig.bass.isMuted())).as("and mutes Bass").isTrue();

            runOnFx(() -> rig.reverbMix.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8));
            assertThat(onFx(() -> rig.reverbSlot.getParameterStore().valueById(REVERB_MIX)))
                    .as("case 10: the body's Mix writes the store").isEqualTo(0.8);
            assertThat(onFx(() -> rig.reverbSlot.getParameterStore().snapshotPendingUiValues()))
                    .as("for the audio side and the next save").containsEntry(REVERB_MIX, 0.8);
            assertThat(onFx(rig.reverbMix::getValue)).isEqualTo(0.8);
        } finally {
            observer.close();
        }
        fixture.journal.drainWithoutSeal(null);
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA)).stream()
                .map(JournalRecord::type).toList())
                .as("the journal recorded the edits").contains("MixerEvent.MuteChanged", "TrackEvent.Muted");
    }

    // ── the rig ──────────────────────────────────────────────────────────────

    /**
     * The window's bound controls over Song A's project, mounted in the main
     * window before the close starts. Built and used on the FX thread.
     */
    private static final class Rig {
        final DawProject project;
        final Track bass;
        final MixerChannel channel;
        final InsertSlot slot;
        final UndoManager undo;
        final AudioEngine engine;
        final BooleanSupplier gate;
        final TrackControlWiring wiring;
        final MixerView mixerView;
        final Label laneStatus = new Label();
        final Label transportStatus = new Label();
        final TrackStripController lanes;
        final TransportController transport;
        final HBox lane;
        final TrackStripController.StripControls laneControls;
        final MixerChannelStrip strip;
        final MixerChannelStripSkin skin;
        final Button phase;
        final ToggleButton bypass;
        final ComboBox<?> inputRouting;
        final ConvolutionReverbPlugin reverb;
        final InsertSlot reverbSlot;
        final PluginEditorSession reverbEditor;
        final Slider reverbMix;
        final AtomicInteger comboActions = new AtomicInteger();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final AtomicInteger lateInputs = new AtomicInteger();
        final AtomicInteger lateActions = new AtomicInteger();
        final Stage mainWindow;
        Stage late;
        Button lateMute;
        boolean comboShowingAfterTheKeys = true;
        boolean comboKeptItsRoute = true;

        Rig(ExitFixture fixture) {
            mainWindow = fixture.stage();
            project = fixture.save.project.get();
            bass = project.getTracks().stream().filter(track -> track.getName().equals("Bass"))
                    .findFirst().orElseThrow();
            channel = project.getMixerChannelForTrack(bass);
            // Below unity, so the slider's INCREMENT moves it.
            channel.setVolume(0.5);
            slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, project.getFormat().channels(),
                    project.getFormat().sampleRate());
            channel.addInsert(slot);
            // A plug-in whose editor is its own Panel body, after the compressor so the rack's first row stays the
            // compressor's.
            reverb = new ConvolutionReverbPlugin();
            reverb.initialize(new PluginContext() {
                @Override public double getSampleRate() { return project.getFormat().sampleRate(); }
                @Override public int getBufferSize() { return project.getFormat().bufferSize(); }
                @Override public void log(String message) { }
            });
            reverbSlot = InsertEffectFactory.createSlotFromPlugin(reverb).orElseThrow();
            channel.addInsert(reverbSlot);
            undo = fixture.save.undoManager.get();
            engine = new AudioEngine(project.getFormat());
            // Composed as MainController.projectActionsAllowed() is.
            gate = () -> !fixture.lifecycle().isProjectInstallationPending()
                    && !fixture.coordinator().isWindowInert();
            wiring = TrackControlWiring.standalone(project, fixture.save.dispatcher, null, engine, errors::add, gate);
            mixerView = new MixerView(project, undo, fixture.save.dispatcher, () -> wiring, gate);
            NotificationBar notifications = new NotificationBar();
            notifications.setAnimated(false);
            VBox trackList = new VBox(new Label("TRACKS"));
            lanes = new TrackStripController(project, undo, engine, mixerView, notifications, laneStatus,
                    trackList, new BorderPane(), new ClipboardManager(), new SelectionModel(),
                    () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { },
                    () -> false, ZoomLevel::new, () -> null, () -> wiring, () -> "", gate);
            lane = lanes.addTrackToUI(bass);
            laneControls = TrackStripController.controlsOf(lane);
            transport = new TransportController(project, engine, undo, notifications, new Label(), transportStatus,
                    new Label(), new Button(), new Button(), () -> false, () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF, track -> { }, () -> true, () -> RoundTripLatency.UNKNOWN,
                    fixture.save.dispatcher);
            transport.setProjectActionsAllowed(gate);
            reverbEditor = PluginEditorSession.open(channel, reverbSlot, new PluginEditorSession.Deps(
                    () -> project.getFormat().sampleRate(), null, null, null, gate));
            StackPane root = (StackPane) fixture.stage().getScene().getRoot();
            root.getChildren().add(new VBox(trackList, transport.createPreRollPostRollControls(), mixerView,
                    reverbEditor.frame()));
            root.applyCss();
            root.layout();
            reverbMix = sliderLabelled(reverbEditor.frame().getBody(), "Mix");

            strip = mixerView.getTrackStrips().get(project.getTracks().indexOf(bass)).strip();
            assertThat(strip.getChannelName()).as("the strip under test is Bass's").isEqualTo("Bass");
            skin = (MixerChannelStripSkin) strip.getSkin();
            phase = (Button) lane.lookup(".track-phase-button");
            InsertEffectRack rack = nodes(mixerView).filter(InsertEffectRack.class::isInstance)
                    .map(InsertEffectRack.class::cast).filter(found -> found.getChannel() == channel)
                    .findFirst().orElseThrow();
            bypass = nodes(rack).filter(ToggleButton.class::isInstance).map(ToggleButton.class::cast)
                    .findFirst().orElseThrow();
            inputRouting = inputRoutingComboOf(strip);
            inputRouting.addEventHandler(ActionEvent.ACTION, _ -> comboActions.incrementAndGet());
            assertThat(laneControls.muteBtn()).isNotNull();
            assertThat(phase).isNotNull();
            assertThat(skin).isNotNull();
        }

        /** Every fact a case could change, model and control, with the controls checked against the model. */
        Map<String, Object> facts() {
            PreRollPostRoll prpr = project.getTransport().getPreRollPostRoll();
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("Bass muted", bass.isMuted());
            facts.put("Bass's channel muted", channel.isMuted());
            facts.put("Bass's volume", channel.getVolume());
            facts.put("Bass phase-inverted", bass.isPhaseInverted());
            facts.put("pre/post-roll", prpr);
            facts.put("insert bypassed", slot.isBypassed());
            facts.put("lane mute shows the model", isActive(laneControls.muteBtn()) == bass.isMuted());
            facts.put("strip M shows the model", skin.muteButton().isSelected() == bass.isMuted()
                    && strip.isMuted() == bass.isMuted());
            facts.put("lane slider shows the model", laneControls.volumeSlider().getValue() == channel.getVolume());
            facts.put("phase shows the model", isActive(phase) == bass.isPhaseInverted());
            facts.put("pre-roll toggle shows the model", transport.preRollToggleForTest().isSelected()
                    == (prpr.enabled() && prpr.preBars() > 0));
            facts.put("pre-roll spinner shows the model",
                    Objects.equals(transport.preRollSpinnerForTest().getValue(), prpr.preBars()));
            facts.put("bypass shows the slot", bypass.isSelected() == !slot.isBypassed());
            facts.put("lane status", laneStatus.getText());
            facts.put("transport status", transportStatus.getText());
            facts.put("Bass's input routing", bass.getInputRouting());
            facts.put("windows showing", Window.getWindows().size() - lateWindows().size());
            PluginParameterStore reverbStore = reverbSlot.getParameterStore();
            facts.put("reverb Mix in its store", reverbStore.valueById(REVERB_MIX));
            facts.put("reverb writes pending for the audio side and a save", reverbStore.snapshotPendingUiValues());
            facts.put("reverb Mix in its plug-in", reverb.getProcessor().getMix());
            facts.put("reverb Mix slider shows the store", reverbMix.getValue() == reverbStore.valueById(REVERB_MIX));
            return facts;
        }

        /** Case 9's window, built and shown the first time a case asks for it. */
        void showALateLane() {
            if (late != null) {
                return;
            }
            HBox lateLane = lanes.addTrackToUI(bass);
            lateMute = TrackStripController.controlsOf(lateLane).muteBtn();
            lateMute.addEventHandler(ActionEvent.ACTION, _ -> lateActions.incrementAndGet());
            StackPane lateRoot = new StackPane(lateLane);
            lateRoot.addEventFilter(MouseEvent.MOUSE_PRESSED, _ -> lateInputs.incrementAndGet());
            lateRoot.addEventFilter(KeyEvent.KEY_PRESSED, _ -> lateInputs.incrementAndGet());
            late = new Stage();
            late.setTitle("A lane shown while the close waits");
            late.initOwner(mainWindow);
            late.setScene(new Scene(lateRoot, 640, 120));
            late.show();
            lateRoot.applyCss();
            lateRoot.layout();
        }

        /** The late window, once case 9 showed it. */
        List<Window> lateWindows() {
            return late == null ? List.of() : List.of(late);
        }

        /** What case 9 saw at its window and at the combo: every input it should not have taken. */
        List<String> lateWindowDifferences() {
            List<String> differences = new ArrayList<>();
            if (lateInputs.get() != 0) {
                differences.add("the late window's root saw " + lateInputs.get() + " inputs");
            }
            if (lateActions.get() != 0) {
                differences.add("the late window's mute fired " + lateActions.get() + " times");
            }
            if (comboActions.get() != 0) {
                differences.add("the input-routing combo fired " + comboActions.get() + " actions");
            }
            if (!comboShowingAfterTheKeys) {
                differences.add("Enter reached the input-routing combo's list");
            }
            if (!comboKeptItsRoute) {
                differences.add("Down or Enter changed Bass's route");
            }
            return differences;
        }

        void close() {
            if (late != null) {
                late.hide();
            }
            if (inputRouting.isShowing()) {
                inputRouting.hide();
            }
            lanes.dispose();
            mixerView.dispose();
            transport.retire();
            wiring.dispose();
            reverbEditor.dispose();
            reverb.dispose();
            engine.shutdown();
        }
    }

    /** The slider a plug-in body shows under the label {@code text}, as the Convolution Reverb lays out its sliders. */
    private static Slider sliderLabelled(Node body, String text) {
        return nodes(body).filter(VBox.class::isInstance).map(VBox.class::cast)
                .filter(box -> box.getChildren().size() == 2
                        && box.getChildren().get(0) instanceof Label label && text.equals(label.getText())
                        && box.getChildren().get(1) instanceof Slider)
                .map(box -> (Slider) box.getChildren().get(1)).findFirst().orElseThrow();
    }

    /** The input-routing combo of the mixer column that holds {@code strip}. */
    private static ComboBox<?> inputRoutingComboOf(MixerChannelStrip strip) {
        for (Parent column = strip.getParent(); column != null; column = column.getParent()) {
            List<ComboBox<?>> found = new ArrayList<>();
            nodes(column).forEach(node -> {
                if (node instanceof ComboBox<?> combo && combo.getTooltip() != null
                        && "Input routing".equals(combo.getTooltip().getText())) {
                    found.add(combo);
                }
            });
            if (found.size() == 1) {
                return found.getFirst();
            }
            if (found.size() > 1) {
                break;
            }
        }
        throw new AssertionError("no input-routing combo in the column of " + strip.getChannelName());
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream()
                        .flatMap(AccessibleActionsWhileACloseWaitsEditNothingTest::nodes))
                : Stream.of(node);
    }

    private static boolean isActive(Node node) {
        return node.getPseudoClassStates().contains(ACTIVE);
    }

    /** What the cases did, captured inside the close and asserted after it. */
    private record Observation(List<CaseResult> cases, List<DawEvent> published, long revisionDelta,
                               int undoDelta, Optional<Throwable> failure) {
        Observation {
            Objects.requireNonNull(cases, "cases must not be null");
            Objects.requireNonNull(published, "published must not be null");
            Objects.requireNonNull(failure, "failure must not be null");
        }
    }

    /** One case and every fact it changed. */
    private record CaseResult(String name, List<String> differences) {
        CaseResult {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(differences, "differences must not be null");
        }
    }

    /** The lifecycle controller's FINE refusals while a case runs; its level is restored on close. */
    private static final class RefusalLog extends Handler implements AutoCloseable {
        private final Level previous = LIFECYCLE_LOG.getLevel();
        private final AtomicInteger refusals = new AtomicInteger();

        RefusalLog() {
            setLevel(Level.ALL);
            LIFECYCLE_LOG.setLevel(Level.FINE);
            LIFECYCLE_LOG.addHandler(this);
        }

        int count() {
            return refusals.get();
        }

        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();
            if (record.getLevel() == Level.FINE && message != null && message.startsWith("Refused while ")
                    && message.endsWith(REFUSAL)) {
                refusals.incrementAndGet();
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            LIFECYCLE_LOG.removeHandler(this);
            LIFECYCLE_LOG.setLevel(previous);
        }
    }

    private static void setField(MainController target, String name, Object value) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void invoke(MainController target, String name) {
        try {
            Method method = MainController.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
