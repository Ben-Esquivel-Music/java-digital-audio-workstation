package com.benesquivelmusic.daw.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DawLauncherTest {

    @Test
    void installsZgcConfigAndSubstitutesSessionMem(@TempDir Path tmp) throws IOException {
        Path settings = tmp.resolve("settings");

        Path written = DawLauncher.installZgcConfig(settings, "2G");

        assertThat(written).isEqualTo(settings.resolve("zgc.conf"));
        assertThat(Files.exists(written)).isTrue();

        String contents = Files.readString(written);
        // No unresolved placeholders.
        assertThat(contents).doesNotContain("${sessionMem}");
        // ZGC flags and substituted heap sizes present.
        assertThat(contents)
                .contains("-XX:+UseZGC")
                .contains("-XX:-ZUncommit")
                .contains("-XX:+AlwaysPreTouch")
                .contains("-Xms2G")
                .contains("-Xmx2G");
        // Removed-in-JDK24 flag must not appear as an active option line (may be
        // mentioned in comments that explain its removal).
        String nonComment = contents.lines()
                .filter(l -> !l.trim().startsWith("#"))
                .reduce("", (a, b) -> a + b + "\n");
        assertThat(nonComment).doesNotContain("ZGenerational");
    }

    @Test
    void overwritesExistingConfigOnSubsequentInstall(@TempDir Path tmp) throws IOException {
        DawLauncher.installZgcConfig(tmp, "2G");
        Path written = DawLauncher.installZgcConfig(tmp, "8G");

        String contents = Files.readString(written);
        assertThat(contents).contains("-Xms8G").contains("-Xmx8G");
        assertThat(contents).doesNotContain("-Xms2G");
    }

    @Test
    void createsSettingsDirectoryIfMissing(@TempDir Path tmp) throws IOException {
        Path nested = tmp.resolve("a/b/c");
        assertThat(Files.exists(nested)).isFalse();
        DawLauncher.installZgcConfig(nested, "4G");
        assertThat(Files.isDirectory(nested)).isTrue();
        assertThat(Files.exists(nested.resolve("zgc.conf"))).isTrue();
    }

    @Test
    void rejectsBlankSessionMem(@TempDir Path tmp) {
        assertThatThrownBy(() -> DawLauncher.installZgcConfig(tmp, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesSessionMemFromSystemProperty() {
        String original = System.getProperty("daw.sessionMem");
        try {
            System.setProperty("daw.sessionMem", "6G");
            assertThat(DawLauncher.resolveSessionMem()).isEqualTo("6G");
        } finally {
            if (original == null) System.clearProperty("daw.sessionMem");
            else System.setProperty("daw.sessionMem", original);
        }
    }

    @Test
    void fallsBackToDefaultSessionMem() {
        String original = System.getProperty("daw.sessionMem");
        try {
            System.clearProperty("daw.sessionMem");
            // ignore env DAW_SESSION_MEM here; if the test runner sets it, accept the override.
            String env = System.getenv("DAW_SESSION_MEM");
            String expected = (env != null && !env.isBlank()) ? env : DawLauncher.DEFAULT_SESSION_MEM;
            assertThat(DawLauncher.resolveSessionMem()).isEqualTo(expected);
        } finally {
            if (original != null) System.setProperty("daw.sessionMem", original);
        }
    }

    @Test
    void userSettingsDirectoryIsUnderUserHome() {
        Path dir = DawLauncher.userSettingsDirectory();
        assertThat(dir).isNotNull();
        assertThat(dir.toString()).contains("java-daw");
    }
}
