package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.ui.MainController;
import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

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

        // Story 289 — the single FX-thread marshalling seam (Control
        // Synchronization Design Book §2.6, §4.5). Constructed and started
        // here (we are on the FX thread in start()) BEFORE the bus, because
        // the bus's ON_UI_THREAD delivery is routed through it: every
        // off-thread hop onto the FX thread — bus subscribers and the 27
        // former ad-hoc Platform.runLater sites — now goes through this one
        // coalescing seam. installDefault publishes it for the awkward
        // minority of call sites that cannot take a constructor dependency
        // (the FXML-instantiated MainController, the toolkit-free getDefault()
        // singletons, short-lived dialogs), mirroring EventBusPublisher.
        FxDispatcher fxDispatcher = new FxDispatcher();
        fxDispatcher.start();
        FxDispatcher.installDefault(fxDispatcher);

        // Story 283 — install the application-wide EventBus before any
        // controller is constructed. The UI executor is required because
        // Workshop S3 subscriptions use DispatchMode.ON_UI_THREAD.
        // Sit alongside Theme/Density/Motion managers — same lifecycle.
        // Story 289 — ON_UI_THREAD delivery now marshals through the
        // FxDispatcher seam (fxDispatcher::onFx is Executor-compatible and
        // posts an unconditional Platform.runLater, which the bus's blocking
        // runAndAwait worker requires) instead of a bare Platform::runLater.
        EventBus bus = DefaultEventBus.builder()
                .uiExecutor(fxDispatcher::onFx)
                .build();
        EventBusPublisher.setDefault(bus);
        primaryStage.addEventHandler(WindowEvent.WINDOW_HIDDEN, _ -> {
            bus.close();
            if (EventBusPublisher.getDefault() == bus) {
                EventBusPublisher.setDefault(null);
            }
            // Story 289 — stop the pulse timer and clear the seam's channels.
            fxDispatcher.dispose();
            if (FxDispatcher.getDefault() == fxDispatcher) {
                FxDispatcher.installDefault(null);
            }
        });

        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("ui/main-view.fxml"));
        Parent root = loader.load();
        // Story 289 — inject the seam into the FXML-instantiated controller
        // (a no-arg fx:controller cannot be constructor-injected). The
        // setter is set-once at app init, exactly like the EventBusPublisher
        // default above; MainController threads it to the collaborators it
        // builds in initialize().
        MainController mainController = loader.getController();
        if (mainController != null) {
            mainController.setFxDispatcher(fxDispatcher);
        }

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
        // Story 279 — touch MotionManager so its singleton constructs at
        // startup: this restores the persisted Reduce Motion flag (or, on
        // first launch, seeds it from the OS-level accessibility hint).
        // MotionManager is a pure observable flag — controls observe it
        // directly, so there is no applyTo(scene) call.
        MotionManager.getDefault();

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
