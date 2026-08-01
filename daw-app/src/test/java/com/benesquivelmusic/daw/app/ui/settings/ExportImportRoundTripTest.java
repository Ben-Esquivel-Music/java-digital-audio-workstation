package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileIO.ImportOutcome;
import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileIO.ProfileScope;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 309 — the §6.3 profile round-trip contract: exporting a scope
 * and re-importing it restores identical values, and the exported
 * profile contains ONLY the selected scopes' keys ({@code keybinding.*}
 * absent when only Application is selected — Key Bindings is its own
 * §5.9 checkbox, carved out of the APPLICATION scope).
 *
 * <p>Pure parse/serialise tests over {@link SettingsProfileIO}'s static
 * format functions — deliberately off-toolkit
 * ({@code feedback_javafx_headless_test_pitfalls}); the off-FX-thread
 * I/O side lives in {@code ProfileIoOffFxThreadTest}.</p>
 */
class ExportImportRoundTripTest {

    private final SettingsCatalogue catalogue = SettingsCatalogue.create();

    /** Descriptor ids belonging to one profile scope, catalogue order. */
    private List<String> idsOf(ProfileScope scope) {
        return catalogue.descriptors().stream()
                .filter(descriptor -> ProfileScope.of(descriptor) == scope)
                .map(SettingDescriptor::id)
                .toList();
    }

    /** Non-comment profile lines. */
    private static List<String> entryLines(String text) {
        return text.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
    }

    private static String keyOf(String entryLine) {
        return entryLine.substring(entryLine.indexOf('|') + 1, entryLine.indexOf('='));
    }

    /**
     * Current values: catalogue defaults overlaid with a few deliberate
     * APPLICATION-scope non-defaults, so the round-trip proves value
     * transport (not just default echo).
     */
    private Function<String, Object> valuesWith(Map<String, Object> overrides) {
        return id -> overrides.containsKey(id)
                ? overrides.get(id)
                : catalogue.byId(id)
                        .map(descriptor -> (Object) descriptor.defaultValue())
                        .orElse(null);
    }

    @Test
    void applicationExportShouldContainOnlyApplicationKeysAndRoundTrip() {
        ThemeManager.Theme nonDefaultTheme = Arrays.stream(ThemeManager.Theme.values())
                .filter(theme -> theme != ThemeManager.DEFAULT_THEME)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Need at least two themes"));
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("appearance.uiScale", 2.0);
        overrides.put("project.useJournaledPersistence", true);
        overrides.put("metronome.enabled", false);
        overrides.put("plugins.scanPaths", "C:/studio/plugins");
        overrides.put(ThemeManager.PREF_KEY, nonDefaultTheme);
        Function<String, Object> values = valuesWith(overrides);

        String text = SettingsProfileIO.serialize(
                catalogue, Set.of(ProfileScope.APPLICATION), values);

        // Only the selected scope's keys — exactly, in catalogue order.
        List<String> lines = entryLines(text);
        assertThat(lines.stream().map(ExportImportRoundTripTest::keyOf).toList())
                .containsExactlyElementsOf(idsOf(ProfileScope.APPLICATION));
        assertThat(lines)
                .allSatisfy(line -> assertThat(line).startsWith("application|"));
        assertThat(lines)
                .as("Key Bindings is its own checkbox — keybinding.* keys are "
                        + "ABSENT from an Application-only export")
                .noneMatch(line -> keyOf(line).startsWith("keybinding."));

        // Re-import restores identical values (canonical types included).
        ImportOutcome outcome = SettingsProfileIO.parseAndValidate(
                catalogue, Set.of(ProfileScope.APPLICATION), text);
        assertThat(outcome.rejects()).isEmpty();
        Map<String, Object> expected = new LinkedHashMap<>();
        for (String id : idsOf(ProfileScope.APPLICATION)) {
            expected.put(id, values.apply(id));
        }
        assertThat(outcome.applied()).containsExactlyEntriesOf(expected);
        assertThat(outcome.applied().get("appearance.uiScale")).isEqualTo(2.0);
        assertThat(outcome.applied().get(ThemeManager.PREF_KEY))
                .isEqualTo(nonDefaultTheme);
    }

    @Test
    void exportShouldBeDeterministicDiffableAndTimestampFree() {
        Function<String, Object> values = valuesWith(Map.of());
        Set<ProfileScope> scopes = Set.of(
                ProfileScope.APPLICATION, ProfileScope.PROJECT_DEFAULTS);

        String first = SettingsProfileIO.serialize(catalogue, scopes, values);
        String second = SettingsProfileIO.serialize(catalogue, scopes, values);

        assertThat(second)
                .as("identical settings serialise identically (no timestamps, "
                        + "stable order — §6.3 diffable)")
                .isEqualTo(first);
        assertThat(first.lines().findFirst().orElseThrow())
                .isEqualTo("# " + SettingsProfileIO.HEADER);
        assertThat(entryLines(first)).hasSize(
                idsOf(ProfileScope.APPLICATION).size()
                        + idsOf(ProfileScope.PROJECT_DEFAULTS).size());
    }

    @Test
    void keyBindingsScopeShouldRoundTripBoundAndExplicitlyUnboundBindings() {
        List<String> keyBindingIds = idsOf(ProfileScope.KEY_BINDINGS);
        assertThat(keyBindingIds).hasSizeGreaterThanOrEqualTo(2);
        String boundId = keyBindingIds.get(0);
        String unboundId = keyBindingIds.get(1);
        KeyCombination bound = new KeyCodeCombination(
                KeyCode.F24, KeyCombination.SHORTCUT_DOWN);
        Map<String, Object> overrides = new HashMap<>();
        overrides.put(boundId, bound);
        overrides.put(unboundId, null);
        Function<String, Object> values = valuesWith(overrides);

        String text = SettingsProfileIO.serialize(
                catalogue, Set.of(ProfileScope.KEY_BINDINGS), values);

        assertThat(entryLines(text))
                .allSatisfy(line ->
                        assertThat(keyOf(line)).startsWith("keybinding."));
        assertThat(text).contains(boundId + "=" + bound.getName());
        assertThat(text).contains(unboundId + "=" + SettingsProfileIO.UNBOUND_MARKER);

        ImportOutcome outcome = SettingsProfileIO.parseAndValidate(
                catalogue, Set.of(ProfileScope.KEY_BINDINGS), text);
        assertThat(outcome.rejects()).isEmpty();
        assertThat(outcome.applied().get(boundId))
                .as("a bound combination round-trips through getName()/valueOf")
                .isEqualTo(bound);
        assertThat(outcome.applied())
                .as("an explicitly cleared binding round-trips as null")
                .containsKey(unboundId);
        assertThat(outcome.applied().get(unboundId)).isNull();
    }

    @Test
    void importShouldSkipEntriesOutsideTheSelectedScopes() {
        Function<String, Object> values = valuesWith(Map.of());
        // A profile carrying BOTH scopes, imported with only one selected.
        String text = SettingsProfileIO.serialize(catalogue,
                Set.of(ProfileScope.APPLICATION, ProfileScope.AUDIO_DEVICE), values);

        ImportOutcome outcome = SettingsProfileIO.parseAndValidate(
                catalogue, Set.of(ProfileScope.AUDIO_DEVICE), text);

        assertThat(outcome.rejects()).isEmpty();
        assertThat(outcome.applied().keySet())
                .as("only the selected scope's entries are applied; the rest "
                        + "are skipped, not errors")
                .containsExactlyElementsOf(idsOf(ProfileScope.AUDIO_DEVICE));
    }
}
