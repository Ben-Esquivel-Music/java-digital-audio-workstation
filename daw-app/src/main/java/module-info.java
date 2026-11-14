/**
 * {@code daw.app} — the JavaFX desktop shell for the Digital Audio
 * Workstation.
 *
 * <p>This is the application leaf module: it depends on the engine
 * ({@code daw.core}), the stable API ({@code daw.sdk}) and the GPU rendering
 * primitives ({@code daw.fx}), wires them together in
 * {@link com.benesquivelmusic.daw.app.DawRuntime}, and presents the JavaFX UI
 * launched from {@link com.benesquivelmusic.daw.app.DawLauncher} /
 * {@link com.benesquivelmusic.daw.app.DawApplication}.</p>
 *
 * <p>{@code daw.app} is the top of the module graph and is consumed by no
 * other module, so it <strong>exports one package, and only to
 * {@code java.logging}</strong>: {@code com.benesquivelmusic.daw.app.logging},
 * which holds nothing but {@code ExitHoldingLogManager}, the class
 * {@code java.util.logging.LogManager} instantiates reflectively when the
 * {@code java.util.logging.manager} property names it (story 336). It does,
 * however, selectively {@code open} four packages for the reflection JavaFX
 * itself performs:</p>
 * <ul>
 *   <li>{@code com.benesquivelmusic.daw.app} is opened to
 *       {@code javafx.graphics} so {@code Application.launch(DawApplication.class,…)}
 *       can reflectively instantiate the {@code Application} subclass.</li>
 *   <li>{@code com.benesquivelmusic.daw.app.ui} is opened to
 *       {@code javafx.fxml} so {@code FXMLLoader} can inject the
 *       {@code @FXML}-annotated fields and {@code onAction} handlers of
 *       {@code MainController} (the only {@code fx:controller} —
 *       {@code main-view.fxml}).</li>
 *   <li>{@code com.benesquivelmusic.daw.app.ui.inspector} and
 *       {@code com.benesquivelmusic.daw.app.ui.inspector.sections} are opened
 *       to {@code javafx.fxml} so {@code FXMLLoader} can instantiate the
 *       {@code InspectorDrawer} and its sections that {@code main-view.fxml}
 *       references (story 272).</li>
 * </ul>
 * All four are <em>qualified</em> opens — no blanket {@code open module} — so
 * the rest of the application stays strongly encapsulated.
 *
 * <p>Built-in plugin discovery uses the JPMS {@link java.util.ServiceLoader}
 * SPI. The {@code ServiceLoader.load(BuiltInDawPlugin.class)} call lives in
 * {@code daw.core} (which declares the matching {@code uses}); {@code daw.app}
 * only invokes the {@code BuiltInDawPlugin.discoverAll()} /
 * {@code menuEntries()} facade, so it does not declare {@code uses} itself.
 */
module daw.app {
    // daw.sdk is pulled in transitively via daw.core (`requires transitive
    // daw.sdk`) but is required explicitly here because daw.app uses the SDK
    // API directly and an explicit `requires` documents that dependency.
    requires daw.core;
    requires daw.sdk;
    requires daw.fx;

    requires javafx.controls;
    requires javafx.graphics;
    requires javafx.fxml;
    requires javafx.media;

    // javax.xml.* (session/preset XML helpers in the UI layer).
    requires java.xml;
    requires java.logging;
    // java.util.prefs — SettingsModel / KeyBindingManager / ToolbarStateStore
    // and other UI preference stores.
    requires java.prefs;
    // java.desktop (java.awt, javax.sound.sampled) is supplied transitively
    // by daw.sdk (`requires transitive java.desktop`).

    // FXMLLoader reflects into the controllers package to bind @FXML
    // fields and #onAction handlers.
    opens com.benesquivelmusic.daw.app.ui to javafx.fxml;
    // Story 272 — FXMLLoader reflectively instantiates InspectorDrawer
    // and its sections referenced from main-view.fxml.
    opens com.benesquivelmusic.daw.app.ui.inspector to javafx.fxml;
    opens com.benesquivelmusic.daw.app.ui.inspector.sections to javafx.fxml;

    // Application.launch(DawApplication.class, …) reflectively constructs
    // the Application subclass.
    opens com.benesquivelmusic.daw.app to javafx.graphics;

    // Story 336 — LogManager's static initializer loads the class that
    // java.util.logging.manager names and instantiates it reflectively from
    // java.logging. Without this qualified export it prints "Could not load
    // Logmanager" on standard error and falls back to the JDK's own manager,
    // and the log loses the lines written while the application exits.
    exports com.benesquivelmusic.daw.app.logging to java.logging;
}
