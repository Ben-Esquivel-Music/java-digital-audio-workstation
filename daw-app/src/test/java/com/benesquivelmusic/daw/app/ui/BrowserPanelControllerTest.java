package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(JavaFxToolkitExtension.class)
class BrowserPanelControllerTest {

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldRejectNullBrowserPanel() throws Exception {
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new BrowserPanelController(
                    null, new Button(), new BorderPane()))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullButton() throws Exception {
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new BrowserPanelController(
                    new BrowserPanel(), null, new BorderPane()))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullRootPane() throws Exception {
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new BrowserPanelController(
                    new BrowserPanel(), new Button(), null))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldStartWithPanelHidden() throws Exception {
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            assertThat(controller.isPanelVisible()).isFalse();
            assertThat(rootPane.getRight()).isNull();
        });
    }

    @Test
    void shouldTogglePanelVisible() throws Exception {
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            // Toggle on
            controller.toggleBrowserPanel();
            assertThat(controller.isPanelVisible()).isTrue();
            assertThat(rootPane.getRight()).isSameAs(panel);
        });
    }

    @Test
    void shouldTogglePanelBackToHidden() throws Exception {
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            // Toggle on then off
            controller.toggleBrowserPanel();
            assertThat(controller.isPanelVisible()).isTrue();

            controller.toggleBrowserPanel();
            assertThat(controller.isPanelVisible()).isFalse();
        });
    }

    @Test
    void shouldAddActiveStyleWhenPanelVisible() throws Exception {
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            controller.toggleBrowserPanel();
            assertThat(button.getStyleClass()).contains("toolbar-button-active");
        });
    }

    @Test
    void shouldRemoveActiveStyleWhenPanelHidden() throws Exception {
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            assertThat(button.getStyleClass()).doesNotContain("toolbar-button-active");
        });
    }

    @Test
    void shouldWireButtonOnAction() throws Exception {
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            assertThat(button.getOnAction()).isNotNull();
        });
    }

    @Test
    void shouldReturnBrowserPanel() throws Exception {
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);

            assertThat(controller.getBrowserPanel()).isSameAs(panel);
        });
    }

    @Test
    void shouldInvokeVisibilityChangedCallbackOnToggle() throws Exception {
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            boolean[] callbackInvoked = {false};
            controller.setOnVisibilityChanged(() -> callbackInvoked[0] = true);

            controller.toggleBrowserPanel();
            assertThat(callbackInvoked[0]).isTrue();
        });
    }

    @Test
    void shouldNotFailWithoutVisibilityChangedCallback() throws Exception {
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            // No callback set — should not throw
            controller.toggleBrowserPanel();
            assertThat(controller.isPanelVisible()).isTrue();
        });
    }
}
