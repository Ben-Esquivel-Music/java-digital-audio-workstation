package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.Knob;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ParameterGridPanel} — the §6.2 declarative parameter grid
 * (story 301).
 *
 * <p>Column math is pure and asserted with no toolkit interaction; the
 * behavioural tests build the panel on the FX thread and assert on style
 * classes and observable properties only — never rasterized pixels or
 * scene geometry (headless test convention). Note: a targeted
 * {@code -Dtest} run of FX tests may trip a known environment-artifact
 * {@code IllegalAccessError}; the full {@code mvn test} suite is
 * authoritative.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ParameterGridPanelTest {

    // ── Fixture descriptors ───────────────────────────────────────────────

    /** Continuous — 0..1 with a non-integral default; must render a knob. */
    private static final PluginParameter MIX =
            new PluginParameter(7, "Mix", 0.0, 1.0, 0.5);

    /** Boolean per the legacy heuristic — unit range, 0/1 default, "toggle" in the name. */
    private static final PluginParameter BYPASS_TOGGLE =
            new PluginParameter(11, "Bypass Toggle", 0.0, 1.0, 0.0);

    /** Discrete — 0..3 all-integral, span 3 (4 states). */
    private static final PluginParameter MODE =
            new PluginParameter(13, "Mode", 0.0, 3.0, 0.0);

    /** A user-gesture report captured from the panel's {@code ValueSink}. */
    private record Change(int parameterId, double value) {
    }

    private static final class RecordingSink implements ParameterGridPanel.ValueSink {
        final List<Change> changes = new ArrayList<>();

        @Override
        public void valueChanged(int parameterId, double newValue) {
            changes.add(new Change(parameterId, newValue));
        }
    }

    // ── computeColumns — pure math, no toolkit needed ─────────────────────

    @ParameterizedTest(name = "computeColumns({0}) == {1}")
    @CsvSource({
            // Exact §6.2 block widths: n*96 + (n-1)*16.
            "880,    8",   // 8*96 + 7*16
            "656,    6",   // 6*96 + 5*16
            "432,    4",   // 4*96 + 3*16
            "208,    2",   // 2*96 + 1*16
            "96,     1",   // 1*96
            // One pixel less than each exact block drops to the next
            // lower ALLOWED count — never an arbitrary count in between.
            "879,    6",
            "655,    4",
            "431,    2",
            "207,    1",
            "95,     1",
            // Mid-band widths stay on the allowed counts.
            "700,    6",
            "500,    4",
            "300,    2",
            // Huge widths cap at 8 columns.
            "100000, 8",
            // Zero / negative → a single column, never a crash.
            "0,      1",
            "-50,    1",
    })
    void computeColumnsHonoursTheAllowedColumnCounts(double availableWidth, int expectedColumns) {
        assertThat(ParameterGridPanel.computeColumns(availableWidth))
                .isEqualTo(expectedColumns);
    }

    @Test
    void computeColumnsBoundariesDeriveFromThePinnedConstants() {
        // Recompute the §6.2 boundaries from the public constants so the
        // relationship (not just the literal pixel values) is pinned.
        int[] allowed = {8, 6, 4, 2, 1};
        for (int i = 0; i < allowed.length; i++) {
            int n = allowed[i];
            double exact = n * ParameterGridPanel.CELL_WIDTH
                    + (n - 1) * ParameterGridPanel.GRID_GAP;
            assertThat(ParameterGridPanel.computeColumns(exact)).isEqualTo(n);
            int nextLower = (i + 1 < allowed.length) ? allowed[i + 1] : 1;
            assertThat(ParameterGridPanel.computeColumns(exact - 1)).isEqualTo(nextLower);
        }
    }

    // ── Cell generation ───────────────────────────────────────────────────

    @Test
    void threeParameterListBuildsThreeCellsWithExpectedControlTypes() {
        RecordingSink sink = new RecordingSink();
        ParameterGridPanel panel = newPanel(sink);

        assertThat(panel.getStyleClass()).contains(ParameterGridPanel.STYLE_CLASS);
        assertThat(panel.getChildrenUnmodifiable()).hasSize(3);
        for (Node cell : panel.getChildrenUnmodifiable()) {
            assertThat(cell.getStyleClass()).contains("parameter-grid-cell");
        }

        // Cell 0 — continuous "Mix": knob + name + numeric readout.
        VBox mixCell = cellAt(panel, 0);
        Knob knob = (Knob) mixCell.lookup(".knob");
        assertThat(knob).isNotNull();
        assertThat(knob.getMin()).isEqualTo(0.0);
        assertThat(knob.getMax()).isEqualTo(1.0);
        assertThat(knob.getDefaultValue()).isEqualTo(0.5);
        assertThat(knob.getValue()).isEqualTo(0.5);
        assertThat(knob.getTooltip().getText())
                .isEqualTo("Double-click to reset to default");
        Label mixName = (Label) mixCell.lookup(".parameter-name");
        assertThat(mixName.getText()).isEqualTo("Mix");
        Label mixReadout = (Label) mixCell.lookup(".parameter-value");
        assertThat(mixReadout.getText()).isEqualTo("0.50");
        assertThat(mixReadout.getStyleClass()).contains("numeric-caption");
        assertThat(mixCell.lookup(".toggle-button")).isNull();

        // Cell 1 — boolean "Bypass Toggle": labelled ON/OFF toggle, no knob.
        VBox bypassCell = cellAt(panel, 1);
        ToggleButton toggle = (ToggleButton) bypassCell.lookup(".toggle-button");
        assertThat(toggle).isNotNull();
        assertThat(toggle.isSelected()).isFalse();
        assertThat(toggle.getText()).isEqualTo("OFF");
        assertThat(bypassCell.lookup(".knob")).isNull();
        assertThat(bypassCell.lookup(".segmented-selector")).isNull();

        // Cell 2 — discrete "Mode": segmented selector, one segment per
        // integer value, default selected.
        VBox modeCell = cellAt(panel, 2);
        assertThat(modeCell.lookup(".segmented-selector")).isNotNull();
        assertThat(modeCell.lookup(".knob")).isNull();
        List<ToggleButton> segments = segmentsOf(panel);
        assertThat(segments).hasSize(4);
        assertThat(segments.stream().map(ToggleButton::getText).toList())
                .containsExactly("0", "1", "2", "3");
        assertThat(selectedSegmentText(segments)).isEqualTo("0");

        // Construction reports nothing through the sink.
        assertThat(sink.changes).isEmpty();
    }

    @Test
    void continuousUnitRangeParameterWithoutToggleNameDoesNotBecomeAToggle() {
        // The conservative boolean heuristic needs the name guard: a 0..1
        // parameter defaulting to an endpoint but NOT named "…toggle…"
        // must not render as an ON/OFF toggle. (With all-integral range
        // values it lands on the discrete branch — the §6.2 two-state
        // segmented selector, like the mockup's SIDECHAIN off/EXT.)
        RecordingSink sink = new RecordingSink();
        PluginParameter dryKill = new PluginParameter(17, "Dry Kill", 0.0, 1.0, 0.0);
        ParameterGridPanel panel = runOnFxThread(
                () -> new ParameterGridPanel(List.of(dryKill), sink));

        assertThat(panel.lookup(".segmented-selector")).isNotNull();
        List<ToggleButton> segments = segmentsOf(panel);
        assertThat(segments.stream().map(ToggleButton::getText).toList())
                .containsExactly("0", "1");
        // No ON/OFF labelled toggle anywhere in the cell.
        assertThat(segments.stream().map(ToggleButton::getText))
                .doesNotContain("ON", "OFF");
    }

    // ── User-gesture path (control → sink) ────────────────────────────────

    @Test
    void knobGestureFiresSinkExactlyOnceWithIdAndValue() {
        RecordingSink sink = new RecordingSink();
        ParameterGridPanel panel = newPanel(sink);
        Knob knob = (Knob) panel.lookup(".knob");

        runOnFxThread(() -> {
            knob.setValue(0.7);
            return null;
        });

        assertThat(sink.changes).containsExactly(new Change(7, 0.7));
    }

    @Test
    void toggleGestureReportsThroughSink() {
        RecordingSink sink = new RecordingSink();
        ParameterGridPanel panel = newPanel(sink);
        ToggleButton toggle = (ToggleButton) cellAt(panel, 1).lookup(".toggle-button");

        runOnFxThread(() -> {
            toggle.fire();
            return null;
        });

        assertThat(toggle.isSelected()).isTrue();
        assertThat(toggle.getText()).isEqualTo("ON");
        assertThat(sink.changes).containsExactly(new Change(11, 1.0));
    }

    @Test
    void segmentSelectionReportsOnceAndResistsDeselection() {
        RecordingSink sink = new RecordingSink();
        ParameterGridPanel panel = newPanel(sink);
        List<ToggleButton> segments = segmentsOf(panel);

        runOnFxThread(() -> {
            segments.get(2).fire();
            return null;
        });
        assertThat(selectedSegmentText(segments)).isEqualTo("2");
        assertThat(sink.changes).containsExactly(new Change(13, 2.0));

        // Clicking the already-selected segment attempts a ToggleGroup
        // deselection — the guard must keep it selected and must NOT
        // re-report through the sink.
        runOnFxThread(() -> {
            segments.get(2).fire();
            return null;
        });
        assertThat(segments.get(2).isSelected()).isTrue();
        assertThat(sink.changes).containsExactly(new Change(13, 2.0));
    }

    // ── Echo path (model → control, sink silent) ──────────────────────────

    @Test
    void updateValueMovesTheKnobAndReadoutWithoutFiringTheSink() {
        RecordingSink sink = new RecordingSink();
        ParameterGridPanel panel = newPanel(sink);
        Knob knob = (Knob) panel.lookup(".knob");
        Label readout = (Label) panel.lookup(".parameter-value");

        runOnFxThread(() -> {
            panel.updateValue(7, 0.25);
            return null;
        });

        assertThat(knob.getValue()).isEqualTo(0.25);
        assertThat(readout.getText()).isEqualTo("0.25");
        assertThat(sink.changes).isEmpty();
    }

    @Test
    void updateValueMovesToggleAndSegmentsWithoutFiringTheSink() {
        RecordingSink sink = new RecordingSink();
        ParameterGridPanel panel = newPanel(sink);
        ToggleButton toggle = (ToggleButton) cellAt(panel, 1).lookup(".toggle-button");
        List<ToggleButton> segments = segmentsOf(panel);

        runOnFxThread(() -> {
            panel.updateValue(11, 1.0);
            panel.updateValue(13, 2.0);
            return null;
        });

        assertThat(toggle.isSelected()).isTrue();
        assertThat(toggle.getText()).isEqualTo("ON");
        assertThat(selectedSegmentText(segments)).isEqualTo("2");
        assertThat(sink.changes).isEmpty();
    }

    @Test
    void updateValueForAnUnknownParameterIdIsIgnored() {
        RecordingSink sink = new RecordingSink();
        ParameterGridPanel panel = newPanel(sink);

        runOnFxThread(() -> {
            panel.updateValue(999, 0.5); // stale drain — must not throw
            return null;
        });

        assertThat(sink.changes).isEmpty();
    }

    @Test
    void setAllValuesEchoesEveryControlWithoutFiringTheSink() {
        RecordingSink sink = new RecordingSink();
        ParameterGridPanel panel = newPanel(sink);
        Knob knob = (Knob) panel.lookup(".knob");
        ToggleButton toggle = (ToggleButton) cellAt(panel, 1).lookup(".toggle-button");
        List<ToggleButton> segments = segmentsOf(panel);

        runOnFxThread(() -> {
            panel.setAllValues(Map.of(7, 0.75, 11, 1.0, 13, 3.0));
            return null;
        });

        assertThat(knob.getValue()).isEqualTo(0.75);
        assertThat(toggle.isSelected()).isTrue();
        assertThat(selectedSegmentText(segments)).isEqualTo("3");
        assertThat(sink.changes).isEmpty();
    }

    @Test
    void valueReadoutUsesTheLegacyFormattingRules() {
        // Legacy PluginParameterEditorPanel.formatValue: |v| >= 1000 → one
        // decimal, otherwise two.
        RecordingSink sink = new RecordingSink();
        PluginParameter cutoff = new PluginParameter(21, "Cutoff", 0.0, 20000.0, 1000.0);
        ParameterGridPanel panel = runOnFxThread(
                () -> new ParameterGridPanel(List.of(cutoff), sink));
        Label readout = (Label) panel.lookup(".parameter-value");

        assertThat(readout.getText()).isEqualTo("1000.0");

        runOnFxThread(() -> {
            panel.updateValue(21, 999.0);
            return null;
        });

        assertThat(readout.getText()).isEqualTo("999.00");
        assertThat(sink.changes).isEmpty();
    }

    // ── Empty grid ────────────────────────────────────────────────────────

    @Test
    void emptyParameterListConstructsAndRendersZeroCells() {
        RecordingSink sink = new RecordingSink();
        ParameterGridPanel panel = runOnFxThread(
                () -> new ParameterGridPanel(List.of(), sink));

        assertThat(panel.getStyleClass()).contains(ParameterGridPanel.STYLE_CLASS);
        assertThat(panel.getChildrenUnmodifiable()).isEmpty();
        assertThat(panel.lookupAll(".parameter-grid-cell")).isEmpty();
        assertThat(sink.changes).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static ParameterGridPanel newPanel(RecordingSink sink) {
        return runOnFxThread(() -> new ParameterGridPanel(
                List.of(MIX, BYPASS_TOGGLE, MODE), sink));
    }

    private static VBox cellAt(ParameterGridPanel panel, int index) {
        return (VBox) panel.getChildrenUnmodifiable().get(index);
    }

    private static List<ToggleButton> segmentsOf(ParameterGridPanel panel) {
        HBox selector = (HBox) panel.lookup(".segmented-selector");
        assertThat(selector).isNotNull();
        List<ToggleButton> segments = new ArrayList<>();
        for (Node node : selector.getChildrenUnmodifiable()) {
            segments.add((ToggleButton) node);
        }
        return segments;
    }

    private static String selectedSegmentText(List<ToggleButton> segments) {
        return segments.stream()
                .filter(ToggleButton::isSelected)
                .map(ToggleButton::getText)
                .findFirst()
                .orElse(null);
    }
}
