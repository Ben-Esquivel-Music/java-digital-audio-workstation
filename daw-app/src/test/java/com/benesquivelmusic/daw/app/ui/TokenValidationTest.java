package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 260 — guards the semantic-token contract in {@code styles.css}.
 *
 * <p>Every literal hex value in the stylesheet must live inside the single
 * fenced Palette A block:
 * <pre>
 *   /* ── Palette A: Onyx Refined ─ TOKEN VALUES ─ *&#47;
 *   ...
 *   /* ── End Palette A ── *&#47;
 * </pre>
 * Any {@code #rrggbb} (or shorthand) literal outside that block is a
 * regression — it means a future change reintroduced palette values into
 * structural CSS, which breaks the theming roadmap (UI Design Book §6,
 * Phase 3).
 */
final class TokenValidationTest {

    private static final String STYLES_CSS_RESOURCE =
            "/com/benesquivelmusic/daw/app/ui/styles.css";

    private static final String PALETTE_START =
            "/* ── Palette A: Onyx Refined ─ TOKEN VALUES ─ */";
    private static final String PALETTE_END = "/* ── End Palette A ── */";

    private static final Pattern HEX_LITERAL = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");

    @Test
    void stylesCssHasNoHexLiteralsOutsidePaletteABlock() throws IOException {
        String css = loadStylesheet();
        List<String> lines = css.lines().toList();

        int startLine = indexOfLineContaining(lines, PALETTE_START);
        int endLine = indexOfLineContaining(lines, PALETTE_END);

        assertThat(startLine)
                .as("Palette A start sentinel '%s' must be present in styles.css", PALETTE_START)
                .isGreaterThanOrEqualTo(0);
        assertThat(endLine)
                .as("Palette A end sentinel '%s' must be present in styles.css", PALETTE_END)
                .isGreaterThan(startLine);

        List<String> offences = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (i >= startLine && i <= endLine) {
                continue;
            }
            String line = lines.get(i);
            Matcher m = HEX_LITERAL.matcher(line);
            while (m.find()) {
                offences.add(String.format(
                        "styles.css:%d  literal hex '%s' outside Palette A block — replace with a token (see UI_DESIGN_BOOK.md §3.1)%n        %s",
                        i + 1, m.group(), line.strip()));
            }
        }

        assertThat(offences)
                .as("styles.css must reference tokens, never literal hex, outside the Palette A block.%n"
                        + "Each offence below identifies the line and the literal that needs to become a token reference:%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    @Test
    void paletteABlockDeclaresEveryRoleToken() throws IOException {
        // The Phase-1 contract: every role listed in UI_DESIGN_BOOK.md §3.1
        // is defined inside the Palette A block. Later stories rely on these
        // names verbatim; a typo here propagates silently.
        String css = loadStylesheet();
        String paletteBlock = extractPaletteBlock(css);

        List<String> required = List.of(
                "-surface-bg", "-surface-1", "-surface-2", "-surface-3", "-surface-overlay",
                "-line-soft", "-line-strong", "-focus-ring",
                "-text-hi", "-text", "-text-mute", "-text-on-accent",
                "-accent", "-accent-soft",
                "-ok", "-warn", "-danger",
                "-meter-low", "-meter-mid", "-meter-hi", "-meter-clip",
                "-font-sans", "-font-mono");

        for (String token : required) {
            assertThat(paletteBlock)
                    .as("Palette A block must declare role token '%s' (UI_DESIGN_BOOK.md §3.1)", token)
                    .contains(token + ":");
        }
    }

    /**
     * Phase 1 of UI Design Book §6 — the 4&nbsp;px grid contract.
     *
     * <p>Every {@code -fx-padding}, {@code -fx-spacing}, {@code -fx-background-radius}
     * and {@code -fx-border-radius} numeric value in {@code styles.css} must
     * be a multiple of 4. The only inline exceptions are:
     * <ul>
     *   <li>{@code 2} — the {@code -spacing-xxs} half-step, used for icon /
     *       text gaps and the check-box mark inset;</li>
     *   <li>{@code 6} — the {@code -radius-2} curated mixed-radius value
     *       used for cards and popovers (UI Design Book §3.3).</li>
     * </ul>
     * The forbidden corner radii {@code 5, 7, 9, 10, 11} are subsumed by
     * the same rule: they are neither multiples of 4 nor in the exception
     * set.
     */
    @Test
    void stylesCssNumericValuesSnapToFourPxGrid() throws IOException {
        String css = loadStylesheet();
        List<String> lines = css.lines().toList();

        // Match a declaration on a single line so we can report line numbers.
        Pattern decl = Pattern.compile(
                "-fx-(padding|spacing|background-radius|border-radius)\\s*:\\s*([^;]+);");
        Pattern numberToken = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\b");

        // Allowed exceptions (UI Design Book §3.3):
        //   2  → -spacing-xxs (icon/text gaps, check-box mark inset)
        //   6  → -radius-2     (cards / popovers — curated mixed radius)
        final int spacingXxs = 2;
        final int radius2 = 6;

        List<String> offences = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = decl.matcher(line);
            while (m.find()) {
                String value = m.group(2).trim();
                Matcher num = numberToken.matcher(value);
                while (num.find()) {
                    double parsed = Double.parseDouble(num.group(1));
                    int n = (int) parsed;
                    if (parsed != n) {
                        offences.add(String.format(
                                "styles.css:%d  non-integer numeric value '%s' in '%s' — only integer multiples of 4 are allowed (UI_DESIGN_BOOK.md §2.3)",
                                i + 1, num.group(1), line.strip()));
                        continue;
                    }
                    boolean ok = (n % 4 == 0) || n == spacingXxs || n == radius2;
                    if (!ok) {
                        offences.add(String.format(
                                "styles.css:%d  value '%d' is not a multiple of 4 (allowed inline exceptions: 2 = -spacing-xxs, 6 = -radius-2) — see UI_DESIGN_BOOK.md §3.3%n        %s",
                                i + 1, n, line.strip()));
                    }
                }
            }
        }

        assertThat(offences)
                .as("Every -fx-padding / -fx-spacing / -fx-background-radius / -fx-border-radius "
                        + "value in styles.css must snap to the 4 px grid.%n"
                        + "Each offence below identifies the line and the literal that needs to "
                        + "become a token-aligned multiple of 4:%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    private static String loadStylesheet() throws IOException {
        try (InputStream in = TokenValidationTest.class.getResourceAsStream(STYLES_CSS_RESOURCE)) {
            assertThat(in)
                    .as("styles.css must be on the test classpath at %s", STYLES_CSS_RESOURCE)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String extractPaletteBlock(String css) {
        int start = css.indexOf(PALETTE_START);
        int end = css.indexOf(PALETTE_END, start);
        assertThat(start).as("Palette A start sentinel must be present").isGreaterThanOrEqualTo(0);
        assertThat(end).as("Palette A end sentinel must be present").isGreaterThan(start);
        return css.substring(start, end + PALETTE_END.length());
    }

    private static int indexOfLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
