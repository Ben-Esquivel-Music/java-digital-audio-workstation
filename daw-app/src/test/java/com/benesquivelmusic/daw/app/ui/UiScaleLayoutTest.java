package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.Knob;
import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;
import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialogCapSeam;
import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.hub.ProjectCard;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.settings.ControlKind;
import com.benesquivelmusic.daw.app.ui.settings.FirstRunWizard;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import javafx.animation.AnimationTimer;
import javafx.css.CssMetaData;
import javafx.css.StyleOrigin;
import javafx.css.Styleable;
import javafx.css.StyleableProperty;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuBar;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 335 — "at scale 2.0 the window is fully laid out and unclipped
 * (content bounds within the window), and controls remain reachable"
 * (studio ruling R6, checked at the 1280×720 minimum window and at every
 * advertised step; amendments A14, A16, A17, A19, A21, A22; the 2B review
 * findings).
 *
 * <p>The main window is {@code main-view.fxml} loaded without its controller
 * ({@code MainController} is never FXML-loaded in tests) plus the runtime
 * content that can be built without it — the real menu bar, the Session
 * Status Strip and the notification bar, mounted where {@code MainController}
 * mounts them — wired by the production seams
 * {@code MainController.installTransportBarScroll} and
 * {@link ToolbarAppearanceController}. Everything is styled by an isolated
 * {@link ThemeManager} installed as the process default before any node is
 * built, so skins and canvases observe its scale.</p>
 *
 * <p>Every method keeps its FX waits (each {@code onFx} budget plus the pulse
 * waits) under the suite's 60&nbsp;s test-method timeout, so a slow run fails
 * at its own guard line and never leaks its FX work into the next test.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class UiScaleLayoutTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final String FXML = "/com/benesquivelmusic/daw/app/ui/main-view.fxml";
    /** Budget of one FX callable; the methods' callables plus pulse waits stay under 60 s. */
    private static final long FX_BUDGET_SECONDS = 20;
    /** The ruled minimum screens: 1280×720, and 1280×680 of visual bounds after a taskbar. */
    private static final Rectangle2D SCREEN_1280_BY_720 = new Rectangle2D(0, 0, 1280, 720);
    private static final Rectangle2D SCREEN_1280_BY_680 = new Rectangle2D(0, 0, 1280, 680);

    /** The CSS properties the scale sheet re-states (fonts through {@code -fx-font}). */
    private static final Set<String> GUARDED_PROPERTIES = Set.of(
            "-fx-font", "-fx-padding", "-fx-label-padding", "-fx-spacing", "-fx-hgap", "-fx-vgap",
            "-fx-graphic-text-gap", "-fx-cell-size",
            "-fx-pref-width", "-fx-pref-height", "-fx-min-width", "-fx-min-height",
            "-fx-max-width", "-fx-max-height");
    /** An empty author sheet whose addition and removal re-styles a scene without laying it out. */
    private static final String CSS_PASS_TRIGGER = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(".ui-scale-guard-css-pass { }".getBytes(StandardCharsets.UTF_8));

    private final List<Preferences> nodes = new ArrayList<>();
    private final List<Window> windows = new ArrayList<>();
    private final List<SettingsDialog> dialogs = new ArrayList<>();
    private final List<MixerView> mixers = new ArrayList<>();
    private ThemeManager appearance;
    private EventBus previousBus;
    private DefaultEventBus bus;

    @BeforeEach
    void isolateTheThemeManagerAndTheBus() {
        appearance = new ThemeManager(node("uiScaleLayoutTheme"));
        ThemeManager.setDefaultForTest(appearance);
        previousBus = EventBusPublisher.getDefault();
        bus = DefaultEventBus.builder().uiExecutor(Runnable::run).build();
        EventBusPublisher.setDefault(bus);
    }

    @AfterEach
    void closeAndRestore() throws Exception {
        try {
            Story307TestSupport.onFx(() -> {
                for (SettingsDialog dialog : dialogs) {
                    dialog.setDirtyClosePrompt(() -> SettingsDialog.DirtyChoice.DISCARD);
                    dialog.close();
                }
                for (MixerView mixer : mixers) {
                    mixer.dispose();
                }
                for (Window window : windows) {
                    window.hide();
                }
                return null;
            }, 15, TimeUnit.SECONDS);
        } finally {
            // Even when the FX teardown fails, the overrides never reach the next class in the fork.
            ThemeManager.setDefaultForTest(null);
            EventBusPublisher.setDefault(previousBus);
            bus.close();
            for (Preferences node : nodes) {
                try {
                    node.removeNode();
                } catch (BackingStoreException | IllegalStateException alreadyGone) {
                    // never flushed
                }
            }
        }
    }

    // ── Main window at every step ────────────────────────────────────────────

    /**
     * Ruling R6 at every advertised step (review 2b-conformance S1, S2): every
     * visible node of the main window — the Session Status Strip and a shown
     * notification included — lies inside 1280×720 or scrolls; Play, Stop and
     * Record and the menu bar are reachable; the window is styled at the step;
     * the A16 overflow groups show exactly when 1280 unscaled px exceed the
     * threshold.
     */
    @ParameterizedTest
    @EnumSource(UiScale.class)
    void atEveryStepTheMainWindowIsLaidOutUnclippedAndTheTransportStaysReachable(UiScale step) throws Exception {
        List<String> failures = Story307TestSupport.onFx(() -> {
            appearance.setUiScale(step);
            MainWindow window = showMainWindow();
            List<String> problems = new ArrayList<>();

            if (window.root.getWidth() != WIDTH || window.root.getHeight() != HEIGHT) {
                problems.add("root is " + window.root.getWidth() + "x" + window.root.getHeight());
            }
            problems.addAll(outsideTheWindow(window.root));
            if (!window.statusStrip.isVisible() || window.statusStrip.getHeight() <= 0) {
                problems.add("the Session Status Strip is not laid out");
            }
            if (!window.notificationBar.isVisible() || window.notificationBar.getHeight() <= 0) {
                problems.add("the shown notification is not laid out");
            }

            ScrollPane scroller = window.node("transportBarScroller", ScrollPane.class);
            scroller.setHvalue(0);
            window.root.layout();
            Node viewport = scroller.lookup(".viewport");
            Bounds viewportInScene = viewport.localToScene(viewport.getBoundsInLocal());
            for (String id : List.of("playButton", "stopButton", "recordButton")) {
                Button button = window.node(id, Button.class);
                Bounds bounds = button.localToScene(button.getBoundsInLocal());
                if (!button.isVisible() || !inside(bounds, 0, 0, WIDTH, HEIGHT) || !inside(bounds, viewportInScene)) {
                    problems.add(id + " not reachable at hvalue 0: " + bounds + " viewport " + viewportInScene);
                }
            }
            Bounds menuBounds = window.menuBar.localToScene(window.menuBar.getBoundsInLocal());
            if (!window.menuBar.isVisible() || !inside(menuBounds, 0, 0, WIDTH, HEIGHT)) {
                problems.add("menu bar not inside the window: " + menuBounds);
            }

            double playFont = window.node("playButton", Button.class).getFont().getSize();
            if (Math.abs(playFont - 11.0 * step.factor()) > 0.01) {
                problems.add(".dawg-button resolves " + playFont + " px, not 11 × " + step.factor());
            }
            boolean wide = WIDTH / step.factor() > ToolbarAppearanceController.TOOLBAR_OVERFLOW_THRESHOLD;
            if (window.node("utilityGroup", HBox.class).isVisible() != wide
                    || window.node("undoRedoGroup", HBox.class).isVisible() != wide) {
                problems.add("toolbar overflow groups " + (wide ? "hidden" : "shown") + " at "
                        + WIDTH / step.factor() + " unscaled px");
            }
            return problems;
        }, 40, TimeUnit.SECONDS);

        assertThat(failures).as("main window at %s in %dx%d", step, WIDTH, HEIGHT).isEmpty();
    }

    /**
     * Amendment A16 — the overflow rule runs in 100% pixels: at 100% a
     * 1400 px window shows the groups, and the same window at 200% hides them.
     */
    @Test
    void theToolbarOverflowRuleComparesUnscaledWidthAndRerunsOnAScaleChange() throws Exception {
        boolean[] shown = Story307TestSupport.onFx(() -> {
            MainWindow window = showMainWindow(1400, HEIGHT, null);
            boolean atOneHundred = window.node("utilityGroup", HBox.class).isVisible();
            appearance.setUiScale(UiScale.P200);
            boolean atTwoHundred = window.node("utilityGroup", HBox.class).isVisible();
            return new boolean[] {atOneHundred, atTwoHundred};
        }, 40, TimeUnit.SECONDS);

        assertThat(shown[0]).as("1400 px at 100%% is wide").isTrue();
        assertThat(shown[1]).as("1400 px at 200%% is 700 unscaled px: narrow").isFalse();
    }

    /**
     * Review 2b-java B1 — a press anywhere in the transport scroller (a
     * label, the bar's background, its scroll bar) never moves the keyboard
     * focus to the scroller, so Space, Home and End still reach the window's
     * Play/Pause and Skip to Start/End accelerators (the real
     * {@link KeyboardShortcutController} registrations) afterwards.
     */
    @Test
    void aPressAnywhereInTheTransportBarLeavesPlayAndSkipOnTheirKeys() throws Exception {
        List<String> failures = Story307TestSupport.onFx(() -> {
            appearance.setUiScale(UiScale.P200);
            MainWindow window = showMainWindow();
            Scene scene = window.root.getScene();
            List<String> calls = new ArrayList<>();
            new KeyboardShortcutController(new KeyBindingManager(node("uiScaleTransportKeys")), recordingHost(calls))
                    .register(scene);
            ScrollPane scroller = window.node("transportBarScroller", ScrollPane.class);
            ScrollBar horizontalBar = scroller.lookupAll(".scroll-bar").stream()
                    .filter(node -> node instanceof ScrollBar bar && bar.getOrientation() == Orientation.HORIZONTAL)
                    .map(ScrollBar.class::cast).findFirst().orElseThrow();
            Map<String, Node> targets = new LinkedHashMap<>();
            targets.put("time display", window.node("timeDisplay", Label.class));
            targets.put("tempo label", window.node("tempoLabel", Label.class));
            targets.put("status label", window.node("statusLabel", Label.class));
            targets.put("bar background", window.node("transportBar", HBox.class));
            // A press on the scroll bar outside its track, thumb and arrows reaches the bar itself.
            targets.put("scroll bar", horizontalBar);

            List<String> problems = new ArrayList<>();
            if (!horizontalBar.isVisible()) {
                problems.add("the transport does not overflow at 200%: no scroll bar to press");
            }
            for (Map.Entry<String, Node> target : targets.entrySet()) {
                window.node("tracksPanelHeader", Label.class).requestFocus();
                calls.clear();
                press(target.getValue(), MouseButton.PRIMARY);
                for (KeyCode code : List.of(KeyCode.SPACE, KeyCode.HOME, KeyCode.END)) {
                    Node owner = scene.getFocusOwner();
                    Event.fireEvent(owner == null ? window.root : owner,
                            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
                }
                if (scroller.isFocused()) {
                    problems.add("a press on the " + target.getKey() + " focused the transport scroller");
                }
                if (!calls.equals(List.of("onPlay", "onSkipBack", "onSkipForward"))) {
                    problems.add("after a press on the " + target.getKey()
                            + " Space/Home/End ran " + calls + " (focus owner " + scene.getFocusOwner() + ")");
                }
            }
            return problems;
        }, 40, TimeUnit.SECONDS);

        assertThat(failures).isEmpty();
    }

    // ── The cascade guard (A14.3 extended; engineer A.5, S4; 2b-java S2) ────

    /**
     * Engineer ruling A.5 (binding): at every step other than 100%, each node
     * renders as its 100% rendering scaled — every scaled property (font size,
     * padding, label padding, spacing and gaps, cell size, pref/min/max sizes)
     * resolves to its 100% value times the factor when a stylesheet set it at
     * 100%, the rule that wins at 100% wins at the step, a value set from Java
     * at 100% is not replaced by CSS, and no rule applies only at the step.
     * Checked after a CSS-only pass, so a CSS value that a later layout would
     * overwrite from Java is seen. Main window (with the Inspector, the status
     * strip and a notification).
     */
    @ParameterizedTest
    @EnumSource(value = UiScale.class, names = {"P90", "P150"})
    void everyScaledPropertyOfTheMainWindowIsItsOneHundredPercentValueTimesTheStep(UiScale step)
            throws Exception {
        List<String> drift = Story307TestSupport.onFx(() -> {
            MainWindow window = showMainWindow();
            return cascadeDrift(List.of(window.root), step);
        }, 40, TimeUnit.SECONDS);

        assertThat(drift).as("main window at %s", step).isEmpty();
    }

    /** Engineer ruling A.5 — the same guard over the Settings dialog (setting rows, nav rail, shell). */
    @ParameterizedTest
    @EnumSource(value = UiScale.class, names = {"P90", "P150"})
    void everyScaledPropertyOfTheSettingsDialogIsItsOneHundredPercentValueTimesTheStep(UiScale step)
            throws Exception {
        List<String> drift = Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = showSettings(new SettingsModel(node("uiScaleGuardSettings")), null);
            return cascadeDrift(List.of(dialog.getDialogPane()), step);
        }, 40, TimeUnit.SECONDS);

        assertThat(drift).as("Settings dialog at %s", step).isEmpty();
    }

    /**
     * Engineer ruling A.5 / review 2b-java S2 — the same guard over an
     * Inspector drawer with its Inserts section ("+ Add") and Notifications
     * section (a history pill with an action, the "Clear" button), and the
     * components whose user-agent sheets {@code styles.css} overrides: a
     * mixer with channel strips, knobs, faders and meters, a track strip, a
     * channel meter, a knob and a project card.
     */
    @ParameterizedTest
    @EnumSource(value = UiScale.class, names = {"P90", "P150"})
    void everyScaledPropertyOfTheInspectorAndTheComponentsIsItsOneHundredPercentValueTimesTheStep(UiScale step)
            throws Exception {
        List<String> drift = Story307TestSupport.onFx(() -> {
            Parent sampler = componentSampler();
            Scene scene = new Scene(sampler, WIDTH, HEIGHT);
            appearance.applyTo(scene);
            show(scene);
            settle(sampler);
            return cascadeDrift(List.of(sampler), step);
        }, 40, TimeUnit.SECONDS);

        assertThat(drift).as("Inspector and components at %s", step).isEmpty();
    }

    // ── Settings dialog ──────────────────────────────────────────────────────

    /**
     * Amendment A21 — no setting-row label that fits at 100% is truncated
     * after a live switch to 200%.
     */
    @Test
    void afterALiveSwitchToTwoHundredPercentNoRowLabelThatFitsAtOneHundredPercentIsTruncated() throws Exception {
        SettingsModel model = new SettingsModel(node("uiScaleTruncation"));
        Map<String, Boolean> truncatedAtOneHundred = Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = showSettings(model, null);
            Map<String, Boolean> truncation = labelTruncation(dialog.getShell());
            appearance.setUiScale(UiScale.P200);
            return truncation;
        }, 18, TimeUnit.SECONDS);
        awaitPulses(3);
        List<String> failures = Story307TestSupport.onFx(() -> {
            SettingsShell shell = dialogs.getLast().getShell();
            settle(dialogs.getLast().getDialogPane());
            Map<String, Boolean> truncatedAtTwoHundred = labelTruncation(shell);
            List<String> problems = new ArrayList<>();
            for (Map.Entry<String, Boolean> entry : truncatedAtOneHundred.entrySet()) {
                if (!entry.getValue() && truncatedAtTwoHundred.getOrDefault(entry.getKey(), true)) {
                    problems.add("label of " + entry.getKey() + " fits at 100% but is truncated at 200%");
                }
            }
            if (truncatedAtOneHundred.size() < 20) {
                problems.add("only " + truncatedAtOneHundred.size() + " row labels were checked");
            }
            return problems;
        }, 20, TimeUnit.SECONDS);

        assertThat(failures).isEmpty();
    }

    /**
     * Ruling R6 + amendment A22 (review 2b-conformance S3, engineer S3) —
     * switched live from 100% to 200%, the Settings window stays inside its
     * screen — the headless screen and the ruled 1280×720 and 1280×680 —, the
     * footer's Cancel / Apply / OK lie inside it in screen coordinates, and
     * the UI Scale row is reachable in its scrolling pane.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 720, 680})
    void switchedLiveToTwoHundredPercentTheSettingsDialogStaysOnItsScreenAndOperable(int screenHeight)
            throws Exception {
        Rectangle2D screen = screen(screenHeight);
        SettingsModel model = new SettingsModel(node("uiScaleLiveSwitch"));
        SettingsDialog dialog = Story307TestSupport.onFx(() -> {
            SettingsDialog shown = showSettings(model, screen);
            appearance.setUiScale(UiScale.P200);
            return shown;
        }, FX_BUDGET_SECONDS, TimeUnit.SECONDS);
        // The window's new size reaches its scene on the next pulses.
        awaitPulses(3);
        List<String> failures = Story307TestSupport.onFx(() -> {
            settle(dialog.getDialogPane());
            return reachabilityProblems(dialog, screen);
        }, FX_BUDGET_SECONDS, TimeUnit.SECONDS);

        assertThat(failures).as("Settings switched live to 200%% (screen %s)", screen).isEmpty();
    }

    /**
     * Ruling R6 + amendment A22 at every step (review 2b-conformance S2, S3;
     * engineer S3) — opened at the step on the ruled 1280×720 and 1280×680
     * screens (and at 200% on the headless screen, where its 1600 px band is
     * wider than the screen), the Settings window fits its screen and Cancel /
     * Apply / OK and the UI Scale row stay reachable, so a user can always
     * return to 100%.
     */
    @ParameterizedTest
    @MethodSource("stepsAndScreens")
    void openedAtTheStepTheSettingsDialogFitsItsScreenAndStaysOperable(UiScale step, int screenHeight)
            throws Exception {
        Rectangle2D screen = screen(screenHeight);
        SettingsModel model = new SettingsModel(node("uiScaleOpenedAt"));
        SettingsDialog dialog = Story307TestSupport.onFx(() -> {
            appearance.setUiScale(step);
            return showSettings(model, screen);
        }, FX_BUDGET_SECONDS, TimeUnit.SECONDS);
        awaitPulses(3);
        List<String> failures = Story307TestSupport.onFx(() -> {
            settle(dialog.getDialogPane());
            return reachabilityProblems(dialog, screen);
        }, FX_BUDGET_SECONDS, TimeUnit.SECONDS);

        assertThat(failures).as("Settings opened at %s (screen %s)", step, screen).isEmpty();
    }

    static Stream<Arguments> stepsAndScreens() {
        List<Arguments> cases = new ArrayList<>();
        for (UiScale step : UiScale.values()) {
            cases.add(Arguments.of(step, 720));
            cases.add(Arguments.of(step, 680));
        }
        cases.add(Arguments.of(UiScale.P200, 0));
        return cases.stream();
    }

    /** 0 → the headless screen's visual bounds; otherwise the ruled 1280 px wide screen of that height. */
    private static Rectangle2D screen(int height) {
        return switch (height) {
            case 0 -> Screen.getPrimary().getVisualBounds();
            case 720 -> SCREEN_1280_BY_720;
            case 680 -> SCREEN_1280_BY_680;
            default -> throw new IllegalArgumentException("screen height " + height);
        };
    }

    /**
     * Studio ruling R6 — one canonical step list is the row's choice options
     * and its validator (the setter's guard), shown with the ruled labels.
     */
    @Test
    void theUiScaleRowIsAChoiceOfTheSevenStepsWithTheRuledLabels() throws Exception {
        SettingsModel model = new SettingsModel(node("uiScaleRow"));
        List<String> labels = Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = showSettings(model, null);
            SettingRow row = dialog.getShell().settingRow("appearance.uiScale").orElseThrow();
            assertThat(row.descriptor().controlKind()).isEqualTo(ControlKind.CHOICE);
            assertThat(row.descriptor().accepts(1.25)).isTrue();
            assertThat(row.descriptor().accepts(1.3)).as("off-step refused").isFalse();
            assertThat(row.descriptor().accepts(3.0)).as("the old slider maximum refused").isFalse();
            return stepLabels(row);
        }, FX_BUDGET_SECONDS, TimeUnit.SECONDS);

        assertThat(labels).containsExactly("90%", "100%", "110%", "125%", "150%", "175%", "200%");
    }

    /**
     * Review 2b-conformance S6 — the first-run wizard's UI Scale row offers
     * the same canonical step list with the ruled labels.
     */
    @Test
    void theFirstRunWizardsUiScaleRowOffersTheSevenStepsWithTheRuledLabels() throws Exception {
        SettingsModel model = new SettingsModel(node("uiScaleWizard"));
        List<String> labels = Story307TestSupport.onFx(() -> {
            FirstRunWizard wizard = new FirstRunWizard(SettingsCatalogue.create(), model, null);
            Scene scene = new Scene(wizard, 900, 700);
            appearance.applyTo(scene);
            wizard.nextStep();
            settle(wizard);
            SettingRow row = wizard.currentStepRows().stream()
                    .filter(candidate -> candidate.descriptor().id().equals("appearance.uiScale"))
                    .findFirst().orElseThrow();
            List<String> shown = stepLabels(row);
            wizard.close();
            return shown;
        }, FX_BUDGET_SECONDS, TimeUnit.SECONDS);

        assertThat(labels).containsExactly("90%", "100%", "110%", "125%", "150%", "175%", "200%");
    }

    /** The row's combo items are the canonical steps; returns their displayed labels. */
    private static List<String> stepLabels(SettingRow row) {
        ComboBox<?> combo = (ComboBox<?>) row.lookup(".combo-box");
        assertThat(List.<Object>copyOf(combo.getItems())).as("the step list")
                .containsExactlyElementsOf(UiScale.factors());
        @SuppressWarnings("unchecked")
        StringConverter<Object> converter = (StringConverter<Object>) (StringConverter<?>) combo.getConverter();
        List<String> shown = new ArrayList<>();
        for (Object item : combo.getItems()) {
            shown.add(converter.toString(item));
        }
        return shown;
    }

    /**
     * The window inside {@code screen}, the footer buttons inside the window
     * and the screen (screen coordinates), and the UI Scale row inside its
     * scrolling viewport once revealed.
     */
    private static List<String> reachabilityProblems(SettingsDialog dialog, Rectangle2D screen) {
        SettingsShell shell = dialog.getShell();
        List<String> problems = new ArrayList<>();
        Window window = dialog.getDialogPane().getScene().getWindow();
        Rectangle2D windowBounds = new Rectangle2D(window.getX(), window.getY(), window.getWidth(),
                window.getHeight());
        if (!contains(screen, windowBounds)) {
            problems.add("dialog window " + windowBounds + " exceeds the screen " + screen);
        }
        Node footer = shell.lookup(".settings-footer");
        for (Node child : ((Parent) footer).getChildrenUnmodifiable()) {
            if (child instanceof Button button && button.isVisible()) {
                Bounds bounds = button.localToScreen(button.getBoundsInLocal());
                if (bounds == null || !contains(screen, rectangle(bounds))
                        || !contains(windowBounds, rectangle(bounds))) {
                    problems.add("footer button '" + button.getText() + "' at " + bounds
                            + " outside the window " + windowBounds + " / screen " + screen);
                }
            }
        }
        shell.revealSetting("appearance.uiScale");
        settle(dialog.getDialogPane());
        SettingRow row = shell.settingRow("appearance.uiScale").orElseThrow();
        ScrollPane paneScroll = enclosingScrollPane(row);
        Node viewport = paneScroll.lookup(".viewport");
        Bounds viewportBounds = viewport.localToScene(viewport.getBoundsInLocal());
        Bounds rowBounds = row.localToScene(row.getBoundsInLocal());
        if (!inside(rowBounds, viewportBounds)) {
            problems.add("UI Scale row " + rowBounds + " not inside its viewport " + viewportBounds);
        }
        return problems;
    }

    // ── Mixer ────────────────────────────────────────────────────────────────

    /**
     * Amendment A19 — in a 1280×720 window the mixer's track columns do not
     * overlap and each channel strip lies inside its column.
     */
    @ParameterizedTest
    @EnumSource(value = UiScale.class, names = {"P100", "P200"})
    void theMixerColumnsHoldTheirStripsWithoutOverlap(UiScale step) throws Exception {
        List<String> failures = Story307TestSupport.onFx(() -> {
            appearance.setUiScale(step);
            MixerView mixer = mixer(5);
            Scene scene = new Scene(mixer, WIDTH, HEIGHT);
            appearance.applyTo(scene);
            show(scene);
            settle(mixer);
            return columnProblems(mixer);
        }, 40, TimeUnit.SECONDS);

        assertThat(failures).as("mixer at %s", step).isEmpty();
    }

    /**
     * Review 2b-java S4.2 / 2b-conformance S6 — a live scale change with the
     * mixer shown re-sizes it: the track columns (rebuilt) and the master
     * strip and its meter (re-sized in place) follow the factor.
     */
    @Test
    void aLiveScaleChangeWithTheMixerShownResizesItsColumnsAndItsMasterStrip() throws Exception {
        double[][] widths = Story307TestSupport.onFx(() -> {
            MixerView mixer = mixer(4);
            Scene scene = new Scene(mixer, WIDTH, HEIGHT);
            appearance.applyTo(scene);
            show(scene);
            settle(mixer);
            double[] atOneHundred = mixerWidths(mixer);
            appearance.setUiScale(UiScale.P150);
            settle(mixer);
            return new double[][] {atOneHundred, mixerWidths(mixer)};
        }, 40, TimeUnit.SECONDS);

        String[] names = {"track column", "master strip pref width", "master meter width"};
        for (int i = 0; i < names.length; i++) {
            assertThat(widths[0][i]).as("%s at 100%%", names[i]).isPositive();
            assertThat(widths[1][i]).as("%s at 150%% (100%%: %s)", names[i], widths[0][i])
                    .isCloseTo(widths[0][i] * 1.5, within(1.0));
        }
    }

    /**
     * Engineer B1 (binding behaviour) — at 200% in the 1280×720 main window,
     * under the real menu bar, transport, status bar and Session Status Strip,
     * the mixer's strips are taller than their viewport: a vertical scroll bar
     * shows, and scrolled to the bottom every strip's Mute/Solo/Arm row and
     * name lie inside the viewport.
     */
    @Test
    void atTwoHundredPercentUnderTheMainWindowChromeEveryStripsControlsScrollIntoView() throws Exception {
        List<String> failures = Story307TestSupport.onFx(() -> {
            appearance.setUiScale(UiScale.P200);
            MixerView mixer = mixer(4);
            MainWindow window = showMainWindow(WIDTH, HEIGHT, mixer);
            ScrollPane strips = enclosingScrollPane(mixer.getChannelStrips());
            List<String> problems = new ArrayList<>();
            boolean verticalBarShown = strips.lookupAll(".scroll-bar").stream()
                    .anyMatch(node -> node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL
                            && bar.isVisible());
            if (!verticalBarShown) {
                problems.add("no vertical scroll bar although the strips are taller than the viewport "
                        + strips.getViewportBounds());
            }
            strips.setVvalue(strips.getVmax());
            settle(window.root);
            Node viewport = strips.lookup(".viewport");
            Bounds viewportInScene = viewport.localToScene(viewport.getBoundsInLocal());
            int checked = 0;
            for (Node node : mixer.getChannelStrips().lookupAll(".mixer-channel-strip")) {
                if (!(node instanceof MixerChannelStrip strip) || !(strip.getSkin() instanceof MixerChannelStripSkin skin)) {
                    continue;
                }
                for (Node control : List.of(skin.msrRow(), skin.nameLabel())) {
                    Bounds bounds = control.localToScene(control.getBoundsInLocal());
                    if (bounds.getMinY() < viewportInScene.getMinY() - 0.5
                            || bounds.getMaxY() > viewportInScene.getMaxY() + 0.5) {
                        problems.add(describe(control) + " at y [" + bounds.getMinY() + ", " + bounds.getMaxY()
                                + "] outside the viewport [" + viewportInScene.getMinY() + ", "
                                + viewportInScene.getMaxY() + "]");
                    }
                }
                checked++;
            }
            if (checked < 4) {
                problems.add("only " + checked + " channel strips checked");
            }
            return problems;
        }, 40, TimeUnit.SECONDS);

        assertThat(failures).isEmpty();
    }

    // ── Main window ──────────────────────────────────────────────────────────

    private record MainWindow(BorderPane root, MenuBar menuBar, SessionStatusStrip statusStrip,
                              NotificationBar notificationBar, Map<String, Object> namespace) {
        <T> T node(String id, Class<T> type) {
            return type.cast(Objects.requireNonNull(namespace.get(id), id));
        }
    }

    private MainWindow showMainWindow() throws IOException {
        return showMainWindow(WIDTH, HEIGHT, null);
    }

    /**
     * main-view.fxml without its controller, plus the real menu bar, the
     * Session Status Strip, a shown notification and the production wiring;
     * {@code center} replaces the FXML centre when not {@code null}.
     */
    private MainWindow showMainWindow(int width, int height, Node center) throws IOException {
        URL location = Objects.requireNonNull(UiScaleLayoutTest.class.getResource(FXML));
        String text;
        try (InputStream in = location.openStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        text = text.replaceAll("\\s+fx:controller=\"[^\"]*\"", "")
                .replaceAll("\\s+onAction=\"#[^\"]*\"", "");
        FXMLLoader loader = new FXMLLoader(location);
        BorderPane root = loader.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> namespace = loader.getNamespace();

        KeyBindingManager keys = new KeyBindingManager(node("uiScaleLayoutKeys"));
        DawMenuBarControllerTest.StubHost host = new DawMenuBarControllerTest.StubHost();
        MenuBar menuBar = new DawMenuBarController(host, keys,
                () -> false, () -> false, () -> false, () -> false, () -> false,
                () -> false, () -> false, () -> false).build();
        ((VBox) root.getTop()).getChildren().addFirst(menuBar);

        // Mounted where MainController mounts them (createSessionStatusStrip,
        // initializeNotificationBar).
        SessionStatusStrip statusStrip = new SessionStatusStrip(
                new ProjectOperationProgress(new FxDispatcher()), MotionManager.getDefault());
        ((VBox) namespace.get("bottomBar")).getChildren().add(statusStrip);
        NotificationBar notificationBar = new NotificationBar();
        ((HBox) namespace.get("notificationBarContainer")).getChildren().add(notificationBar);
        HBox.setHgrow(notificationBar, Priority.ALWAYS);
        notificationBar.show(NotificationLevel.WARNING, "Input 1 is clipping on the last take",
                "Configure input", () -> { });
        NotificationHistoryService history = new NotificationHistoryService();
        history.record(NotificationLevel.WARNING, "Input 1 is clipping on the last take",
                Optional.of(() -> { }), Optional.of("Configure input"));
        ((InspectorDrawer) namespace.get("inspectorDrawer")).setNotificationHistoryService(history);
        if (center != null) {
            root.setCenter(center);
        }

        MainWindow window = new MainWindow(root, menuBar, statusStrip, notificationBar, namespace);
        MainController.installTransportBarScroll(window.node("transportBarScroller", ScrollPane.class),
                window.node("transportBar", HBox.class));
        new ToolbarAppearanceController(
                new ToolbarAppearanceController.TransportButtons(
                        window.node("skipBackButton", Button.class), window.node("playButton", Button.class),
                        window.node("stopButton", Button.class), window.node("recordButton", Button.class),
                        window.node("skipForwardButton", Button.class), window.node("loopButton", Button.class),
                        window.node("loopRecordButton", Button.class), window.node("metronomeButton", Button.class)),
                new ToolbarAppearanceController.ToolbarButtons(
                        window.node("addAudioTrackButton", Button.class), window.node("addMidiTrackButton", Button.class),
                        window.node("undoButton", Button.class), window.node("redoButton", Button.class),
                        window.node("snapButton", Button.class), window.node("saveButton", Button.class),
                        window.node("pluginsButton", Button.class)),
                new ToolbarAppearanceController.AppearanceLabels(
                        window.node("statusLabel", Label.class), window.node("timeDisplay", Label.class),
                        window.node("tracksPanelHeader", Label.class), window.node("arrangementPanelHeader", Label.class),
                        window.node("arrangementPlaceholder", Label.class), window.node("monitoringLabel", Label.class),
                        window.node("statusBarLabel", Label.class), window.node("ioRoutingLabel", Label.class),
                        window.node("recIndicator", Label.class)),
                new ToolbarAppearanceController.OverflowGroups(
                        window.node("utilityGroup", HBox.class), window.node("undoRedoGroup", HBox.class)),
                root, keys, appearance.uiScaleProperty()).apply();

        Scene scene = new Scene(root, width, height);
        appearance.applyTo(scene);
        show(scene);
        settle(root);
        return window;
    }

    private void show(Scene scene) {
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        windows.add(stage);
    }

    /** The {@code LiveMasteringViewTest} idiom: CSS, layout, a pulse-equivalent snapshot, layout. */
    private static void settle(Parent root) {
        root.applyCss();
        root.layout();
        Scene scene = root.getScene();
        scene.snapshot(new WritableImage((int) Math.max(1, scene.getWidth()), (int) Math.max(1, scene.getHeight())));
        root.applyCss();
        root.layout();
    }

    /**
     * Every visible node whose scene bounds leave the window, except what a
     * scroll pane scrolls: a scroll pane and its viewport are checked, the
     * content inside the viewport (and the skin's wrapper around it) is not.
     */
    private static List<String> outsideTheWindow(Parent root) {
        List<String> out = new ArrayList<>();
        walk(root, Collections.newSetFromMap(new IdentityHashMap<>()), out);
        return out;
    }

    private static void walk(Node node, Set<Node> viewports, List<String> out) {
        if (!node.isVisible()) {
            return;
        }
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        if (!bounds.isEmpty() && !inside(bounds, 0, 0, WIDTH, HEIGHT)) {
            out.add(describe(node) + " at " + bounds);
        }
        if (viewports.contains(node)) {
            return;
        }
        if (node instanceof ScrollPane scrollPane && scrollPane.getSkin() != null) {
            Node viewport = scrollPane.lookup(".viewport");
            if (viewport != null) {
                viewports.add(viewport);
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walk(child, viewports, out);
            }
        }
    }

    /** Presses and releases {@code button} at the centre of {@code target}, as the scene would deliver it. */
    private static void press(Node target, MouseButton button) {
        Bounds bounds = target.localToScene(target.getBoundsInLocal());
        double x = bounds.getCenterX();
        double y = bounds.getCenterY();
        boolean primary = button == MouseButton.PRIMARY;
        boolean secondary = button == MouseButton.SECONDARY;
        Event.fireEvent(target, new MouseEvent(MouseEvent.MOUSE_PRESSED, x, y, x, y, button, 1,
                false, false, false, false, primary, false, secondary, false, false, false, null));
        Event.fireEvent(target, new MouseEvent(MouseEvent.MOUSE_RELEASED, x, y, x, y, button, 1,
                false, false, false, false, false, false, false, false, false, true, null));
    }

    /** A {@link KeyboardShortcutController.Host} that records the names of the host methods its actions call. */
    private static KeyboardShortcutController.Host recordingHost(List<String> calls) {
        return (KeyboardShortcutController.Host) Proxy.newProxyInstance(
                KeyboardShortcutController.Host.class.getClassLoader(),
                new Class<?>[] {KeyboardShortcutController.Host.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method, args);
                    }
                    calls.add(method.getName());
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> "recordingHost";
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return null;
    }

    // ── The cascade guard ────────────────────────────────────────────────────

    /** A scaled property as resolved: its value, the origin of the value, and whether it is the initial value. */
    private record Resolved(Object value, StyleOrigin origin, boolean initial) {
    }

    /**
     * Resolves every guarded property of every node under {@code roots} at
     * 100% and again at {@code step} (each after a CSS-only pass) and returns
     * every node whose property does not render as its 100% rendering scaled.
     */
    private List<String> cascadeDrift(List<Parent> roots, UiScale step) {
        Map<Node, Map<String, Resolved>> atOneHundred = new IdentityHashMap<>();
        for (Parent root : roots) {
            settle(root);
            reapplyCssWithoutLayout(root);
            resolveScaledProperties(root, atOneHundred);
        }
        appearance.setUiScale(step);
        Map<Node, Map<String, Resolved>> atStep = new IdentityHashMap<>();
        for (Parent root : roots) {
            settle(root);
            reapplyCssWithoutLayout(root);
            resolveScaledProperties(root, atStep);
        }
        List<String> problems = new ArrayList<>();
        int styledChecked = 0;
        for (Map.Entry<Node, Map<String, Resolved>> node : atOneHundred.entrySet()) {
            Map<String, Resolved> after = atStep.get(node.getKey());
            if (after == null) {
                continue;
            }
            double ownFontRatio = fontRatio(node.getValue(), after);
            for (Map.Entry<String, Resolved> property : node.getValue().entrySet()) {
                Resolved before = property.getValue();
                Resolved now = after.get(property.getKey());
                if (now == null || before.origin() == StyleOrigin.INLINE) {
                    continue;
                }
                String where = describe(node.getKey()) + " " + property.getKey() + ": " + format(before.value())
                        + " (" + before.origin() + ") -> " + format(now.value()) + " (" + now.origin() + ")";
                if (property.getKey().equals("-fx-font")) {
                    // A font set from Java stays Java's; every other font follows the root
                    // (an unstyled one inherits the scaled .root font size) or its sheet.
                    if (before.origin() == StyleOrigin.USER) {
                        if (now.origin() != StyleOrigin.USER) {
                            problems.add(where + " — a font set from Java at 100% is replaced by CSS");
                        }
                    } else {
                        styledChecked++;
                        if (!scaled(before.value(), now.value(), step.factor(), ownFontRatio)) {
                            problems.add(where + " — not its 100% value × " + step.factor());
                        }
                    }
                } else if (before.origin() == StyleOrigin.USER || (before.origin() == null && !before.initial())) {
                    // Set from Java (a Region size set before its property exists has no origin yet).
                    if (now.origin() == StyleOrigin.AUTHOR || now.origin() == StyleOrigin.USER_AGENT) {
                        problems.add(where + " — a value set from Java at 100% is replaced by CSS");
                    }
                } else if (before.origin() == null) {
                    if (now.origin() != null && !Objects.equals(before.value(), now.value())) {
                        problems.add(where + " — a rule applies only at " + step);
                    }
                } else {
                    styledChecked++;
                    if (now.origin() == StyleOrigin.USER || now.origin() == null) {
                        problems.add(where + " — styled at 100% but not at " + step);
                    } else if (!scaled(before.value(), now.value(), step.factor(), ownFontRatio)) {
                        problems.add(where + " — not its 100% value × " + step.factor());
                    }
                }
            }
        }
        if (styledChecked < 100) {
            problems.add("only " + styledChecked + " stylesheet-set properties were checked");
        }
        return problems;
    }

    /** Re-styles {@code root}'s scene without laying it out, so a CSS value Java would overwrite in layout shows. */
    private static void reapplyCssWithoutLayout(Parent root) {
        Scene scene = root.getScene();
        scene.getStylesheets().add(CSS_PASS_TRIGGER);
        root.applyCss();
        scene.getStylesheets().remove(CSS_PASS_TRIGGER);
        root.applyCss();
    }

    private static void resolveScaledProperties(Node node, Map<Node, Map<String, Resolved>> out) {
        Map<String, Resolved> properties = new LinkedHashMap<>();
        for (CssMetaData<? extends Styleable, ?> meta : node.getCssMetaData()) {
            if (!GUARDED_PROPERTIES.contains(meta.getProperty())) {
                continue;
            }
            @SuppressWarnings("unchecked")
            CssMetaData<Node, ?> nodeMeta = (CssMetaData<Node, ?>) meta;
            StyleableProperty<?> property = nodeMeta.getStyleableProperty(node);
            if (property != null) {
                Object value = property.getValue();
                properties.put(meta.getProperty(), new Resolved(value, property.getStyleOrigin(),
                        Objects.equals(value, nodeMeta.getInitialValue(node))));
            }
        }
        out.put(node, properties);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                resolveScaledProperties(child, out);
            }
        }
    }

    /** The node's own font size ratio (an em length follows its own font), or {@code NaN} without a font. */
    private static double fontRatio(Map<String, Resolved> before, Map<String, Resolved> after) {
        Resolved fontBefore = before.get("-fx-font");
        Resolved fontAfter = after.get("-fx-font");
        if (fontBefore != null && fontAfter != null && fontBefore.value() instanceof Font a
                && fontAfter.value() instanceof Font b && a.getSize() > 0) {
            return b.getSize() / a.getSize();
        }
        return Double.NaN;
    }

    /** Whether {@code now} is {@code before} times the factor (or the node's own font ratio, for em lengths). */
    private static boolean scaled(Object before, Object now, double factor, double ownFontRatio) {
        if (before instanceof Font a && now instanceof Font b) {
            return Math.abs(b.getSize() - a.getSize() * factor) <= 0.5;
        }
        if (before instanceof Insets a && now instanceof Insets b) {
            return scaledLength(a.getTop(), b.getTop(), factor, ownFontRatio)
                    && scaledLength(a.getRight(), b.getRight(), factor, ownFontRatio)
                    && scaledLength(a.getBottom(), b.getBottom(), factor, ownFontRatio)
                    && scaledLength(a.getLeft(), b.getLeft(), factor, ownFontRatio);
        }
        if (before instanceof Number a && now instanceof Number b) {
            return scaledLength(a.doubleValue(), b.doubleValue(), factor, ownFontRatio);
        }
        return Objects.equals(before, now);
    }

    private static boolean scaledLength(double before, double now, double factor, double ownFontRatio) {
        if (!Double.isFinite(before) || before < 0 || before >= 1e5) {
            // USE_COMPUTED_SIZE / USE_PREF_SIZE / Double.MAX_VALUE: sentinels, never scaled.
            return Double.compare(before, now) == 0;
        }
        return Math.abs(now - before * factor) <= 0.5
                || (!Double.isNaN(ownFontRatio) && Math.abs(now - before * ownFontRatio) <= 0.5);
    }

    private static String format(Object value) {
        if (value instanceof Font font) {
            return font.getSize() + "px";
        }
        return String.valueOf(value);
    }

    /**
     * An Inspector drawer (Inserts "+ Add"; a Notifications history with an
     * action pill and "Clear") beside the UA-styled components {@code styles.css}
     * overrides: a two-track mixer, a track strip, a channel meter, a knob, a
     * project card and a channel strip of its own.
     */
    private Parent componentSampler() {
        InspectorDrawer drawer = new InspectorDrawer();
        NotificationHistoryService history = new NotificationHistoryService();
        history.record(NotificationLevel.WARNING, "Input 1 is clipping on the last take",
                Optional.of(() -> { }), Optional.of("Configure input"));
        history.record(NotificationLevel.INFO, "Project saved", Optional.empty(), Optional.empty());
        drawer.setNotificationHistoryService(history);

        TrackStrip trackStrip = new TrackStrip();
        trackStrip.setTrackName("Vox");
        LevelMeter meter = new LevelMeter();
        meter.getStyleClass().add("size-channel");
        Knob knob = Knob.create().size(36).unit("dB").build();
        ProjectCard card = new ProjectCard();
        card.setName("Song A");
        // A channel strip outside the mixer too: the mixer rebuilds its own strips on a scale
        // change, so only a strip that lives across the switch can be compared.
        MixerChannelStrip channelStrip = new MixerChannelStrip();
        HBox components = new HBox(8, trackStrip, meter, knob, card, channelStrip);
        return new BorderPane(mixer(2), components, drawer, null, null);
    }

    // ── Settings dialog ──────────────────────────────────────────────────────

    /** A Settings dialog on its Appearance category, capped to {@code screen} when given (else its real screen). */
    private SettingsDialog showSettings(SettingsModel model, Rectangle2D screen) {
        SettingsDialog dialog = new SettingsDialog(model);
        dialogs.add(dialog);
        if (screen != null) {
            DawgDialogCapSeam.capBounds(dialog, screen);
        }
        dialog.selectCategory("appearance");
        dialog.show();
        settle(dialog.getDialogPane());
        return dialog;
    }

    /** Row id → whether its label's rendered text is cut, across every category. */
    private static Map<String, Boolean> labelTruncation(SettingsShell shell) {
        Map<String, Boolean> truncated = new LinkedHashMap<>();
        for (String category : List.of("audio", "appearance", "project", "recording", "backups",
                "keyBindings", "plugins", "general")) {
            shell.selectCategory(category);
            shell.applyCss();
            shell.layout();
            for (Node node : shell.lookupAll(".setting-row")) {
                if (node instanceof SettingRow row && row.getSkin() != null) {
                    Label label = (Label) row.lookup(".setting-row-label");
                    Node rendered = label.lookup(".text");
                    if (label.getGraphic() == null && rendered instanceof Text text && !label.getText().isEmpty()) {
                        truncated.put(row.descriptor().id(), !text.getText().equals(label.getText()));
                    }
                }
            }
        }
        return truncated;
    }

    // ── Mixer ────────────────────────────────────────────────────────────────

    private MixerView mixer(int tracks) {
        DawProject project = new DawProject("Mixer", AudioFormat.CD_QUALITY);
        for (String name : List.of("Kick", "Snare", "Bass", "Vocal", "Keys").subList(0, tracks)) {
            project.createAudioTrack(name);
        }
        MixerView mixer = new MixerView(project);
        mixers.add(mixer);
        return mixer;
    }

    private static List<Region> trackColumns(MixerView mixer) {
        List<Region> columns = new ArrayList<>();
        for (Node child : mixer.getChannelStrips().getChildren()) {
            if (child.getStyleClass().contains(MixerView.TRACK_STRIP_HOST_STYLE_CLASS)) {
                columns.add((Region) child);
            }
        }
        return columns;
    }

    /** The first track column's width, the master strip's preferred width and its meter's width. */
    private static double[] mixerWidths(MixerView mixer) {
        Region masterMeter = (Region) mixer.getMasterStrip().lookupAll("*").stream()
                .filter(node -> node instanceof LevelMeterDisplay)
                .findFirst().orElseThrow();
        return new double[] {trackColumns(mixer).getFirst().getWidth(), mixer.getMasterStrip().getPrefWidth(),
                masterMeter.getWidth()};
    }

    private static List<String> columnProblems(MixerView mixer) {
        List<String> problems = new ArrayList<>();
        List<Region> columns = trackColumns(mixer);
        if (columns.size() < 4) {
            problems.add("only " + columns.size() + " track columns");
        }
        for (int i = 0; i + 1 < columns.size(); i++) {
            Region left = columns.get(i);
            Region right = columns.get(i + 1);
            if (left.getLayoutX() + left.getWidth() > right.getLayoutX() + 0.5) {
                problems.add("columns " + i + " and " + (i + 1) + " overlap: " + left.getBoundsInParent()
                        + " / " + right.getBoundsInParent());
            }
        }
        for (Region column : columns) {
            Node strip = column.lookup(".mixer-channel-strip");
            if (!(strip instanceof MixerChannelStrip)) {
                problems.add("no channel strip in " + describe(column));
                continue;
            }
            Bounds inColumn = column.sceneToLocal(strip.localToScene(strip.getLayoutBounds()));
            if (inColumn.getMinX() < -0.5 || inColumn.getMaxX() > column.getWidth() + 0.5) {
                problems.add("strip " + inColumn + " outside its column of width " + column.getWidth());
            }
        }
        return problems;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Waits, bounded (8 s, 18 s with its two FX calls), until the toolkit ran {@code pulses} more pulses (an animation timer counts them). */
    private static void awaitPulses(int pulses) throws Exception {
        CountDownLatch counted = new CountDownLatch(pulses);
        AnimationTimer timer = Story307TestSupport.onFx(() -> {
            AnimationTimer counting = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    counted.countDown();
                }
            };
            counting.start();
            return counting;
        });
        try {
            assertThat(counted.await(8, TimeUnit.SECONDS)).as("%d pulses", pulses).isTrue();
        } finally {
            Story307TestSupport.onFx(() -> {
                timer.stop();
                return null;
            });
        }
    }

    private static ScrollPane enclosingScrollPane(Node node) {
        for (Node n = node.getParent(); n != null; n = n.getParent()) {
            if (n instanceof ScrollPane scrollPane) {
                return scrollPane;
            }
        }
        throw new AssertionError("no scroll pane around " + describe(node));
    }

    private static boolean inside(Bounds bounds, double minX, double minY, double maxX, double maxY) {
        return bounds.getMinX() >= minX - 0.5 && bounds.getMinY() >= minY - 0.5
                && bounds.getMaxX() <= maxX + 0.5 && bounds.getMaxY() <= maxY + 0.5;
    }

    private static boolean inside(Bounds bounds, Bounds container) {
        return inside(bounds, container.getMinX(), container.getMinY(), container.getMaxX(), container.getMaxY());
    }

    private static Rectangle2D rectangle(Bounds bounds) {
        return new Rectangle2D(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
    }

    private static boolean contains(Rectangle2D outer, Rectangle2D inner) {
        return inner.getMinX() >= outer.getMinX() - 0.5 && inner.getMinY() >= outer.getMinY() - 0.5
                && inner.getMaxX() <= outer.getMaxX() + 0.5 && inner.getMaxY() <= outer.getMaxY() + 0.5;
    }

    private static String describe(Node node) {
        String id = node.getId() == null ? "" : "#" + node.getId();
        return node.getClass().getSimpleName() + id + node.getStyleClass()
                + (node instanceof Labeled labeled ? " '" + labeled.getText() + "'" : "");
    }

    private Preferences node(String name) {
        Preferences node = Preferences.userRoot().node(name + "_" + System.nanoTime());
        nodes.add(node);
        return node;
    }
}
