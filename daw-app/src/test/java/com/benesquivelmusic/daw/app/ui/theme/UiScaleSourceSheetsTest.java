package com.benesquivelmusic.daw.app.ui.theme;

import com.benesquivelmusic.daw.app.ui.SourceScanSupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 (studio ruling R6) — the UI-scale sheet re-states the lengths of
 * every application stylesheet, so every stylesheet under {@code ui/**} that
 * holds a scaled declaration must be in {@link ThemeManager}'s source list
 * (otherwise its px lengths would silently stay at 100&nbsp;%). The theme
 * overlays are not sources: they may only re-declare colour tokens.
 */
class UiScaleSourceSheetsTest {

    private static final Path UI_RESOURCES = SourceScanSupport.locateDawAppModule()
            .resolve("src/main/resources/com/benesquivelmusic/daw/app/ui");
    private static final Path UI_SOURCES = SourceScanSupport.locateDawAppModule()
            .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");

    @Test
    void everyStylesheetWithAScaledDeclarationIsAScaleSource() throws IOException {
        List<String> sheets = stylesheets();
        assertThat(sheets).as("the scan found the application stylesheets").contains("styles.css");

        List<String> overlays = ThemeManager.themeOverlaySheets();
        for (String sheet : sheets) {
            String css = Files.readString(UI_RESOURCES.resolve(sheet), StandardCharsets.UTF_8);
            if (overlays.contains(sheet)) {
                assertThat(ScaledStylesheet.hasScaledDeclarations(css))
                        .as("theme overlay %s must not declare a scaled length", sheet)
                        .isFalse();
            } else if (ScaledStylesheet.hasScaledDeclarations(css)) {
                assertThat(ThemeManager.scaleSourceSheets())
                        .as("%s declares scaled lengths, so ThemeManager must re-state it", sheet)
                        .contains(sheet);
            }
        }
    }

    @Test
    void everyListedSourceExistsAndUserAgentSheetsPrecedeTheAuthorSheets() {
        List<String> sources = ThemeManager.scaleSourceSheets();
        for (String source : sources) {
            assertThat(UI_RESOURCES.resolve(source)).as("source sheet %s", source).isRegularFile();
        }
        // Amendment A14: component (user-agent) sheets first, then styles.css,
        // then the views' Parent-level sheets.
        int base = sources.indexOf("styles.css");
        assertThat(sources.subList(base + 1, sources.size()))
                .containsExactly("hub/project-hub.css", "hub/welcome-view.css");
    }

    /**
     * Review 2b-java N5 — classified by how each sheet is attached, not by its
     * list position: a {@code Parent} that adds an application stylesheet to
     * its own list (it beats the scene's scale sheet at equal specificity)
     * must also keep the scale sheet at the tail of that list through
     * {@link ThemeManager#registerScaledParent}. Every stylesheet mutation in
     * the {@code ui} main code outside {@code ThemeManager} is a call on the
     * view's own list followed by that registration, and each such view's
     * sheet is one of the Parent-level sources after {@code styles.css}.
     */
    @Test
    void everyViewThatAddsItsOwnStylesheetRegistersForTheScaleSheet() throws IOException {
        Pattern mutation = Pattern.compile("(\\.?)getStylesheets\\(\\)\\s*\\.\\s*(add|addAll|setAll|set)\\s*\\(");
        Pattern sheetName = Pattern.compile("getResource\\(\\s*\"([^\"]+\\.css)\"");
        List<String> views = new ArrayList<>();
        try (Stream<Path> files = Files.walk(UI_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                if (file.getFileName().toString().equals("ThemeManager.java")) {
                    continue;
                }
                String code = SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher matcher = mutation.matcher(code);
                while (matcher.find()) {
                    String where = UI_SOURCES.relativize(file).toString().replace('\\', '/');
                    assertThat(matcher.group(1)).as("%s mutates another node's or a scene's stylesheets"
                            + " (only ThemeManager may)", where).isEmpty();
                    assertThat(code).as("%s adds its own stylesheet", where)
                            .contains("registerScaledParent(this)");
                    Matcher name = sheetName.matcher(code.substring(matcher.end()));
                    assertThat(name.find()).as("%s names the sheet it adds", where).isTrue();
                    String sheet = UI_SOURCES.relativize(file.getParent()).resolve(name.group(1)).toString()
                            .replace('\\', '/');
                    views.add(sheet);
                }
            }
        }
        List<String> sources = ThemeManager.scaleSourceSheets();
        assertThat(views).as("the Parent-level sheets the views add").isNotEmpty();
        assertThat(sources.subList(sources.indexOf("styles.css") + 1, sources.size()))
                .as("the Parent-level sources after styles.css are exactly the views' own sheets")
                .containsExactlyInAnyOrderElementsOf(views);
    }

    private static List<String> stylesheets() throws IOException {
        try (Stream<Path> files = Files.walk(UI_RESOURCES)) {
            return files.filter(path -> path.toString().endsWith(".css"))
                    .map(path -> UI_RESOURCES.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }
}
