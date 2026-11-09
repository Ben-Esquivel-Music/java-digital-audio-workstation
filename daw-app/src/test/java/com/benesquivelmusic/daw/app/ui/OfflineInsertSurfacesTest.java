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

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 334, studio-engineer ruling E22 (with E3, G7, G16 and R1): an insert a
 * project open could not load is kept at its place, passing audio through, and
 * every surface that shows inserts marks it offline. Through a real reopen —
 * the project reader turns the saved {@code <insert>} elements into offline
 * slots — the rack row carries {@code insert-slot-offline}, the accessible
 * text {@code <name>, offline}, a warn mark instead of the bypass toggle, no
 * double-click editor, no sidechain selector and a context menu of Remove only
 * (which removes it as an undoable edit); the mixer strip's dot takes
 * {@code offline}; the track-header badge takes {@code offline} with the
 * tooltip {@code <name> (offline)}, whatever the stored bypass; the
 * Inspector's row takes {@code insert-dot-offline} and the word
 * {@code Offline}, without the ✎ pencil; and the editor door opens no
 * session. Each row's tooltip names the cause and what to do about it, in
 * the ruled words for every cause.
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
            assertThat(row.getAccessibleText()).isEqualTo("Old FX, offline");
            Label mark = (Label) row.getChildren().getFirst();
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
            assertThat(offline.getAccessibleText()).isEqualTo("Old FX, offline");
            assertThat(skin.insertDotActive(1)).isFalse();
            assertThat(skin.insertDotActive(2)).as("the engaged offline insert's dot is not the active colour")
                    .isFalse();
            assertThat(dotIn(skin.insertRowNode(2)).getStyleClass()).contains("offline").doesNotContain("active");
            Node working = skin.insertRowNode(0);
            assertThat(dotIn(working).getStyleClass()).contains("active").doesNotContain("offline");
            assertThat(working.getAccessibleText()).isEqualTo("Compressor");
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
            Label word = (Label) row.getChildren().getLast();
            assertThat(word.getText()).isEqualTo("Offline");
            assertThat(word.getStyleClass()).contains("inspector-row-offline");
            assertThat(descendants(row)).as("no ✎ pencil")
                    .noneMatch(Button.class::isInstance)
                    .noneMatch(node -> node.getStyleClass().contains("inspector-row-edit"));
            assertThat(row.getAccessibleText()).isEqualTo("Old FX, offline");
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
        return Window.getWindows().stream().filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast)
                .filter(Window::isShowing).findFirst()
                .orElseThrow(() -> new AssertionError("the row's context menu shows"));
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
