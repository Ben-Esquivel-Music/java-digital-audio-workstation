package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The close protocol's test fixture ends cleanly even when a step of its own
 * teardown throws (PR #989 review 5711460812): its window fence is lowered
 * first, and its answerer of the close's prompts is removed and its main
 * window hidden all the same. The fence's watch for new windows and the
 * answerer are both on the global list of windows, so either left there would
 * act on every window a later test class in the same JVM shows, and one
 * failure would turn into failures blamed on unrelated classes.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ExitFixtureLeavesNoFenceUpWhenItsTeardownThrowsTest {

    @TempDir
    Path workspace;

    @Test
    void aTeardownStepThatThrowsStillLowersTheFenceAndRemovesTheAnswerer() throws Exception {
        // No journal: a teardown that throws skips the journal's drain, which would leave its file open.
        ExitFixture fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, false, UnaryOperator.identity());
        WindowInputFence fence = fixture.fence();
        Stage later = onFx(() -> {
            // Titled as the unsaved-changes prompt, which the fixture's answerer records.
            Stage stage = new Stage();
            stage.setTitle(ExitFixture.UNSAVED_TITLE);
            stage.setScene(new Scene(new StackPane(), 200, 120));
            return stage;
        });
        try {
            runOnFx(fence::raise);
            fixture.handOff = new CompletableFuture<>() {
                @Override
                public boolean complete(Void value) {
                    throw new IllegalStateException("a teardown step that throws");
                }
            };

            assertThatThrownBy(fixture::close).hasMessage("a teardown step that throws");

            List<Boolean> afterTheTeardown = onFx(() -> {
                later.show();
                return List.of(fence.isRaised(), fence.fencedWindows().contains(later), fixture.stage().isShowing());
            });
            assertThat(afterTheTeardown)
                    .as("the fence is down, a window shown later is not fenced, and the main window is hidden")
                    .containsExactly(false, false, false);
            assertThat(fixture.unsavedPrompts).as("nor is that window recorded: the answerer was removed").isEmpty();
        } finally {
            runOnFx(() -> {
                fence.lower();
                if (later.isShowing()) {
                    later.hide();
                }
                if (fixture.stage().isShowing()) {
                    fixture.stage().hide();
                }
            });
        }
    }
}
