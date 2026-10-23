package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 330 (studio-engineer rulings F1, F2) — the pre/post-roll controls
 * over a transport that restores {@code (preBars, postBars, enabled)} from the
 * project file as three stored facts.
 *
 * <p>A toggle click works from what the toggle shows (its side rolls when
 * {@code enabled && bars > 0}) and never changes whether the other side
 * rolls; a spinner edit changes only its side's bar count and keeps
 * {@code enabled}. Each of the four controls marks the project dirty when it
 * changes the stored value and only then — no undo entry — and building the
 * controls over any restored value, the sync that shows it included, leaves
 * the transport and the project's clean state untouched (Control
 * Synchronization Design Book §5.2: dirty-only, like loop and punch).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PreRollPostRollControlsTest {

    private static final AudioFormat FORMAT = new AudioFormat(48000, 2, 16, 256);

    private final List<TransportController> controllers = new CopyOnWriteArrayList<>();
    private UndoManager undoManager;
    private Label statusBarLabel;

    @AfterEach
    void retireTheControllers() throws Exception {
        for (TransportController controller : controllers) {
            runOnFx(controller::retire);
        }
    }

    // ── the toggles ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "({0}, {1}, {2}) Pre-Roll click → ({3}, {4}, {5})")
    @CsvSource({
            // stored          after the click
            "2, 0, false,      2, 0, true",    // an inert amount: on with it
            "0, 0, true,       2, 0, true",    // nothing to roll: on with the default
            "0, 0, false,      2, 0, true",    // DISABLED
            "2, 3, true,       0, 3, true",    // off; post-roll keeps rolling
            "0, 3, true,       2, 3, true",    // on; post-roll keeps rolling
            "2, 3, false,      2, 0, true",    // on; the inert post amount is dropped, not armed
    })
    void aPreRollClickWorksFromWhatTheToggleShows(int pre, int post, boolean enabled,
                                                  int expectedPre, int expectedPost, boolean expectedEnabled)
            throws Exception {
        PreRollPostRoll expected = new PreRollPostRoll(expectedPre, expectedPost, expectedEnabled);
        DawProject project = projectHolding(new PreRollPostRoll(pre, post, enabled));
        TransportController controller = controlsOver(project);

        runOnFx(() -> controller.preRollToggleForTest().fire());

        assertThat(project.getTransport().getPreRollPostRoll()).isEqualTo(expected);
        assertShows(controller, expected);
        assertThat(onFx(statusBarLabel::getText)).isEqualTo("Pre-roll: " + (rolls(expected, true) ? "ON" : "OFF"));
    }

    @ParameterizedTest(name = "({0}, {1}, {2}) Post-Roll click → ({3}, {4}, {5})")
    @CsvSource({
            // stored          after the click
            "2, 0, false,      0, 2, true",    // on with the default; the inert pre amount is dropped
            "0, 0, true,       0, 2, true",
            "0, 0, false,      0, 2, true",    // DISABLED
            "2, 3, true,       2, 0, true",    // off; pre-roll keeps rolling
            "0, 3, true,       0, 3, false",   // off; enabled cleared, the amount kept
            "2, 3, false,      0, 3, true",    // on with the stored amount
    })
    void aPostRollClickWorksFromWhatTheToggleShows(int pre, int post, boolean enabled,
                                                   int expectedPre, int expectedPost, boolean expectedEnabled)
            throws Exception {
        PreRollPostRoll expected = new PreRollPostRoll(expectedPre, expectedPost, expectedEnabled);
        DawProject project = projectHolding(new PreRollPostRoll(pre, post, enabled));
        TransportController controller = controlsOver(project);

        runOnFx(() -> controller.postRollToggleForTest().fire());

        assertThat(project.getTransport().getPreRollPostRoll()).isEqualTo(expected);
        assertShows(controller, expected);
        assertThat(onFx(statusBarLabel::getText)).isEqualTo("Post-roll: " + (rolls(expected, false) ? "ON" : "OFF"));
    }

    // ── the spinners ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "({0}, {1}, {2}) {3} spinner → {4} gives ({5}, {6}, {7})")
    @CsvSource({
            // stored          side  value  after the edit
            "2, 0, false,      pre,  3,     3, 0, false",   // a new amount arms nothing
            "2, 0, false,      post, 1,     2, 1, false",
            "2, 0, false,      pre,  0,     0, 0, false",   // both zero: DISABLED
            "0, 0, true,       pre,  3,     3, 0, true",    // enabled: the side moved off zero rolls
            "0, 0, true,       post, 1,     0, 1, true",
            "0, 0, false,      pre,  3,     3, 0, false",   // DISABLED
            "0, 0, false,      post, 1,     0, 1, false",
            "2, 3, true,       pre,  0,     0, 3, true",
            "2, 3, true,       post, 0,     2, 0, true",
            "2, 3, true,       pre,  5,     5, 3, true",
            "0, 3, true,       post, 0,     0, 0, false",   // both zero: DISABLED
            "0, 3, true,       pre,  2,     2, 3, true",
    })
    void aSpinnerEditChangesOnlyItsSidesBarsAndKeepsEnabled(int pre, int post, boolean enabled, String side,
                                                           int value, int expectedPre, int expectedPost,
                                                           boolean expectedEnabled) throws Exception {
        PreRollPostRoll expected = new PreRollPostRoll(expectedPre, expectedPost, expectedEnabled);
        DawProject project = projectHolding(new PreRollPostRoll(pre, post, enabled));
        TransportController controller = controlsOver(project);

        runOnFx(() -> spinner(controller, side).getValueFactory().setValue(value));

        assertThat(project.getTransport().getPreRollPostRoll()).isEqualTo(expected);
        assertShows(controller, expected);
    }

    // ── dirty: a changed value marks it, the stored value does not ───────────

    @Test
    void eachOfTheFourControlsMarksTheProjectDirtyWhenItChangesTheValue() throws Exception {
        DawProject project = projectHolding(PreRollPostRoll.DISABLED);
        TransportController controller = controlsOver(project);

        runOnFx(() -> controller.preRollToggleForTest().fire());
        assertThat(project.isDirty()).as("the Pre-Roll toggle").isTrue();

        runOnFx(project::markClean);
        runOnFx(() -> controller.postRollToggleForTest().fire());
        assertThat(project.isDirty()).as("the Post-Roll toggle").isTrue();

        runOnFx(project::markClean);
        runOnFx(() -> controller.preRollSpinnerForTest().getValueFactory().setValue(4));
        assertThat(project.isDirty()).as("the pre-roll spinner").isTrue();

        runOnFx(project::markClean);
        runOnFx(() -> controller.postRollSpinnerForTest().getValueFactory().setValue(6));
        assertThat(project.isDirty()).as("the post-roll spinner").isTrue();

        runOnFx(project::markClean);
        runOnFx(() -> {
            Spinner<Integer> preSpinner = controller.preRollSpinnerForTest();
            preSpinner.getEditor().setText("7");
            preSpinner.commitValue();
        });
        assertThat(project.isDirty()).as("a typed value committed").isTrue();

        assertThat(project.getTransport().getPreRollPostRoll()).isEqualTo(new PreRollPostRoll(7, 6, true));
        assertThat(onFx(undoManager::canUndo)).as("no undo entry").isFalse();
        assertThat(onFx(undoManager::getHistory)).isEmpty();
    }

    @Test
    void aWriteOfTheStoredValueLeavesTheProjectClean() throws Exception {
        PreRollPostRoll stored = new PreRollPostRoll(3, 1, true);
        DawProject project = projectHolding(stored);
        TransportController controller = controlsOver(project);

        runOnFx(() -> controller.preRollSpinnerForTest().getValueFactory().setValue(3));
        runOnFx(() -> controller.postRollSpinnerForTest().getValueFactory().setValue(1));
        assertThat(project.isDirty()).as("a spinner set to its current value").isFalse();

        runOnFx(() -> {
            Spinner<Integer> preSpinner = controller.preRollSpinnerForTest();
            preSpinner.getEditor().setText("3");
            preSpinner.commitValue();
        });
        assertThat(project.isDirty()).as("a typed identical value committed").isFalse();

        // The transport changes behind the controls (no write through them);
        // the sync that shows it echoes every value back through the spinner
        // listeners, which find the stored bar counts and write nothing.
        PreRollPostRoll behind = new PreRollPostRoll(6, 0, false);
        runOnFx(() -> {
            project.getTransport().setPreRollPostRoll(behind);
            controller.syncPreRollControls();
        });
        assertThat(project.getTransport().getPreRollPostRoll()).as("the echo wrote nothing").isEqualTo(behind);
        assertThat(project.isDirty()).as("the sync").isFalse();
        assertShows(controller, behind);
        assertThat(onFx(undoManager::canUndo)).isFalse();
    }

    // ── building the controls over a restored value ──────────────────────────

    @ParameterizedTest(name = "({0}, {1}, {2})")
    @CsvSource({
            "2, 0, false",
            "0, 0, true",
            "0, 0, false",
            "3, 1, true",
            "8, 8, true",
    })
    void buildingTheControlsLeavesTheRestoredValueAndTheCleanStateUntouched(int pre, int post, boolean enabled)
            throws Exception {
        PreRollPostRoll stored = new PreRollPostRoll(pre, post, enabled);
        DawProject project = projectHolding(stored);
        TransportController controller = newController(project);

        runOnFx(controller::createPreRollPostRollControls);

        assertThat(project.getTransport().getPreRollPostRoll()).isEqualTo(stored);
        assertThat(project.isDirty()).as("a just-opened project stays clean").isFalse();
        assertShows(controller, stored);
    }

    /** Every value a transport can hold: each bar count 0 to {@link PreRollPostRoll#MAX_BARS}, enabled or not. */
    static Stream<Arguments> everyStorableValue() {
        List<Arguments> values = new ArrayList<>();
        for (int pre = 0; pre <= PreRollPostRoll.MAX_BARS; pre++) {
            for (int post = 0; post <= PreRollPostRoll.MAX_BARS; post++) {
                values.add(Arguments.of(pre, post, false));
                values.add(Arguments.of(pre, post, true));
            }
        }
        return values.stream();
    }

    @Test
    void theStorableValuesAreEveryBarCountTheSpinnersShowOnBothSidesEnabledOrNot() {
        assertThat(everyStorableValue()).hasSize(162);
    }

    @ParameterizedTest(name = "({0}, {1}, {2})")
    @MethodSource("everyStorableValue")
    void buildingTheControlsOverAnyStorableValueWritesNothingAndKeepsTheProjectClean(int pre, int post,
                                                                                     boolean enabled)
            throws Exception {
        PreRollPostRoll stored = new PreRollPostRoll(pre, post, enabled);
        DawProject project = projectHolding(stored);
        assertThat(project.getTransport().getPreRollPostRoll()).as("fixture: the transport holds it")
                .isEqualTo(stored);
        TransportController controller = newController(project);

        runOnFx(controller::createPreRollPostRollControls);

        assertThat(project.getTransport().getPreRollPostRoll()).isEqualTo(stored);
        assertThat(project.isDirty()).as("a clean project stays clean").isFalse();
        assertShows(controller, stored);
    }

    @Test
    void aReopenedFileWhoseBarCountTheReaderBoundedStaysCleanOnceTheControlsAreBuilt() throws Exception {
        String xml = """
                <daw-project version="%d">
                  <transport pre-roll-bars="12" post-roll-bars="3" pre-roll-enabled="true"/>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION);
        DawProject loaded = new ProjectDeserializer().deserialize(xml);
        PreRollPostRoll bounded = new PreRollPostRoll(PreRollPostRoll.MAX_BARS, 3, true);
        assertThat(loaded.getTransport().getPreRollPostRoll())
                .as("fixture: the reader restores a count above the largest as the largest")
                .isEqualTo(bounded);
        runOnFx(loaded::markClean);
        TransportController controller = newController(loaded);

        runOnFx(controller::createPreRollPostRollControls);

        assertThat(loaded.getTransport().getPreRollPostRoll()).isEqualTo(bounded);
        assertThat(loaded.isDirty()).isFalse();
        assertShows(controller, bounded);
    }

    @Test
    void aProjectLoadedFromItsFileKeepsAnInertPreRollAndStaysCleanOnceTheControlsAreBuilt() throws Exception {
        PreRollPostRoll inert = new PreRollPostRoll(2, 0, false);
        DawProject saved = new DawProject("Saved", FORMAT);
        saved.getTransport().setPreRollPostRoll(inert);
        String xml = new ProjectSerializer().serialize(saved);
        DawProject loaded = new ProjectDeserializer().deserialize(xml);
        assertThat(loaded.getTransport().getPreRollPostRoll()).as("fixture: the file restores it").isEqualTo(inert);
        runOnFx(loaded::markClean);
        TransportController controller = newController(loaded);

        runOnFx(controller::createPreRollPostRollControls);

        assertThat(loaded.getTransport().getPreRollPostRoll()).isEqualTo(inert);
        assertThat(loaded.isDirty()).isFalse();
        assertShows(controller, inert);
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private static DawProject projectHolding(PreRollPostRoll stored) throws Exception {
        DawProject project = new DawProject("Song", FORMAT);
        project.getTransport().setPreRollPostRoll(stored);
        runOnFx(project::markClean);
        return project;
    }

    /** A controller over {@code project} with its controls built; the project is still clean. */
    private TransportController controlsOver(DawProject project) throws Exception {
        TransportController controller = newController(project);
        runOnFx(controller::createPreRollPostRollControls);
        assertThat(project.isDirty()).as("fixture: building the controls wrote nothing").isFalse();
        return controller;
    }

    private TransportController newController(DawProject project) throws Exception {
        TransportController controller = onFx(() -> {
            undoManager = new UndoManager();
            statusBarLabel = new Label();
            return new TransportController(project, new AudioEngine(project.getFormat()), undoManager,
                    new NotificationBar(), new Label(), statusBarLabel, new Label(),
                    new Button(), new Button(),
                    () -> false,
                    () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF,
                    track -> { },
                    () -> true,
                    () -> RoundTripLatency.UNKNOWN);
        });
        controllers.add(controller);
        return controller;
    }

    private static Spinner<Integer> spinner(TransportController controller, String side) {
        return switch (side) {
            case "pre" -> controller.preRollSpinnerForTest();
            case "post" -> controller.postRollSpinnerForTest();
            default -> throw new IllegalArgumentException("side: " + side);
        };
    }

    /** Whether {@code prpr}'s pre-roll ({@code pre}) or post-roll side rolls: what its toggle shows. */
    private static boolean rolls(PreRollPostRoll prpr, boolean pre) {
        return prpr.enabled() && (pre ? prpr.preBars() : prpr.postBars()) > 0;
    }

    /** The controls show {@code prpr}: each toggle selected while its side rolls, each spinner its bar count. */
    private static void assertShows(TransportController controller, PreRollPostRoll prpr) throws Exception {
        assertThat(onFx(() -> controller.preRollToggleForTest().isSelected())).as("Pre-Roll toggle")
                .isEqualTo(rolls(prpr, true));
        assertThat(onFx(() -> controller.postRollToggleForTest().isSelected())).as("Post-Roll toggle")
                .isEqualTo(rolls(prpr, false));
        assertThat(onFx(() -> controller.preRollSpinnerForTest().getValue())).as("pre-roll spinner")
                .isEqualTo(prpr.preBars());
        assertThat(onFx(() -> controller.postRollSpinnerForTest().getValue())).as("post-roll spinner")
                .isEqualTo(prpr.postBars());
    }
}
