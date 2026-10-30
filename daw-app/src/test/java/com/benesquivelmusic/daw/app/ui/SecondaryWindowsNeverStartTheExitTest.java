package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, ruling R12 #14 — the secondary windows' own close requests
 * never start the exit: the real Project Hub, opened by the lifecycle
 * controller's own presenter as File ▸ Recent Projects opens it, and a
 * stand-in floating dock panel owned by the main window, whose close handler
 * is the dock host's idiom (it re-docks, never vetoes and never names the
 * coordinator — {@code ExitWiringSourceTest} pins the real handler so),
 * closed by their close boxes close themselves and nothing else: no step of
 * the protocol runs and the main window stays. The plug-in editors are not
 * built here: a window's close request never reaches another window's
 * filter, which is what this proves of every secondary window. Only the main
 * window's close box asks the coordinator, which then runs its whole exit.
 * DISPOSE's half of the ruling — it hides every secondary window, a vetoing
 * one included, and keeps the close's progress surface until HIDE — is
 * {@link WindowLifetimeDisposalTest}'s, over the application's own disposal.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SecondaryWindowsNeverStartTheExitTest {

    @TempDir
    Path workspace;

    @Test
    void aFloatingPanelsAndTheHubsCloseBoxesCloseOnlyThemselves() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            AtomicInteger redocked = new AtomicInteger();
            AtomicReference<Stage> floating = new AtomicReference<>();
            AtomicReference<Stage> hub = new AtomicReference<>();
            runOnFx(() -> {
                Stage panel = owned(exit.stage(), "Mixer");
                // The dock host's idiom (MainController.ensureFloating): re-dock, never veto, never ask the exit.
                panel.setOnCloseRequest(_ -> redocked.incrementAndGet());
                panel.show();
                floating.set(panel);
                // The real Hub, through the lifecycle controller's default presenter (the stage-7 review N4).
                exit.lifecycle().onRecentProjects();
                hub.set(javafx.stage.Window.getWindows().stream()
                        .filter(window -> window instanceof Stage stage && "Project Hub".equals(stage.getTitle()))
                        .map(Stage.class::cast).findFirst()
                        .orElseThrow(() -> new AssertionError("the Project Hub did not open")));
            });

            runOnFx(() -> {
                Stage panel = floating.get();
                panel.fireEvent(new WindowEvent(panel, WindowEvent.WINDOW_CLOSE_REQUEST));
                Stage projects = hub.get();
                projects.fireEvent(new WindowEvent(projects, WindowEvent.WINDOW_CLOSE_REQUEST));
            });
            exit.save.settle();

            assertThat(redocked).as("the panel's own handler ran").hasValue(1);
            assertThat(onFx(() -> floating.get().isShowing() || hub.get().isShowing()))
                    .as("each closed itself").isFalse();
            assertThat(exit.steps).as("no step of the protocol ran").isEmpty();
            assertThat(onFx(() -> exit.stage().isShowing())).as("the main window stays").isTrue();
            assertThat(exit.disposals).hasValue(0);
            assertThat(exit.terminalExits).hasValue(0);

            exit.closeBox();
            exit.awaitStep(ExitCoordinator.ExitStep.HIDE);
            assertThat(exit.steps.getFirst().step()).isEqualTo(ExitCoordinator.ExitStep.INTERCEPT);
            assertThat(exit.terminalExits).as("the main window's close box is the exit").hasValue(1);
        }
    }

    private static Stage owned(Stage owner, String title) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle(title);
        stage.setScene(new Scene(new StackPane(), 160, 100));
        return stage;
    }
}
