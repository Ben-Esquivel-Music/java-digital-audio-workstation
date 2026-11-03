package com.benesquivelmusic.daw.core.plugin.editor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Convolution Reverb's "Load File…" follows the door pattern (PR #989
 * review 5711460812; the studio engineer's ruling 9 R9-10 (iii)): it reads the
 * host's UI-write admission before its native chooser opens and again once
 * the chooser returns, before the load starts — a close may begin while the
 * chooser is open. A native {@code FileChooser} cannot be driven headless, so
 * this pins the order in the source of {@code openImpulseResponseFile}. A
 * source scan, not a reflection consumer (dawg-annotations-reflection §4,
 * "Adding a new reflection consumer").
 */
class ConvolutionReverbFileLoadReadsTheGateSourceTest {

    private static final String SIGNATURE = "private void openImpulseResponseFile()";
    private static final String READ = "uiWritesAdmitted()";
    private static final String CHOOSER = "showOpenDialog(";
    private static final String LOAD = "loadImpulseResponseFromFileAsync(";

    @Test
    void theLoadReadsTheGateBeforeItsChooserOpensAndAgainBeforeItLoads() throws IOException {
        String body = bodyOf(withoutComments(Files.readString(
                editorPackage().resolve("ConvolutionReverbEditor.java"), StandardCharsets.UTF_8)));

        assertThat(occurrences(body, CHOOSER)).as("one chooser").isOne();
        assertThat(occurrences(body, LOAD)).as("one load").isOne();
        int chooser = body.indexOf(CHOOSER);
        int load = body.indexOf(LOAD);
        int firstRead = body.indexOf(READ);
        assertThat(firstRead).as("a read of the admission comes before the chooser opens")
                .isNotNegative().isLessThan(chooser);
        int secondRead = body.indexOf(READ, chooser);
        assertThat(secondRead).as("and another after it returns, before the load starts")
                .isGreaterThan(chooser).isLessThan(load);
    }

    /** The braces-balanced body of {@link #SIGNATURE}, from its opening brace to its closing one. */
    private static String bodyOf(String code) {
        int signature = code.indexOf(SIGNATURE);
        assertThat(signature).as("%s is declared", SIGNATURE).isNotNegative();
        assertThat(code.indexOf(SIGNATURE, signature + 1)).as("once").isNegative();
        int open = code.indexOf('{', signature);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("the body of " + SIGNATURE + " never closes");
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }

    /** {@code source} without its block and line comments; a string literal holding a comment marker is not one here. */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\r\\n]*", "");
    }

    /**
     * This package's {@code src/main/java} directory. Surefire runs in the
     * module ({@code daw-core}); the fallbacks cover a run from the reactor
     * root or below it.
     */
    private static Path editorPackage() {
        Path relative = Paths.get("src", "main", "java", "com", "benesquivelmusic", "daw", "core", "plugin", "editor");
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path candidate = cwd; candidate != null; candidate = candidate.getParent()) {
            Path direct = candidate.resolve(relative);
            if (Files.isDirectory(direct) && Files.exists(direct.resolve("ConvolutionReverbEditor.java"))) {
                return direct;
            }
            Path viaModule = candidate.resolve("daw-core").resolve(relative);
            if (Files.isDirectory(viaModule)) {
                return viaModule;
            }
        }
        throw new AssertionError("daw-core's plugin editor sources were not found from " + cwd);
    }
}
