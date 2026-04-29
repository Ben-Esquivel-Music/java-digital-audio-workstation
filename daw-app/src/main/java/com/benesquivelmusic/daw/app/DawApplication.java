package com.benesquivelmusic.daw.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Main JavaFX application class for the Digital Audio Workstation.
 */
public final class DawApplication extends Application {

    private static final String APP_TITLE = "Digital Audio Workstation";
    private static final double DEFAULT_WIDTH = 1600;
    private static final double DEFAULT_HEIGHT = 900;

    private final DawRuntime runtime;

    /** JavaFX-required no-arg constructor: builds a default {@link DawRuntime}. */
    public DawApplication() {
        this(new DawRuntime());
    }

    /**
     * Test/override constructor: lets callers inject a {@link DawRuntime}
     * configured with non-default collaborators (e.g. fakes, fixed clocks).
     */
    public DawApplication(DawRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    /** The injected runtime — exposed so future controllers can read it. */
    public DawRuntime runtime() {
        return runtime;
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("ui/main-view.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(
                getClass().getResource("ui/styles.css").toExternalForm());

        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1280);
        primaryStage.setMinHeight(720);
        primaryStage.setMaximized(true);
        primaryStage.show();
    }
}
