package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.FontResources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 266 — verifies the JetBrains Mono TTF resources required by the
 * {@code -font-mono} CSS stack (UI Design Book §3.2) are bundled in the
 * application classpath.
 *
 * <p>This test is deliberately <em>toolkit-free</em>. An earlier draft used
 * {@code JavaFxToolkitExtension} so it could also call
 * {@link javafx.scene.text.Font#loadFont} and assert that JetBrains Mono
 * appeared in {@link javafx.scene.text.Font#getFamilies()}. That check
 * caused the test to hang inside Surefire on Windows — {@code Platform.startup}
 * does not always return in a forked test JVM without an interactive
 * display. The story explicitly allows that branch to be warn-only ("logs
 * a warning rather than failing in CI where font loading may be skipped
 * headless"), and {@code DawApplication.loadBundledFonts()} already swallows
 * registration failures at runtime, so dropping the toolkit-dependent
 * branch entirely keeps the test reliable without weakening the contract:
 * if the resources are on the classpath, the runtime path will pick them
 * up; if the JavaFX font subsystem rejects them on a given host, the CSS
 * stack falls back to IBM Plex Mono / Cascadia Code / Consolas / monospace.
 */
final class FontResourcesTest {

    @Test
    void everyBundledJetBrainsMonoTtfIsOnTheClasspath() throws Exception {
        for (String weight : FontResources.JETBRAINS_MONO_WEIGHTS) {
            String path = FontResources.JETBRAINS_MONO_DIR + weight;
            try (InputStream in = FontResourcesTest.class.getResourceAsStream(path)) {
                assertThat(in)
                        .as("Bundled JetBrains Mono weight '%s' must be on the classpath "
                                + "(story 266 / UI Design Book §3.2). If this fails the OFL "
                                + ".ttf files were removed — re-vendor from "
                                + "https://github.com/JetBrains/JetBrainsMono.", path)
                        .isNotNull();
            }
        }
    }

    @Test
    void licenseFileIsBundledAlongsideTheFonts() throws Exception {
        String path = FontResources.JETBRAINS_MONO_DIR + FontResources.LICENSE_FILENAME;
        try (InputStream in = FontResourcesTest.class.getResourceAsStream(path)) {
            assertThat(in)
                    .as("The OFL LICENSE must be bundled alongside the JetBrains Mono "
                            + "TTFs at %s (story 266 / UI Design Book §3.2 — vendor "
                            + "compliance).", FontResources.JETBRAINS_MONO_DIR)
                    .isNotNull();
        }
    }

    /**
     * Confirms each bundled TTF starts with a valid sfnt magic number —
     * either {@code 0x00010000} (TrueType) or {@code "OTTO"} (OpenType CFF).
     * A non-matching prefix means the file is truncated, corrupt, or was
     * stored as Git-LFS-pointer text rather than the real binary — a
     * regression the classpath-presence check at the top cannot detect.
     */
    @Test
    void everyBundledJetBrainsMonoTtfHasAValidSfntMagicNumber() throws IOException {
        for (String weight : FontResources.JETBRAINS_MONO_WEIGHTS) {
            String path = FontResources.JETBRAINS_MONO_DIR + weight;
            try (InputStream in = FontResourcesTest.class.getResourceAsStream(path)) {
                assertThat(in)
                        .as("Bundled JetBrains Mono weight '%s' must be on the classpath", path)
                        .isNotNull();
                byte[] magic = in.readNBytes(4);
                boolean trueType = magic.length == 4
                        && magic[0] == 0x00 && magic[1] == 0x01
                        && magic[2] == 0x00 && magic[3] == 0x00;
                boolean openType = magic.length == 4
                        && magic[0] == 'O' && magic[1] == 'T'
                        && magic[2] == 'T' && magic[3] == 'O';
                assertThat(trueType || openType)
                        .as("Bundled font '%s' must start with the TrueType sfnt magic "
                                + "(00 01 00 00) or the OpenType-CFF magic (\"OTTO\") — got %s. "
                                + "If this fails the .ttf was truncated or stored as an "
                                + "LFS pointer; re-vendor from "
                                + "https://github.com/JetBrains/JetBrainsMono.",
                                path, hex(magic))
                        .isTrue();
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", bytes[i] & 0xff));
        }
        return sb.toString();
    }
}
