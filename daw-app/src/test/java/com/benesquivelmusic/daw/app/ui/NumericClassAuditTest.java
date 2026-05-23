package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 266 — guards the rule from UI Design Book §3.2 that every number
 * the user reads from the UI is drawn in {@code -font-mono} via one of
 * the five numeric typography classes:
 * <ul>
 *   <li>{@code .numeric-display}  — 14 px hero (transport time, master meter peak)
 *   <li>{@code .numeric-display-stage} — 48 px Performance Stage clock
 *                                   (story 280 — the hero family at stage size)
 *   <li>{@code .numeric-value}    — 12 px standard (fader dB, BPM, dialog readouts)
 *   <li>{@code .numeric-caption}  — 11 px tight inline (channel strips, beat labels)
 *   <li>{@code .numeric-mono}     — family + weight only (tight contexts whose
 *                                   container prescribes a non-standard size)
 * </ul>
 *
 * <p>Two passes guard this:
 * <ol>
 *   <li><b>FXML pass</b> — walks {@code main-view.fxml}: every {@code <Label>}
 *       whose {@code text} starts with a digit must carry one of the
 *       numeric classes in its {@code styleClass} attribute.
 *   <li><b>Java pass</b> — walks every {@code .java} file under
 *       {@code daw-app/src/main}: every {@code new Label(...)} that
 *       displays numerics — either a literal starting with a digit, or a
 *       {@code String.format(...)} containing a numeric conversion
 *       specifier ({@code %d}, {@code %f}, {@code %e}, {@code %g},
 *       {@code %o}, {@code %x}) — must be followed by a
 *       {@code getStyleClass().add(...)} (or {@code .addAll(...)}) that
 *       includes one of the five numeric classes.
 * </ol>
 *
 * <p>Both passes are structural / static checks; they parse the FXML XML
 * via {@link DocumentBuilderFactory} (with FEATURE_SECURE_PROCESSING and
 * DOCTYPE disabled) and the Java source via line-oriented regex, so the
 * test does not require the JavaFX toolkit.
 */
final class NumericClassAuditTest {

    private static final String FXML_RESOURCE =
            "/com/benesquivelmusic/daw/app/ui/main-view.fxml";

    private static final List<String> NUMERIC_CLASSES = List.of(
            "numeric-display", "numeric-display-stage",
            "numeric-value", "numeric-caption", "numeric-mono");

    // ── FXML pass ───────────────────────────────────────────────────────

    @Test
    void everyNumericLabelInMainViewFxmlCarriesANumericClass() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document doc;
        try (InputStream in = getClass().getResourceAsStream(FXML_RESOURCE)) {
            assertThat(in)
                    .as("main-view.fxml must be present on the classpath at %s", FXML_RESOURCE)
                    .isNotNull();
            doc = factory.newDocumentBuilder().parse(in);
        }

