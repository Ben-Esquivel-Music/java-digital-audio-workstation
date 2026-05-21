package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main JavaFX application class for the Digital Audio Workstation.
 */
public final class DawApplication extends Application {

    private static final Logger LOG = Logger.getLogger(DawApplication.class.getName());

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
        loadBundledFonts();

        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("ui/main-view.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        // Story 277 — ThemeManager owns the ordered stylesheet list
        // (base styles.css + the persisted token-theme overlay) and
        // re-applies it to the registered scene when the user switches
        // theme in Preferences, so the whole UI re-themes with no
        // restart.
        ThemeManager.getDefault().applyTo(scene);
        // Story 278 — DensityManager adds the persisted .density-* class
        // to the scene root (restoring the user's density at startup) and
        // re-applies it to the registered scene when the user switches
        // density in Preferences, so the UI re-densifies with no restart.
        DensityManager.getDefault().applyTo(scene);

        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1280);
        primaryStage.setMinHeight(720);
        primaryStage.setMaximized(true);
        primaryStage.show();
    }

    /**
     * Registers the bundled JetBrains Mono weights with the JavaFX font
     * system (story 266 / UI Design Book §3.2). The actual size passed to
     * {@link Font#loadFont(InputStream, double)} is irrelevant — it controls
     * the size of the returned {@code Font} instance, not the registered
     * family — but JavaFX requires a positive number.
     *
     * <p>Failures are deliberately swallowed: a missing or unreadable TTF
     * must <em>never</em> break startup. The {@code -font-mono} CSS stack
     * lists IBM Plex Mono, Cascadia Code, Consolas, and the generic
     * {@code monospace} family as fall-backs, all of which are mono and
     * therefore preserve the tabular-figure contract.
     */
    private void loadBundledFonts() {
        for (String weight : FontResources.JETBRAINS_MONO_WEIGHTS) {
            String resource = FontResources.JETBRAINS_MONO_DIR + weight;
            try (InputStream in = DawApplication.class.getResourceAsStream(resource)) {
                if (in == null) {
                    LOG.fine(() -> "Font resource not bundled: " + resource);
                    continue;
                }
                Font font = Font.loadFont(in, 12);
                if (font == null) {
                    LOG.warning(() -> "Font.loadFont returned null for " + resource
                            + " — JetBrains Mono will be unavailable; -font-mono will fall "
                            + "back to the next family in the CSS stack.");
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING,
                        e,
                        () -> "Failed to load bundled font " + resource
                                + " — -font-mono will fall back to the next family in the CSS stack.");
            }
        }
    }
}
