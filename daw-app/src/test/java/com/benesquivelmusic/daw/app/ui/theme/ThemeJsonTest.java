package com.benesquivelmusic.daw.app.ui.theme;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Verifies that {@link ThemeJson} round-trips a theme without losing data.
 */
class ThemeJsonTest {

    @Test
    void roundTripPreservesAllFields() {
        Map<String, Theme.Color> colors = new LinkedHashMap<>();
        colors.put("background", new Theme.Color("#0a0a0a", "background"));
        colors.put("foreground", new Theme.Color("#fefefe", "foreground"));
        colors.put("accent", new Theme.Color("#82b1ff", "accent"));
        Theme original = new Theme(
                "round-trip",
                "Round Trip",
                "Test theme",
                true,
                colors,
                List.of(
                        new Theme.Pair("foreground", "background"),
                        new Theme.Pair("accent", "background")));
        String json = ThemeJson.toJson(original);
        Theme parsed = ThemeJson.parse(json);

        assertThat(parsed.id()).isEqualTo(original.id());
        assertThat(parsed.name()).isEqualTo(original.name());
        assertThat(parsed.description()).isEqualTo(original.description());
        assertThat(parsed.dark()).isEqualTo(original.dark());
        assertThat(parsed.colors()).containsAllEntriesOf(original.colors());
        assertThat(parsed.pairs()).containsExactlyElementsOf(original.pairs());
    }

    @Test
    void parseRejectsThemeWithUnknownColorReferenceInPair() {
        // Pair references a color that doesn't exist — Theme constructor enforces this.
        String json = """
                {
                  "id": "broken",
                  "name": "Broken",
                  "description": "",
                  "dark": true,
                  "colors": {
                    "background": { "value": "#000", "role": "background" }
                  },
                  "pairs": [
                    { "foreground": "missing", "background": "background" }
                  ]
                }
                """;
        assertThatIllegalArgumentException().isThrownBy(() -> ThemeJson.parse(json));
    }

    @Test
    void parseRejectsBadHexValue() {
        String json = """
                {
                  "id": "x", "name": "x", "description": "", "dark": true,
                  "colors": { "bg": { "value": "not-a-color", "role": "background" } },
                  "pairs": []
                }
                """;
        assertThatIllegalArgumentException().isThrownBy(() -> ThemeJson.parse(json));
    }
}
