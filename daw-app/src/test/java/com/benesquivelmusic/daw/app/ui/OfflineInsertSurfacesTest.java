package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.InsertSlotModel;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.vm.ChannelVM;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OfflineCause;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.plugin.PluginResolution;
import com.benesquivelmusic.daw.core.plugin.PluginResolver;
import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.TraversalDirection;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 334, studio-engineer ruling E22 (with E3, G7, G16 and R1): an insert a
 * project open could not load is kept at its place, passing audio through, and
 * every surface that shows inserts marks it offline. Through a real reopen —
 * the project reader turns the saved {@code <insert>} elements into offline
 * slots — the rack row carries {@code insert-slot-offline}, a warn mark
 * read as {@code <name>, offline} instead of the bypass toggle, no
 * double-click editor, no sidechain selector and a context menu of Remove only
 * (which removes it as an undoable edit); the mixer strip's dot takes
 * {@code offline}; the track-header badge takes {@code offline} with the
 * tooltip {@code <name> (offline)}, whatever the stored bypass; the
 * Inspector's row takes {@code insert-dot-offline} and the word
 * {@code Offline}, without the ✎ pencil; and the editor door opens no
 * session. Each row's tooltip names the cause and what to do about it, in
 * the ruled words for every cause.
 *
 * <p>Ruling R-N1 (the second review's comment on the Inspector's offline
 * row; javafx-application-design §14, UI Design Book §2.8 "Keyboard parity"):
 * on the rack and in the Inspector the row's state mark is its one keyboard
 * stop, where a working row's own stop stands — a button assistive technology
 * reads as {@code <name>, offline} with the row's tooltip as its help, which
 * shows that text on Space, Enter or an invoke, hides it on a second press,
 * Escape or the focus leaving, and changes nothing; on the rack the keyboard's
 * context-menu request on it reaches the row's Remove. The badge and the strip
 * row are indicators with no stop, their words on nodes assistive technology
 * reads. Tab is driven with {@link Node#requestFocusTraversal}, in a shown
 * stage: a popup needs a window, and only a focused window makes the focus live.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class OfflineInsertSurfacesTest {

    /**
     * On Vox: a working built-in Compressor; an effect type this build does not
     * know, saved bypassed with a sidechain key; a JAR plugin; an analyzer this
     * build does not know.
     */
    private static final String SONG = """
            <daw-project version="%d">
              <metadata><name>Song</name></metadata>
              <audio-format sample-rate="48000.0" channels="2" bit-depth="24" buffer-size="256"/>
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000a1" name="Drums" type="AUDIO"/>
                <track id="00000000-0000-0000-0000-0000000000a2" name="Vox" type="AUDIO"/>
              </tracks>
              <mixer>
                <master volume="1.0"/>
                <channels>
                  <channel volume="1.0"/>
                  <channel volume="1.0"><inserts>
                    <insert bypassed="false" effect-type="COMPRESSOR" expensive="false" name="Compressor"/>
                    <insert bypassed="true" effect-type="NOT_A_TYPE" expensive="true" name="Old FX" sidechain-source="channel:0"/>
                    <insert bypassed="false" expensive="false" name="Comp X" plugin-id="com.example.comp" plugin-source="jar" plugin-version="1.2.0"/>
                    <insert analyzer-plugin-id="com.example.gone" bypassed="false" expensive="false" name="Old meter"/>
                  </inserts></channel>
                </channels>
                <return-buses/>
              </mixer>
            </daw-project>
            """.formatted(MigrationRegistry.CURRENT_VERSION);

    private static final String KEPT = " It passes audio through unchanged and is kept as saved.";
    private static final String OLD_FX_TOOLTIP =
            "Old FX is offline: this version has no effect type NOT_A_TYPE." + KEPT;
    private static final String NOT_INSTALLED_TOOLTIP =
            "Comp X is offline: the plugin com.example.comp 1.2.0 is not installed." + KEPT
                    + " Install it, then reopen the project.";
    private static final String MISSING_AT_STARTUP_TOOLTIP =
            "Comp X is offline: the plugin com.example.comp is installed, but its file did not load when the"
                    + " application started." + KEPT + " Reinstall it in Manage Plugins, then reopen the project.";
    private static final String UNKNOWN_ANALYZER_TOOLTIP =
            "Old meter is offline: this version has no analyzer com.example.gone." + KEPT;

    /** Ruling-3 Q6: a JAR insert asked for after the registry's startup wait ran out; no action. */
    private static final String FAILED_TO_LOAD_TOOLTIP = "Comp X is offline: the plugin failed to load." + KEPT;

    /** The key {@link Tooltip#install} keeps a node's tooltip under. */
    private static final String INSTALLED_TOOLTIP_KEY = "javafx.scene.control.Tooltip";

    /** The style class of an offline Inspector row's keyboard stop, its word (ruling R-N1). */
    private static final String INSPECTOR_STOP = "inspector-row-offline-info";

    /** The style class of an offline rack row's keyboard stop, its warn mark (ruling R-N1). */
    private static final String RACK_STOP = "insert-slot-offline-mark";

    @Test
    void eachSurfaceMarksTheOfflineInsertAndOffersNoBypassNoEditorNoPencil() throws Exception {
        DawProject project = reopened(PluginResolver.NONE);
        try {
            Track vox = project.getTracks().get(1);
            MixerChannel channel = project.getMixerChannelForTrack(vox);
            List<InsertSlot> slots = List.copyOf(channel.getInsertSlots());
            assertThat(slots).as("fixture: the reopen kept every insert at its place")
                    .extracting(InsertSlot::getName).containsExactly("Compressor", "Old FX", "Comp X", "Old meter");
            assertThat(slots).as("fixture: the three it could not load are offline")
                    .extracting(slot -> slot.offline().isPresent()).containsExactly(false, true, true, true);
            InsertSlot oldFx = slots.get(1);
            assertThat(oldFx.isBypassed()).as("fixture: the stored bypass decision is kept as saved").isTrue();
            assertThat(oldFx.getSidechainSource()).as("fixture: its sidechain key resolved").isNotNull();

            ArrangementStripFixture.onFx(() -> {
                theRackMarksItAndOffersRemoveOnly(project, channel, oldFx);
                theStripDotIsOffline(channel);
                theTrackHeaderBadgeIsOffline(project, vox);
                theInspectorRowHasNoPencil(channel);
                theEditorDoorOpensNothing(channel, slots.getFirst(), oldFx);
            });
        } finally {
            project.disposeInsertsWhenQuiescent().join();
        }
    }

    @Test
    void theTooltipNamesTheCauseAndTheAction() throws Exception {
        DawProject notInstalled = reopened(PluginResolver.NONE);
        DawProject missingAtStartup = reopened((identity, context) -> new PluginResolution.MissingAtStartup());
        DawProject timedOut = reopened((identity, context) -> new PluginResolution.FailedToLoad("TimeoutException"));
        try {
            MixerChannel notInstalledVox = notInstalled.getMixerChannelForTrack(notInstalled.getTracks().get(1));
            MixerChannel missingVox = missingAtStartup.getMixerChannelForTrack(missingAtStartup.getTracks().get(1));
            MixerChannel timedOutVox = timedOut.getMixerChannelForTrack(timedOut.getTracks().get(1));

            assertThat(ArrangementStripFixture.onFx(() -> rackTooltips(notInstalledVox)))
                    .containsExactly(OLD_FX_TOOLTIP, NOT_INSTALLED_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
            assertThat(ArrangementStripFixture.onFx(() -> inspectorTooltips(notInstalledVox)))
                    .containsExactly(OLD_FX_TOOLTIP, NOT_INSTALLED_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
            assertThat(ArrangementStripFixture.onFx(() -> rackTooltips(missingVox)))
                    .containsExactly(OLD_FX_TOOLTIP, MISSING_AT_STARTUP_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
            assertThat(ArrangementStripFixture.onFx(() -> inspectorTooltips(missingVox)))
                    .containsExactly(OLD_FX_TOOLTIP, MISSING_AT_STARTUP_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
            assertThat(ArrangementStripFixture.onFx(() -> rackTooltips(timedOutVox)))
                    .containsExactly(OLD_FX_TOOLTIP, FAILED_TO_LOAD_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
            assertThat(ArrangementStripFixture.onFx(() -> inspectorTooltips(timedOutVox)))
                    .containsExactly(OLD_FX_TOOLTIP, FAILED_TO_LOAD_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
        } finally {
            notInstalled.disposeInsertsWhenQuiescent().join();
            missingAtStartup.disposeInsertsWhenQuiescent().join();
            timedOut.disposeInsertsWhenQuiescent().join();
        }
    }

    /** One cause and the row tooltip ruled for it. */
    record Wording(String name, OfflineCause cause, String tooltip) {
        Wording {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(tooltip, "tooltip must not be null");
        }

        @Override
        public String toString() {
            return cause.getClass().getSimpleName() + " " + tooltip;
        }
    }

    static Stream<Wording> wordings() {
        return Stream.of(
                new Wording("Old FX", new OfflineCause.UnknownEffectType("NOT_A_TYPE"), OLD_FX_TOOLTIP),
                new Wording("Imager", new OfflineCause.FormatNotSupported("STEREO_IMAGER", 1, 48_000.0),
                        "Imager is offline: this effect cannot run in this project's audio format." + KEPT),
                new Wording("De-esser", new OfflineCause.UnknownBuiltInPlugin("com.benesquivelmusic.daw.x"),
                        "De-esser is offline: this version has no built-in plugin com.benesquivelmusic.daw.x."
                                + KEPT),
                new Wording("Comp X", new OfflineCause.NotInstalled(PluginSourceKind.JAR, "com.example.comp", "1.2.0"),
                        NOT_INSTALLED_TOOLTIP),
                new Wording("Glue", new OfflineCause.NotInstalled(PluginSourceKind.CLAP, "com.example.glue", "1.4.4"),
                        "Glue is offline: the CLAP plugin com.example.glue 1.4.4 is not installed." + KEPT
                                + " Install it, then reopen the project."),
                new Wording("Comp X",
                        new OfflineCause.MissingAtStartup(PluginSourceKind.JAR, "com.example.comp", "1.2.0"),
                        MISSING_AT_STARTUP_TOOLTIP),
                new Wording("Comp X", new OfflineCause.InstalledAsOtherKind(PluginSourceKind.JAR, "com.example.comp",
                        "1.2.0", PluginSourceKind.CLAP),
                        "Comp X is offline: the plugin com.example.comp is installed only as a CLAP plugin." + KEPT),
                new Wording("Glue", new OfflineCause.StateRejected(PluginSourceKind.CLAP, "com.example.glue", "1.4.4",
                        "1.4.4", "IllegalArgumentException"),
                        "Glue is offline: the plugin did not accept its saved settings." + KEPT),
                new Wording("Glue", new OfflineCause.StateUnreadable(PluginSourceKind.CLAP, "com.example.glue", "1.4.4"),
                        "Glue is offline: the project's saved settings for this plugin are damaged." + KEPT),
                new Wording("Comp X", new OfflineCause.NotLoadedBySnapshotRestore(PluginSourceKind.JAR,
                        "com.example.comp", "1.2.0"),
                        "Comp X is offline: restoring a snapshot does not load plugins." + KEPT
                                + " Save, then reopen the project."),
                new Wording("Comp X", new OfflineCause.FailedToLoad(PluginSourceKind.JAR, "com.example.comp", "1.2.0",
                        "TimeoutException"),
                        "Comp X is offline: the plugin failed to load." + KEPT),
                // Ruling-4 Q8: a latency query that threw keeps the failed-to-load tooltip as it is.
                new Wording("Comp X", new OfflineCause.FailedToLoad(PluginSourceKind.JAR, "com.example.comp", "1.2.0",
                        "NullPointerException when asked for its latency"),
                        FAILED_TO_LOAD_TOOLTIP),
                new Wording("Insert 2", new OfflineCause.NoIdentity(),
                        "Insert 2 is offline: the project does not record which plugin this was." + KEPT),
                new Wording("Old meter", new OfflineCause.UnknownAnalyzer("com.example.gone"),
                        UNKNOWN_ANALYZER_TOOLTIP));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wordings")
    void everyCauseIsWordedAsRuled(Wording wording) {
        assertThat(OfflineInsertText.tooltip(wording.name(), wording.cause())).isEqualTo(wording.tooltip());
    }

    @Test
    void everyCauseHasARuledWording() {
        Set<Class<?>> worded = wordings().map(wording -> wording.cause().getClass()).collect(Collectors.toSet());
        assertThat(worded).as("every OfflineCause is in wordings()")
                .containsExactlyInAnyOrder(OfflineCause.class.getPermittedSubclasses());
    }

    @Test
    void theWordIsTheOfflineClipsWordAndTheShortTextsAreTheRuledOnes() {
        assertThat(OfflineInsertText.word()).isEqualTo("Offline")
                .as("one word across the session: story 329's offline clips")
                .isEqualTo(ProjectAudioText.text("clipPlaceholder.offline"));
        assertThat(OfflineInsertText.accessible("Comp X")).isEqualTo("Comp X, offline");
        assertThat(OfflineInsertText.badgeTooltip("Comp X")).isEqualTo("Comp X (offline)");
        assertThat(OfflineInsertText.removeItem()).as("the offline row's menu item, read from the bundle")
                .isEqualTo("Remove");
    }

    // ── keyboard and assistive technology (ruling R-N1) ─────────────────────

    @Test
    void theInspectorsOfflineWordIsTheRowsKeyboardStopAndTabReachesEveryOfflineRow() throws Exception {
        DawProject project = reopened(PluginResolver.NONE);
        try {
            MixerChannel channel = vox(project);
            ArrangementStripFixture.onFx(() -> {
                try (ShownInspector inspector = new ShownInspector(channel)) {
                    Scene scene = inspector.scene();
                    List<HBox> rows = inspector.rows();
                    Button pencil = (Button) rows.getFirst().getChildren().getLast();
                    pencil.requestFocus();
                    assertThat(scene.getFocusOwner()).as("fixture: the working row's pencil holds the focus")
                            .isSameAs(pencil);

                    List<Node> reached = tabs(scene, 3);

                    assertThat(reached).as("Tab reaches every offline row's word, in chain order, skipping none")
                            .containsExactly(stopIn(rows.get(1), INSPECTOR_STOP), stopIn(rows.get(2), INSPECTOR_STOP),
                                    stopIn(rows.get(3), INSPECTOR_STOP));
                    for (Node stop : reached) {
                        assertThat(stop.isFocusTraversable()).isTrue();
                        assertThat(stop).as("a button, never a toggle (E3)")
                                .isInstanceOf(Button.class).isNotInstanceOf(ToggleButton.class);
                    }
                }
            });
        } finally {
            project.disposeInsertsWhenQuiescent().join();
        }
    }

    @Test
    void theInspectorsOfflineWordReadsAsTheInsertOfflineWithTheReasonAsItsHelp() throws Exception {
        DawProject notInstalled = reopened(PluginResolver.NONE);
        DawProject missingAtStartup = reopened((identity, context) -> new PluginResolution.MissingAtStartup());
        DawProject timedOut = reopened((identity, context) -> new PluginResolution.FailedToLoad("TimeoutException"));
        try {
            MixerChannel notInstalledVox = vox(notInstalled);
            ArrangementStripFixture.onFx(() -> {
                try (ShownInspector inspector = new ShownInspector(notInstalledVox)) {
                    Node word = stopIn(inspector.rows().get(1), INSPECTOR_STOP);
                    assertThat(word.queryAccessibleAttribute(AccessibleAttribute.ROLE)).isEqualTo(AccessibleRole.BUTTON);
                    assertThat(word.queryAccessibleAttribute(AccessibleAttribute.TEXT)).isEqualTo("Old FX, offline")
                            .isEqualTo(OfflineInsertText.accessible("Old FX"));
                    assertThat(word.queryAccessibleAttribute(AccessibleAttribute.HELP))
                            .as("why it is offline and what to do, as its help").isEqualTo(OLD_FX_TOOLTIP);
                }
            });

            assertThat(ArrangementStripFixture.onFx(() -> inspectorWordHelps(notInstalledVox)))
                    .containsExactly(OLD_FX_TOOLTIP, NOT_INSTALLED_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
            assertThat(ArrangementStripFixture.onFx(() -> inspectorWordHelps(vox(missingAtStartup))))
                    .containsExactly(OLD_FX_TOOLTIP, MISSING_AT_STARTUP_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
            assertThat(ArrangementStripFixture.onFx(() -> inspectorWordHelps(vox(timedOut))))
                    .containsExactly(OLD_FX_TOOLTIP, FAILED_TO_LOAD_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
        } finally {
            notInstalled.disposeInsertsWhenQuiescent().join();
            missingAtStartup.disposeInsertsWhenQuiescent().join();
            timedOut.disposeInsertsWhenQuiescent().join();
        }
    }

    @Test
    void theInspectorsOfflineWordShowsTheReasonOnEnterSpaceOrInvokeAndOpensNoEditor() throws Exception {
        DawProject project = reopened(PluginResolver.NONE);
        try {
            MixerChannel channel = vox(project);
            ArrangementStripFixture.onFx(() -> {
                try (ShownInspector inspector = new ShownInspector(channel)) {
                    HBox row = inspector.rows().get(1);
                    Chain chain = Chain.of(channel);
                    int selected = inspector.drawer().getInsertsSection().selectedIndexProperty().get();
                    showsTheReasonAndChangesNothing(inspector.scene(), stopIn(row, INSPECTOR_STOP),
                            row.getChildren().get(1), OLD_FX_TOOLTIP, () -> {
                                assertThat(inspector.opened()).as("E3: no editor opens").isEmpty();
                                assertThat(inspector.drawer().getInsertsSection().selectedIndexProperty().get())
                                        .as("E3: no insert is selected for one").isEqualTo(selected);
                                assertThat(Chain.of(channel)).as("E3: the chain and every bypass are as they were")
                                        .isEqualTo(chain);
                                assertThat(inspector.drawer().isExpanded())
                                        .as("Escape went no further: the Inspector stays open").isTrue();
                            });
                }
            });
        } finally {
            project.disposeInsertsWhenQuiescent().join();
        }
    }

    @Test
    void theRacksOfflineMarkIsTheTabStopWhereAWorkingRowsBypassToggleStands() throws Exception {
        DawProject project = reopened(PluginResolver.NONE);
        try {
            MixerChannel channel = vox(project);
            ArrangementStripFixture.onFx(() -> {
                try (ShownRack shown = new ShownRack(project, channel)) {
                    Scene scene = shown.scene();
                    List<Node> workingStops = descendants(shown.rack().getChildren().get(1))
                            .filter(Node::isFocusTraversable).toList();
                    assertThat(workingStops.getFirst()).as("fixture: a working row's first stop is its bypass toggle")
                            .isInstanceOf(ToggleButton.class);
                    workingStops.getLast().requestFocus();
                    assertThat(scene.getFocusOwner()).as("fixture: the working row's last stop holds the focus")
                            .isSameAs(workingStops.getLast());

                    List<Node> reached = tabs(scene, 3);

                    assertThat(reached).as("Tab reaches every offline row's warn mark, in chain order, skipping none")
                            .containsExactly(stopIn(shown.row(2), RACK_STOP), stopIn(shown.row(3), RACK_STOP),
                                    stopIn(shown.row(4), RACK_STOP));
                    for (int index = 2; index <= 4; index++) {
                        Node stop = reached.get(index - 2);
                        assertThat(stop).as("where a working row's bypass toggle stands: its row's first child")
                                .isSameAs(shown.row(index).getChildren().getFirst());
                        assertThat(stop).isInstanceOf(Button.class);
                    }
                }
            });
        } finally {
            project.disposeInsertsWhenQuiescent().join();
        }
    }

    @Test
    void theRacksOfflineMarkReadsAsTheInsertOfflineWithTheReasonAsItsHelpForEveryCause() throws Exception {
        DawProject notInstalled = reopened(PluginResolver.NONE);
        DawProject missingAtStartup = reopened((identity, context) -> new PluginResolution.MissingAtStartup());
        DawProject timedOut = reopened((identity, context) -> new PluginResolution.FailedToLoad("TimeoutException"));
        try {
            MixerChannel notInstalledVox = vox(notInstalled);
            ArrangementStripFixture.onFx(() -> {
                try (ShownRack shown = new ShownRack(notInstalled, notInstalledVox)) {
                    Node mark = stopIn(shown.row(2), RACK_STOP);
                    assertThat(mark.queryAccessibleAttribute(AccessibleAttribute.ROLE)).isEqualTo(AccessibleRole.BUTTON);
                    assertThat(mark.queryAccessibleAttribute(AccessibleAttribute.TEXT)).isEqualTo("Old FX, offline");
                    assertThat(mark.queryAccessibleAttribute(AccessibleAttribute.HELP))
                            .as("why it is offline and what to do, as its help").isEqualTo(OLD_FX_TOOLTIP);
                    HBox oldMeter = shown.row(4);
                    assertThat(((Label) oldMeter.getChildren().get(1)).getText())
                            .as("fixture: the row shows a truncated name").isNotEqualTo("Old meter");
                    assertThat(stopIn(oldMeter, RACK_STOP).queryAccessibleAttribute(AccessibleAttribute.TEXT))
                            .as("assistive technology reads the full name, not the truncation")
                            .isEqualTo("Old meter, offline");
                }
            });

            assertThat(ArrangementStripFixture.onFx(() -> rackMarkHelps(notInstalledVox)))
                    .containsExactly(OLD_FX_TOOLTIP, NOT_INSTALLED_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
            assertThat(ArrangementStripFixture.onFx(() -> rackMarkHelps(vox(missingAtStartup))))
                    .containsExactly(OLD_FX_TOOLTIP, MISSING_AT_STARTUP_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
            assertThat(ArrangementStripFixture.onFx(() -> rackMarkHelps(vox(timedOut))))
                    .containsExactly(OLD_FX_TOOLTIP, FAILED_TO_LOAD_TOOLTIP, UNKNOWN_ANALYZER_TOOLTIP);
        } finally {
            notInstalled.disposeInsertsWhenQuiescent().join();
            missingAtStartup.disposeInsertsWhenQuiescent().join();
            timedOut.disposeInsertsWhenQuiescent().join();
        }
    }

    @Test
    void theRacksOfflineMarkShowsTheReasonAndChangesNothing() throws Exception {
        DawProject project = reopened(PluginResolver.NONE);
        try {
            MixerChannel channel = vox(project);
            ArrangementStripFixture.onFx(() -> {
                try (ShownRack shown = new ShownRack(project, channel)) {
                    HBox row = shown.row(2);
                    InsertSlot oldFx = channel.getInsertSlots().get(1);
                    Chain chain = Chain.of(channel);
                    Accelerators accelerators = showsTheReasonAndChangesNothing(shown.scene(), stopIn(row, RACK_STOP),
                            row.getChildren().get(1), OLD_FX_TOOLTIP, () -> {
                                assertThat(shown.opened()).as("E3: no editor opens").isEmpty();
                                assertThat(oldFx.isBypassed()).as("E3: its stored bypass is kept as saved").isTrue();
                                assertThat(Chain.of(channel)).as("E3: the chain and every bypass are as they were")
                                        .isEqualTo(chain);
                                assertThat(channel.getInsertSlots()).hasSize(4);
                            });

                    Event.fireEvent(row.getChildren().get(1), key(KeyEvent.KEY_PRESSED, KeyCode.ESCAPE));
                    assertThat(accelerators.escapes().get())
                            .as("non-vacuity: Escape elsewhere in the row reaches its accelerator").isEqualTo(1);
                }
            });
        } finally {
            project.disposeInsertsWhenQuiescent().join();
        }
    }

    @Test
    void theRacksOfflineRowReachesRemoveFromTheKeyboardAsAWorkingRowDoes() throws Exception {
        DawProject project = reopened(PluginResolver.NONE);
        try {
            MixerChannel channel = vox(project);
            InsertSlot oldFx = channel.getInsertSlots().get(1);
            ArrangementStripFixture.onFx(() -> {
                try (ShownRack shown = new ShownRack(project, channel)) {
                    Scene scene = shown.scene();
                    List<Node> workingStops = descendants(shown.rack().getChildren().get(1))
                            .filter(Node::isFocusTraversable).toList();
                    assertThat(workingStops.getFirst()).as("fixture: a working row's first stop is its bypass toggle")
                            .isInstanceOf(ToggleButton.class);
                    workingStops.getFirst().requestFocus();
                    ContextMenu working = keyboardContextMenu(scene);
                    // InsertEffectRack.buildPopulatedSlot: new CheckMenuItem("Expensive (eligible for bypass)"),
                    // a SeparatorMenuItem, new MenuItem("Remove").
                    assertThat(working.getItems().stream().filter(item -> !(item instanceof SeparatorMenuItem)))
                            .as("non-vacuity: the keyboard's request on a working row's toggle opens its row's menu")
                            .extracting(MenuItem::getText).containsExactly("Expensive (eligible for bypass)", "Remove");
                    working.hide();

                    Node mark = stopIn(shown.row(2), RACK_STOP);
                    workingStops.getLast().requestFocus();
                    assertThat(tabs(scene, 1)).as("the keyboard reaches the Old FX mark: Tab from the working row")
                            .containsExactly(mark);
                    ContextMenu menu = keyboardContextMenu(scene);
                    assertThat(menu.getItems()).as("Shift+F10 or the Menu key on the mark opens the row's menu")
                            .extracting(MenuItem::getText).containsExactly(OfflineInsertText.removeItem())
                            .containsExactly("Remove");
                    MenuItem remove = menu.getItems().getFirst();
                    menu.hide();
                    remove.fire();
                    assertThat(channel.getInsertSlots()).as("Remove removes it").doesNotContain(oldFx);
                    assertThat(shown.undo().undo()).isTrue();
                    assertThat(channel.getInsertSlots().get(1)).as("undo puts the same offline insert back at its place")
                            .isSameAs(oldFx);
                }
            });
        } finally {
            project.disposeInsertsWhenQuiescent().join();
        }
    }

    @Test
    void aTrackHeaderBadgeIsReadAsTheInsertsNameAndState() throws Exception {
        DawProject project = reopened(PluginResolver.NONE);
        try {
            MixerChannel channel = vox(project);
            ArrangementStripFixture.onFx(() -> {
                FxDispatcher dispatcher = new FxDispatcher();
                ChannelVM vm = new ChannelVM(channel, dispatcher);
                try {
                    HBox chain = new HBox();
                    TrackStripController.renderInsertIndicators(chain, List.copyOf(vm.insertsProperty()));
                    Scene scene = new Scene(new StackPane(chain), 300, 40);
                    scene.getRoot().applyCss();

                    Node offline = chain.getChildren().get(1);
                    assertThat(offline).as("still an indicator label").isInstanceOf(Label.class);
                    assertThat(offline.getAccessibleText()).isEqualTo("Old FX, offline");
                    assertThat(offline.queryAccessibleAttribute(AccessibleAttribute.TEXT))
                            .as("assistive technology reads the name and state, not the abbreviation")
                            .isEqualTo("Old FX, offline");
                    assertThat(offline.isFocusTraversable()).as("an indicator, not a Tab stop").isFalse();
                    Node working = chain.getChildren().getFirst();
                    assertThat(working.getAccessibleText()).isEqualTo("Compressor");
                    assertThat(working.queryAccessibleAttribute(AccessibleAttribute.TEXT)).isEqualTo("Compressor");
                } finally {
                    vm.dispose();
                    dispatcher.dispose();
                }
            });
        } finally {
            project.disposeInsertsWhenQuiescent().join();
        }
    }

    @Test
    void aMixerStripRowsStateIsOnItsNameWhereAssistiveTechnologyReadsIt() throws Exception {
        DawProject project = reopened(PluginResolver.NONE);
        try {
            MixerChannel channel = vox(project);
            ArrangementStripFixture.onFx(() -> {
                FxDispatcher dispatcher = new FxDispatcher();
                ChannelVM vm = new ChannelVM(channel, dispatcher);
                try {
                    MixerChannelStrip strip = new MixerChannelStrip();
                    strip.insertsProperty().setAll(List.copyOf(vm.insertsProperty()));
                    StackPane root = new StackPane(strip);
                    root.getStyleClass().add("root-pane");
                    Scene scene = new Scene(root, 120, 600);
                    ThemeManager.getDefault().applyTo(scene);
                    root.applyCss();
                    root.layout();
                    MixerChannelStripSkin skin = (MixerChannelStripSkin) strip.getSkin();

                    Node offline = skin.insertRowNode(1);
                    Label name = nameIn(offline);
                    assertThat(name.queryAccessibleAttribute(AccessibleAttribute.TEXT)).isEqualTo("Old FX, offline");
                    assertThat(name.queryAccessibleAttribute(AccessibleAttribute.ROLE))
                            .as("an element assistive technology is given").isEqualTo(AccessibleRole.TEXT);
                    assertThat(offline.getAccessibleText()).as("no words on the layout row, which it never reads")
                            .isNull();
                    assertThat(nameIn(skin.insertRowNode(0)).queryAccessibleAttribute(AccessibleAttribute.TEXT))
                            .isEqualTo("Compressor");
                } finally {
                    vm.dispose();
                    dispatcher.dispose();
                }
            });
        } finally {
            project.disposeInsertsWhenQuiescent().join();
        }
    }

    // ── the surfaces (FX thread) ────────────────────────────────────────────

    /** Surface A: the editing rack, mounted in a shown stage so its context menu can show. */
    private static void theRackMarksItAndOffersRemoveOnly(DawProject project, MixerChannel channel,
                                                          InsertSlot oldFx) {
        UndoManager undo = new UndoManager();
        FxDispatcher dispatcher = new FxDispatcher();
        InsertEffectRack rack = new InsertEffectRack(channel, 2, 48_000.0, 256, undo, dispatcher, () -> true);
        List<InsertSlot> opened = new ArrayList<>();
        rack.setOnOpenEditor((host, slot) -> opened.add(slot));
        rack.setMixer(project.getMixer());
        rack.rebuildSlots();
        StackPane host = new StackPane(rack);
        host.getStyleClass().add("root-pane");
        Stage stage = new Stage();
        stage.setScene(new Scene(host, 200, 400));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.show();
        try {
            Node working = rack.getChildren().get(1);
            assertThat(working).as("the working compressor keeps its sidechain selector").isInstanceOf(VBox.class);
            assertThat(descendants(working).filter(ToggleButton.class::isInstance)).hasSize(1);
            assertThat(descendants(working).filter(ComboBox.class::isInstance)).hasSize(1);

            for (int index = 2; index <= 4; index++) {
                Node offline = rack.getChildren().get(index);
                assertThat(offline).as("an offline row stands alone: no sidechain selector").isInstanceOf(HBox.class);
                assertThat(offline.getStyleClass()).contains("insert-slot-populated", "insert-slot-offline");
                assertThat(descendants(offline)).as("no bypass toggle, no sidechain selector")
                        .noneMatch(ToggleButton.class::isInstance).noneMatch(ComboBox.class::isInstance);
            }

            HBox row = (HBox) rack.getChildren().get(2);
            Labeled mark = (Labeled) row.getChildren().getFirst();
            assertThat(mark.queryAccessibleAttribute(AccessibleAttribute.TEXT))
                    .as("the row's words, on the node assistive technology reads (R-N1)")
                    .isEqualTo("Old FX, offline");
            assertThat(mark.getStyleClass()).contains("insert-slot-offline-mark");
            assertThat(mark.getGraphic()).as("the warn glyph").isNotNull();
            Label name = (Label) row.getChildren().get(1);
            assertThat(name.getText()).isEqualTo("Old FX");
            assertThat(name.getStyleClass()).contains("insert-slot-offline-name");
            assertThat(name.getOnMouseClicked()).as("no double-click handler").isNull();
            name.fireEvent(doubleClick());
            assertThat(opened).as("a double-click opens no editor").isEmpty();

            ContextMenu menu = contextMenuOf(row);
            assertThat(menu.getItems()).as("no Expensive item, no separator")
                    .extracting(MenuItem::getText).containsExactly("Remove");
            MenuItem remove = menu.getItems().getFirst();
            menu.hide();
            remove.fire();
            assertThat(channel.getInsertSlots()).as("Remove removes it").hasSize(3).doesNotContain(oldFx);
            assertThat(undo.undo()).isTrue();
            assertThat(channel.getInsertSlots().get(1)).as("undo puts the same offline insert back at its place")
                    .isSameAs(oldFx);
            assertThat(rack.getChildren().get(2).getStyleClass()).as("and the rack shows it offline again")
                    .contains("insert-slot-offline");
        } finally {
            rack.dispose();
            stage.hide();
            dispatcher.dispose();
        }
    }

    /** Surface B: the mixer strip's indicator rows, mapped by the channel's view-model. */
    private static void theStripDotIsOffline(MixerChannel channel) {
        FxDispatcher dispatcher = new FxDispatcher();
        ChannelVM vm = new ChannelVM(channel, dispatcher);
        try {
            List<InsertSlotModel> models = List.copyOf(vm.insertsProperty());
            assertThat(models.getFirst()).isEqualTo(new InsertSlotModel("Compressor", true, false, false, "Compressor"));
            assertThat(models.get(1)).as("never active, its stored bypass kept, worded for assistive technology")
                    .isEqualTo(new InsertSlotModel("Old FX", false, true, true, "Old FX, offline"));
            assertThat(models.get(2)).as("an offline insert saved engaged is never active either (E22)")
                    .isEqualTo(new InsertSlotModel("Comp X", false, false, true, "Comp X, offline"));

            MixerChannelStrip strip = new MixerChannelStrip();
            strip.insertsProperty().setAll(models);
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 120, 600);
            ThemeManager.getDefault().applyTo(scene);
            root.applyCss();
            root.layout();
            MixerChannelStripSkin skin = (MixerChannelStripSkin) strip.getSkin();

            Node offline = skin.insertRowNode(1);
            assertThat(dotIn(offline).getStyleClass()).contains("offline").doesNotContain("active");
            assertThat(nameIn(offline).queryAccessibleAttribute(AccessibleAttribute.TEXT))
                    .as("the row's words, on its name, which assistive technology reads (R-N1)")
                    .isEqualTo("Old FX, offline");
            assertThat(skin.insertDotActive(1)).isFalse();
            assertThat(skin.insertDotActive(2)).as("the engaged offline insert's dot is not the active colour")
                    .isFalse();
            assertThat(dotIn(skin.insertRowNode(2)).getStyleClass()).contains("offline").doesNotContain("active");
            Node working = skin.insertRowNode(0);
            assertThat(dotIn(working).getStyleClass()).contains("active").doesNotContain("offline");
            assertThat(nameIn(working).queryAccessibleAttribute(AccessibleAttribute.TEXT)).isEqualTo("Compressor");
        } finally {
            vm.dispose();
            dispatcher.dispose();
        }
    }

    /** Surface C: the arrangement track header's badges, through the production strip binding. */
    private static void theTrackHeaderBadgeIsOffline(DawProject project, Track vox) {
        ArrangementStripFixture rig = new ArrangementStripFixture(project, true);
        try {
            HBox chain = ArrangementStripFixture.controlsOf(rig.addStrip(vox)).insertChain();
            List<Label> badges = chain.getChildren().stream().map(Label.class::cast).toList();
            assertThat(badges).extracting(Label::getText).containsExactly("COM", "OLD", "COM", "OLD");
            Label oldFx = badges.get(1);
            assertThat(oldFx.getStyleClass()).contains("track-insert-indicator", TrackStripController.OFFLINE_STYLE_CLASS)
                    .as("offline wins over the stored bypass")
                    .doesNotContain(TrackStripController.BYPASSED_STYLE_CLASS);
            assertThat(oldFx.getTooltip().getText()).isEqualTo("Old FX (offline)");
            assertThat(badges.getFirst().getStyleClass()).doesNotContain(TrackStripController.OFFLINE_STYLE_CLASS);
            assertThat(badges.getFirst().getTooltip().getText()).isEqualTo("Compressor");
        } finally {
            rig.close();
        }
    }

    /** Surface D: the Inspector's INSERTS rows. */
    private static void theInspectorRowHasNoPencil(MixerChannel channel) {
        FxDispatcher dispatcher = new FxDispatcher();
        InspectorDrawer drawer = new InspectorDrawer();
        List<InsertSlot> opened = new ArrayList<>();
        drawer.bindInserts(() -> channel, (host, slot) -> opened.add(slot), host -> { }, dispatcher);
        try {
            List<HBox> rows = insertRows(drawer);
            assertThat(rows).hasSize(4);
            HBox row = rows.get(1);
            assertThat(row.getChildren()).hasSize(3);
            assertThat(row.getChildren().getFirst().getStyleClass()).contains("insert-dot-offline")
                    .doesNotContain("insert-dot-active", "insert-dot-inactive");
            Labeled word = (Labeled) row.getChildren().getLast();
            assertThat(word.getText()).isEqualTo("Offline");
            assertThat(word.getStyleClass()).contains("inspector-row-offline");
            assertThat(descendants(row)).as("no ✎ pencil")
                    .noneMatch(node -> node.getStyleClass().contains("inspector-row-edit"));
            int selected = drawer.getInsertsSection().selectedIndexProperty().get();
            descendants(row).filter(ButtonBase.class::isInstance).map(ButtonBase.class::cast)
                    .forEach(ButtonBase::fire);
            assertThat(opened).as("E3: nothing in the offline row opens an editor").isEmpty();
            assertThat(drawer.getInsertsSection().selectedIndexProperty().get())
                    .as("nor selects the insert for one").isEqualTo(selected);
            assertThat(word.queryAccessibleAttribute(AccessibleAttribute.TEXT))
                    .as("the row's words, on the node assistive technology reads (R-N1)")
                    .isEqualTo("Old FX, offline");
            assertThat(installedTooltip(row)).isEqualTo(OLD_FX_TOOLTIP);

            Button pencil = (Button) rows.getFirst().getChildren().getLast();
            assertThat(pencil.getStyleClass()).as("non-vacuity: the working row keeps its pencil")
                    .contains("inspector-row-edit");
            pencil.fire();
            assertThat(opened).containsExactly(channel.getInsertSlots().getFirst());
        } finally {
            drawer.disposeInsertBinding();
            dispatcher.dispose();
        }
    }

    /** Surface E: the one door every editor opener reaches. */
    private static void theEditorDoorOpensNothing(MixerChannel channel, InsertSlot working, InsertSlot oldFx) {
        List<String> statuses = new ArrayList<>();
        List<Node> shownEditors = new ArrayList<>();
        PluginViewController controller = new PluginViewController(new PluginViewController.Deps(
                () -> 48_000, () -> 256, () -> null, () -> { }, (message, icon) -> statuses.add(message),
                (level, message) -> { }, (segments, editor) -> shownEditors.add(editor), () -> null, () -> { }));
        try {
            controller.openSlotEditor(channel, oldFx);
            assertThat(controller.activeEditorSessionForTest()).as("an offline insert opens no editor").isNull();
            assertThat(shownEditors).isEmpty();
            assertThat(statuses).isEmpty();

            controller.openSlotEditor(channel, working);
            Object session = controller.activeEditorSessionForTest();
            assertThat(session).as("non-vacuity: the working insert's editor opens").isNotNull();
            controller.openSlotEditor(channel, oldFx);
            assertThat(controller.activeEditorSessionForTest()).as("the editor shown stays").isSameAs(session);
            assertThat(shownEditors).hasSize(1);
        } finally {
            controller.dispose();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static DawProject reopened(PluginResolver resolver) throws IOException {
        ProjectDeserializer reader = new ProjectDeserializer();
        reader.setPluginResolver(resolver);
        return reader.deserialize(SONG);
    }

    /** The offline rack rows' tooltips, in chain order; each row's own equals its name's. FX thread. */
    private static List<String> rackTooltips(MixerChannel channel) {
        InsertEffectRack rack = new InsertEffectRack(channel, 2, 48_000.0, 256, null, null, () -> true);
        try {
            List<String> tooltips = new ArrayList<>();
            for (Node node : rack.getChildren()) {
                if (node.getStyleClass().contains("insert-slot-offline")) {
                    HBox row = (HBox) node;
                    String text = ((Label) row.getChildren().get(1)).getTooltip().getText();
                    assertThat(installedTooltip(row)).isEqualTo(text);
                    tooltips.add(text);
                }
            }
            return tooltips;
        } finally {
            rack.dispose();
        }
    }

    /** The offline Inspector rows' tooltips, in chain order. FX thread. */
    private static List<String> inspectorTooltips(MixerChannel channel) {
        FxDispatcher dispatcher = new FxDispatcher();
        InspectorDrawer drawer = new InspectorDrawer();
        drawer.bindInserts(() -> channel, (host, slot) -> { }, host -> { }, dispatcher);
        try {
            return insertRows(drawer).stream()
                    .filter(row -> row.getChildren().getFirst().getStyleClass().contains("insert-dot-offline"))
                    .map(OfflineInsertSurfacesTest::installedTooltip).toList();
        } finally {
            drawer.disposeInsertBinding();
            dispatcher.dispose();
        }
    }

    private static List<HBox> insertRows(InspectorDrawer drawer) {
        return drawer.getInsertsSection().getRowsContainer().getChildren().stream().map(HBox.class::cast).toList();
    }

    private static String installedTooltip(Node node) {
        Object tooltip = node.getProperties().get(INSTALLED_TOOLTIP_KEY);
        assertThat(tooltip).as("the row has a tooltip installed").isInstanceOf(Tooltip.class);
        return ((Tooltip) tooltip).getText();
    }

    /** Requests {@code row}'s context menu as a right-click does and returns the menu that shows. */
    private static ContextMenu contextMenuOf(Node row) {
        row.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1, 1, 1, 1, false, null));
        return showingContextMenu();
    }

    /**
     * Requests the focus owner's context menu as Shift+F10 or the Menu key
     * does — {@code Scene.processMenuEvent} fires a keyboard-triggered request
     * at the focus owner — and returns the menu that shows.
     */
    private static ContextMenu keyboardContextMenu(Scene scene) {
        Node owner = scene.getFocusOwner();
        Bounds bounds = owner.getLayoutBounds();
        Point2D inScene = owner.localToScene(bounds.getCenterX(), bounds.getCenterY());
        Point2D onScreen = owner.localToScreen(bounds.getCenterX(), bounds.getCenterY());
        owner.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, inScene.getX(), inScene.getY(),
                onScreen.getX(), onScreen.getY(), true, null));
        return showingContextMenu();
    }

    private static ContextMenu showingContextMenu() {
        return Window.getWindows().stream().filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast)
                .filter(Window::isShowing).findFirst()
                .orElseThrow(() -> new AssertionError("the row's context menu shows"));
    }

    private static MixerChannel vox(DawProject project) {
        return project.getMixerChannelForTrack(project.getTracks().get(1));
    }

    /** The node in {@code row} with {@code styleClass}: an offline row's keyboard stop. */
    private static Node stopIn(Node row, String styleClass) {
        return descendants(row).filter(node -> node.getStyleClass().contains(styleClass)).findFirst()
                .orElseThrow(() -> new AssertionError("the offline row has a ." + styleClass + " node"));
    }

    /** The name label of a mixer strip's insert row. */
    private static Label nameIn(Node row) {
        return descendants(row).filter(Label.class::isInstance).map(Label.class::cast).findFirst()
                .orElseThrow(() -> new AssertionError("the row renders its name"));
    }

    /** Presses Tab {@code count} times from the focus owner, as the keyboard does; the focus owners reached. */
    private static List<Node> tabs(Scene scene, int count) {
        List<Node> reached = new ArrayList<>();
        for (int tab = 0; tab < count; tab++) {
            scene.getFocusOwner().requestFocusTraversal(TraversalDirection.NEXT);
            reached.add(scene.getFocusOwner());
        }
        return reached;
    }

    /** The offline Inspector rows' word helps, in chain order; each equals its row's tooltip. FX thread. */
    private static List<String> inspectorWordHelps(MixerChannel channel) {
        FxDispatcher dispatcher = new FxDispatcher();
        InspectorDrawer drawer = new InspectorDrawer();
        drawer.bindInserts(() -> channel, (host, slot) -> { }, host -> { }, dispatcher);
        try {
            List<String> helps = new ArrayList<>();
            for (HBox row : insertRows(drawer)) {
                if (row.getChildren().getFirst().getStyleClass().contains("insert-dot-offline")) {
                    Object help = stopIn(row, INSPECTOR_STOP).queryAccessibleAttribute(AccessibleAttribute.HELP);
                    assertThat(help).as("the word's help is its row's tooltip").isEqualTo(installedTooltip(row));
                    helps.add((String) help);
                }
            }
            return helps;
        } finally {
            drawer.disposeInsertBinding();
            dispatcher.dispose();
        }
    }

    /** The offline rack rows' mark helps, in chain order; each equals its name's and its row's tooltip. FX thread. */
    private static List<String> rackMarkHelps(MixerChannel channel) {
        InsertEffectRack rack = new InsertEffectRack(channel, 2, 48_000.0, 256, null, null, () -> true);
        try {
            List<String> helps = new ArrayList<>();
            for (Node node : rack.getChildren()) {
                if (node.getStyleClass().contains("insert-slot-offline")) {
                    HBox row = (HBox) node;
                    Object help = stopIn(row, RACK_STOP).queryAccessibleAttribute(AccessibleAttribute.HELP);
                    assertThat(help).as("the mark's help is its name's tooltip")
                            .isEqualTo(((Label) row.getChildren().get(1)).getTooltip().getText())
                            .as("and its row's").isEqualTo(installedTooltip(row));
                    helps.add((String) help);
                }
            }
            return helps;
        } finally {
            rack.dispose();
        }
    }

    /**
     * Drives an offline row's focused keyboard stop as a keyboard and an
     * assistive technology do — Space, Space again, Enter, Escape, an invoke,
     * the focus leaving — and checks after each that exactly one tooltip shows
     * {@code reason}, or none shows, that neither Space nor Escape reached an
     * accelerator, and that {@code unchanged} holds. Then, with nothing
     * showing, Escape on the focused mark goes on past it to its row, from
     * where it reaches the Inspector's collapse or the transport's Stop; a
     * probe on the row ends it there. Last, Space fired at {@code elsewhere}
     * reaches the Space accelerator: it is live. FX thread.
     */
    private static Accelerators showsTheReasonAndChangesNothing(Scene scene, Node mark, Node elsewhere, String reason,
                                                                Runnable unchanged) {
        Accelerators accelerators = new Accelerators(acceleratorRuns(scene, KeyCode.SPACE),
                acceleratorRuns(scene, KeyCode.ESCAPE));
        Runnable nothingElse = () -> {
            unchanged.run();
            assertThat(accelerators.plays().get()).as("Space on the mark never plays").isZero();
            assertThat(accelerators.escapes().get()).as("nor does Escape reach its accelerator").isZero();
        };
        mark.requestFocus();
        assertThat(scene.getFocusOwner()).as("fixture: the mark holds the focus").isSameAs(mark);

        stroke(scene.getFocusOwner(), KeyCode.SPACE);
        assertThat(shownText()).as("Space shows why the insert is offline and what to do").isEqualTo(reason);
        nothingElse.run();
        stroke(scene.getFocusOwner(), KeyCode.SPACE);
        assertThat(showingTooltips()).as("a second Space hides it").isEmpty();
        nothingElse.run();

        stroke(scene.getFocusOwner(), KeyCode.ENTER);
        assertThat(shownText()).as("Enter shows it").isEqualTo(reason);
        nothingElse.run();
        Event.fireEvent(scene.getFocusOwner(), key(KeyEvent.KEY_PRESSED, KeyCode.ESCAPE));
        assertThat(showingTooltips()).as("Escape hides it").isEmpty();
        nothingElse.run();

        mark.executeAccessibleAction(AccessibleAction.FIRE);
        assertThat(shownText()).as("an assistive technology's invoke shows it").isEqualTo(reason);
        nothingElse.run();
        mark.requestFocusTraversal(TraversalDirection.NEXT);
        assertThat(scene.getFocusOwner()).as("fixture: Tab moved the focus on").isNotSameAs(mark);
        assertThat(showingTooltips()).as("the focus leaving hides it").isEmpty();
        nothingElse.run();

        // With nothing showing, Escape is not the mark's: OfflineInsertMark consumes
        // it only to hide the reason. It goes on to the row, and from there to the
        // Inspector's collapse or the transport's Stop; the row's probe ends it
        // there, so neither runs here.
        AtomicInteger pastTheMark = new AtomicInteger();
        Parent row = mark.getParent();
        assertThat(row.getOnKeyPressed()).as("fixture: the row has no key handler of its own").isNull();
        row.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                pastTheMark.incrementAndGet();
                event.consume();
            }
        });
        try {
            mark.requestFocus();
            assertThat(scene.getFocusOwner()).as("fixture: the mark holds the focus again").isSameAs(mark);
            assertThat(showingTooltips()).as("fixture: nothing shows").isEmpty();
            Event.fireEvent(scene.getFocusOwner(), key(KeyEvent.KEY_PRESSED, KeyCode.ESCAPE));
            assertThat(pastTheMark.get()).as("with nothing showing, Escape on the mark goes on to its row")
                    .isEqualTo(1);
        } finally {
            row.setOnKeyPressed(null);
        }
        nothingElse.run();

        Event.fireEvent(elsewhere, key(KeyEvent.KEY_PRESSED, KeyCode.SPACE));
        assertThat(accelerators.plays().get())
                .as("non-vacuity: Space elsewhere in the row reaches the Space accelerator").isEqualTo(1);
        return accelerators;
    }

    /** Presses and releases {@code code} on {@code target}, as one key stroke does. */
    private static void stroke(Node target, KeyCode code) {
        Event.fireEvent(target, key(KeyEvent.KEY_PRESSED, code));
        Event.fireEvent(target, key(KeyEvent.KEY_RELEASED, code));
    }

    private static KeyEvent key(EventType<KeyEvent> type, KeyCode code) {
        return new KeyEvent(type, KeyEvent.CHAR_UNDEFINED, "", code, false, false, false, false);
    }

    /** Puts an accelerator for the unmodified {@code code} on {@code scene}; the count of its runs. */
    private static AtomicInteger acceleratorRuns(Scene scene, KeyCode code) {
        AtomicInteger runs = new AtomicInteger();
        scene.getAccelerators().put(new KeyCodeCombination(code), runs::incrementAndGet);
        return runs;
    }

    private static List<Tooltip> showingTooltips() {
        return Window.getWindows().stream().filter(Tooltip.class::isInstance).map(Tooltip.class::cast)
                .filter(Window::isShowing).toList();
    }

    /** The text of the one tooltip showing. */
    private static String shownText() {
        List<Tooltip> showing = showingTooltips();
        assertThat(showing).as("exactly one tooltip shows").hasSize(1);
        return showing.getFirst().getText();
    }

    /** {@code content} as the themed root of a scene, as the application styles it. FX thread. */
    private static Scene themedScene(Node content, double width, double height) {
        StackPane root = new StackPane(content);
        root.getStyleClass().add("root-pane");
        Scene scene = new Scene(root, width, height);
        ThemeManager.getDefault().applyTo(scene);
        return scene;
    }

    /** The accelerator counts {@link #showsTheReasonAndChangesNothing} put on the scene. */
    record Accelerators(AtomicInteger plays, AtomicInteger escapes) {
        Accelerators {
            Objects.requireNonNull(plays, "plays must not be null");
            Objects.requireNonNull(escapes, "escapes must not be null");
        }
    }

    /** A channel's chain by identity and every slot's stored bypass: what an activation must leave as it was (E3). */
    record Chain(List<InsertSlot> slots, List<Boolean> bypassed) {
        Chain {
            slots = List.copyOf(slots);
            bypassed = List.copyOf(bypassed);
        }

        static Chain of(MixerChannel channel) {
            List<InsertSlot> slots = List.copyOf(channel.getInsertSlots());
            return new Chain(slots, slots.stream().map(InsertSlot::isBypassed).toList());
        }
    }

    /** The Inspector bound to a channel, expanded in a shown, themed stage. Built, used and closed on the FX thread. */
    private static final class ShownInspector implements AutoCloseable {

        private final FxDispatcher dispatcher = new FxDispatcher();
        private final InspectorDrawer drawer = new InspectorDrawer();
        private final List<InsertSlot> opened = new ArrayList<>();
        private final Stage stage = new Stage();

        ShownInspector(MixerChannel channel) {
            drawer.setAnimated(false);
            drawer.setExpanded(true);
            drawer.bindInserts(() -> channel, (host, slot) -> opened.add(slot), host -> { }, dispatcher);
            stage.setScene(themedScene(drawer, 320, 900));
            stage.show();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
        }

        Scene scene() {
            return stage.getScene();
        }

        InspectorDrawer drawer() {
            return drawer;
        }

        List<InsertSlot> opened() {
            return opened;
        }

        List<HBox> rows() {
            return insertRows(drawer);
        }

        @Override
        public void close() {
            stage.hide();
            drawer.disposeInsertBinding();
            dispatcher.dispose();
        }
    }

    /** The editing rack of a channel, in a shown, themed stage. Built, used and closed on the FX thread. */
    private static final class ShownRack implements AutoCloseable {

        private final UndoManager undo = new UndoManager();
        private final FxDispatcher dispatcher = new FxDispatcher();
        private final List<InsertSlot> opened = new ArrayList<>();
        private final InsertEffectRack rack;
        private final Stage stage = new Stage();

        ShownRack(DawProject project, MixerChannel channel) {
            rack = new InsertEffectRack(channel, 2, 48_000.0, 256, undo, dispatcher, () -> true);
            rack.setOnOpenEditor((host, slot) -> opened.add(slot));
            rack.setMixer(project.getMixer());
            rack.rebuildSlots();
            stage.setScene(themedScene(rack, 200, 400));
            stage.show();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
        }

        Scene scene() {
            return stage.getScene();
        }

        InsertEffectRack rack() {
            return rack;
        }

        HBox row(int index) {
            return (HBox) rack.getChildren().get(index);
        }

        UndoManager undo() {
            return undo;
        }

        List<InsertSlot> opened() {
            return opened;
        }

        @Override
        public void close() {
            rack.dispose();
            stage.hide();
            dispatcher.dispose();
        }
    }

    private static MouseEvent doubleClick() {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 2,
                false, false, false, false, true, false, false, false, false, true, null);
    }

    private static Circle dotIn(Node row) {
        return descendants(row).filter(Circle.class::isInstance).map(Circle.class::cast).findFirst()
                .orElseThrow(() -> new AssertionError("the row renders a status dot"));
    }

    private static Stream<Node> descendants(Node node) {
        if (node instanceof Parent parent) {
            return Stream.concat(Stream.of(node),
                    parent.getChildrenUnmodifiable().stream().flatMap(OfflineInsertSurfacesTest::descendants));
        }
        return Stream.of(node);
    }
}
