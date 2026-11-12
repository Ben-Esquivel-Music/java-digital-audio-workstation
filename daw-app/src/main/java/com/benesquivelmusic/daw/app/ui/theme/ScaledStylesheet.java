package com.benesquivelmusic.daw.app.ui.theme;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the generated UI-scale stylesheet (story 335, studio ruling R6).
 *
 * <p>JavaFX 27 has no {@code rem} unit, and {@code em} font sizes compound
 * through nesting, so the application's px lengths cannot be rewritten as
 * relative units. Instead this class re-states, under the <em>same
 * selectors</em> and in source order, every declaration of a scaled property
 * with each plain number multiplied by the factor. Every value is absolute,
 * so nothing compounds — the {@code rem} reading. A leading
 * {@code .root { -fx-font-size }} rule scales Modena's em metrics and all text
 * that has no explicit size.</p>
 *
 * <p><strong>Cascade (amendment A14).</strong> Every declaration of a scaled
 * property is emitted, not only the scalable ones: a later lookup, keyword,
 * {@code em}/{@code %} value or negative size sentinel is copied verbatim, so
 * it still beats an earlier px declaration it beat in its own sheet. The
 * caller passes the user-agent (component) sheets first and the author sheets
 * after them, in their cascade order, so an author declaration still follows
 * the component declaration it overrides.</p>
 *
 * <p><strong>Scaled:</strong> {@code -fx-font-size}; the box lengths
 * {@code -fx-padding}, {@code -fx-label-padding}, {@code -fx-spacing},
 * {@code -fx-hgap}, {@code -fx-vgap}, {@code -fx-graphic-text-gap},
 * {@code -fx-cell-size} and {@code -fx-(pref|min|max)-(width|height)}; the
 * corner radii {@code -fx-background-radius} and {@code -fx-border-radius}.
 * <strong>Not scaled:</strong> stroke widths, background and border insets
 * (they are tied to stroke widths), effects, transforms and shapes.</p>
 *
 * <p>The application CSS has no {@code @}-rules and no {@code -fx-font}
 * shorthand; an {@code @}-rule (its block, or a statement at-rule up to its
 * semicolon) is skipped rather than copied.</p>
 */
final class ScaledStylesheet {

    /** The properties whose declarations the scale sheet re-states. */
    static final Set<String> SCALED_PROPERTIES = Set.of(
            "-fx-font-size",
            "-fx-padding", "-fx-label-padding", "-fx-spacing",
            "-fx-hgap", "-fx-vgap", "-fx-graphic-text-gap", "-fx-cell-size",
            "-fx-pref-width", "-fx-pref-height",
            "-fx-min-width", "-fx-min-height",
            "-fx-max-width", "-fx-max-height",
            "-fx-background-radius", "-fx-border-radius");

    /** Size properties whose negative values are the computed/pref-size sentinels. */
    private static final Set<String> SIZE_PROPERTIES = Set.of(
            "-fx-pref-width", "-fx-pref-height",
            "-fx-min-width", "-fx-min-height",
            "-fx-max-width", "-fx-max-height");

    private static final Pattern LENGTH = Pattern.compile("(-?(?:\\d+\\.?\\d*|\\.\\d+))(px)?");
    private static final Pattern SEPARATOR = Pattern.compile("[\\s,]+");
    private static final String IMPORTANT = "!important";

    private ScaledStylesheet() {
    }

    /**
     * Renders the scale sheet for {@code scale}.
     *
     * @param sourceCss  the source stylesheets' text, user-agent sheets first,
     *                   then author sheets in cascade order
     * @param scale      the step to render
     * @param rootFontPx the default font size at 100&nbsp;% ({@code Font.getDefault().getSize()})
     * @return the generated CSS text
     */
    static String render(List<String> sourceCss, UiScale scale, double rootFontPx) {
        Objects.requireNonNull(sourceCss, "sourceCss must not be null");
        Objects.requireNonNull(scale, "scale must not be null");
        StringBuilder out = new StringBuilder();
        out.append(".root { -fx-font-size: ")
                .append(format(rootFontPx * scale.factor())).append("px; }\n");
        for (String css : sourceCss) {
            for (Block block : blocks(stripComments(css))) {
                List<String> declarations = new ArrayList<>();
                for (String declaration : declarations(block.body())) {
                    int colon = declaration.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String property = declaration.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                    if (!SCALED_PROPERTIES.contains(property)) {
                        continue;
                    }
                    String value = declaration.substring(colon + 1).strip();
                    declarations.add(property + ": " + scaledValue(property, value, scale.factor()) + ";");
                }
                if (!declarations.isEmpty()) {
                    out.append(block.selectors()).append(" { ")
                            .append(String.join(" ", declarations)).append(" }\n");
                }
            }
        }
        return out.toString();
    }

