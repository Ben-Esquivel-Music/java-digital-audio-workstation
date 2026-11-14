package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;
import com.benesquivelmusic.daw.app.ui.SourceScanSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every launch path names the application's log manager, and its package is
 * exported to {@code java.logging} and nothing else (story 336, Technical
 * Notes: "every launch path names it, jpackage's {@code --java-options}
 * included, and its package is exported to {@code java.logging}";
 * javafx-application-design "## 2. Project structure & build": a module
 * exports only what is needed). The paths are the installer's launcher
 * (jpackage {@code --java-options}), the jlink image's {@code bin/daw} and
 * {@code bin/daw.bat} and {@code mvn javafx:run} (the javafx-maven-plugin's
 * {@code <options>}), and the manual commands of {@code docs/java26-setup.md};
 * any other launch is covered by {@code DawLauncher.main} naming it itself
 * ({@code PackagedLaunchLoggingTest}).
 */
class LaunchPathsNameTheLogManagerTest {

    private static final String OPTION = "-Djava.util.logging.manager=" + ExitHoldingLogManager.class.getName();
    private static final String LAUNCH = "-m daw.app/com.benesquivelmusic.daw.app.DawLauncher";

    @Test
    void theInstallersLauncherCarriesTheOptionAsAJavaOptionsPair() throws IOException {
        List<String> arguments = ChildJvm.jpackageArguments();

        assertThat(arguments).as("non-vacuity: the jpackage arguments were read")
                .contains("--module", "daw.app/com.benesquivelmusic.daw.app.DawLauncher");
        int option = arguments.indexOf(OPTION);
        assertThat(option).as("the option is a jpackage argument").isPositive();
        assertThat(arguments.get(option - 1)).isEqualTo("--java-options");
        assertThat(ChildJvm.logManagerOption()).isEqualTo(OPTION);
    }

    @Test
    void theJlinkLauncherScriptsAndJavafxRunCarryTheOption() throws IOException {
        assertThat(ChildJvm.jlinkLauncherOptions()).containsExactly(OPTION);
    }

    @Test
    void bothManualLaunchCommandsOfTheSetupGuideCarryTheOption() throws IOException {
        Path guide = SourceScanSupport.locateDawAppModule().getParent().resolve("docs/java26-setup.md");
        List<String> lines = Files.readAllLines(guide, StandardCharsets.UTF_8);
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(LAUNCH)) {
                StringBuilder command = new StringBuilder(lines.get(i));
                for (int previous = i - 1; previous >= 0 && lines.get(previous).strip().endsWith("\\"); previous--) {
                    command.insert(0, lines.get(previous) + "\n");
                }
                commands.add(command.toString());
            }
        }

        assertThat(commands).hasSize(2).allSatisfy(command -> assertThat(command).contains(OPTION + " \\"));
    }

    @Test
    void theModuleExportsOnlyTheLoggingPackageAndOnlyToJavaLogging() throws IOException {
        String moduleInfo = SourceScanSupport.stripComments(Files.readString(
                SourceScanSupport.locateDawAppModule().resolve("src/main/java/module-info.java"),
                StandardCharsets.UTF_8));
        List<String> exports = moduleInfo.lines().map(String::strip).filter(line -> line.startsWith("exports "))
                .toList();
        assertThat(exports).containsExactly("exports com.benesquivelmusic.daw.app.logging to java.logging;");

        ModuleDescriptor descriptor = ExitHoldingLogManager.class.getModule().getDescriptor();
        assertThat(descriptor).as("surefire runs daw.app as a named module").isNotNull();
        assertThat(descriptor.name()).isEqualTo("daw.app");
        assertThat(descriptor.exports()).hasSize(1);
        ModuleDescriptor.Exports export = descriptor.exports().iterator().next();
        assertThat(export.source()).isEqualTo("com.benesquivelmusic.daw.app.logging");
        assertThat(export.isQualified()).isTrue();
        assertThat(export.targets()).isEqualTo(Set.of("java.logging"));
    }
}
