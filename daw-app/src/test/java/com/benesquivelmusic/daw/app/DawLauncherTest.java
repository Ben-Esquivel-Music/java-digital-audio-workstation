package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.DawLauncher.SessionMem;
import com.benesquivelmusic.daw.app.DawLauncher.SessionMem.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

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
    void theSystemPropertysSizeIsTrimmedAndAccepted() {
        assertThat(DawLauncher.resolveSessionMem(Optional.of("  16G "), Optional.empty()))
                .isEqualTo(new SessionMem.Accepted("16G", Source.SYSTEM_PROPERTY));
    }

    @Test
    void withTheSystemPropertyAbsentOrBlankTheEnvironmentVariablesSizeIsAccepted() {
        assertThat(DawLauncher.resolveSessionMem(Optional.empty(), Optional.of("8g")))
                .isEqualTo(new SessionMem.Accepted("8g", Source.ENVIRONMENT_VARIABLE));
        assertThat(DawLauncher.resolveSessionMem(Optional.of(" \t "), Optional.of("8g")))
                .isEqualTo(new SessionMem.Accepted("8g", Source.ENVIRONMENT_VARIABLE));
    }

    @Test
    void withBothAbsentOrBlankTheDefaultIsAccepted() {
        SessionMem fallback = new SessionMem.Accepted(DawLauncher.DEFAULT_SESSION_MEM, Source.DEFAULT);

        assertThat(DawLauncher.DEFAULT_SESSION_MEM).isEqualTo("4G");
        assertThat(DawLauncher.resolveSessionMem(Optional.empty(), Optional.empty())).isEqualTo(fallback);
        assertThat(DawLauncher.resolveSessionMem(Optional.of(""), Optional.of("  "))).isEqualTo(fallback);
    }

    @Test
    void aSystemPropertyThatIsNotAHeapSizeIsRejectedAndTheEnvironmentVariableDoesNotTakeItsPlace() {
        assertThat(DawLauncher.resolveSessionMem(Optional.of("4GB"), Optional.of("8G")))
                .isEqualTo(new SessionMem.Rejected(Source.SYSTEM_PROPERTY, "4GB"));
    }

    @Test
    void anEnvironmentVariableThatIsNotAHeapSizeIsRejectedAndNamed() {
        assertThat(DawLauncher.resolveSessionMem(Optional.empty(), Optional.of("4GB")))
                .isEqualTo(new SessionMem.Rejected(Source.ENVIRONMENT_VARIABLE, "4GB"));
    }

    @Test
    void aTerabyteUnitIsRejected() {
        assertThat(DawLauncher.resolveSessionMem(Optional.of("1T"), Optional.empty()))
                .isEqualTo(new SessionMem.Rejected(Source.SYSTEM_PROPERTY, "1T"));
    }

    @Test
    void aRejectionKeepsTheValueAsItWasSetUntrimmed() {
        SessionMem.Rejected rejected = rejection(Optional.of(" 4GB "), Optional.empty());

        assertThat(rejected).isEqualTo(new SessionMem.Rejected(Source.SYSTEM_PROPERTY, " 4GB "));
        assertThat(rejected.warning()).contains("holds \" 4GB \", which is not");
    }

    @Test
    void theWarningNamesTheSystemPropertyAndItsValue() {
        assertThat(rejection(Optional.of("4GB"), Optional.empty()).warning())
                .isEqualTo("The system property daw.sessionMem holds \"4GB\", which is not a heap size this setting"
                        + " accepts: use a whole number, optionally followed by k, m or g in either case, such as 16G."
                        + " zgc.conf was not written; a zgc.conf from an earlier launch is left as it was.");
    }

    @Test
    void theWarningNamesTheEnvironmentVariableAndItsValue() {
        assertThat(rejection(Optional.empty(), Optional.of("4GB")).warning())
                .isEqualTo("The environment variable DAW_SESSION_MEM holds \"4GB\", which is not a heap size this"
                        + " setting accepts: use a whole number, optionally followed by k, m or g in either case, such"
                        + " as 16G. zgc.conf was not written; a zgc.conf from an earlier launch is left as it was.");
    }

    @Test
    void aLineBreakInTheValueIsWrittenAsItsEscapeSoTheWarningIsOneLine() {
        String warning = rejection(Optional.empty(), Optional.of("4G\n-XX:+HeapDumpOnOutOfMemoryError")).warning();

        assertThat(warning).contains(" holds \"4G\\u000A-XX:+HeapDumpOnOutOfMemoryError\", which is not");
        assertThat(Pattern.compile("\\R").matcher(warning).find()).as("no line break in %s", warning).isFalse();
    }

    private static SessionMem.Rejected rejection(Optional<String> property, Optional<String> variable) {
        SessionMem resolved = DawLauncher.resolveSessionMem(property, variable);
        if (resolved instanceof SessionMem.Rejected rejected) {
            return rejected;
        }
        throw new AssertionError("expected a rejection, but was " + resolved);
    }

    @Test
    void rejectsSessionMemWithWhitespaceOrJunk(@TempDir Path tmp) {
        assertThatThrownBy(() -> DawLauncher.installZgcConfig(tmp, "4 G"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DawLauncher.installZgcConfig(tmp, "4G\n-XX:+HeapDumpOnOutOfMemoryError"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DawLauncher.installZgcConfig(tmp, "abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trimsWhitespaceAroundValidSessionMem(@TempDir Path tmp) throws IOException {
        Path written = DawLauncher.installZgcConfig(tmp, "  4G  ");
        String contents = Files.readString(written);
        assertThat(contents).contains("-Xms4G").contains("-Xmx4G");
    }

    @Test
    void returnsAbsolutePath(@TempDir Path tmp) throws IOException {
        Path written = DawLauncher.installZgcConfig(tmp, "2G");
        assertThat(written.isAbsolute()).isTrue();
    }

    @Test
    void userSettingsDirectoryIsUnderUserHome() {
        Path dir = DawLauncher.userSettingsDirectory();
        assertThat(dir).isNotNull();
        assertThat(dir.toString()).contains("java-daw");
    }
}