    /**
     * @param css a stylesheet's text
     * @return a {@code data:} URI carrying it, loadable by the CSS engine
     */
    static String dataUri(String css) {
        return "data:text/css;base64,"
                + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether {@code css} holds at least one declaration of a scaled property
     * (the source-sheet completeness check).
     *
     * @param css a stylesheet's text
     * @return {@code true} if the scale sheet would re-state something from it
     */
    static boolean hasScaledDeclarations(String css) {
        for (Block block : blocks(stripComments(css))) {
            for (String declaration : declarations(block.body())) {
                int colon = declaration.indexOf(':');
                if (colon > 0 && SCALED_PROPERTIES.contains(
                        declaration.substring(0, colon).strip().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Scales {@code value} if every token is a finite number with an optional
     * {@code px}; otherwise (a lookup, keyword, {@code em}, {@code %}, or a
     * negative size sentinel) returns it unchanged.
     */
    static String scaledValue(String property, String value, double factor) {
        String body = value;
        String suffix = "";
        if (body.toLowerCase(Locale.ROOT).endsWith(IMPORTANT)) {
            body = body.substring(0, body.length() - IMPORTANT.length()).strip();
            suffix = " " + IMPORTANT;
        }
        String[] tokens = SEPARATOR.split(body.strip());
        if (tokens.length == 0) {
            return value;
        }
        for (String token : tokens) {
            Matcher matcher = LENGTH.matcher(token);
            if (!matcher.matches()) {
                return value;
            }
            double number = Double.parseDouble(matcher.group(1));
            if (!Double.isFinite(number)) {
                return value;
            }
            if (number < 0 && SIZE_PROPERTIES.contains(property)) {
                return value;
            }
        }
        // Replace each number in place so the separators (spaces, commas) survive.
        Matcher matcher = LENGTH.matcher(body);
        StringBuilder scaled = new StringBuilder();
        while (matcher.find()) {
            double number = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2) == null ? "" : matcher.group(2);
            matcher.appendReplacement(scaled, Matcher.quoteReplacement(format(number * factor) + unit));
        }
        matcher.appendTail(scaled);
        return scaled + suffix;
    }

    /** Plain decimal text with at most four fraction digits and no trailing zeros. */
    private static String format(double number) {
        BigDecimal decimal = BigDecimal.valueOf(Math.round(number * 10_000) / 10_000.0).stripTrailingZeros();
        return decimal.scale() < 0 ? decimal.setScale(0).toPlainString() : decimal.toPlainString();
    }

    private record Block(String selectors, String body) {
    }

    /** Removes {@code /* … *}{@code /} comments, leaving quoted text alone. */
    private static String stripComments(String css) {
        StringBuilder out = new StringBuilder(css.length());
        char quote = 0;
        for (int i = 0; i < css.length(); i++) {
            char c = css.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                out.append(c);
            } else if (c == '/' && i + 1 < css.length() && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                i = end < 0 ? css.length() : end + 1;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Splits comment-free CSS into top-level rule blocks. An {@code @}-rule is
     * skipped: with its block, or — a statement at-rule such as
     * {@code @import "x.css";} — up to its semicolon, so it never swallows the
     * rule after it (review 2b-java N1).
     */
    private static List<Block> blocks(String css) {
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < css.length()) {
            int start = i;
            while (start < css.length() && Character.isWhitespace(css.charAt(start))) {
                start++;
            }
            if (start < css.length() && css.charAt(start) == '@') {
                int semicolon = indexOutsideQuotes(css, ';', start);
                int blockOpen = indexOutsideQuotes(css, '{', start);
                if (semicolon >= 0 && (blockOpen < 0 || semicolon < blockOpen)) {
                    i = semicolon + 1;
                    continue;
                }
            }
            int open = indexOutsideQuotes(css, '{', i);
            if (open < 0) {
                break;
            }
            int close = matchingBrace(css, open);
            String selectors = css.substring(i, open).strip();
            String body = css.substring(open + 1, close < 0 ? css.length() : close);
            if (!selectors.isEmpty() && !selectors.startsWith("@")) {
                blocks.add(new Block(selectors.replaceAll("\\s+", " "), body));
            }
            i = close < 0 ? css.length() : close + 1;
        }
        return blocks;
    }

    /** Splits a block body at top-level semicolons (not inside quotes or parentheses). */
    private static List<String> declarations(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ';' && depth == 0) {
                addIfPresent(out, body.substring(start, i));
                start = i + 1;
            }
        }
        addIfPresent(out, body.substring(start));
        return out;
    }

    private static void addIfPresent(List<String> out, String declaration) {
        String trimmed = declaration.strip();
        if (!trimmed.isEmpty()) {
            out.add(trimmed);
        }
    }

    private static int indexOutsideQuotes(String css, char target, int from) {
        char quote = 0;
        for (int i = from; i < css.length(); i++) {
            char c = css.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == target) {
                return i;
            }
        }
        return -1;
    }

    private static int matchingBrace(String css, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < css.length(); i++) {
            char c = css.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
