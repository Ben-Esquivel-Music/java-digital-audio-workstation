package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@link MixerView} builds each strip's right-click menu when it refreshes.
 * A freeze controller attached after construction, as
 * {@code MainController.handleProjectRebuild()} attaches it after every Open,
 * reaches the strip menu once {@link MixerView#refresh()} has run. The menu
 * is read by requesting it on the strip in a showing window, as a right-click
 * does.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerViewFreezeMenuTest {

    private static final String FREEZE = "❄ Freeze track";
    private static final String UNFREEZE = "❄ Unfreeze track";

    private Stage stage;

    @AfterEach
    void closeStage() throws Exception {
        if (stage != null) {
            onFx(() -> {
                stage.close();
                return null;
            });
        }
    }

    @Test
    void aFreezeControllerAttachedAfterConstructionReachesTheStripMenuAtTheNextRefresh() throws Exception {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        UndoManager undoManager = new UndoManager();

        MenuStages menus = onFx(() -> {
            MixerView view = new MixerView(project, undoManager);
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 900, 600));
            stage.show();
            view.applyCss();
            view.layout();
            List<String> constructed = stripMenuItems(view);
            view.setTrackFreezeController(new TrackFreezeController(
                    () -> project, () -> undoManager, null, null, null, null));
            view.refresh();
            view.applyCss();
            view.layout();
            return new MenuStages(constructed, stripMenuItems(view));
        });

        assertThat(menus.constructed())
                .as("guard: the strip menu was found and read")
                .contains("Create VCA from selection")
                .doesNotContain(FREEZE);
        assertThat(menus.refreshed())
                .as("after the setter and refresh() the strip menu offers the freeze entries")
                .contains(FREEZE, UNFREEZE);
    }

    private record MenuStages(List<String> constructed, List<String> refreshed) { }

    /** Requests the first track strip's context menu, reads its item texts and hides it (FX thread). */
    private static List<String> stripMenuItems(MixerView view) {
        Node strip = view.getChannelStrips().getChildren().stream()
                .filter(n -> n.getStyleClass().contains(MixerView.TRACK_STRIP_HOST_STYLE_CLASS))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the mixer has no track strip"));
        EventHandler<? super ContextMenuEvent> request = strip.getOnContextMenuRequested();
        assertThat(request).as("the track strip has a context-menu handler").isNotNull();
        request.handle(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                10, 10, 10, 10, false, null));
        ContextMenu menu = Window.getWindows().stream()
                .filter(window -> window instanceof ContextMenu candidate
                        && candidate.getOwnerNode() == strip)
                .map(ContextMenu.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the strip's context menu did not show"));
        List<String> items = menu.getItems().stream()
                .map(MenuItem::getText)
                .filter(text -> text != null)
                .toList();
        menu.hide();
        return items;
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("the FX action completed").isTrue();
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }
}
