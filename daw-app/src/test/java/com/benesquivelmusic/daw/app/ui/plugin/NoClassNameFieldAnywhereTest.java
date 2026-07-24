package com.benesquivelmusic.daw.app.ui.plugin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 304 — guards the §8.5.2 "manifest required to install" contract at the
 * source level: the §1.4 "type a class name" friction is fully gone from the
 * plugin install flow (Plugin View Design Book §1.4, §8.5.2). No plugin-UI
 * source may reference the retired {@code classNameField}, and the two
 * install-flow sources ({@code PluginInstallPanel.java},
 * {@code PluginManagerDialog.java}) may not construct or mention a text field
 * at all — a manifest-less JAR is rejected with copy, never offered a typing
 * affordance.
 *
 * <p>This is a sibling of {@code RunLaterConsolidationTest} and
 * {@code StatusStripNoLabelSetTextTest} — the conformance-sentinel pattern: a
 * green gate that scans the source tree and prevents drift, so a future edit
 * cannot quietly reintroduce a class-name input.</p>
 *
 * <h3>Scope &amp; preprocessing</h3>
 *
 * <p>The scan covers {@code PluginManagerDialog.java} (which lives in
 * {@code …app.ui}) plus every {@code .java} under {@code …app.ui.plugin} in
 * {@code src/main} — this test itself lives under {@code src/test}, so the walk
 * is self-excluding. To stay faithful to a SOURCE-level scan it strips
 * {@code //} and {@code /* *\/} comments before matching — exactly as
 * {@code RunLaterConsolidationTest} does — so a historical mention in Javadoc
 * would not false-match. The {@code TextField} assertion is deliberately scoped
 * to exactly the two install-flow files rather than the whole package:
 * {@code PluginBrowser.java} legitimately owns a {@code TextField} — its §6.7
 * search box — which is browser filtering, not class-name entry.</p>
 */
final class NoClassNameFieldAnywhereTest {

    /**
     * The install-flow sources that must be free of any text-field token: the
     * §6.8 install panel and the manager dialog that hosts the install flow.
     * {@code PluginBrowser.java} is excluded on purpose — its search box is a
     * legitimate {@code TextField} (§6.7), unrelated to class-name entry.
     */
    private static final Set<String> INSTALL_FLOW_FILES =
            Set.of("PluginInstallPanel.java", "PluginManagerDialog.java");

    /** Files the non-empty-scan guard requires the walk to have visited. */
    private static final List<String> REQUIRED_SCANNED_FILES = List.of(
            "PluginManagerDialog.java",
            "PluginInstallPanel.java",
            "PluginBrowser.java",
            "PluginJarScanner.java");

    /**
     * One Java comment or one string / text-block literal. Alternation order
     * matters: a literal is matched before {@code //} and {@code /*} so a
     * delimiter inside a string is not mistaken for a comment, and a quote inside
     * a comment is consumed as part of the comment. Mirrors
     * {@code RunLaterConsolidationTest#COMMENT_OR_STRING}.
     */
    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\""        // text block
            + "|\"(?:\\\\.|[^\"\\\\])*\""   // string literal
            + "|//[^\\n]*"                  // line comment
            + "|/\\*[\\s\\S]*?\\*/");       // block comment

    @Test
    void installFlowSourcesCarryNoClassNameFieldOrTextField() throws IOException {
        Path uiDir = locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        Path pluginDir = uiDir.resolve("plugin");
        Path managerDialog = uiDir.resolve("PluginManagerDialog.java");
        assertThat(Files.isDirectory(pluginDir))
                .as("the plugin UI sources must live under %s", pluginDir)
                .isTrue();
        assertThat(Files.isRegularFile(managerDialog))
                .as("the manager dialog source must exist: %s", managerDialog)
                .isTrue();

        List<Path> inScope = new ArrayList<>();
        inScope.add(managerDialog);
        Files.walkFileTree(pluginDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".java")) {
                    inScope.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<String> classNameFieldOffenders = new ArrayList<>();
        List<String> textFieldOffenders = new ArrayList<>();
        List<String> scanned = new ArrayList<>();

        for (Path file : inScope) {
            String name = file.getFileName().toString();
            scanned.add(name);

            // Strip comments so a historical Javadoc mention does not
            // false-match; string literals are kept — neither token legitimately
            // appears inside a string in any in-scope file.
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (code.contains("classNameField")) {
                classNameFieldOffenders.add(name
                        + "  — references the retired classNameField (story 304 "
                        + "removed the class-name install fallback)");
            }
            if (INSTALL_FLOW_FILES.contains(name) && code.contains("TextField")) {
                textFieldOffenders.add(name
                        + "  — the install flow may not contain a TextField; a "
                        + "manifest-less JAR is rejected with copy, never offered "
                        + "a typing affordance (story 304 §8.5.2)");
            }
        }

        // Guard against a silently-empty scan — a broken path would make this
        // audit vacuously pass.
        assertThat(scanned)
                .as("the scan must visit the install-flow and browser sources")
                .contains(REQUIRED_SCANNED_FILES.toArray(String[]::new))
                .hasSizeGreaterThanOrEqualTo(REQUIRED_SCANNED_FILES.size() + 1);

        classNameFieldOffenders.sort(String::compareTo);
        assertThat(classNameFieldOffenders)
                .as("Story 304 (§1.4, §8.5.2) — no plugin-UI source may reference "
                        + "the retired classNameField. Offending files:%n  %s",
                        String.join("\n  ", classNameFieldOffenders))
                .isEmpty();

        textFieldOffenders.sort(String::compareTo);
        assertThat(textFieldOffenders)
                .as("Story 304 (§1.4, §8.5.2) — the install-flow sources must be "
                        + "TextField-free. Offending files:%n  %s",
                        String.join("\n  ", textFieldOffenders))
                .isEmpty();
    }

    /**
     * Locate the {@code daw-app} module root. Surefire normally sets the working
     * directory to the module itself; the fallbacks cover invocations from the
     * repo root (e.g. {@code mvn -pl daw-app test}). Mirrors
     * {@code RunLaterConsolidationTest#locateDawAppModule()}.
     */
    private static Path locateDawAppModule() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (isDawAppModule(cwd)) {
            return cwd;
        }
        Path child = cwd.resolve("daw-app");
        if (isDawAppModule(child)) {
            return child;
        }
        Path candidate = cwd.getParent();
        for (int i = 0; i < 5 && candidate != null; i++) {
            if (isDawAppModule(candidate)) {
                return candidate;
            }
            Path nested = candidate.resolve("daw-app");
            if (isDawAppModule(nested)) {
                return nested;
            }
            candidate = candidate.getParent();
        }
        return cwd;
    }

    private static boolean isDawAppModule(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(
                        dir.resolve("src/main/java/com/benesquivelmusic/daw/app"));
    }

    /**
     * Removes {@code //} and {@code /* *\/} comments while preserving string /
     * text-block literals. Mirrors {@code RunLaterConsolidationTest#stripComments}.
     */
    private static String stripComments(String source) {
        Matcher m = COMMENT_OR_STRING.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        while (m.find()) {
            String token = m.group();
            // A string / text block (starts with a quote) is code — keep it
            // verbatim; anything else the alternation matched is a comment and is
            // replaced with a single space.
            m.appendReplacement(out, token.charAt(0) == '"'
                    ? Matcher.quoteReplacement(token) : " ");
        }
        m.appendTail(out);
        return out.toString();
    }
}