        NodeList labels = doc.getElementsByTagName("Label");
        List<String> offences = new ArrayList<>();
        for (int i = 0; i < labels.getLength(); i++) {
            Element label = (Element) labels.item(i);
            String text = label.getAttribute("text");
            if (text == null || text.isBlank()) {
                // Empty-text labels are typically toggled by controllers
                // (REC indicator, ripple banner). They are not in scope —
                // when the controller sets their text it should also apply
                // a numeric class if appropriate. That code-side check
                // belongs to the Java pass below.
                continue;
            }
            if (!startsWithNumeric(text)) {
                continue;
            }
            String classes = label.getAttribute("styleClass");
            boolean ok = false;
            for (String numericClass : NUMERIC_CLASSES) {
                if (containsClass(classes, numericClass)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                String fxId = label.getAttribute("fx:id");
                offences.add(String.format(
                        "<Label fx:id=\"%s\" text=\"%s\" styleClass=\"%s\"/>",
                        fxId.isEmpty() ? "(no fx:id)" : fxId, text, classes));
            }
        }

        assertThat(offences)
                .as("Every <Label> whose text starts with a digit in main-view.fxml must "
                        + "carry one of %s in its styleClass (story 266 / UI Design Book §3.2). "
                        + "Offending labels:%n%s",
                        NUMERIC_CLASSES, String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    /**
     * Returns whether the comma-separated styleClass attribute contains the
     * given class as an entry. FXML supports both space- and comma-
     * separated tokens (e.g. {@code "time-display, numeric-display"} or
     * {@code "time-display numeric-display"}); we tokenise on both.
     */
    private static boolean containsClass(String styleClassAttr, String className) {
        if (styleClassAttr == null || styleClassAttr.isBlank()) return false;
        for (String token : styleClassAttr.split("[,\\s]+")) {
            if (token.equals(className)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} when the text starts with an optional sign
     * ({@code +} or {@code -}) followed by a digit — matching labels such
     * as {@code -12.4 dB} or {@code +3.0 dB} in addition to plain
     * digit-leading strings like {@code 120.0 BPM}.
     */
    private static boolean startsWithNumeric(String text) {
        if (text == null || text.isEmpty()) return false;
        char first = text.charAt(0);
        if (Character.isDigit(first)) return true;
        if ((first == '-' || first == '+') && text.length() > 1) {
            return Character.isDigit(text.charAt(1));
        }
        return false;
    }

    // ── Java pass ───────────────────────────────────────────────────────

    /**
     * Captures the left-hand-side variable name from a
     * {@code new Label(...)} assignment. Examples it matches:
     * <pre>
     *   Label foo = new Label(...)
     *   this.foo = new Label(...)
     *   foo = new Label(...)
     * </pre>
     * Anonymous constructions like {@code grid.add(new Label("100%"), …)}
     * are intentionally skipped — they cannot carry a class without
     * structural change, and the audit's purpose is to catch named-Label
     * regressions, not force refactoring of helper-builder code.
     */
    private static final Pattern LABEL_CONSTRUCTION = Pattern.compile(
            "(?:Label\\s+|this\\.)?(\\w+)\\s*=\\s*new\\s+Label\\s*\\(\\s*(.*?)\\s*\\)\\s*;");

    /**
     * A numeric conversion specifier in a {@link String#format} format
     * string. Matches {@code %d}, {@code %2d}, {@code %.3f}, {@code %+5.2g}
     * — but NOT {@code %s}, {@code %c}, {@code %n}, {@code %b}, which do
     * not produce numbers.
     */
    private static final Pattern NUMERIC_FORMAT_SPECIFIER = Pattern.compile(
            "%[+\\-#0,\\s(]?\\d*(?:\\.\\d+)?[dfeEgGoxX]");

    /**
     * Captures the LHS variable of a {@code String.format(...)} construction
     * inside a {@code new Label(...)}; group 1 is the variable, group 2 is
     * the format-string literal.
     */
    private static final Pattern LABEL_FORMAT_CONSTRUCTION = Pattern.compile(
            "(?:Label\\s+|this\\.)?(\\w+)\\s*=\\s*new\\s+Label\\s*\\(\\s*"
                    + "String\\.format\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * Captures the LHS variable of a literal-string {@code new Label("...")}
     * construction; group 1 is the variable, group 2 is the literal text.
     */
    private static final Pattern LABEL_LITERAL_CONSTRUCTION = Pattern.compile(
            "(?:Label\\s+|this\\.)?(\\w+)\\s*=\\s*new\\s+Label\\s*\\(\\s*"
                    + "\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");

    @Test
    void everyJavaConstructedNumericLabelGetsANumericClass() throws IOException {
        Path moduleRoot = locateDawAppModule();
        Path srcRoot = moduleRoot.resolve("src/main/java");
        assertThat(Files.isDirectory(srcRoot))
                .as("Java sources must live under %s", srcRoot)
                .isTrue();

        List<String> offences = new ArrayList<>();
        Files.walkFileTree(srcRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().endsWith(".java")) {
                    scanJavaFile(file, offences);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(offences)
                .as("Every numeric Label constructed in Java source (a `new Label(\"<digit>…\")` "
                        + "literal, or a `new Label(String.format(\"…%%d/%%f/…\"))`) must add one "
                        + "of %s via getStyleClass() (story 266 / UI Design Book §3.2). "
                        + "Offending sites:%n%s",
                        NUMERIC_CLASSES, String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    /**
     * Scans a single {@code .java} file for numeric Label constructions
     * that lack a numeric class. The check is purely line-oriented — we
     * read the whole file as a single string, find numeric Label
     * constructions, capture the LHS variable name, then verify the same
     * file contains a {@code <var>.getStyleClass().add(...)} or
     * {@code addAll(...)} call that mentions one of the numeric classes.
     */
    private static void scanJavaFile(Path file, List<String> offences) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);

        Set<String> numericVars = new HashSet<>();

        // Numeric-format construction: new Label(String.format("…%d/%f/…", …))
        Matcher fmt = LABEL_FORMAT_CONSTRUCTION.matcher(source);
        while (fmt.find()) {
            String varName = fmt.group(1);
            String formatString = fmt.group(2);
            if (NUMERIC_FORMAT_SPECIFIER.matcher(formatString).find()) {
                numericVars.add(varName);
            }
        }

        // Literal construction: new Label("<digit>…")
        Matcher lit = LABEL_LITERAL_CONSTRUCTION.matcher(source);
        while (lit.find()) {
            String varName = lit.group(1);
            String literal = lit.group(2);
            if (!literal.isEmpty() && startsWithNumeric(literal)) {
                numericVars.add(varName);
            }
        }

        if (numericVars.isEmpty()) return;

        for (String varName : numericVars) {
            if (!hasNumericClassAddition(source, varName)) {
                offences.add(file + " — `" + varName
                        + "` constructs a numeric Label but never calls "
                        + "getStyleClass().add(...) with one of "
                        + NUMERIC_CLASSES);
            }
        }
    }

    /**
     * Returns whether the source file contains an explicit
     * {@code <varName>.getStyleClass().add("numeric-…")} or
     * {@code <varName>.getStyleClass().addAll(..., "numeric-…", ...)}
     * invocation. The regex tolerates whitespace and additional class
     * arguments between the parentheses.
     */
    private static boolean hasNumericClassAddition(String source, String varName) {
        for (String numericClass : NUMERIC_CLASSES) {
            // Match: <var>.getStyleClass().add("<numericClass>")
            // and:   <var>.getStyleClass().addAll(..., "<numericClass>", ...)
            // (?:this\.)? optional so `this.foo.getStyleClass()...` still matches.
            Pattern p = Pattern.compile(
                    "(?:this\\.)?"
                            + Pattern.quote(varName)
                            + "\\.getStyleClass\\(\\)\\.(?:add|addAll)\\s*\\([^)]*\""
                            + Pattern.quote(numericClass)
                            + "\"");
            if (p.matcher(source).find()) return true;
        }
        return false;
    }

    /**
     * Locate the {@code daw-app} module root. Surefire normally sets the
     * working directory to the module itself; as a fallback we also check
     * for a {@code daw-app} child directory (covers invocations from the
     * repo root such as {@code mvn -pl daw-app test}).
     */
    private static Path locateDawAppModule() {
        Path cwd = Paths.get("").toAbsolutePath();

        if (isDawAppModule(cwd)) return cwd;

        Path child = cwd.resolve("daw-app");
        if (isDawAppModule(child)) return child;

        Path candidate = cwd.getParent();
        for (int i = 0; i < 5 && candidate != null; i++) {
            if (isDawAppModule(candidate)) return candidate;
            Path nested = candidate.resolve("daw-app");
            if (isDawAppModule(nested)) return nested;
            candidate = candidate.getParent();
        }

        return cwd;
    }

    private static boolean isDawAppModule(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(dir.resolve("src/main/java/com/benesquivelmusic/daw/app"));
    }
}
