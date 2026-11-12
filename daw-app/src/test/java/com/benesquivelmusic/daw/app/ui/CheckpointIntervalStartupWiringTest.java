package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 — "Interval startup-wiring test": a fresh launch constructs the
 * window's {@code CheckpointManager} from the persisted autosave interval, not
 * {@link AutoSaveConfig#DEFAULT}, and the strip's countdown — the
 * {@code ProjectOperationProgress} interval the countdown binds, written only
 * from {@link CheckpointEvent.Rearmed} — follows it with no Settings visit.
 *
 * <p>The behaviour half builds the manager from
 * {@link MainController#startupAutoSaveConfig}, the seam {@code initialize()}
 * calls, inside {@link SaveFlowFixture} (the real manager, project manager and
 * strip listener, wired as the application wires them); the source half pins
 * that {@code initialize()} — which no test runs, it builds a live audio
 * engine — constructs the manager through that seam
 * ({@link SourceScanSupport}, the {@code SourcePinnedWiringTest} idiom).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CheckpointIntervalStartupWiringTest {

    private static final Duration PERSISTED = Duration.ofSeconds(600);

    @Test
    void theManagerIsBuiltFromThePersistedIntervalAndTheStripCountdownFollowsIt(@TempDir Path workspace)
            throws Exception {
        SettingsModel model = Story307TestSupport.model("checkpointIntervalStartup");
        model.setAutoSaveIntervalSeconds((int) PERSISTED.toSeconds());
        assertThat(Duration.ofSeconds(model.getAutoSaveIntervalSeconds()))
                .as("precondition: not DEFAULT's interval").isNotEqualTo(AutoSaveConfig.DEFAULT.autoSaveInterval());

        AutoSaveConfig config = MainController.startupAutoSaveConfig(model);
        assertThat(config.autoSaveInterval()).isEqualTo(PERSISTED);
        assertThat(config.maxCheckpoints()).isEqualTo(AutoSaveConfig.DEFAULT.maxCheckpoints());
        assertThat(config.enabled()).isEqualTo(AutoSaveConfig.DEFAULT.enabled());

        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, config)) {
            assertThat(fixture.checkpoints.getConfig().autoSaveInterval()).isEqualTo(PERSISTED);
            // Re-arms reach the recorder on the scheduler thread, after the start returns.
            fixture.awaitOnFx(() -> rearm(fixture, CheckpointEvent.RearmReason.STARTED, PERSISTED).isPresent(),
                    "Song A's checkpoints started and armed at the persisted interval");
            assertThat(fixture.checkpointEvents).as("Song A's checkpoints started and armed")
                    .anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(CheckpointEvent.Rearmed.class,
                            rearmed -> {
                                assertThat(rearmed.reason()).isEqualTo(CheckpointEvent.RearmReason.STARTED);
                                assertThat(rearmed.interval()).isEqualTo(PERSISTED);
                            }));
            // The strip model's dispatcher applies keyed state on its pulses; the fixture pulses it.
            fixture.awaitOnFx(() -> PERSISTED.equals(fixture.progress.getCheckpointInterval()),
                    "the strip countdown's interval is the persisted one, with no Settings visit");
            assertThat(SaveFlowFixture.onFx(fixture.progress::getNextCheckpointInstant))
                    .as("and the countdown runs").isNotNull();
        }
    }

    /**
     * Book §5.6, "Read on apply: yes (existing reconfigure)" — an Apply that
     * changes the interval reconfigures the window's checkpoint manager through
     * {@link LiveSettingsApplier}, the Settings change listener's applier,
     * keeping its cap and enablement, and the strip's countdown follows the
     * new cadence.
     */
    @Test
    void anApplyWithANewIntervalReconfiguresTheManagerAndTheStripCountdownFollowsIt(@TempDir Path workspace)
            throws Exception {
        Duration applied = Duration.ofSeconds(120);
        AutoSaveConfig startup = new AutoSaveConfig(PERSISTED, 37, true);
        Preferences prefs = Preferences.userRoot().node("checkpointIntervalApply_" + System.nanoTime());
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, startup)) {
            fixture.awaitOnFx(() -> PERSISTED.equals(fixture.progress.getCheckpointInterval()),
                    "the countdown starts at the startup interval");
            SettingsModel model = new SettingsModel(prefs);
            model.setAutoSaveIntervalSeconds((int) applied.toSeconds());
            ThemeManager appearance = new ThemeManager(prefs.node("theme"));

            SaveFlowFixture.onFx(() -> {
                LiveSettingsApplier.apply(model, model.getPluginScanPaths(), appearance, fixture.projectManager,
                        fixture.project.get(), new PluginRegistry());
                return null;
            });

            assertThat(fixture.checkpoints.getConfig()).as("the cadence follows the Apply; cap and enablement stay")
                    .isEqualTo(new AutoSaveConfig(applied, 37, true));
            // reconfigure sets the config synchronously; its re-arm reaches the recorder on the scheduler thread.
            fixture.awaitOnFx(() -> rearm(fixture, CheckpointEvent.RearmReason.RECONFIGURED, applied).isPresent(),
                    "the re-arm at the applied interval");
            assertThat(fixture.checkpointEvents).as("re-armed at the applied interval")
                    .anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(CheckpointEvent.Rearmed.class,
                            rearmed -> {
                                assertThat(rearmed.reason()).isEqualTo(CheckpointEvent.RearmReason.RECONFIGURED);
                                assertThat(rearmed.interval()).isEqualTo(applied);
                            }));
            CheckpointEvent.Rearmed reconfigured =
                    rearm(fixture, CheckpointEvent.RearmReason.RECONFIGURED, applied).orElseThrow();
            OptionalLong running = fixture.checkpoints.runningGeneration();
            assertThat(running).as("Song A's checkpoints still run").isPresent();
            assertThat(reconfigured.generation()).as("the re-arm belongs to the running generation")
                    .isEqualTo(running.getAsLong());
            fixture.awaitOnFx(() -> applied.equals(fixture.progress.getCheckpointInterval()),
                    "the strip countdown's interval is the applied one");
            // The strip writes the due time and the interval in one keyed write; a 120 s tick cannot re-arm in between.
            assertThat(SaveFlowFixture.onFx(fixture.progress::getNextCheckpointInstant))
                    .as("the countdown runs to the reconfigured re-arm's due time, not the startup one")
                    .isEqualTo(reconfigured.nextDue());
        } finally {
            prefs.removeNode();
        }
    }

    @Test
    void initializeConstructsTheCheckpointManagerThroughTheStartupSeam() throws IOException {
        Path mainController = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        String raw = Files.readString(mainController, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: MainController was read").isGreaterThan(10_000);
        String code = SourceScanSupport.stripComments(raw).replaceAll("\\s+", " ");
        String initialize = methodBody(code, "private void initialize()");

        assertThat(initialize)
                .contains("new CheckpointManager(startupAutoSaveConfig(startupSettings))")
                .doesNotContain("AutoSaveConfig.DEFAULT");
    }

    /** The first re-arm the fixture recorded for {@code reason} at {@code interval}, if it has arrived. */
    private static Optional<CheckpointEvent.Rearmed> rearm(SaveFlowFixture fixture, CheckpointEvent.RearmReason reason,
                                                           Duration interval) {
        return fixture.checkpointEvents.stream()
                .filter(CheckpointEvent.Rearmed.class::isInstance)
                .map(CheckpointEvent.Rearmed.class::cast)
                .filter(rearmed -> rearmed.reason() == reason && interval.equals(rearmed.interval()))
                .findFirst();
    }

    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("the source declares %s", signature).isNotNegative();
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
}
