package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 262 — De-Rainbow the Transport Bar.
 *
 * <p>UI Design Book §2.1 / §7.2 forbid per-element hues. Every transport
 * button shares one structural style; the active <em>state</em> (Play
 * during playback, Loop while looping, Record while armed) is
 * communicated by a single {@code :active} pseudo-class fill — never by
 * a per-button hue. This test parses {@code styles.css} and asserts:</p>
 *
 * <ul>
 *   <li>There is exactly one {@code -fx-background-color} declaration on
 *       the base {@code .transport-button} selector (no per-button
 *       background-colour overrides on Play/Pause/Stop).</li>
 *   <li>There are no {@code -fx-border-color} declarations on per-button
 *       selectors {@code .play-button}, {@code .pause-button},
 *       {@code .stop-button}. The {@code .record-button} and
 *       {@code .loop-button} selectors only carry the {@code :active}
 *       armed-state override.</li>
 * </ul>
 */
final class TransportStyleTest {

    private static final String CSS_RESOURCE =
            "/com/benesquivelmusic/daw/app/ui/styles.css";

    @Test
    void transportButtonHasExactlyOneBackgroundColorDeclaration() throws Exception {
        String css = loadCss();

        // Collect every declaration that targets a transport selector.
        // We strip block bodies so we count rule blocks, not properties.
        List<RuleBlock> blocks = parseRules(css);
        List<RuleBlock> transportRules = new ArrayList<>();
        for (RuleBlock rb : blocks) {
            if (isTransportRule(rb.selector)) {
                transportRules.add(rb);
            }
        }

        // Story 263 unified the three button systems behind .dawg-button.
        // Exactly one rule sets the base background — the .dawg-button
        // selector itself. Legacy aliases (.transport-button, .toolbar-button,
        // .button) must not re-declare -fx-background-color.
        long baseBgRules = transportRules.stream()
                .filter(rb -> rb.selector.equals(".dawg-button"))
                .filter(rb -> rb.body.contains("-fx-background-color"))
                .count();
        assertThat(baseBgRules)
                .as("Exactly one .dawg-button rule must declare the base "
                        + "-fx-background-color (UI Design Book §2.1, story 263).")
                .isEqualTo(1);

        long legacyAliasBgRules = transportRules.stream()
                .filter(rb -> rb.selector.equals(".transport-button")
                        || rb.selector.equals(".toolbar-button"))
                .filter(rb -> rb.body.contains("-fx-background-color"))
                .count();
        assertThat(legacyAliasBgRules)
                .as("Legacy button aliases (.transport-button, .toolbar-button) "
                        + "must not re-declare -fx-background-color — "
                        + "the unified rule lives on .dawg-button. "
                        + "(.button is an intentional fallback for dialog buttons.)")
                .isZero();

        // No per-button class (.play-button, .pause-button, .stop-button)
        // may declare its own background-color or border-color.
        List<String> offences = new ArrayList<>();
        for (RuleBlock rb : transportRules) {
            if (isForbiddenPerButtonRule(rb.selector)) {
                if (rb.body.contains("-fx-background-color")) {
                    offences.add(rb.selector + " declares -fx-background-color "
                            + "(forbidden per-button hue, §2.1).");
                }
                if (rb.body.contains("-fx-border-color")) {
                    offences.add(rb.selector + " declares -fx-border-color "
                            + "(forbidden per-button hue, §2.1).");
                }
            }
        }
        assertThat(offences)
                .as("No transport-bar per-button class may carry its own "
                        + "background-color or border-color — the rainbow was "
                        + "removed in story 262 (UI Design Book §1.2, §2.1, §7.2):%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    @Test
    void noPerButtonBorderColorVariationOnTransportButtons() throws Exception {
        String css = loadCss();
        List<RuleBlock> blocks = parseRules(css);

        // Build the set of border-color values declared by transport
        // rules. The only allowed border-color tokens are -line-strong
        // (base + hover), -accent (active), and -danger (record active).
        // Critically: there must be no per-button-class rule that swaps
        // the border to a unique hue (no .play-button → -ok border,
        // no .stop-button → -warn border, etc.).
        List<String> offences = new ArrayList<>();
        for (RuleBlock rb : blocks) {
            if (!isTransportRule(rb.selector)) continue;
            if (isForbiddenPerButtonRule(rb.selector)
                    && rb.body.contains("-fx-border-color")) {
                offences.add(rb.selector + " — per-button border-color is forbidden.");
            }
        }
        assertThat(offences)
                .as("Per-button transport border-color variation is forbidden "
                        + "(UI Design Book §2.1, §7.2):%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    @Test
    void pauseButtonStyleMustBeRemoved() throws Exception {
        String css = loadCss();
        // Story 262 — UI Design Book §5.1: the Pause button was dropped.
        // Its style block must no longer exist.
        assertThat(css)
                .as(".pause-button style block must be removed (story 262, §5.1).")
                .doesNotContain(".pause-button");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A parsed CSS rule: selector list + body text between the braces. */
    private record RuleBlock(String selector, String body) {}

    /** Returns true if the selector targets the transport button row. */
    private static boolean isTransportRule(String selector) {
        return selector.contains(".transport-button")
                || selector.contains(".toolbar-button")
                || selector.equals(".button")
                || selector.startsWith(".button:")
                || selector.startsWith(".button.")
                || selector.contains(".dawg-button")
                || selector.equals(".play-button")
                || selector.startsWith(".play-button:")
                || selector.equals(".pause-button")
                || selector.startsWith(".pause-button:")
                || selector.equals(".stop-button")
                || selector.startsWith(".stop-button:")
                || selector.equals(".record-button")
                || selector.startsWith(".record-button:")
                || selector.equals(".loop-button")
                || selector.startsWith(".loop-button:");
    }

    /**
     * Returns true if the selector is a per-button rainbow rule that must
     * not carry its own background-color or border-color. The base
     * {@code .transport-button} rule and the {@code :active} armed-state
     * overrides are allowed.
     */
    private static boolean isForbiddenPerButtonRule(String selector) {
        // The base structural rules and their state pseudo-classes are allowed.
        if (selector.equals(".dawg-button")
                || selector.startsWith(".dawg-button:")
                || selector.startsWith(".dawg-button.size-")
                || selector.startsWith(".dawg-button.danger")
                || selector.equals(".transport-button")
                || selector.startsWith(".transport-button:")
                || selector.equals(".toolbar-button")
                || selector.startsWith(".toolbar-button:")
                || selector.equals(".button")
                || selector.startsWith(".button:")) {
            return false;
        }
        // The :active armed-state overrides are allowed (this is exactly
        // where the single allowed accent/danger fill lives).
        if (selector.contains(":active")) {
            return false;
        }
        return selector.contains(".play-button")
                || selector.contains(".pause-button")
                || selector.contains(".stop-button")
                || selector.contains(".record-button")
                || selector.contains(".loop-button");
    }

    /** Splits the stylesheet into top-level rule blocks. */
    private static List<RuleBlock> parseRules(String css) {
        // Strip /* ... */ comments first so they don't confuse the brace
        // matcher.
        String stripped = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL)
                .matcher(css).replaceAll("");
        List<RuleBlock> rules = new ArrayList<>();
        Pattern p = Pattern.compile("([^{}]+)\\{([^{}]*)\\}", Pattern.DOTALL);
        Matcher m = p.matcher(stripped);
        while (m.find()) {
            String selector = m.group(1).trim();
            String body = m.group(2).trim();
            // Selector lists are comma-separated; explode them so each
            // selector is tested independently.
            for (String sel : selector.split(",")) {
                rules.add(new RuleBlock(sel.trim(), body));
            }
        }
        return rules;
    }

    private static String loadCss() throws Exception {
        try (InputStream in = TransportStyleTest.class.getResourceAsStream(CSS_RESOURCE)) {
            assertThat(in)
                    .as("styles.css must be on the test classpath at %s", CSS_RESOURCE)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
