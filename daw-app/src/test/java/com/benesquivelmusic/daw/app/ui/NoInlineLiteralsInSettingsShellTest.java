package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 306 — the inline-style-literal conformance sentinel (Settings View
 * Design Book §2.6 / §1.5, rejection #2; pattern:
 * {@code feedback_ui_overhaul_conformance_sentinel}). The Rail &amp; Pane
 * shell replaced the old dialog's hard-coded {@code -fx-text-fill: #ff9100}
 * restart hint, the muted {@code #808080} hints, and the
 * {@code -fx-font-weight: bold} section headers with semantic tokens in CSS.
 * This scan proves none of them can creep back: no settings-shell Java
 * source may carry an inline text-fill hex, font-size, or font-weight
 * literal — every colour/size/weight lives in a stylesheet.
 *
 * <p>Scope: every {@code .java} under
 * {@code ui/settings/} (recursively — the whole shell package: shell, rail,
 * row, search index, skins, and the story-305 catalogue types) plus
 * {@code ui/SettingsDialog.java}, the rewired dialog that used to hold the
 * offenders.</p>
 *
 * <p>Mechanics (shared {@link SourceScanSupport} harness, sibling of
 * {@code LegacyHardcodedColorAuditTest}): comments are stripped so a Javadoc
 * mention of a banned token cannot false-match, but string literals are
 * KEPT — the banned tokens only ever live inside {@code setStyle("…")}
 * string arguments, so blanking strings would blind the scan. The non-empty
 * guard pins the expected file set: a broken path must fail loudly, never
 * let non-presence be "proven" over an empty scan.</p>
 */
final class NoInlineLiteralsInSettingsShellTest {

    /**
     * The §1.5 / rejection-#2 banned inline literals. The text-fill ban
     * targets hex paints ({@code -fx-text-fill: #…}, whitespace-tolerant);
     * font-size and font-weight are banned outright — there is no
     * acceptable inline value for either in shell Java code.
     */
    private static final List<Pattern> BANNED_LITERALS = List.of(
            Pattern.compile("-fx-text-fill:\\s*#"),
            Pattern.compile("-fx-font-size:"),
            Pattern.compile("-fx-font-weight:"));

    /** Files the scan must have visited for its "no offenders" claim to mean anything. */
    private static final List<String> EXPECTED_FILES = List.of(
            "SettingsDialog.java",
            "SettingsShell.java",
            "SettingsNavRail.java",
            "SettingRow.java",
            "SettingsSearchIndex.java",
            // Story 309 additions — profile I/O + sheet and the wizard.
            "SettingsProfileIO.java",
            "SettingsProfileSheet.java",
            "FirstRunWizard.java");

    @Test
    void settingsShellSourcesCarryNoInlineStyleLiterals() throws IOException {
        Path module = SourceScanSupport.locateDawAppModule();
        Path settingsDir = module.resolve(
                "src/main/java/com/benesquivelmusic/daw/app/ui/settings");
        Path settingsDialog = module.resolve(
                "src/main/java/com/benesquivelmusic/daw/app/ui/SettingsDialog.java");
        assertThat(Files.isDirectory(settingsDir))
                .as("the settings shell sources must live under %s", settingsDir)
                .isTrue();
        assertThat(Files.isRegularFile(settingsDialog))
                .as("the rewired dialog must live at %s", settingsDialog)
                .isTrue();

        List<Path> scanScope = new ArrayList<>();
        Files.walkFileTree(settingsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".java")) {
                    scanScope.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        scanScope.sort(Path::compareTo);
        scanScope.add(settingsDialog);

        List<String> scannedNames = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Path file : scanScope) {
            String name = file.getFileName().toString();
            scannedNames.add(name);
            // Strip comments only — the banned tokens live inside string
            // literals (setStyle arguments), so strings must survive.
            String code = SourceScanSupport.stripComments(
                    Files.readString(file, StandardCharsets.UTF_8));
            for (Pattern banned : BANNED_LITERALS) {
                if (banned.matcher(code).find()) {
                    offenders.add(name + "  — contains " + banned.pattern());
                }
            }
        }

        // Guard against a vacuous pass: the scan must have visited the shell
        // classes this sentinel exists to police.
        assertThat(scannedNames)
                .as("the scan must cover the rewired dialog and the four new "
                        + "shell classes — an incomplete scan cannot prove "
                        + "non-presence")
                .contains(EXPECTED_FILES.toArray(String[]::new));
        assertThat(scannedNames)
                .as("the settings package scan visited suspiciously few files")
                .hasSizeGreaterThanOrEqualTo(6);

        offenders.sort(String::compareTo);
        assertThat(offenders)
                .as("Story 306 — no settings-shell Java source may carry an "
                        + "inline style literal (Settings View Design Book "
                        + "§2.6 / §1.5, rejection #2). Colours, font sizes and "
                        + "font weights belong in styles.css / the component "
                        + "user-agent sheets as semantic tokens. "
                        + "Offenders:%n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }
}
