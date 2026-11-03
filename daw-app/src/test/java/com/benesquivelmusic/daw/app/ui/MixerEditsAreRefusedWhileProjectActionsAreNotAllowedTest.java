package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueSend;
import com.benesquivelmusic.daw.core.mixer.LinkMode;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.VcaGroup;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mixer's own edits read the window's edit gate (PR #989 review
 * 5711460812; rulings R-1.2, R-1.4 and R-5.1 A): a strip rename, which the
 * view records in its history; the A/B slot recall and the A/B toggle, which
 * write the channels outside the intent sink; and a VCA's mute raised
 * through the standalone wiring a view whose host wiring is gone builds for
 * itself. While the gate says no, the act on the real control (the second
 * paragraph says how each row acts) changes nothing, adds no history entry,
 * publishes nothing, and the control shows the model; once it says yes, the
 * same act edits. The mirror heal is a correctness writer, not an intent: it
 * keeps the Track mirror true while the gate says no.
 *
 * <p>The mixer's other handlers are held to the same rule: the solo-safe
 * reset and items, the A/B slot save and clear, the routing combos (and the
 * strip's captions, which read the combo's live selection), the link toggle
 * and its popover, a send's level, the cue sends, a cue bus's master, mute,
 * All Pre, remove, copy and creation, a VCA's name, gain, colour and delete,
 * and a snapshot's recall and delete. A menu item is activated as its showing
 * menu activates it; a routing combo's choice is made through its selection
 * model, and a VCA colour by the picker's value and its action event, as
 * those controls make them; a strip rename's editor is opened by a
 * double-click, and it and a VCA name are committed by their action event
 * after an assistive technology's {@code SET_TEXT}; the A/B toggle, which the
 * mixer's shortcut calls, and a cue bus's creation, which its button and menu
 * item call, are called directly; every other control is acted on by an
 * assistive technology's action.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);

    private final AtomicBoolean allowed = new AtomicBoolean(false);
    private final List<DawEvent> published = new CopyOnWriteArrayList<>();
    private EventBus previousBus;
    private DefaultEventBus bus;
    private EventBus.Subscription recording;
    private DawProject project;
    private Track vox;
    private MixerChannel channel;
    private UndoManager undo;
    private FxDispatcher dispatcher;
    private TrackControlWiring wiring;
    private MixerView mixerView;
    private Stage stage;
    private Track bass;
    private MixerChannel returnBus;
    private UUID cueBusId;

    @BeforeEach
    void aMixerBehindTheGate() throws Exception {
        previousBus = EventBusPublisher.getDefault();
        bus = new DefaultEventBus();
        recording = bus.onPublication(DawEvent.class, published::add);
        EventBusPublisher.setDefault(bus);
        project = new DawProject("Mix", FORMAT);
        vox = project.createAudioTrack("Vox");
        bass = project.createAudioTrack("Bass");
        channel = project.getMixerChannelForTrack(vox);
        project.getVcaGroupManager().createVcaGroup("Lead", List.of(channel.getId()));
        returnBus = project.getMixer().addReturnBus("Return 1");
        cueBusId = project.getCueBusManager().createCueBus("Phones", 1).id();
        undo = new UndoManager();
        dispatcher = new FxDispatcher();
        runOnFx(() -> {
            wiring = TrackControlWiring.standalone(project, dispatcher, null, allowed::get);
            mixerView = new MixerView(project, undo, dispatcher, () -> wiring, allowed::get);
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(mixerView), 1200, 700));
            stage.show();
            mixerView.applyCss();
            mixerView.layout();
        });
    }

    @AfterEach
    void close() throws Exception {
        try {
            runOnFx(() -> {
                stage.hide();
                mixerView.dispose();
                wiring.dispose();
                dispatcher.dispose();
            });
        } finally {
            recording.close();
            EventBusPublisher.setDefault(previousBus);
            bus.close();
        }
    }

    /** One mixer handler: how its refusal and its admission show. */
    record Row(String name, Act act) {
        Row {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(act, "act must not be null");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    interface Act {
        void refusedThenAdmitted(MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest test) throws Exception;
    }

    static Stream<Row> rows() {
        return Stream.of(
                new Row("strip rename", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::stripRename),
                new Row("slot recall", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::slotRecall),
                new Row("A/B toggle", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::abToggle),
                new Row("host wiring gone: VCA M FIRE",
                        MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::vcaMuteThroughTheFallback),
                new Row("theHealReSyncsTheTrackMirrorWhileInert",
                        MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::healWhileInert),
                new Row("reset solo safe", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::resetSoloSafe),
                new Row("A/B Save", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::abSave),
                new Row("A/B Clear", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::abClear),
                new Row("input routing", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::inputRouting),
                new Row("output routing", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::outputRouting),
                new Row("solo-safe (both items)",
                        MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::soloSafeItems),
                new Row("link toggle", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::linkToggle),
                new Row("link popover check box",
                        MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::linkPopoverCheckBox),
                new Row("send level SET_VALUE", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::sendLevel),
                new Row("cue send gain", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::cueSendGain),
                new Row("cue send pan", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::cueSendPan),
                new Row("cue send tap", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::cueSendTap),
                new Row("cue master", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::cueMaster),
                new Row("cue mute", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::cueMute),
                new Row("all-pre", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::allPre),
                new Row("cue remove", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::cueRemove),
                new Row("cue copy", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::cueCopy),
                new Row("create cue bus (no window opens)",
                        MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::createCueBus),
                new Row("VCA name", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::vcaName),
                new Row("VCA gain SET_VALUE", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::vcaGain),
                new Row("VCA colour", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::vcaColour),
                new Row("VCA delete", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::vcaDelete),
                new Row("snapshot recall", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::snapshotRecall),
                new Row("snapshot delete", MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::snapshotDelete));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void aMixerEditIsRefusedWhileTheGateSaysNoAndAdmittedOnceItSaysYes(Row row) throws Exception {
        row.act().refusedThenAdmitted(this);
    }

    // ── the rows ─────────────────────────────────────────────────────────────

    private void stripRename() throws Exception {
        MixerChannelStrip strip = onFx(this::voxStrip);
        MixerChannelStripSkin skin = onFx(() -> (MixerChannelStripSkin) strip.getSkin());
        runOnFx(() -> renameThroughTheInlineEditor(skin, "Lead"));
        assertThat(onFx(vox::getName)).as("refused: the name is unchanged").isEqualTo("Vox");
        assertThat(undo.undoSize()).as("no history entry, no redo stack cleared").isZero();
        assertThat(onFx(strip::getChannelName)).as("the strip snaps back to the track's name").isEqualTo("Vox");
        assertThat(onFx(() -> skin.nameLabel().getText()))
                .as("and its label shows the live name, not the refused one").isEqualTo("Vox");
        assertThat(published).as("nothing published").isEmpty();

        allowed.set(true);
        runOnFx(() -> renameThroughTheInlineEditor(skin, "Lead"));
        assertThat(onFx(vox::getName)).isEqualTo("Lead");
        assertThat(undo.undoSize()).isEqualTo(1);
    }

    /** Double-clicks the strip's name, types {@code text} into the inline editor, and presses Enter. FX thread. */
    private static void renameThroughTheInlineEditor(MixerChannelStripSkin skin, String text) {
        skin.nameLabel().fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 2,
                false, false, false, false, false, false, false, false, false, false, null));
        assertThat(skin.nameEditor().isVisible()).as("the double-click opened the inline editor").isTrue();
        skin.nameEditor().executeAccessibleAction(AccessibleAction.SET_TEXT, text);
        skin.nameEditor().fireEvent(new ActionEvent());
    }

    private void slotRecall() throws Exception {
        storeTheSlotsAndMoveTheFader();
        Button recallA = onFx(() -> button("Recall snapshot slot A"));
        runOnFx(() -> recallA.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(channel.getVolume()).as("refused: nothing recalled").isEqualTo(0.8);
        assertThat(project.getMixerSnapshotManager().getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.B);
        assertThat(undo.undoSize()).as("no history entry").isZero();
        assertThat(published).as("nothing published").isEmpty();

        allowed.set(true);
        runOnFx(() -> recallA.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(channel.getVolume()).isEqualTo(0.3);
        assertThat(project.getMixerSnapshotManager().getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.A);
    }

    private void abToggle() throws Exception {
        storeTheSlotsAndMoveTheFader();
        runOnFx(() -> mixerView.toggleAB());
        assertThat(channel.getVolume()).as("refused: nothing recalled").isEqualTo(0.8);
        assertThat(project.getMixerSnapshotManager().getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.B);
        assertThat(undo.undoSize()).as("no history entry").isZero();
        assertThat(published).as("nothing published").isEmpty();

        allowed.set(true);
        runOnFx(() -> mixerView.toggleAB());
        assertThat(channel.getVolume()).isEqualTo(0.3);
        assertThat(project.getMixerSnapshotManager().getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.A);
    }

    /** Slot A holds Vox at 0.3, slot B is active; the fader then moves to 0.8. FX thread inside. */
    private void storeTheSlotsAndMoveTheFader() throws Exception {
        runOnFx(() -> {
            MixerSnapshotManager manager = project.getMixerSnapshotManager();
            channel.setVolume(0.3);
            manager.setSlot(MixerSnapshotManager.Slot.A, MixerSnapshot.capture(project.getMixer(), "A"));
            manager.setSlot(MixerSnapshotManager.Slot.B, MixerSnapshot.capture(project.getMixer(), "B"));
            manager.setActiveSlot(MixerSnapshotManager.Slot.B);
            channel.setVolume(0.8);
        });
    }

    private void vcaMuteThroughTheFallback() throws Exception {
        MixerView orphan = onFx(() -> new MixerView(project, undo, dispatcher, () -> null, allowed::get));
        try {
            Button vcaMute = onFx(() -> nodes(orphan.getVcaStrips()).filter(Button.class::isInstance)
                    .map(Button.class::cast).filter(button -> "M".equals(button.getText()))
                    .findFirst().orElseThrow());
            assertThat(onFx(orphan::getTrackControlWiring)).as("fixture: the view owns its fallback wiring")
                    .isNotSameAs(wiring);
            runOnFx(() -> vcaMute.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> vox.isMuted() || channel.isMuted()))
                    .as("refused through the fallback wiring's gate").isFalse();
            assertThat(published).as("nothing published").isEmpty();

            allowed.set(true);
            runOnFx(() -> vcaMute.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> vox.isMuted() && channel.isMuted())).isTrue();
        } finally {
            runOnFx(orphan::dispose);
        }
    }

    private void healWhileInert() throws Exception {
        runOnFx(() -> {
            // A channel-only write, as a raw snapshot recall makes it, leaves the Track mirror behind.
            channel.setMuted(true);
            assertThat(vox.isMuted()).as("fixture: the mirror is behind").isFalse();
            undo.execute(new UndoableAction() {
                @Override public String description() { return "a history change"; }
                @Override public void execute() { }
                @Override public void undo() { }
            });
        });
        assertThat(onFx(vox::isMuted)).as("the heal writes the Track mirror while the gate says no").isTrue();
    }

    // ── the rows of the mixer's other handlers ──────────────────────────────

    private void resetSoloSafe() throws Exception {
        runOnFx(() -> channel.setSoloSafe(true));
        MenuItem reset = onFx(() -> mixerMenuItem("Reset solo safe to defaults"));
        published.clear();
        runOnFx(reset::fire);
        assertThat(onFx(channel::isSoloSafe)).as("refused: the flags are as they were").isTrue();
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(reset::fire);
        assertThat(onFx(channel::isSoloSafe)).isFalse();
    }

    private void abSave() throws Exception {
        MenuItem save = onFx(() -> slotMenuItem("Save current state to A"));
        runOnFx(save::fire);
        assertThat(onFx(() -> project.getMixerSnapshotManager().getSlot(MixerSnapshotManager.Slot.A)))
                .as("refused: slot A stays empty").isNull();
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(save::fire);
        assertThat(onFx(() -> project.getMixerSnapshotManager().getSlot(MixerSnapshotManager.Slot.A))).isNotNull();
    }

    private void abClear() throws Exception {
        runOnFx(() -> project.getMixerSnapshotManager().setSlot(MixerSnapshotManager.Slot.A,
                MixerSnapshot.capture(project.getMixer(), "A")));
        MenuItem clear = onFx(() -> slotMenuItem("Clear A"));
        published.clear();
        runOnFx(clear::fire);
        assertThat(onFx(() -> project.getMixerSnapshotManager().getSlot(MixerSnapshotManager.Slot.A)))
                .as("refused: slot A keeps its snapshot").isNotNull();
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(clear::fire);
        assertThat(onFx(() -> project.getMixerSnapshotManager().getSlot(MixerSnapshotManager.Slot.A))).isNull();
    }

    private void inputRouting() throws Exception {
        MixerChannelStrip strip = onFx(this::voxStrip);
        ComboBox<?> combo = onFx(() -> columnNode(strip, ComboBox.class, "Input routing"));
        Object shown = onFx(combo::getValue);
        Object route = onFx(vox::getInputRouting);
        int other = onFx(() -> otherIndex(combo));
        published.clear();
        runOnFx(() -> combo.getSelectionModel().select(other));
        assertThat(onFx(vox::getInputRouting)).as("refused: the track's route is unchanged").isEqualTo(route);
        assertThat(onFx(combo::getValue)).as("the combo shows the track's route").isSameAs(shown);
        assertThat(onFx(strip::getInputLabel)).as("and the strip's caption is the route's, not the refused one")
                .isEqualTo(String.valueOf(shown));
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> combo.getSelectionModel().select(other));
        assertThat(onFx(vox::getInputRouting)).isNotEqualTo(route);
        assertThat(onFx(strip::getInputLabel)).isEqualTo(String.valueOf(onFx(combo::getValue)));
    }

    private void outputRouting() throws Exception {
        MixerChannelStrip strip = onFx(this::voxStrip);
        ComboBox<?> combo = onFx(() -> columnNode(strip, ComboBox.class, "Output routing"));
        Object shown = onFx(combo::getValue);
        Object route = onFx(channel::getOutputRouting);
        int other = onFx(() -> otherIndex(combo));
        published.clear();
        runOnFx(() -> combo.getSelectionModel().select(other));
        assertThat(onFx(channel::getOutputRouting)).as("refused: the channel's route is unchanged").isEqualTo(route);
        assertThat(onFx(combo::getValue)).as("the combo shows the channel's route").isSameAs(shown);
        assertThat(onFx(strip::getOutputLabel)).as("and the strip's caption is the route's, not the refused one")
                .isEqualTo(String.valueOf(shown));
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> combo.getSelectionModel().select(other));
        assertThat(onFx(channel::getOutputRouting)).isNotEqualTo(route);
        assertThat(onFx(strip::getOutputLabel)).isEqualTo(String.valueOf(onFx(combo::getValue)));
    }

    private void soloSafeItems() throws Exception {
        MixerChannelStrip strip = onFx(this::voxStrip);
        CheckMenuItem trackItem = onFx(() -> soloSafeItem(
                ((MixerChannelStripSkin) strip.getSkin()).soloButton().getContextMenu()));
        CheckMenuItem returnItem = onFx(() -> soloSafeItem(
                mixerView.getReturnStripControls()
                        .get(project.getMixer().getReturnBuses().indexOf(returnBus)).soloBtn().getContextMenu()));
        boolean trackWas = onFx(channel::isSoloSafe);
        boolean returnWas = onFx(returnBus::isSoloSafe);
        published.clear();
        runOnFx(() -> {
            toggleAsItsMenuDoes(trackItem);
            toggleAsItsMenuDoes(returnItem);
        });
        assertThat(onFx(channel::isSoloSafe)).as("refused: the track's flag is unchanged").isEqualTo(trackWas);
        assertThat(onFx(returnBus::isSoloSafe)).as("refused: the return's flag is unchanged").isEqualTo(returnWas);
        assertThat(onFx(trackItem::isSelected)).as("the track's item shows the model").isEqualTo(trackWas);
        assertThat(onFx(returnItem::isSelected)).as("the return's item shows the model").isEqualTo(returnWas);
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> {
            toggleAsItsMenuDoes(trackItem);
            toggleAsItsMenuDoes(returnItem);
        });
        assertThat(onFx(channel::isSoloSafe)).isEqualTo(!trackWas);
        assertThat(onFx(returnBus::isSoloSafe)).isEqualTo(!returnWas);
    }

    private void linkToggle() throws Exception {
        Button toggle = onFx(() -> nodes(mixerView).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getStyleClass().contains("mixer-channel-link-toggle") && !button.isDisabled())
                .findFirst().orElseThrow());
        runOnFx(() -> toggle.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> project.getChannelLinkManager().getLinks())).as("refused: nothing is linked").isEmpty();
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> toggle.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> project.getChannelLinkManager().getLinks())).hasSize(1);
    }

    private void linkPopoverCheckBox() throws Exception {
        UUID left = channel.getId();
        UUID right = project.getMixerChannelForTrack(bass).getId();
        runOnFx(() -> project.getChannelLinkManager().link(
                new ChannelLink(left, right, LinkMode.RELATIVE, true, true, true, false)));
        ChannelLinkPopover popover = onFx(() -> new ChannelLinkPopover(project.getChannelLinkManager(), undo,
                project.getChannelLinkManager().getLink(left), allowed::get));
        published.clear();
        runOnFx(() -> popover.getSendsBox().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> project.getChannelLinkManager().getLink(left).linkSends()))
                .as("refused: the link is unchanged").isFalse();
        assertThat(onFx(() -> popover.getSendsBox().isSelected())).as("the check box shows the link").isFalse();
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> popover.getSendsBox().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> project.getChannelLinkManager().getLink(left).linkSends())).isTrue();
    }

    private void sendLevel() throws Exception {
        Slider send = onFx(() -> columnNode(voxStrip(), Slider.class, "Send to Return 1"));
        runOnFx(() -> send.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.6));
        assertThat(onFx(() -> channel.getSendForTarget(returnBus))).as("refused: no send is made").isNull();
        assertThat(onFx(send::getValue)).as("the slider shows the model").isEqualTo(0.0);
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> send.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.6));
        assertThat(onFx(() -> channel.getSendForTarget(returnBus).getLevel())).isEqualTo(0.6);
    }

    private void cueSendGain() throws Exception {
        Slider gain = onFx(() -> columnNode(voxStrip(), Slider.class, "Cue send gain"));
        runOnFx(() -> gain.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.7));
        assertThat(onFx(() -> cueBus().findSend(voxId()))).as("refused: no cue send is made").isNull();
        assertThat(onFx(gain::getValue)).as("the slider shows the model").isEqualTo(0.0);
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> gain.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.7));
        assertThat(onFx(() -> cueBus().findSend(voxId()).gain())).isEqualTo(0.7);
    }

    private void cueSendPan() throws Exception {
        withAVoxCueSend(true);
        Slider pan = onFx(() -> columnNode(voxStrip(), Slider.class, "Cue send pan"));
        runOnFx(() -> pan.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.4));
        assertThat(onFx(() -> cueBus().findSend(voxId()).pan())).as("refused: the pan is unchanged").isEqualTo(0.0);
        assertThat(onFx(pan::getValue)).as("the slider shows the model").isEqualTo(0.0);
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> pan.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.4));
        assertThat(onFx(() -> cueBus().findSend(voxId()).pan())).isEqualTo(0.4);
    }

    private void cueSendTap() throws Exception {
        withAVoxCueSend(true);
        Button tap = onFx(() -> columnNode(voxStrip(), Button.class, "Cue send tap point"));
        runOnFx(() -> tap.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> cueBus().findSend(voxId()).preFader())).as("refused: still pre-fader").isTrue();
        assertThat(onFx(tap::getText)).as("the button shows the model").isEqualTo("F");
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> tap.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> cueBus().findSend(voxId()).preFader())).isFalse();
    }

    private void cueMaster() throws Exception {
        Slider master = onFx(() -> cueStripNode(Slider.class, "Cue Bus Master Gain"));
        double gain = onFx(() -> cueBus().masterGain());
        runOnFx(() -> master.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.5));
        assertThat(onFx(() -> cueBus().masterGain())).as("refused: the master is unchanged").isEqualTo(gain);
        assertThat(onFx(master::getValue)).as("the fader shows the model").isEqualTo(gain);
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> master.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.5));
        assertThat(onFx(() -> cueBus().masterGain())).isEqualTo(0.5);
    }

    private void cueMute() throws Exception {
        Button mute = onFx(() -> cueStripNode(Button.class, "Mute Cue Bus"));
        double gain = onFx(() -> cueBus().masterGain());
        assertThat(gain).as("fixture: the cue bus plays").isPositive();
        runOnFx(() -> mute.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> cueBus().masterGain())).as("refused: not muted").isEqualTo(gain);
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> mute.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> cueBus().masterGain())).isZero();
    }

    private void allPre() throws Exception {
        withAVoxCueSend(false);
        ToggleButton allPre = onFx(() -> nodes(mixerView.getCueBusStrips()).filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast).filter(button -> "All Pre".equals(button.getText()))
                .findFirst().orElseThrow());
        runOnFx(() -> allPre.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> cueBus().findSend(voxId()).preFader())).as("refused: the send stays post-fader")
                .isFalse();
        assertThat(onFx(allPre::isSelected)).as("the toggle the action flipped shows the model").isFalse();
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> allPre.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> cueBus().findSend(voxId()).preFader())).isTrue();
    }

    private void cueRemove() throws Exception {
        Button remove = onFx(() -> cueStripNode(Button.class, "Remove Cue Bus"));
        runOnFx(() -> remove.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> project.getCueBusManager().getById(cueBusId))).as("refused: the cue bus stays")
                .isNotNull();
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> remove.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> project.getCueBusManager().getById(cueBusId))).isNull();
    }

    private void cueCopy() throws Exception {
        Button copy = onFx(() -> cueStripNode(Button.class, "Copy current main-mix"));
        runOnFx(() -> copy.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> cueBus().sends())).as("refused: no send is copied").isEmpty();
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> copy.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> cueBus().sends())).isNotEmpty();
    }

    private void createCueBus() throws Exception {
        int buses = onFx(() -> project.getCueBusManager().getCueBuses().size());
        assertThat(windowsShownBy(() -> mixerView.promptCreateCueBus()))
                .as("refused before its dialog: no window opens").isZero();
        assertThat(onFx(() -> project.getCueBusManager().getCueBuses())).as("and no bus is made").hasSize(buses);
        assertNothingRecorded();

        allowed.set(true);
        assertThat(windowsShownBy(() -> mixerView.promptCreateCueBus()))
                .as("admitted: its dialog opens (and the posted hide answers it)").isEqualTo(1);
    }

    private void vcaName() throws Exception {
        TextField name = onFx(() -> vcaStrip().getNameField());
        runOnFx(() -> {
            name.executeAccessibleAction(AccessibleAction.SET_TEXT, "Drums");
            name.fireEvent(new ActionEvent());
        });
        assertThat(onFx(() -> vca().label())).as("refused: the name is unchanged").isEqualTo("Lead");
        assertThat(onFx(() -> name.getText())).as("the field shows the group's name").isEqualTo("Lead");
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> {
            name.executeAccessibleAction(AccessibleAction.SET_TEXT, "Drums");
            name.fireEvent(new ActionEvent());
        });
        assertThat(onFx(() -> vca().label())).isEqualTo("Drums");
    }

    private void vcaGain() throws Exception {
        Slider fader = onFx(() -> vcaStrip().getGainFader());
        double gain = onFx(() -> vca().masterGainDb());
        runOnFx(() -> fader.executeAccessibleAction(AccessibleAction.SET_VALUE, -6.0));
        assertThat(onFx(() -> vca().masterGainDb())).as("refused: the gain is unchanged").isEqualTo(gain);
        assertThat(onFx(fader::getValue)).as("the fader shows the group's gain").isEqualTo(gain);
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> fader.executeAccessibleAction(AccessibleAction.SET_VALUE, -6.0));
        assertThat(onFx(() -> vca().masterGainDb())).isEqualTo(-6.0);
    }

    private void vcaColour() throws Exception {
        VcaStrip strip = onFx(this::vcaStrip);
        ColorPicker picker = onFx(() -> nodes(strip).filter(ColorPicker.class::isInstance)
                .map(ColorPicker.class::cast).findFirst().orElseThrow());
        Object colour = onFx(() -> vca().color());
        runOnFx(() -> pickAsThePaletteDoes(picker, Color.RED));
        assertThat(onFx(() -> vca().color())).as("refused: the colour is unchanged").isEqualTo(colour);
        assertThat(onFx(this::vcaStrip)).as("the strip is the same node: nothing was rebuilt").isSameAs(strip);
        assertThat(onFx(() -> nodes(strip).anyMatch(node -> node == picker)))
                .as("and the picker the action was on is still in it").isTrue();
        // A group without a colour shows ColorPicker's own default, white.
        assertThat(onFx(picker::getValue)).as("which shows the group's colour, not the refused pick")
                .isEqualTo(vca().color() == null ? Color.WHITE : Color.web(vca().color().getHexColor()));
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> pickAsThePaletteDoes(picker, Color.RED));
        assertThat(onFx(() -> vca().color().getHexColor())).as("the same picker picks once admitted")
                .isEqualToIgnoringCase("#FF0000");
    }

    private void vcaDelete() throws Exception {
        Button delete = onFx(() -> nodes(vcaStrip()).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getTooltip() != null
                        && "Delete VCA group".equals(button.getTooltip().getText()))
                .findFirst().orElseThrow());
        runOnFx(() -> delete.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> project.getVcaGroupManager().getVcaGroups())).as("refused: the VCA stays").hasSize(1);
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> delete.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> project.getVcaGroupManager().getVcaGroups())).isEmpty();
    }

    private void snapshotRecall() throws Exception {
        aStoredSnapshotAtThreeTenths();
        Button recall = onFx(() -> snapshotButton("Recall this snapshot"));
        runOnFx(() -> recall.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(channel::getVolume)).as("refused: nothing recalled").isEqualTo(0.8);
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> recall.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(channel::getVolume)).isEqualTo(0.3);
        assertThat(undo.undoSize()).isEqualTo(1);
    }

    private void snapshotDelete() throws Exception {
        aStoredSnapshotAtThreeTenths();
        Button delete = onFx(() -> snapshotButton("Delete this snapshot."));
        runOnFx(() -> delete.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> project.getMixerSnapshotManager().getSnapshotCount()))
                .as("refused: the snapshot stays").isEqualTo(1);
        assertNothingRecorded();

        allowed.set(true);
        runOnFx(() -> delete.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> project.getMixerSnapshotManager().getSnapshotCount())).isZero();
    }

    // ── helpers of the other handlers' rows ─────────────────────────────────

    private void assertNothingRecorded() {
        assertThat(undo.undoSize()).as("no history entry").isZero();
        assertThat(published).as("nothing published").isEmpty();
    }

    private UUID voxId() {
        return UUID.fromString(vox.getId());
    }

    private CueBus cueBus() {
        return project.getCueBusManager().getById(cueBusId);
    }

    private VcaGroup vca() {
        return project.getVcaGroupManager().getVcaGroups().getFirst();
    }

    /** Gives Vox a cue send at half gain and centre pan, then rebuilds the view onto it. */
    private void withAVoxCueSend(boolean preFader) throws Exception {
        runOnFx(() -> {
            project.getCueBusManager().replace(cueBus().withSend(new CueSend(voxId(), 0.5, 0.0, preFader)));
            mixerView.refresh();
            mixerView.applyCss();
            mixerView.layout();
        });
        published.clear();
    }

    /** Stores a snapshot of Vox at 0.3, moves the fader to 0.8 and shows the snapshots panel. */
    private void aStoredSnapshotAtThreeTenths() throws Exception {
        runOnFx(() -> {
            channel.setVolume(0.3);
            project.getMixerSnapshotManager().addSnapshot(MixerSnapshot.capture(project.getMixer(), "S"));
            channel.setVolume(0.8);
            mixerView.getSnapshotsPanel().refresh();
            nodes(mixerView).filter(ToggleButton.class::isInstance).map(ToggleButton.class::cast)
                    .filter(button -> "Snapshots".equals(button.getText())).findFirst().orElseThrow()
                    .setSelected(true);
            mixerView.applyCss();
            mixerView.layout();
        });
        published.clear();
    }

    private Button snapshotButton(String tooltip) {
        return nodes(mixerView.getSnapshotsPanel()).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getTooltip() != null && button.getTooltip().getText().startsWith(tooltip))
                .findFirst().orElseThrow();
    }

    private VcaStrip vcaStrip() {
        return mixerView.getVcaStrips().getChildren().stream().filter(VcaStrip.class::isInstance)
                .map(VcaStrip.class::cast).findFirst().orElseThrow();
    }

    private MenuItem mixerMenuItem(String text) {
        MenuButton menu = nodes(mixerView).filter(MenuButton.class::isInstance).map(MenuButton.class::cast)
                .filter(button -> "⋮".equals(button.getText())).findFirst().orElseThrow();
        return items(menu.getItems()).filter(item -> text.equals(item.getText())).findFirst().orElseThrow();
    }

    private MenuItem slotMenuItem(String text) {
        return button("Recall snapshot slot A").getContextMenu().getItems().stream()
                .filter(item -> text.equals(item.getText())).findFirst().orElseThrow();
    }

    private static Stream<MenuItem> items(List<MenuItem> items) {
        return items.stream().flatMap(item -> item instanceof Menu menu
                ? Stream.concat(Stream.of(item), items(menu.getItems())) : Stream.of(item));
    }

    private static CheckMenuItem soloSafeItem(ContextMenu menu) {
        return menu.getItems().stream().filter(CheckMenuItem.class::isInstance).map(CheckMenuItem.class::cast)
                .filter(item -> "Solo safe".equals(item.getText())).findFirst().orElseThrow();
    }

    /**
     * Toggles the item and fires it, as its showing menu does when the item
     * is activated (fx ContextMenuContent {@code doSelect}); a bare
     * {@code fire()} does not toggle a {@code CheckMenuItem}. FX thread.
     */
    private static void toggleAsItsMenuDoes(CheckMenuItem item) {
        item.setSelected(!item.isSelected());
        item.fire();
    }

    /** Sets the picker's value and fires its action, as its palette does on a pick (fx ColorPalette). FX thread. */
    private static void pickAsThePaletteDoes(ColorPicker picker, Color colour) {
        picker.setValue(colour);
        picker.fireEvent(new ActionEvent());
    }

    private static int otherIndex(ComboBox<?> combo) {
        return combo.getSelectionModel().getSelectedIndex() == 0 ? 1 : 0;
    }

    /**
     * The one control of {@code type} whose tooltip starts with
     * {@code tooltip} in the mixer column that holds {@code strip}: the
     * nearest ancestor holding exactly one.
     */
    private static <T extends javafx.scene.control.Control> T columnNode(Node strip, Class<T> type, String tooltip) {
        for (Parent ancestor = strip.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            List<T> found = nodes(ancestor).filter(type::isInstance).map(type::cast)
                    .filter(control -> control.getTooltip() != null
                            && control.getTooltip().getText().startsWith(tooltip))
                    .toList();
            if (found.size() == 1) {
                return found.getFirst();
            }
            if (found.size() > 1) {
                break;
            }
        }
        throw new AssertionError("no single " + type.getSimpleName() + " \"" + tooltip + "\" in the strip's column");
    }

    private <T extends javafx.scene.control.Control> T cueStripNode(Class<T> type, String tooltip) {
        return nodes(mixerView.getCueBusStrips()).filter(type::isInstance).map(type::cast)
                .filter(control -> control.getTooltip() != null && control.getTooltip().getText().startsWith(tooltip))
                .findFirst().orElseThrow();
    }

    /**
     * Runs {@code act} on the FX thread and counts the windows it shows. The
     * listener records each window and posts its hide, never hiding inside
     * the add notification (ruling-4 H2); it is removed first, then every
     * recorded window still showing is hidden. Implicit exit is off under
     * {@link JavaFxToolkitExtension}.
     */
    private static int windowsShownBy(Runnable act) throws Exception {
        List<Window> shown = new ArrayList<>();
        ListChangeListener<Window> counter = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    shown.add(window);
                    Platform.runLater(window::hide);
                }
            }
        };
        runOnFx(() -> {
            Window.getWindows().addListener(counter);
            try {
                act.run();
            } finally {
                Window.getWindows().removeListener(counter);
                for (Window window : shown) {
                    if (window.isShowing()) {
                        window.hide();
                    }
                }
            }
        });
        return shown.size();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MixerChannelStrip voxStrip() {
        MixerChannelStrip strip = mixerView.getTrackStrips().get(project.getTracks().indexOf(vox)).strip();
        assertThat(strip.getChannelName()).as("fixture: the strip is Vox's").isEqualTo("Vox");
        return strip;
    }

    private Button button(String tooltipPrefix) {
        return nodes(mixerView).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(found -> found.getTooltip() != null && found.getTooltip().getText().startsWith(tooltipPrefix))
                .findFirst().orElseThrow();
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream()
                        .flatMap(MixerEditsAreRefusedWhileProjectActionsAreNotAllowedTest::nodes))
                : Stream.of(node);
    }
}
