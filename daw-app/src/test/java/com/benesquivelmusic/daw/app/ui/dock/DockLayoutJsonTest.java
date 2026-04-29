package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for {@link DockLayoutJson}.
 */
class DockLayoutJsonTest {

    @Test
    void roundTripPreservesEveryField() {
        DockLayout original = DockLayout.empty()
                .withEntry(DockEntry.docked("arrangement", DockZone.CENTER, 0, true))
                .withEntry(DockEntry.docked("browser", DockZone.LEFT, 0, false))
                .withEntry(DockEntry.floating("mixer",
                        new Rectangle2D(120.5, 80, 900, 480)));

        String json = DockLayoutJson.toJson(original);
        DockLayout restored = DockLayoutJson.parse(json);

        assertThat(restored.entries()).hasSize(3);
        assertThat(restored.entry("arrangement").get().zone()).isEqualTo(DockZone.CENTER);
        assertThat(restored.entry("browser").get().visible()).isFalse();
        DockEntry mix = restored.entry("mixer").get();
        assertThat(mix.zone()).isEqualTo(DockZone.FLOATING);
        assertThat(mix.floatingBounds()).isEqualTo(new Rectangle2D(120.5, 80, 900, 480));
    }

    @Test
    void parseTolerateNullBlankAndCorruptInput() {
        assertThat(DockLayoutJson.parse(null).entries()).isEmpty();
        assertThat(DockLayoutJson.parse("").entries()).isEmpty();
        assertThat(DockLayoutJson.parse("not json {{{").entries()).isEmpty();
        assertThat(DockLayoutJson.parse("{\"entries\": 5}").entries()).isEmpty();
    }

    @Test
    void unknownZoneFallsBackToCenter() {
        String json = "{\"entries\":[{\"id\":\"x\",\"zone\":\"DIAGONAL\","
                + "\"tabIndex\":0,\"visible\":true}]}";
        DockLayout l = DockLayoutJson.parse(json);
        assertThat(l.entry("x").get().zone()).isEqualTo(DockZone.CENTER);
    }

    @Test
    void floatingWithoutBoundsFallsBackToCenter() {
        String json = "{\"entries\":[{\"id\":\"x\",\"zone\":\"FLOATING\",\"visible\":true}]}";
        DockLayout l = DockLayoutJson.parse(json);
        assertThat(l.entry("x").get().zone()).isEqualTo(DockZone.CENTER);
    }

    @Test
    void emptyLayoutSerialisesToEntriesArray() {
        assertThat(DockLayoutJson.toJson(DockLayout.empty()))
                .isEqualTo("{\"entries\":[]}");
    }

    @Test
    void parsedLayoutSurvivesUnknownExtraFields() {
        // Forward-compat: future versions might add new fields per entry;
        // older parsers should ignore them.
        String json = "{\"entries\":[{\"id\":\"a\",\"zone\":\"TOP\","
                + "\"tabIndex\":0,\"visible\":true,\"futureField\":42}]}";
        DockLayout l = DockLayoutJson.parse(json);
        assertThat(l.entry("a").get().zone()).isEqualTo(DockZone.TOP);
    }
}
