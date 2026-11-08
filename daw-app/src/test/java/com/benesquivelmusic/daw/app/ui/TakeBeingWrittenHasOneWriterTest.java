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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333 (design §3.8; javafx-application-design skill §4, "Use JavaFX
 * Properties for everything observable") — the record coordinator's take
 * fact has one writer: {@code writingPipeline} and {@code abandonedStart} are
 * assigned only inside {@code setWritingPipeline} and
 * {@code setAbandonedStart}, each of which tells the
 * {@code takeBeingWritten} property — set only by
 * {@code takeBeingWrittenChanged()}, from the very body of
 * {@code isTakeBeingWritten()} — so the close protocol, which waits on the
 * property, is told of every change. A field assigned anywhere else would
 * change the fact without telling the property, and the close would wait for
 * ever. The source scan reads the coordinator with comments stripped and
 * literals blanked ({@link SourceScanSupport}).
 */
class TakeBeingWrittenHasOneWriterTest {

    private static final Path COORDINATOR = SourceScanSupport.locateDawAppModule()
            .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/RecordCoordinator.java");

    @Test
    void theTakesStateIsAssignedOnlyInItsSettersWhichTellTheProperty() throws IOException {
        String code = code();
        List<String> offenders = new ArrayList<>();
        for (String[] writer : new String[][] {{"writingPipeline", "setWritingPipeline"},
                {"abandonedStart", "setAbandonedStart"}}) {
            String setter = body(code, writer[1]);
            int everywhere = count(assignmentOf(writer[0]), code);
            int inside = count(assignmentOf(writer[0]), setter);
            if (inside != 1) {
                offenders.add(writer[1] + " assigns " + writer[0] + " " + inside + " times");
            }
            if (everywhere != inside) {
                offenders.add(writer[0] + " is assigned " + (everywhere - inside) + " times outside " + writer[1]);
            }
            if (!setter.contains("takeBeingWrittenChanged();")) {
                offenders.add(writer[1] + " does not tell the property");
            }
        }
        assertThat(offenders).isEmpty();
        assertThat(count(Pattern.compile("\\btakeBeingWritten\\s*\\.\\s*set\\s*\\("), code))
                .as("takeBeingWrittenChanged() is the property's one writer").isEqualTo(1);
        assertThat(body(code, "takeBeingWrittenChanged"))
                .isEqualTo("{takeBeingWritten.set(writingPipeline!=null||abandonedStart!=null);}");
        assertThat(body(code, "isTakeBeingWritten")).as("the fact and the property are one expression")
                .isEqualTo("{returnwritingPipeline!=null||abandonedStart!=null;}");
    }

    private static String code() throws IOException {
        String source = Files.readString(COORDINATOR, StandardCharsets.UTF_8);
        assertThat(source.length()).as("non-vacuity: the coordinator was read").isGreaterThan(10_000);
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }

    private static Pattern assignmentOf(String field) {
        return Pattern.compile("(?<![\\w.$])(?:this\\s*\\.\\s*)?" + Pattern.quote(field) + "\\s*=(?!=)");
    }

    private static int count(Pattern pattern, String code) {
        Matcher match = pattern.matcher(code);
        int found = 0;
        while (match.find()) {
            found++;
        }
        return found;
    }

    /** The body, brace to matching brace, of the one method named {@code method}, whitespace removed. */
    private static String body(String code, String method) {
        Matcher declaration = Pattern.compile("^[ \\t]*[\\w.<>?,\\[\\] ]*[\\w>\\]]\\s+" + Pattern.quote(method)
                + "\\s*\\([^;{}]*\\)\\s*(?:throws\\s[^;{}]*)?\\{", Pattern.MULTILINE).matcher(code);
        List<Integer> openingBraces = new ArrayList<>();
        while (declaration.find()) {
            openingBraces.add(declaration.end() - 1);
        }
        assertThat(openingBraces).as("RecordCoordinator declares %s exactly once", method).hasSize(1);
        int open = openingBraces.getFirst();
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1).replaceAll("\\s+", "");
            }
        }
        throw new AssertionError("unbalanced braces after " + method);
    }
}
