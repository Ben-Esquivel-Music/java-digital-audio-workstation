package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectCard;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHubView;
import com.benesquivelmusic.daw.app.ui.hub.WelcomeView;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 335 (studio ruling R6) — the two views whose own stylesheet sits at
 * {@code Parent} level (it beats the scene's sheets at equal specificity)
 * register with {@link ThemeManager#registerScaledParent}, so the scale sheet
 * also sits at the tail of their own list and their lengths scale (review
 * 2b-conformance S6, 2b-java S4.3); and a hub grid card that a List → Grid
 * switch made content-sized stays content-sized at every step (2b-java
 * S2(b)).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class UiScaleParentSheetsTest {

    private static final String SCALE_SHEET_PREFIX = "data:text/css;base64,";

    @TempDir
    Path tempRoot;

    private FxDispatcher dispatcher;
    private Preferences prefs;
    private ThemeManager appearance;
    private final List<Thread> scans = new ArrayList<>();

    @BeforeEach
    void isolate() throws Exception {
        prefs = Preferences.userRoot().node("uiScaleParentSheets_" + System.nanoTime());
        appearance = new ThemeManager(prefs);
        ThemeManager.setDefaultForTest(appearance);
        dispatcher = new FxDispatcher();
        FxDispatcher.installDefault(dispatcher);
        Story307TestSupport.onFx(() -> {
            dispatcher.start();
            return null;
        });
    }

    @AfterEach
    void restore() throws Exception {
        try {
            for (Thread scan : scans) {
                scan.join(5_000);
            }
            Story307TestSupport.onFx(() -> {
                dispatcher.dispose();
                return null;
            });
        } finally {
            // Even when the FX teardown fails, the overrides never reach the next class in the fork.
            FxDispatcher.installDefault(null);
            ThemeManager.setDefaultForTest(null);
            try {
                prefs.removeNode();
            } catch (BackingStoreException | IllegalStateException alreadyGone) {
                // never flushed
            }
        }
    }

    /** One step's look of a view: its own sheet list's tail and one of its own px lengths. */
    private record Look(String lastOwnSheet, double paddingTop) {
    }

    /** One step's grid card: its pref-width property, its laid-out width and the width its content asks for. */
    private record CardWidth(UiScale step, double prefWidthProperty, double width, double contentWidth) {
    }

    @Test
    void theProjectHubKeepsTheScaleSheetAtTheTailOfItsOwnListAndItsLengthsScale() throws Exception {
        Look[] looks = Story307TestSupport.onFx(() -> {
            ProjectHubView hub = new ProjectHubView(List.of(), new ProjectHealthScanner(dispatcher),
                    _ -> { }, _ -> { });
            scans.addAll(hub.pendingScans());
            return looksAcrossSteps(hub, ".project-hub-sections");
        }, 30, TimeUnit.SECONDS);

        assertLengthScalesWithTheOwnSheet(looks, 16);
    }

    @Test
    void theWelcomeViewKeepsTheScaleSheetAtTheTailOfItsOwnListAndItsLengthsScale() throws Exception {
        Look[] looks = Story307TestSupport.onFx(() -> {
            WelcomeView welcome = new WelcomeView(List.of(), new ProjectHealthScanner(dispatcher), dispatcher,
                    () -> { }, _ -> { }, () -> { }, () -> { }, () -> { });
            return looksAcrossSteps(welcome, ".welcome-sections");
        }, 30, TimeUnit.SECONDS);

        assertLengthScalesWithTheOwnSheet(looks, 24);
    }

    /**
     * A card that a List → Grid switch at 100% made content-sized keeps its
     * pref width computed at every advertised step — never the user-agent
     * 240 px times the scale — and is laid out at the width its content asks
     * for.
     */
    @Test
    void aGridCardMadeContentSizedByAListToGridSwitchStaysContentSizedAtEveryStep() throws Exception {
        Path project = writeProject("Song A");
        List<CardWidth> widths = Story307TestSupport.onFx(() -> {
            ProjectHubView hub = new ProjectHubView(List.of(project), new ProjectHealthScanner(dispatcher),
                    _ -> { }, _ -> { });
            scans.addAll(hub.pendingScans());
            Scene scene = new Scene(hub, 1200, 800);
            appearance.applyTo(scene);
            settle(hub);
            hub.listToggle().setSelected(true);
            hub.gridToggle().setSelected(true);
            settle(hub);
            ProjectCard card = hub.recentCards().getFirst();
            List<CardWidth> measured = new ArrayList<>();
            for (UiScale step : UiScale.values()) {
                appearance.setUiScale(step);
                settle(hub);
                measured.add(new CardWidth(step, card.getPrefWidth(), card.getWidth(), card.prefWidth(-1)));
            }
            return measured;
        }, 30, TimeUnit.SECONDS);

        assertThat(widths).extracting(CardWidth::step).as("every step").containsExactly(UiScale.values());
        for (CardWidth at : widths) {
            assertThat(at.prefWidthProperty())
                    .as("at %s the card is content-sized (not the user-agent 240 px × the scale)", at.step())
                    .isEqualTo(Region.USE_COMPUTED_SIZE);
            assertThat(at.width()).as("at %s it is laid out at the width its content asks for", at.step())
                    .isCloseTo(at.contentWidth(), within(1.0));
        }
    }

    private Look[] looksAcrossSteps(Parent view, String sectionsSelector) {
        Scene scene = new Scene(view, 1200, 800);
        appearance.applyTo(scene);
        Look atOneHundred = look(view, sectionsSelector);
        appearance.setUiScale(UiScale.P150);
        Look atOneFifty = look(view, sectionsSelector);
        appearance.setUiScale(UiScale.P100);
        Look backAtOneHundred = look(view, sectionsSelector);
        assertThat(scene).isNotNull();
        return new Look[] {atOneHundred, atOneFifty, backAtOneHundred};
    }

    private static Look look(Parent view, String selector) {
        settle(view);
        Region sections = (Region) view.lookup(selector);
        List<String> own = view.getStylesheets();
        return new Look(own.getLast(), sections.getPadding().getTop());
    }

    private static void assertLengthScalesWithTheOwnSheet(Look[] looks, double paddingAtOneHundred) {
        assertThat(looks[0].lastOwnSheet()).as("no scale sheet in the view's own list at 100%%")
                .doesNotStartWith(SCALE_SHEET_PREFIX);
        assertThat(looks[0].paddingTop()).isCloseTo(paddingAtOneHundred, within(1e-6));
        assertThat(looks[1].lastOwnSheet()).as("the scale sheet is the tail of the view's own list at 150%%")
                .startsWith(SCALE_SHEET_PREFIX);
        assertThat(looks[1].paddingTop()).as("the view's own px length at 150%%")
                .isCloseTo(paddingAtOneHundred * 1.5, within(1e-6));
        assertThat(looks[2].lastOwnSheet()).as("gone again at 100%%").doesNotStartWith(SCALE_SHEET_PREFIX);
        assertThat(looks[2].paddingTop()).isCloseTo(paddingAtOneHundred, within(1e-6));
    }

    private static void settle(Parent root) {
        root.applyCss();
        root.layout();
    }

    private Path writeProject(String name) throws IOException {
        Path dir = Files.createDirectory(tempRoot.resolve("proj-" + UUID.randomUUID()));
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                    <metadata>
                        <name>%s</name>
                        <created-at>2026-01-01T00:00:00Z</created-at>
                        <last-modified>2026-01-01T00:00:00Z</last-modified>
                    </metadata>
                </daw-project>
                """.formatted(name);
        Files.writeString(dir.resolve("project.daw"), xml);
        return dir;
    }
}
