package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 309 — the §3.2 "applies to…" reach cue (the rejection-#8 guard).
 * A {@code PROJECT_DEFAULTS} row reads "applies to new projects"; a
 * {@code THIS_PROJECT} row reads "applies to the open project"; the cue
 * is keyed off {@code Scope} only.
 *
 * <p>§3.2 dual-reach note, documented here on purpose: "Default Tempo"
 * ({@code project.defaultTempo}) is <em>stored</em> as a default for
 * future projects — that is its scope and its cue — while
 * {@code LiveSettingsApplier} additionally live-applies it to the open
 * project on Apply. The secondary live application is behaviour, not
 * scope (see the {@code Scope.PROJECT_DEFAULTS} Javadoc); the cue
 * deliberately names the storage reach.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectDefaultCueTest {

    private <T> T runOnFxThread(Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Throwable t) {
                // Capture AssertionError too — an FX-thread assertion must
                // fail the test, not vanish into the FX exception handler.
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX task completed within the timeout")
                .isTrue();
        Throwable thrown = error.get();
        if (thrown instanceof Error e) {
            throw e;
        }
        if (thrown instanceof Exception e) {
            throw e;
        }
        return ref.get();
    }

    private static SettingsShell newShell(SettingsCatalogue catalogue) {
        Preferences prefs = Preferences.userRoot()
                .node("projectDefaultCueTest_" + System.nanoTime());
        SettingsModel model = new SettingsModel(prefs);
        SettingsShell.ValueAccess access = id -> switch (id) {
            case "project.defaultTempo" -> model.getDefaultTempo();
            case "project.autoSaveIntervalSeconds" -> model.getAutoSaveIntervalSeconds();
            case "project.useJournaledPersistence" -> model.isUseJournaledPersistence();
            case "appearance.uiScale" -> model.getUiScale();
            // Every other id: the descriptor default IS the persisted value
            // over an empty node — no live singleton touched.
            default -> catalogue.byId(id)
                    .map(descriptor -> (Object) descriptor.defaultValue())
                    .orElse(null);
        };
        return new SettingsShell(catalogue, access, null, null);
    }

    private static String cueTextOf(SettingRow row) {
        Node cue = row.lookup(".setting-row-scope-cue");
        return cue instanceof Label label ? label.getText() : null;
    }

    @Test
    void projectDefaultAndThisProjectRowsShouldCarryTheirReachCues() throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue catalogue = SettingsCatalogue.create();
            SettingsShell shell = newShell(catalogue);
            StackPane root = new StackPane(shell);
            new Scene(root, 1024, 720);

            // PROJECT_DEFAULTS — "Default Tempo" (also live-applied to the
            // open project by LiveSettingsApplier; see the class Javadoc).
            shell.selectCategory("project");
            root.applyCss();
            root.layout();
            SettingRow tempo = shell.settingRow("project.defaultTempo").orElseThrow();
            assertThat(cueTextOf(tempo))
                    .as("a PROJECT_DEFAULTS row cues its storage reach")
                    .isEqualTo("applies to new projects");

            // THIS_PROJECT — the metronome click gain (round-trips through
            // the project XML, story 308).
            shell.selectCategory("recording");
            root.applyCss();
            root.layout();
            SettingRow clickGain = shell.settingRow("metronome.clickGain").orElseThrow();
            assertThat(cueTextOf(clickGain))
                    .as("a THIS_PROJECT row cues the open project")
                    .isEqualTo("applies to the open project");

            // APPLICATION — no cue at all (the cue is keyed off Scope only).
            shell.selectCategory("appearance");
            root.applyCss();
            root.layout();
            SettingRow uiScale = shell.settingRow("appearance.uiScale").orElseThrow();
            assertThat(uiScale.lookup(".setting-row-scope-cue"))
                    .as("an APPLICATION row renders no reach cue")
                    .isNull();
            return null;
        });
    }
}
