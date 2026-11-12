package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 335 — "UI-scale startup test" (studio ruling R6): a persisted
 * non-default scale is applied at launch, before the primary scene shows,
 * through the theme manager's scale sheet (a root font size and rem-resolved
 * lengths), never through a scale transform.
 *
 * <p>{@code MainController} is never FXML-loaded in tests: the launch path is
 * reached through its {@code applyStartupUiScale} seam, and the source pins
 * prove {@code initialize()} calls that seam with the process theme manager
 * and that {@code DawApplication} applies that manager to the primary scene
 * before {@code show()}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class UiScaleStartupWiringTest {

    private static final Path APP = SourceScanSupport.locateDawAppModule()
            .resolve("src/main/java/com/benesquivelmusic/daw/app");

    private final List<Preferences> nodes = new ArrayList<>();

    @AfterEach
    void removeNodes() {
        for (Preferences node : nodes) {
            try {
                node.removeNode();
            } catch (BackingStoreException | IllegalStateException alreadyGone) {
                // never flushed
            }
        }
    }

    @Test
    void aPersistedScaleIsAppliedAtLaunchAsAScaleSheetOnThePrimaryScene() throws Exception {
        Preferences prefs = node("uiScaleStartup");
        new SettingsModel(prefs).setUiScale(1.5);
        SettingsModel relaunched = new SettingsModel(prefs);
        ThemeManager appearance = new ThemeManager(node("uiScaleStartupTheme"));

        MainController.applyStartupUiScale(relaunched, appearance);

        assertThat(appearance.getUiScale()).isEqualTo(UiScale.P150);
        double[] resolved = Story307TestSupport.onFx(() -> {
            Button button = new Button("Play");
            button.getStyleClass().add("dawg-button");
            Label plain = new Label("Untitled");
            VBox root = new VBox(button, plain);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 800, 600);
            appearance.applyTo(scene);
            assertThat(scene.getStylesheets().getLast())
                    .as("the scale sheet is the tail of the ordered list")
                    .startsWith("data:text/css;base64,");
            assertThat(root.getTransforms()).as("no scale transform").isEmpty();
            root.applyCss();
            return new double[] {button.getFont().getSize(), plain.getFont().getSize(),
                    Font.getDefault().getSize()};
        });
        assertThat(resolved[0]).as(".dawg-button 11 px × 1.5").isCloseTo(16.5, within(0.01));
        assertThat(resolved[1]).as("unstyled text: the default size × 1.5")
                .isCloseTo(resolved[2] * 1.5, within(0.01));
    }

    @Test
    void anOffStepPersistedScaleSnapsAtLoadAndStillAppliesAtLaunch() {
        Preferences prefs = node("uiScaleOffStep");
        ThemeManager appearance = new ThemeManager(node("uiScaleOffStepTheme"));

        prefs.putDouble("appearance.uiScale", 1.3);
        SettingsModel snapped = new SettingsModel(prefs);
        assertThat(snapped.getUiScale()).isEqualTo(1.25);
        assertThat(prefs.getDouble("appearance.uiScale", Double.NaN))
                .as("the stored value is rewritten only when the user applies the row").isEqualTo(1.3);
        MainController.applyStartupUiScale(snapped, appearance);
        assertThat(appearance.getUiScale()).isEqualTo(UiScale.P125);

        prefs.putDouble("appearance.uiScale", 3.0);
        assertThat(new SettingsModel(prefs).getUiScale()).isEqualTo(2.0);
    }

    @Test
    void initializeAppliesTheStartupScaleAndTheAppAppliesTheManagerBeforeShow() throws IOException {
        String main = code(APP.resolve("ui/MainController.java"));
        assertThat(methodBody(main, "private void initialize()"))
                .as("initialize() applies the persisted scale next to the startup settings")
                .contains("applyStartupUiScale(startupSettings, ThemeManager.getDefault());");

        String app = code(APP.resolve("DawApplication.java"));
        int applied = app.indexOf("ThemeManager.getDefault().applyTo(scene);");
        int shown = app.indexOf("primaryStage.show();");
        assertThat(applied).as("DawApplication applies the theme manager to the scene").isPositive();
        assertThat(applied).as("…before the primary stage shows").isLessThan(shown);

        for (String file : List.of("ui/LiveSettingsApplier.java", "ui/MainController.java", "DawApplication.java")) {
            assertThat(code(APP.resolve(file))).as("%s applies no scale transform", file)
                    .doesNotContain("new Scale(")
                    .doesNotContain("javafx.scene.transform.Scale");
        }
        assertThat(methodBody(code(APP.resolve("ui/LiveSettingsApplier.java")), "static void apply("))
                .as("an Apply pushes the scale step to the theme manager")
                .contains("appearance.setUiScale(UiScale.ofFactor(model.getUiScale()));");
    }

    /**
     * Review 2b-conformance S5 — the layout test drives the production seams
     * directly, so the two calls the main window's reachability depends on are
     * pinned at their call sites: {@code initialize()} installs the transport
     * scroller's wiring (scroll-bar reserve, viewport-following width, focus
     * guard), and the toolbar overflow rule receives the live UI-scale property
     * (amendment A16), not a fixed one.
     */
    @Test
    void initializeInstallsTheTransportScrollerAndTheToolbarRuleObservesTheLiveScale() throws IOException {
        String main = code(APP.resolve("ui/MainController.java"));
        assertThat(methodBody(main, "private void initialize()"))
                .as("initialize() installs the transport scroller's production wiring")
                .contains("installTransportBarScroll(transportBarScroller, transportBar);");
        assertThat(methodBody(main, "private void createToolbarAppearanceController()"))
                .as("the toolbar overflow rule observes the live UI-scale property")
                .contains("ThemeManager.getDefault().uiScaleProperty())");
    }

    private Preferences node(String name) {
        Preferences node = Preferences.userRoot().node(name + "_" + System.nanoTime());
        nodes.add(node);
        return node;
    }

    private static String code(Path file) throws IOException {
        return SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The brace-matched body of the first method whose declaration starts with {@code signature}. */
    private static String methodBody(String code, String signature) {
        int start = code.indexOf(signature);
        assertThat(start).as("declaration %s", signature).isNotNegative();
        int open = code.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced body for " + signature);
    }
}
