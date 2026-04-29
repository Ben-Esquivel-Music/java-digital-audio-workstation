package com.benesquivelmusic.daw.app.ui.theme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that all bundled themes are loadable, and that every declared
 * foreground/background pair in every bundled theme reaches WCAG 2.1 AAA
 * (the stronger requirement, per the issue spec "all AAA-compliant").
 *
 * <p>Also exercises the user-themes directory loading and the
 * "duplicate and edit" save round-trip.</p>
 *
 * <p>These tests are pure-logic — no JavaFX/display required.</p>
 */
class ThemeRegistryTest {

    @Test
    void bundledThemesAreLoaded(@TempDir Path tmp) {
        ThemeRegistry registry = new ThemeRegistry(tmp.resolve("user-themes"));
        List<String> ids = registry.themes().stream().map(Theme::id).toList();
        assertThat(ids).containsExactlyInAnyOrderElementsOf(ThemeRegistry.BUNDLED_IDS);
    }

    @Test
    void everyBundledThemePassesAaForEveryDeclaredPair(@TempDir Path tmp) {
        ThemeRegistry registry = new ThemeRegistry(tmp.resolve("user-themes"));
        for (ThemeAuditReport report : registry.auditAll()) {
            assertThat(report.passesAA())
                    .as("theme '%s' must pass WCAG AA for every declared pair; got %s",
                            report.theme().id(), describe(report))
                    .isTrue();
        }
    }

    @Test
    void everyBundledThemePassesAaaForEveryDeclaredPair(@TempDir Path tmp) {
        // Issue requirement: "all AAA-compliant".
        ThemeRegistry registry = new ThemeRegistry(tmp.resolve("user-themes"));
        for (ThemeAuditReport report : registry.auditAll()) {
            assertThat(report.passesAAA())
                    .as("theme '%s' must pass WCAG AAA for every declared pair; got %s",
                            report.theme().id(), describe(report))
                    .isTrue();
        }
    }

    @Test
    void defaultThemeIdIsBundled(@TempDir Path tmp) {
        ThemeRegistry registry = new ThemeRegistry(tmp.resolve("user-themes"));
        assertThat(registry.find(ThemeRegistry.DEFAULT_THEME_ID)).isPresent();
        assertThat(registry.findOrDefault("does-not-exist").id())
                .isEqualTo(ThemeRegistry.DEFAULT_THEME_ID);
    }

    @Test
    void userThemeOverridesBundledWhenIdMatches(@TempDir Path tmp) throws IOException {
        Path userDir = tmp.resolve("user-themes");
        Files.createDirectories(userDir);
        // Write a minimal user theme with the same id as a bundled theme.
        Map<String, Theme.Color> colors = new LinkedHashMap<>();
        colors.put("background", new Theme.Color("#000000", "background"));
        colors.put("foreground", new Theme.Color("#ffffff", "foreground"));
        Theme override = new Theme(
                "dark-accessible",
                "Dark Accessible (override)",
                "test override",
                true,
                colors,
                List.of(new Theme.Pair("foreground", "background")));
        ThemeJson.write(override, userDir.resolve("dark-accessible.json"));

        ThemeRegistry registry = new ThemeRegistry(userDir);
        Theme loaded = registry.find("dark-accessible").orElseThrow();
        assertThat(loaded.name()).isEqualTo("Dark Accessible (override)");
    }

    @Test
    void saveUserThemeWritesFileAndRegisters(@TempDir Path tmp) throws IOException {
        Path userDir = tmp.resolve("user-themes");
        ThemeRegistry registry = new ThemeRegistry(userDir);

        Map<String, Theme.Color> colors = new LinkedHashMap<>();
        colors.put("background", new Theme.Color("#101010", "background"));
        colors.put("foreground", new Theme.Color("#fafafa", "foreground"));
        Theme custom = new Theme(
                "my-custom",
                "My Custom",
                "round-trip",
                true,
                colors,
                List.of(new Theme.Pair("foreground", "background")));

        Path written = registry.saveUserTheme(custom);
        assertThat(Files.isRegularFile(written)).isTrue();
        assertThat(written.getFileName().toString()).isEqualTo("my-custom.json");
        assertThat(registry.find("my-custom")).isPresent();

        // Reload from disk and ensure the theme survives a round-trip.
        ThemeRegistry reloaded = new ThemeRegistry(userDir);
        Theme back = reloaded.find("my-custom").orElseThrow();
        assertThat(back.name()).isEqualTo("My Custom");
        assertThat(back.hex("foreground")).isEqualToIgnoringCase("#fafafa");
    }

    private static String describe(ThemeAuditReport report) {
        StringBuilder sb = new StringBuilder();
        for (ThemeAuditReport.Entry e : report.entries()) {
            sb.append("\n  ").append(e.pair().foreground()).append(" on ")
                    .append(e.pair().background())
                    .append(" -> ").append(ThemeContrastValidator.describe(e.ratio()));
        }
        return sb.toString();
    }
}
