package com.benesquivelmusic.daw.app.ui.theme;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.css.CssParser;
import javafx.css.Declaration;
import javafx.css.ParsedValue;
import javafx.css.Rule;
import javafx.css.Selector;
import javafx.css.Size;
import javafx.css.SizeUnits;
import javafx.css.Stylesheet;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 335 (studio ruling R6; amendment A14) — the generated UI-scale sheet:
 * which declarations it re-states and how, that it never reverses the cascade,
 * and that it parses cleanly for every real source sheet at every step.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ScaledStylesheetTest {

    private static final double ROOT_FONT = 12.0;

    @Test
    void theRootRuleSetsTheScaledDefaultFontSize() {
        String css = ScaledStylesheet.render(List.of(), UiScale.P150, ROOT_FONT);

        assertThat(css).startsWith(".root { -fx-font-size: 18px; }");
    }

    @Test
    void everyScaledCategoryIsMultipliedUnderItsOwnSelector() {
        String source = """
                /* a comment with { braces } and -fx-padding: 99; */
                .a, .b:hover > .c {
                    -fx-font-size: 11px;
                    -fx-padding: 4 8;
                    -fx-label-padding: 2px 3px 2px 3px;
                    -fx-spacing: 6;
                    -fx-hgap: 4; -fx-vgap: 5;
                    -fx-graphic-text-gap: 3;
                    -fx-cell-size: 24;
                    -fx-pref-width: 40; -fx-min-height: 10; -fx-max-width: 100.5;
                    -fx-background-radius: 4 4 0 0, 3;
                    -fx-border-radius: 6;
                    -fx-text-fill: red;
                }
                .unitless { -fx-font-size: 10 }
                """;

        String css = ScaledStylesheet.render(List.of(source), UiScale.P200, ROOT_FONT);

        assertThat(css).contains(".a, .b:hover > .c { -fx-font-size: 22px; -fx-padding: 8 16;"
                + " -fx-label-padding: 4px 6px 4px 6px; -fx-spacing: 12; -fx-hgap: 8; -fx-vgap: 10;"
                + " -fx-graphic-text-gap: 6; -fx-cell-size: 48; -fx-pref-width: 80; -fx-min-height: 20;"
                + " -fx-max-width: 201; -fx-background-radius: 8 8 0 0, 6; -fx-border-radius: 12; }");
        assertThat(css).contains(".unitless { -fx-font-size: 20; }");
        assertThat(css).as("unscaled properties and comments are not re-stated")
                .doesNotContain("-fx-text-fill").doesNotContain("99");
    }

    @Test
    void strokesInsetsEffectsAndTransformsAreNeverScaled() {
        String source = """
                .x {
                    -fx-border-width: 1;
                    -fx-background-insets: 0, 1;
                    -fx-border-insets: 2;
                    -fx-effect: dropshadow(gaussian, black, 4, 0, 0, 1);
                    -fx-translate-x: 4;
                    -fx-shape: "M0 0 L4 4";
                }
                """;

        String css = ScaledStylesheet.render(List.of(source), UiScale.P200, ROOT_FONT);

        assertThat(css).isEqualTo(".root { -fx-font-size: 24px; }\n");
    }

    @Test
    void sentinelsKeywordsRelativeUnitsAndLookupsAreCopiedVerbatim() {
        String source = """
                .s {
                    -fx-pref-width: -1;
                    -fx-max-height: -1.0;
                    -fx-padding: 0.5em 1em;
                    -fx-font-size: 120%;
                    -fx-min-width: -my-width;
                    -fx-padding: 4 !important;
                }
                """;

        String css = ScaledStylesheet.render(List.of(source), UiScale.P150, ROOT_FONT);

        assertThat(css).contains(".s { -fx-pref-width: -1; -fx-max-height: -1.0; -fx-padding: 0.5em 1em;"
                + " -fx-font-size: 120%; -fx-min-width: -my-width; -fx-padding: 6 !important; }");
    }

    /**
     * Amendment A14 — a later declaration that is not scalable (a sentinel, a
     * relative unit) must keep beating an earlier px declaration of the same
     * property after the scale sheet is appended. Emitting only the scalable
     * declarations would let the earlier px copy win.
     */
    @Test
    void aLaterUnscalableDeclarationStillWinsOverAnEarlierPxOne() throws Exception {
        String source = """
                .fixture-box { -fx-pref-width: 40; }
                .fixture-box { -fx-pref-width: -1; }
                .fixture-text { -fx-font-size: 10px; }
                .fixture-text { -fx-font-size: 1.5em; }
                """;
        String scaleSheet = ScaledStylesheet.dataUri(
                ScaledStylesheet.render(List.of(source), UiScale.P200, ROOT_FONT));

        double[] resolved = runOnFxThread(() -> {
            Region box = new Region();
            box.getStyleClass().add("fixture-box");
            Label text = new Label("x");
            text.getStyleClass().add("fixture-text");
            StackPane root = new StackPane(box, text);
            Scene scene = new Scene(root, 200, 100);
            scene.getStylesheets().setAll(ScaledStylesheet.dataUri(source), scaleSheet);
            root.applyCss();
            return new double[] {box.getPrefWidth(), text.getFont().getSize()};
        });

        assertThat(resolved[0]).as("the later USE_COMPUTED_SIZE sentinel still wins").isEqualTo(-1.0);
        assertThat(resolved[1]).as("the later 1.5em still wins (1.5 × the 24 px scaled root)")
                .isCloseTo(36.0, within(0.01));
    }

    @Test
    void theRenderOfEveryRealSourceSheetParsesCleanlyAtEveryStep() throws Exception {
        List<String> sources = ThemeManager.scaleSourceCss();
        for (UiScale step : UiScale.values()) {
            String css = ScaledStylesheet.render(sources, step, ROOT_FONT);
            List<String> errors = runOnFxThread(() -> {
                CssParser.errorsProperty().clear();
                new CssParser().parse(css);
                List<String> found = CssParser.errorsProperty().stream()
                        .map(Object::toString).toList();
                CssParser.errorsProperty().clear();
                return found;
            });
            assertThat(errors).as("parse errors in the %s scale sheet", step).isEmpty();
        }
    }

    /**
     * The design's oracle: every px {@code -fx-font-size} of every source
     * sheet appears in the render under the same selectors, times the factor
     * (read through the JavaFX CSS parser, not this class's own parsing).
     */
    @Test
    void everyPxFontSizeOfEverySourceSheetAppearsTimesTheFactor() throws Exception {
        List<String> sources = ThemeManager.scaleSourceCss();
        UiScale step = UiScale.P175;
        Stylesheet rendered = runOnFxThread(() -> new CssParser().parse(
                ScaledStylesheet.render(sources, step, ROOT_FONT)));
        Map<String, List<Double>> renderedSizes = pxFontSizesBySelectors(rendered);

        int checked = 0;
        for (String source : sources) {
            Stylesheet sheet = runOnFxThread(() -> new CssParser().parse(source));
            for (Map.Entry<String, List<Double>> entry : pxFontSizesBySelectors(sheet).entrySet()) {
                for (double px : entry.getValue()) {
                    assertThat(renderedSizes.getOrDefault(entry.getKey(), List.of()))
                            .as("%s { -fx-font-size: %spx } at %s", entry.getKey(), px, step)
                            .anySatisfy(scaled -> assertThat(scaled).isCloseTo(px * step.factor(), within(1e-3)));
                    checked++;
                }
            }
        }
        assertThat(checked).as("the oracle saw the sources' font sizes").isGreaterThan(50);
    }

    @Test
    void theDataUriCarriesTheTextAsBase64Css() {
        assertThat(ScaledStylesheet.dataUri(".a { -fx-padding: 2; }"))
                .isEqualTo("data:text/css;base64,LmEgeyAtZngtcGFkZGluZzogMjsgfQ==");
    }

    /**
     * Review 2b-java N1 — a statement at-rule ({@code @import "x.css";}) has
     * no block: it is skipped up to its semicolon and never swallows the rule
     * after it; a block at-rule is skipped with its block.
     */
    @Test
    void anAtRuleIsSkippedWithoutSwallowingTheRuleAfterIt() {
        String source = """
                @import "elsewhere.css";
                .after-import { -fx-padding: 4; }
                @font-face { -fx-font-size: 30px; }
                .after-block { -fx-spacing: 6; }
                """;

        String css = ScaledStylesheet.render(List.of(source), UiScale.P150, ROOT_FONT);

        assertThat(css).contains(".after-import { -fx-padding: 6; }")
                .contains(".after-block { -fx-spacing: 9; }")
                .doesNotContain("@import").doesNotContain("@font-face").doesNotContain("45px");
    }

    /** Px font sizes per rule, keyed by the rule's selectors as the parser prints them. */
    private static Map<String, List<Double>> pxFontSizesBySelectors(Stylesheet sheet) {
        Map<String, List<Double>> sizes = new LinkedHashMap<>();
        for (Rule rule : sheet.getRules()) {
            String selectors = rule.getSelectors().stream().map(Selector::toString)
                    .collect(Collectors.joining(", "));
            for (Declaration declaration : rule.getDeclarations()) {
                if (!"-fx-font-size".equals(declaration.getProperty())) {
                    continue;
                }
                pxSize(declaration).ifPresent(px ->
                        sizes.computeIfAbsent(selectors, _ -> new ArrayList<>()).add(px));
            }
        }
        return sizes;
    }

    @SuppressWarnings("unchecked")
    private static Optional<Double> pxSize(Declaration declaration) {
        Object inner = declaration.getParsedValue().getValue();
        if (inner instanceof ParsedValue<?, ?> value && value.getValue() instanceof Size size
                && size.getUnits() == SizeUnits.PX) {
            return Optional.of(size.getValue());
        }
        return Optional.empty();
    }
}
