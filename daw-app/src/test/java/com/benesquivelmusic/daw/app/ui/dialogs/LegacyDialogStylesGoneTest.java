package com.benesquivelmusic.daw.app.ui.dialogs;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Story 276 — the three legacy chrome regressions must stay gone in
 * {@code styles.css} (UI Design Book §5.9):
 *
 * <ol>
 *   <li>no {@code linear-gradient} inside any rule whose selector
 *       touches {@code .dialog-} (the gradient header);</li>
 *   <li>no {@code -ok} token (or literal green hex) on any
 *       {@code .dialog-*} / {@code .button.default} / {@code :default}
 *       button rule (the green primary button).</li>
 * </ol>
 *
 * <p>Plain JUnit — no toolkit. The stylesheet is loaded as a resource
 * and scanned with a tiny brace-aware selector&rarr;body splitter.</p>
 */
class LegacyDialogStylesGoneTest {

    /** A {selector list, declaration body} pair from the stylesheet. */
    private record Rule(String selector, String body) { }

    @Test
    void noGradientScopedToAnyDialogSelector() {
        List<Rule> rules = parseRules(loadCss());
        List<String> offenders = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.selector().contains(".dialog-")
                    && rule.body().toLowerCase(Locale.ROOT).contains("linear-gradient")) {
                offenders.add(rule.selector().strip());
            }
        }
        assertThat(offenders)
                .as("§5.9 — no .dialog-* rule may paint a linear-gradient "
                        + "(flat -surface-1 header). Offending selectors: " + offenders)
                .isEmpty();
    }

    @Test
    void noGreenTokenOnDefaultOrDialogButtonRules() {
        // Literal green hex guard: Palette-A -ok is #5BD2A0. Also reject
        // any 3/6-digit hex whose green channel dominates strongly (a
        // belt-and-braces check against a re-introduced literal green).
        Pattern okToken = Pattern.compile("(^|[^\\w-])-ok([^\\w-]|$)");
        List<Rule> rules = parseRules(loadCss());
        List<String> offenders = new ArrayList<>();
        for (Rule rule : rules) {
            String sel = rule.selector();
            boolean isDialogOrDefaultButton =
                    sel.contains(".dialog-")
                            || sel.contains(":default")
                            || sel.contains(".button.default")
                            || sel.contains(".dawg-button.default");
            if (!isDialogOrDefaultButton) {
                continue;
            }
            String body = rule.body();
            if (okToken.matcher(body).find()) {
                offenders.add(sel.strip() + "  (uses -ok)");
            }
            if (containsGreenHex(body)) {
                offenders.add(sel.strip() + "  (literal green hex)");
            }
        }
        if (!offenders.isEmpty()) {
            fail("§5.9 / §3.1 — primary/dialog buttons must be -accent, "
                    + "never -ok / green. Offenders:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    @Test
    void primaryButtonResolvesToAccentToken() {
        // Positive guard: the migrated .button:default rule must paint
        // -accent. Protects against a future edit that drops the green
        // but leaves the button neutral (non-primary).
        List<Rule> rules = parseRules(loadCss());
        boolean found = rules.stream()
                .filter(r -> r.selector().contains(":default")
                        && !r.selector().contains(":hover"))
                .anyMatch(r -> r.body().contains("-accent"));
        assertThat(found)
                .as("§5.9 / §3.1 — the :default (primary) button rule "
                        + "must fill with -accent")
                .isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Detects an {@code #rrggbb} / {@code #rgb} whose green strongly dominates. */
    private static boolean containsGreenHex(String body) {
        var m = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})").matcher(body);
        while (m.find()) {
            String hex = m.group(1);
            int r;
            int g;
            int b;
            if (hex.length() == 3) {
                r = Integer.parseInt(hex.substring(0, 1).repeat(2), 16);
                g = Integer.parseInt(hex.substring(1, 2).repeat(2), 16);
                b = Integer.parseInt(hex.substring(2, 3).repeat(2), 16);
            } else {
                r = Integer.parseInt(hex.substring(0, 2), 16);
                g = Integer.parseInt(hex.substring(2, 4), 16);
                b = Integer.parseInt(hex.substring(4, 6), 16);
            }
            if (g > r + 40 && g > b + 20) {
                return true;
            }
        }
        return false;
    }

    /**
     * Minimal brace-aware splitter: comments stripped, then each
     * top-level {@code selector { body }} block is captured. CSS in this
     * stylesheet has no nested braces inside rule bodies, so a depth
     * counter is sufficient and robust.
     */
    private static List<Rule> parseRules(String css) {
        String noComments = css.replaceAll("(?s)/\\*.*?\\*/", "");
        List<Rule> rules = new ArrayList<>();
        StringBuilder selector = new StringBuilder();
        StringBuilder body = new StringBuilder();
        boolean inBody = false;
        int depth = 0;
        for (int i = 0; i < noComments.length(); i++) {
            char c = noComments.charAt(i);
            if (c == '{') {
                depth++;
                if (depth == 1) {
                    inBody = true;
                    continue;
                }
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    rules.add(new Rule(selector.toString(), body.toString()));
                    selector.setLength(0);
                    body.setLength(0);
                    inBody = false;
                    continue;
                }
            }
            if (inBody) {
                body.append(c);
            } else {
                selector.append(c);
            }
        }
        return rules;
    }

    private static String loadCss() {
        String url = ThemeManager.getDefault().baseStylesheetUrl();
        try (InputStream in = URI.create(url).toURL().openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Fallback to the classloader resource if the external-form
            // URL cannot be opened directly.
            URL res = LegacyDialogStylesGoneTest.class.getResource(
                    "/com/benesquivelmusic/daw/app/ui/styles.css");
            if (res == null) {
                throw new IllegalStateException("styles.css not found on classpath", e);
            }
            try (InputStream in = res.openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e2) {
                throw new IllegalStateException("failed to read styles.css", e2);
            }
        }
    }
}
