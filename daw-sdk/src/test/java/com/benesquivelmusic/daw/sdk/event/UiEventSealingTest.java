package com.benesquivelmusic.daw.sdk.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 292 — verifies the closed shape of the new UI-only {@link UiEvent}
 * sealed family and proves, the same way {@link DawEventExhaustiveSwitchTest}
 * does for the engine domain, that:
 *
 * <ul>
 *   <li>an exhaustive {@code switch} over {@link UiEvent} compiles with no
 *       {@code default} branch (adding a permitted variant breaks compilation —
 *       story 202's sealing guarantee, mirrored here);</li>
 *   <li>{@link DawEvent}'s {@code permits} clause is <strong>unchanged</strong>
 *       by this story — the UI events are a <em>sibling</em> family under the
 *       shared {@link BusEvent} super-type, never added to {@code DawEvent}
 *       (story 292 Non-Goal: "Do not add the new UI events to {@code DawEvent}'s
 *       {@code permits}").</li>
 * </ul>
 */
class UiEventSealingTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * Returns a short textual classification of the UI event.
     *
     * <p>This switch has <strong>no {@code default} branch</strong> and covers
     * every permitted sub-type of {@link UiEvent}. If a new permitted sub-type is
     * added to {@link UiEvent} without updating this method, compilation fails
     * with "the switch expression does not cover all possible input values" — the
     * compile-time exhaustiveness guarantee this story preserves for the UI
     * family.</p>
     */
    private static String classify(UiEvent event) {
        return switch (event) {
            case UiEvent.SelectionChanged ignored -> "selection";
            case UiEvent.UndoStateChanged ignored -> "undoState";
        };
    }

    @Test
    void exhaustiveSwitchCoversUiEvents() {
        assertThat(classify(new UiEvent.SelectionChanged(T0))).isEqualTo("selection");
        assertThat(classify(new UiEvent.UndoStateChanged(T0))).isEqualTo("undoState");
    }

    @Test
    void uiEventPermitsExactlySelectionChangedAndUndoStateChanged() {
        List<String> permitted = Arrays.stream(UiEvent.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .toList();
        assertThat(permitted).containsExactlyInAnyOrder("SelectionChanged", "UndoStateChanged");
    }

    @Test
    void dawEventPermitsClauseIsUnchangedByThisStory() {
        // The eight engine-domain families and nothing else — story 292 must NOT
        // add SelectionChanged/UndoStateChanged here.
        List<String> permitted = Arrays.stream(DawEvent.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .toList();
        assertThat(permitted).containsExactlyInAnyOrder(
                "TransportEvent",
                "MixerEvent",
                "TrackEvent",
                "ClipEvent",
                "ProjectEvent",
                "AutomationEvent",
                "PluginEvent",
                "XrunEvent");
        assertThat(permitted).doesNotContain("UiEvent", "SelectionChanged", "UndoStateChanged");
    }

    @Test
    void busEventPermitsExactlyDawEventAndUiEvent() {
        List<String> permitted = Arrays.stream(BusEvent.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .toList();
        assertThat(permitted).containsExactlyInAnyOrder("DawEvent", "UiEvent");
    }

    @Test
    void uiEventIsABusEventButNotADawEvent() {
        UiEvent selection = new UiEvent.SelectionChanged(T0);
        assertThat(selection).isInstanceOf(BusEvent.class);
        // The whole point of the BusEvent split: a UiEvent rides the bus without
        // being a DawEvent, so the engine domain stays closed (story 292).
        assertThat(selection).isNotInstanceOf(DawEvent.class);
    }

    @Test
    void uiEventsExposeTheirTimestamp() {
        assertThat(new UiEvent.SelectionChanged(T0).timestamp()).isEqualTo(T0);
        assertThat(new UiEvent.UndoStateChanged(T0).timestamp()).isEqualTo(T0);
    }
}
