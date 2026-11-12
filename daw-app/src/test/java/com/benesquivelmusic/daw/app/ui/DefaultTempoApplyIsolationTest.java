package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.transport.TempoChangeEvent;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 — "Tempo isolation test" (studio rulings R4 and ruling-2,
 * engineer critique S1): an Apply of unrelated rows never touches the open
 * project's tempo; an edited default tempo lands on a non-pristine
 * constant-tempo project as one undoable step (undo restores the prior
 * tempo); a pristine Untitled project takes it as its template; a bulk Apply
 * (wizard Finish, profile import, Restore All Defaults) and a project with
 * tempo changes are never written, and say so.
 *
 * <p>The end-to-end cases run the production Settings surface through a
 * partial {@link MainController} — {@code new MainController()} with its
 * fields set, the {@code MainControllerAudioSettingsWiringTest} idiom (it is
 * never FXML-loaded: {@code initialize()} builds a live audio engine) — and
 * its real {@code createSettingsDialog()}: its live-settings listener and its
 * default-tempo listener. The decision itself,
 * {@link MainController#applyEditedDefaultTempo}, is pinned for every outcome
 * with a gate supplier. The Apply's {@code SettingsApplied} publish goes to a
 * private bus and the theme manager is an isolated one, so the user's real
 * preferences are never touched.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DefaultTempoApplyIsolationTest {

    private static final String BULK_TEXT =
            "Default tempo saved for new projects. The open project's tempo was not changed.";
    private static final String TEMPO_CHANGES_TEXT =
            "Default tempo saved for new projects. The open project has tempo changes, so its tempo was not changed.";

    private final List<Preferences> nodes = new ArrayList<>();
    private EventBus previousBus;
    private DefaultEventBus bus;

    @BeforeEach
    void isolateTheBusAndTheThemeManager() {
        previousBus = EventBusPublisher.getDefault();
        bus = DefaultEventBus.builder().uiExecutor(Runnable::run).build();
        EventBusPublisher.setDefault(bus);
        ThemeManager.setDefaultForTest(new ThemeManager(node("defaultTempoIsolationTheme")));
    }

    @AfterEach
    void restore() throws BackingStoreException {
        ThemeManager.setDefaultForTest(null);
        EventBusPublisher.setDefault(previousBus);
        bus.close();
        for (Preferences node : nodes) {
            node.removeNode();
        }
    }

    // ── End to end through MainController.createSettingsDialog() ──────────

    @Test
    void anApplyOfOnlyTheThemeRowLeavesTheOpenProjectsTempoAlone() throws Exception {
        Harness harness = harness(133.0, false);
        String status = Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = harness.controller().createSettingsDialog();
            SettingRow theme = dialog.getShell().settingRow(ThemeManager.PREF_KEY).orElseThrow();
            ThemeManager.Theme other = Arrays.stream(ThemeManager.Theme.values())
                    .filter(candidate -> candidate != theme.getValue()).findFirst().orElseThrow();
            theme.setValue(other);
            assertThat(dialog.getShell().pendingEdits()).containsOnlyKeys(ThemeManager.PREF_KEY);
            dialog.applySettings();
            return harness.status().getText();
        }, 30, TimeUnit.SECONDS);

        assertThat(harness.project().getTransport().getTempo()).isEqualTo(133.0);
        assertThat(harness.history().undoSize()).isZero();
        assertThat(status).isEmpty();
    }

    @Test
    void anEditedDefaultTempoLandsAsOneUndoableStepAndUndoRestoresThePriorTempo() throws Exception {
        Harness harness = harness(133.0, false);
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = harness.controller().createSettingsDialog();
            dialog.getShell().settingRow("project.defaultTempo").orElseThrow().setValue("140");
            dialog.applySettings();
            return null;
        }, 30, TimeUnit.SECONDS);

        assertThat(harness.model().getDefaultTempo()).isEqualTo(140.0);
        assertThat(harness.project().getTransport().getTempo()).isEqualTo(140.0);
        assertThat(harness.history().undoSize()).isEqualTo(1);
        assertThat(harness.history().undoDescription()).isEqualTo(String.format("Set Tempo to %.1f BPM", 140.0));
        assertThat(Story307TestSupport.onFx(() -> harness.status().getText()))
                .isEqualTo(String.format("Tempo set to %.1f BPM", 140.0));
        Story307TestSupport.onFx(() -> harness.history().undo());
        assertThat(harness.project().getTransport().getTempo()).isEqualTo(133.0);
    }

    @Test
    void aProjectWithTempoChangesIsNotWrittenAndTheNoticeSaysWhy() throws Exception {
        Harness harness = harness(133.0, false);
        harness.project().getTransport().getTempoMap().addTempoChange(TempoChangeEvent.instant(16, 90));
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = harness.controller().createSettingsDialog();
            dialog.getShell().settingRow("project.defaultTempo").orElseThrow().setValue("150");
            dialog.applySettings();
            return null;
        }, 30, TimeUnit.SECONDS);

        assertThat(harness.model().getDefaultTempo()).as("saved for new projects").isEqualTo(150.0);
        assertThat(harness.project().getTransport().getTempo()).isEqualTo(133.0);
        assertThat(harness.history().undoSize()).isZero();
        assertThat(harness.bar().getCurrentLevel()).isEqualTo(NotificationLevel.INFO);
        assertThat(harness.bar().getMessage()).isEqualTo(TEMPO_CHANGES_TEXT);
    }

    @Test
    void aPristineUntitledProjectTakesTheEditedTempoAsItsTemplate() throws Exception {
        Harness harness = harness(120.0, true);
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = harness.controller().createSettingsDialog();
            dialog.getShell().settingRow("project.defaultTempo").orElseThrow().setValue("140");
            dialog.applySettings();
            return null;
        }, 30, TimeUnit.SECONDS);

        assertThat(harness.project().getTransport().getTempo()).isEqualTo(140.0);
        assertThat(harness.history().undoSize()).isZero();
        assertThat(harness.history().redoSize()).isZero();
        assertThat(harness.project().isDirty()).as("a template is no edit").isFalse();
        assertThat(harness.bar().getCurrentLevel()).as("and says nothing").isNull();
    }

    @Test
    void theWizardsBulkApplyNeverWritesANonPristineProjectAndSaysSo() throws Exception {
        Harness harness = harness(133.0, false);
        Story307TestSupport.onFx(() -> MainController.applyWizardEdits(harness.controller().createSettingsDialog(),
                Map.of("project.defaultTempo", "140")), 30, TimeUnit.SECONDS);

        assertThat(harness.model().getDefaultTempo()).as("saved for new projects").isEqualTo(140.0);
        assertThat(harness.project().getTransport().getTempo()).isEqualTo(133.0);
        assertThat(harness.history().undoSize()).isZero();
        assertThat(harness.bar().getCurrentLevel()).isEqualTo(NotificationLevel.INFO);
        assertThat(harness.bar().getMessage()).isEqualTo(BULK_TEXT);
    }

    /** Studio ruling-2 Q1 — Restore All Defaults is a bulk Apply: the song keeps its tempo, and the INFO says so. */
    @Test
    void restoreAllDefaultsNeverWritesANonPristineProjectsTempo() throws Exception {
        Harness harness = harness(150.0, false);
        harness.model().setDefaultTempo(150.0);
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = harness.controller().createSettingsDialog();
            dialog.setRestoreAllConfirm(() -> true);
            dialog.restoreAllDefaults();
            return null;
        }, 30, TimeUnit.SECONDS);

        assertThat(harness.model().getDefaultTempo()).isEqualTo(SettingsModel.DEFAULT_TEMPO);
        assertThat(harness.project().getTransport().getTempo()).isEqualTo(150.0);
        assertThat(harness.history().undoSize()).isZero();
        assertThat(harness.bar().getCurrentLevel()).isEqualTo(NotificationLevel.INFO);
        assertThat(harness.bar().getMessage()).isEqualTo(BULK_TEXT);
    }

    /**
     * Studio ruling-2 Q2 — the three reset tiers only stage the default; the
     * Settings dialog's own Apply that commits them is interactive.
     */
    @Test
    void everyResetTierThenApplyIsAnInteractiveEdit() throws Exception {
        List<Consumer<SettingsShell>> tiers = List.of(
                shell -> shell.settingRow("project.defaultTempo").orElseThrow().resetToDefault(),
                shell -> shell.resetGroup("project", "defaults"),
                shell -> shell.resetCategory("project"));
        for (Consumer<SettingsShell> tier : tiers) {
            SettingsModel model = new SettingsModel(node("defaultTempoResetTier"));
            model.setDefaultTempo(128.0);
            List<SettingsDialog.DefaultTempoEdit> edits = Story307TestSupport.onFx(() -> {
                List<SettingsDialog.DefaultTempoEdit> heard = new ArrayList<>();
                SettingsDialog dialog = new SettingsDialog(model);
                dialog.setDefaultTempoEditListener(heard::add);
                tier.accept(dialog.getShell());
                dialog.applySettings();
                return heard;
            }, 30, TimeUnit.SECONDS);
            assertThat(edits).containsExactly(new SettingsDialog.DefaultTempoEdit(SettingsModel.DEFAULT_TEMPO, false));
        }
    }

    // ── The decision, outcome by outcome ──────────────────────────────────

    @Test
    void theDecisionReadsTheGateFirst() {
        DawProject project = project(133.0);
        UndoManager history = new UndoManager();
        for (boolean pristine : new boolean[] {true, false}) {
            for (boolean bulk : new boolean[] {true, false}) {
                assertThat(MainController.applyEditedDefaultTempo(project, history, () -> false, pristine, bulk,
                        140.0)).isEqualTo(MainController.DefaultTempoOutcome.GATE_CLOSED);
            }
        }
        assertThat(project.getTransport().getTempo()).isEqualTo(133.0);
        assertThat(history.undoSize()).isZero();
    }

    @Test
    void aPristineProjectTakesTheTemplateWhateverTheOrigin() {
        for (boolean bulk : new boolean[] {true, false}) {
            DawProject project = project(120.0);
            UndoManager history = new UndoManager();
            assertThat(MainController.applyEditedDefaultTempo(project, history, () -> true, true, bulk, 97.0))
                    .isEqualTo(MainController.DefaultTempoOutcome.TEMPLATE);
            assertThat(project.getTransport().getTempo()).isEqualTo(97.0);
            assertThat(history.undoSize()).isZero();
            assertThat(project.isDirty()).isFalse();
        }
    }

    @Test
    void aBulkEditNeverWritesANonPristineProject() {
        DawProject project = project(133.0);
        UndoManager history = new UndoManager();
        assertThat(MainController.applyEditedDefaultTempo(project, history, () -> true, false, true, 140.0))
                .isEqualTo(MainController.DefaultTempoOutcome.BULK_NOT_WRITTEN);
        assertThat(project.getTransport().getTempo()).isEqualTo(133.0);
        assertThat(history.undoSize()).isZero();
    }

    @Test
    void tempoChangesAreNeverWrittenEvenInAPristineProject() {
        for (boolean pristine : new boolean[] {true, false}) {
            DawProject project = project(133.0);
            project.getTransport().getTempoMap().addTempoChange(TempoChangeEvent.instant(16, 90));
            UndoManager history = new UndoManager();
            assertThat(MainController.applyEditedDefaultTempo(project, history, () -> true, pristine, false, 140.0))
                    .isEqualTo(MainController.DefaultTempoOutcome.TEMPO_CHANGES);
            assertThat(project.getTransport().getTempo()).isEqualTo(133.0);
            assertThat(history.undoSize()).isZero();
        }
    }

    @Test
    void anEqualTempoWritesNothingAndADifferentOneWritesOneUndoableStep() {
        DawProject project = project(133.0);
        UndoManager history = new UndoManager();
        assertThat(MainController.applyEditedDefaultTempo(project, history, () -> true, false, false, 133.0))
                .isEqualTo(MainController.DefaultTempoOutcome.UNCHANGED);
        assertThat(history.undoSize()).isZero();

        assertThat(MainController.applyEditedDefaultTempo(project, history, () -> true, false, false, 140.0))
                .isEqualTo(MainController.DefaultTempoOutcome.WRITTEN);
        assertThat(project.getTransport().getTempo()).isEqualTo(140.0);
        assertThat(history.undoSize()).isEqualTo(1);
        assertThat(history.undoDescription()).isEqualTo(String.format("Set Tempo to %.1f BPM", 140.0));
        assertThat(project.getTransport().getTempoMap().getTempoChangeCount()).as("beat 0 only").isEqualTo(1);
        history.undo();
        assertThat(project.getTransport().getTempo()).isEqualTo(133.0);
    }

    // ── Fixture ───────────────────────────────────────────────────────────

    private record Harness(MainController controller, SettingsModel model, DawProject project, UndoManager history,
                           NotificationBar bar, Label status) {}

    /**
     * A partial controller over a constant-tempo project at {@code tempo},
     * with an empty history and no project folder: pristine (clean) when asked,
     * else made non-pristine by an unsaved change.
     */
    private Harness harness(double tempo, boolean pristine) throws Exception {
        SettingsModel model = new SettingsModel(node("defaultTempoIsolation"));
        DawProject project = project(tempo);
        if (!pristine) {
            project.markDirty();
        }
        UndoManager history = new UndoManager();
        NotificationBar bar = Story307TestSupport.onFx(() -> {
            NotificationBar created = new NotificationBar();
            created.setAnimated(false);
            return created;
        });
        Label status = Story307TestSupport.onFx(Label::new);
        MainController controller = new MainController();
        setField(controller, "settingsModel", model);
        setField(controller, "project", project);
        setField(controller, "undoManager", history);
        setField(controller, "projectManager", new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT)));
        setField(controller, "pluginRegistry", new PluginRegistry());
        setField(controller, "notificationBar", bar);
        setField(controller, "statusBarLabel", status);
        setField(controller, "rootPane", Story307TestSupport.onFx(BorderPane::new));
        return new Harness(controller, model, project, history, bar, status);
    }

    private static DawProject project(double tempo) {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.getTransport().setTempo(tempo);
        project.markClean();
        return project;
    }

    private Preferences node(String prefix) {
        Preferences node = Preferences.userRoot().node(prefix + "_" + System.nanoTime());
        nodes.add(node);
        return node;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
