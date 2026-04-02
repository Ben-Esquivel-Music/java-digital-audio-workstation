package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class HelpDialogTest {

    private <T> T runOnFxThread(java.util.concurrent.Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (error.get() != null) {
            throw error.get();
        }
        return ref.get();
    }

    @Test
    void shouldCreateDialogWithCorrectTitle() throws Exception {
        String title = runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            return dialog.getTitle();
        });
        assertThat(title).isEqualTo("Help");
    }

    @Test
    void shouldHaveThreeTabs() throws Exception {
        int tabCount = runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            DialogPane pane = dialog.getDialogPane();
            TabPane tabPane = (TabPane) pane.getContent();
            return tabPane.getTabs().size();
        });
        assertThat(tabCount).isEqualTo(3);
    }

    @Test
    void shouldHaveKeyboardShortcutsTab() throws Exception {
        String tabText = runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            DialogPane pane = dialog.getDialogPane();
            TabPane tabPane = (TabPane) pane.getContent();
            return tabPane.getTabs().get(0).getText();
        });
        assertThat(tabText).isEqualTo("Keyboard Shortcuts");
    }

    @Test
    void shouldHaveGettingStartedTab() throws Exception {
        String tabText = runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            DialogPane pane = dialog.getDialogPane();
            TabPane tabPane = (TabPane) pane.getContent();
            return tabPane.getTabs().get(1).getText();
        });
        assertThat(tabText).isEqualTo("Getting Started");
    }

    @Test
    void shouldHaveAboutTab() throws Exception {
        String tabText = runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            DialogPane pane = dialog.getDialogPane();
            TabPane tabPane = (TabPane) pane.getContent();
            return tabPane.getTabs().get(2).getText();
        });
        assertThat(tabText).isEqualTo("About");
    }

    @Test
    void shouldHaveCloseButton() throws Exception {
        boolean hasClose = runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            return dialog.getDialogPane().getButtonTypes().contains(ButtonType.CLOSE);
        });
        assertThat(hasClose).isTrue();
    }

    @Test
    void shouldHaveHeaderText() throws Exception {
        String headerText = runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            return dialog.getHeaderText();
        });
        assertThat(headerText).isEqualTo("Help & Documentation");
    }

    @Test
    void tabsShouldNotBeClosable() throws Exception {
        boolean allNonClosable = runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            DialogPane pane = dialog.getDialogPane();
            TabPane tabPane = (TabPane) pane.getContent();
            return tabPane.getTabs().stream().noneMatch(tab -> tab.isClosable());
        });
        assertThat(allNonClosable).isTrue();
    }
}
