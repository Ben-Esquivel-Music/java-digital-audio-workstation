package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The insert rack's edits read the window's edit gate (PR #989 review
 * 5711460812; rulings R-1.2, R-5.1 C, ruling-2 F8 N5, ruling-3 G4b): the
 * empty slot's plug-in browser, a pick made while the browser was open, the
 * row's bypass, its "Expensive" flag, its sidechain source and its removal.
 * While the gate says no, the act — an assistive technology's action on the
 * real control, or on the item's node in the row's context menu, which a
 * context-menu request opens; but for the sidechain source, chosen through
 * its combo's selection model, and the pick, applied as the plug-in
 * browser's answer applies it — changes nothing, adds no history entry,
 * publishes nothing, opens no window and does not grey the rack, and the
 * same node the act invoked shows the slot again (no rebuild, so the element
 * stays: the javafx skill §14); once the gate says yes, the same act edits.
 *
 * <p>The rack is mounted in a shown stage. Every window an act shows is
 * recorded and hidden on a later turn, never inside its own add
 * notification (ruling-4 H2); the extension keeps implicit exit off.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InsertRackEditsAreRefusedWhileProjectActionsAreNotAllowedTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);

    @TempDir
    Path workspace;

    private final AtomicBoolean allowed = new AtomicBoolean(false);
    private final List<DawEvent> published = new CopyOnWriteArrayList<>();
    private final List<Window> shown = new CopyOnWriteArrayList<>();
    private final ListChangeListener<Window> windows = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                shown.add(window);
                Platform.runLater(window::hide);
            }
        }
    };
    private EventBus previousBus;
    private DefaultEventBus bus;
    private EventBus.Subscription recording;
    private DawProject project;
    private MixerChannel channel;
    private InsertSlot slot;
    private UndoManager undo;
    private FxDispatcher dispatcher;
    private InsertEffectRack rack;
    private Stage stage;

    @BeforeEach
    void aRackBehindTheGate() throws Exception {
        previousBus = EventBusPublisher.getDefault();
        bus = new DefaultEventBus();
        recording = bus.onPublication(DawEvent.class, published::add);
        EventBusPublisher.setDefault(bus);
        project = new DawProject("Rack", FORMAT);
        Track vox = project.createAudioTrack("Vox");
        project.createAudioTrack("Kick");
        channel = project.getMixerChannelForTrack(vox);
        slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, FORMAT.sampleRate());
        channel.addInsert(slot);
        undo = new UndoManager();
        dispatcher = new FxDispatcher();
        runOnFx(() -> {
            rack = new InsertEffectRack(channel, 2, FORMAT.sampleRate(), FORMAT.bufferSize(), undo, dispatcher,
                    allowed::get);
            rack.setMixer(project.getMixer());
            rack.rebuildSlots();
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(rack), 200, 400));
            stage.show();
            rack.applyCss();
            rack.layout();
            Window.getWindows().addListener(windows);
        });
    }

    @AfterEach
    void close() throws Exception {
        try {
            runOnFx(() -> {
                rack.dispose();
                Window.getWindows().removeListener(windows);
                for (Window window : shown) {
                    if (window.isShowing()) {
                        window.hide();
                    }
                }
                stage.hide();
                dispatcher.dispose();
            });
        } finally {
            recording.close();
            EventBusPublisher.setDefault(previousBus);
            bus.close();
        }
    }

    /** One rack handler: how its refusal and its admission show. */
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
        void refusedThenAdmitted(InsertRackEditsAreRefusedWhileProjectActionsAreNotAllowedTest test) throws Exception;
    }

    static Stream<Row> rows() {
        return Stream.of(
                new Row("empty slot FIRE opens no browser",
                        InsertRackEditsAreRefusedWhileProjectActionsAreNotAllowedTest::emptySlot),
                new Row("Expensive item", InsertRackEditsAreRefusedWhileProjectActionsAreNotAllowedTest::expensive),
                new Row("sidechain source", InsertRackEditsAreRefusedWhileProjectActionsAreNotAllowedTest::sidechain),
                new Row("Remove item", InsertRackEditsAreRefusedWhileProjectActionsAreNotAllowedTest::removeItem),
                new Row("bypass", InsertRackEditsAreRefusedWhileProjectActionsAreNotAllowedTest::bypass),
                new Row("aPluginPickedWhileTheBrowserWasOpenIsNotLoaded",
                        InsertRackEditsAreRefusedWhileProjectActionsAreNotAllowedTest::pickedWhileTheBrowserWasOpen));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void aRackEditIsRefusedWhileTheGateSaysNoAndAdmittedOnceItSaysYes(Row row) throws Exception {
        row.act().refusedThenAdmitted(this);
    }

    // ── the rows ─────────────────────────────────────────────────────────────

    private void emptySlot() throws Exception {
        Button empty = onFx(() -> (Button) nodes(rack).filter(node -> node.getStyleClass().contains("insert-slot-empty"))
                .filter(node -> !node.isDisabled()).findFirst().orElseThrow());
        runOnFx(() -> empty.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(shown).as("refused: no plug-in browser opens").isEmpty();
        assertNothingElseHappened(1);

        allowed.set(true);
        runOnFx(() -> empty.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(shown).as("admitted: the browser opened (and the test closed it)").hasSize(1);
    }

    private void expensive() throws Exception {
        String text = "Expensive (eligible for bypass)";
        CheckMenuItem expensive = onFx(() -> (CheckMenuItem) fireThroughTheMenu(text));
        assertThat(slot.isExpensive()).as("refused: the flag is unchanged").isFalse();
        assertThat(onFx(expensive::isSelected)).as("the same item the menu toggled shows the slot again").isFalse();
        assertNothingElseHappened(1);

        allowed.set(true);
        runOnFx(() -> fireThroughTheMenu(text));
        assertThat(slot.isExpensive()).isTrue();
    }

    private void sidechain() throws Exception {
        ComboBox<?> combo = onFx(() -> (ComboBox<?>) nodes(rack).filter(ComboBox.class::isInstance)
                .findFirst().orElseThrow(() -> new AssertionError("fixture: the compressor offers a sidechain")));
        runOnFx(() -> combo.getSelectionModel().select(1));
        assertThat(slot.getSidechainSource()).as("refused: no sidechain source").isNull();
        assertThat(onFx(() -> combo.getSelectionModel().getSelectedIndex())).as("the same combo shows the slot")
                .isZero();
        assertNothingElseHappened(1);

        allowed.set(true);
        runOnFx(() -> combo.getSelectionModel().select(1));
        assertThat(slot.getSidechainSource()).isNotNull();
    }

    private void removeItem() throws Exception {
        runOnFx(() -> fireThroughTheMenu("Remove"));
        assertNothingElseHappened(1);

        allowed.set(true);
        runOnFx(() -> fireThroughTheMenu("Remove"));
        assertThat(channel.getInsertCount()).isZero();
        assertThat(undo.undoSize()).isEqualTo(1);
    }

    private void bypass() throws Exception {
        ToggleButton bypass = onFx(() -> (ToggleButton) nodes(rack).filter(ToggleButton.class::isInstance)
                .findFirst().orElseThrow());
        runOnFx(() -> bypass.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(slot.isBypassed()).as("refused: not bypassed").isFalse();
        assertThat(onFx(bypass::isSelected)).as("the same node shows the slot again").isTrue();
        assertThat(onFx(() -> nodes(rack).anyMatch(node -> node == bypass)))
                .as("no rebuild: the element an assistive technology invoked stays").isTrue();
        assertNothingElseHappened(1);

        allowed.set(true);
        runOnFx(() -> bypass.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(slot.isBypassed()).isTrue();
    }

    private void pickedWhileTheBrowserWasOpen() throws Exception {
        ExternalPluginEntry missing = new ExternalPluginEntry(workspace.resolve("missing.jar"), "com.example.Missing");
        runOnFx(() -> rack.applyPickerSelection(1, new PluginBrowser.ExternalSelection(missing)));
        assertThat(onFx(rack::isDisabled)).as("refused: no load starts, so the rack never greys").isFalse();
        assertNothingElseHappened(1);
        runOnFx(() -> rack.applyPickerSelection(1, new PluginBrowser.BuiltInSelection(InsertEffectType.PARAMETRIC_EQ)));
        assertNothingElseHappened(1);

        allowed.set(true);
        runOnFx(() -> rack.applyPickerSelection(1, new PluginBrowser.BuiltInSelection(InsertEffectType.PARAMETRIC_EQ)));
        assertThat(channel.getInsertCount()).as("admitted: inserted").isEqualTo(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Shows the populated row's context menu as a right-click does, and
     * activates its item {@code text} with an assistive technology's FIRE on
     * the item's node in the showing menu — which toggles a check item and
     * then fires it, as a click does. Returns the item. FX thread.
     */
    private MenuItem fireThroughTheMenu(String text) {
        Node row = rack.lookup(".insert-slot-populated");
        row.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1, 1, 1, 1, false, null));
        ContextMenu menu = Window.getWindows().stream().filter(ContextMenu.class::isInstance)
                .map(ContextMenu.class::cast).filter(Window::isShowing).findFirst()
                .orElseThrow(() -> new AssertionError("the row's context menu shows"));
        shown.clear(); // the menu is the test's means, not a window the act showed
        MenuItem item = menu.getItems().stream().filter(candidate -> text.equals(candidate.getText()))
                .findFirst().orElseThrow();
        menu.getScene().getRoot().applyCss();
        menu.getScene().getRoot().layout();
        Node itemNode = nodes(menu.getScene().getRoot())
                .filter(node -> node.getStyleClass().contains("menu-item"))
                .filter(node -> nodes(node).anyMatch(label -> label instanceof javafx.scene.control.Label shownLabel
                        && text.equals(shownLabel.getText())))
                .findFirst().orElseThrow(() -> new AssertionError("the menu shows the item " + text));
        itemNode.executeAccessibleAction(AccessibleAction.FIRE);
        menu.hide();
        return item;
    }

    /** The rack still holds exactly {@code inserts}; no history entry, no publication, no window. */
    private void assertNothingElseHappened(int inserts) throws Exception {
        assertThat(channel.getInsertCount()).as("no insert added or removed").isEqualTo(inserts);
        assertThat(undo.undoSize()).as("no history entry").isZero();
        assertThat(published).as("nothing published").isEmpty();
        assertThat(onFx(rack::isDisabled)).as("the rack never greys").isFalse();
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream()
                        .flatMap(InsertRackEditsAreRefusedWhileProjectActionsAreNotAllowedTest::nodes))
                : Stream.of(node);
    }
}
