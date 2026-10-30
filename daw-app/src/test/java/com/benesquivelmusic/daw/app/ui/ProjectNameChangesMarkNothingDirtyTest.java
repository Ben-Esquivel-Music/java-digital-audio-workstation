package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333 (the studio engineer's E3-N9): Save As renames the live project
 * after its copy's folder and then marks it clean at the capture's revision
 * ({@code markCleanIfUnchanged}); a listener of the {@code NAME} change that
 * marked the project dirty would advance the revision first, and the copy
 * would open dirty. This scan reads every main source of daw-app and
 * daw-core, comments stripped and literals blanked, and asserts that no
 * {@code case NAME} arm — and no {@code ChangeKind.NAME} test — marks
 * anything dirty. {@code SaveAsFlowTest} pins the same at run time with the
 * project's view model listening. A source scan, not a reflection consumer.
 */
class ProjectNameChangesMarkNothingDirtyTest {

    private static final Pattern NAME_ARM = Pattern.compile("case\\s+(?:[\\w.]+\\.)?NAME\\b[^-:]*(->|:)");
    private static final Pattern NAME_TEST = Pattern.compile("==\\s*(?:[\\w.]+\\.)?ChangeKind\\.NAME\\b");

    @Test
    void noListenerOfANameChangeMarksTheProjectDirty() throws IOException {
        Path app = SourceScanSupport.locateDawAppModule();
        List<String> arms = new ArrayList<>();
        for (Path root : List.of(app.resolve("src/main/java"), app.getParent().resolve("daw-core/src/main/java"))) {
            try (Stream<Path> sources = Files.walk(root)) {
                for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(
                            Files.readString(source, StandardCharsets.UTF_8)));
                    collect(code, NAME_ARM, source, arms);
                    collect(code, NAME_TEST, source, arms);
                }
            }
        }

        assertThat(arms).as("non-vacuity: the project view model's and the channel registry's NAME arms")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(arms).as("E3-N9: a rename marks nothing dirty").noneMatch(arm -> arm.contains("markDirty"));
    }

    /** Adds the code each match of {@code pattern} runs: a block, or the statement up to its {@code ;}. */
    private static void collect(String code, Pattern pattern, Path source, List<String> arms) {
        Matcher match = pattern.matcher(code);
        while (match.find()) {
            int at = match.end();
            while (at < code.length() && Character.isWhitespace(code.charAt(at))) {
                at++;
            }
            String arm;
            if (at < code.length() && code.charAt(at) == '{') {
                int depth = 0;
                int end = at;
                for (; end < code.length(); end++) {
                    if (code.charAt(end) == '{') {
                        depth++;
                    } else if (code.charAt(end) == '}' && --depth == 0) {
                        break;
                    }
                }
                arm = code.substring(at, Math.min(end + 1, code.length()));
            } else {
                int end = code.indexOf(';', at);
                arm = code.substring(at, end < 0 ? code.length() : end + 1);
            }
            arms.add(source.getFileName() + ": " + arm.replaceAll("\\s+", " "));
        }
    }
}
