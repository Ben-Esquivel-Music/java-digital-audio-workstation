package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.JarOnlyExceptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logging is configured in code, exactly as the packaged launcher runs it
 * (story 336, Technical Notes: "the jlink/jpackage runtime ships only the JDK
 * console default, so the sink must be configured in code at launch"; Goals—Tests
 * bullet 3: the log file exists under the user settings directory and records
 * INFO and above). Each case runs {@link PackagedLaunchLoggingMain} in a JVM of
 * its own ({@link ChildJvm}: {@code -m daw.app/…}, no surefire
 * {@code --add-reads}), which calls the production {@code DawLauncher.prepareLaunch}
 * on the production settings directory:
 * <ul>
 *   <li>with the {@code -Djava.util.logging.manager} option of the jpackage
 *       {@code --java-options} — {@code java.logging} instantiates the
 *       application's manager through the module's qualified export;</li>
 *   <li>with no option — {@code DawLauncher} names the manager itself;</li>
 *   <li>with the JDK's own manager named — the banner records that the exit
 *       hold is inactive, and the session ends without the closing line.</li>
 * </ul>
 */
class PackagedLaunchLoggingTest {

    private static final String APPLICATION_MANAGER = "log manager: " + ExitHoldingLogManager.class.getName();

    @Test
    void thePackagedOptionSelectsTheApplicationsManagerAndTheLogIsWrittenFromCode(@TempDir Path work)
            throws Exception {
        Path jar = JarOnlyExceptions.buildJar(work.resolve("plugins"));

        ChildJvm.Result child = ChildJvm.run(work.resolve("child"), PackagedLaunchLoggingMain.class,
                List.of(ChildJvm.logManagerOption()), List.of(jar.toString()));

        assertThat(child.exited()).as("the child ended; %s", child.printed()).isTrue();
        assertThat(child.exitValue()).as(child.printed()).isZero();
        assertThat(child.stdout()).contains(APPLICATION_MANAGER).contains("done");
        assertThat(child.stderr()).doesNotContain("Could not load Logmanager");
        assertThat(child.logFile()).as("under the production settings directory").exists();
        String log = child.log();
        assertThat(log).contains(": Session started: Digital Audio Workstation ").contains(": Exit hold: active")
                .contains(": packaged launch: an INFO line")
                .doesNotContain("packaged launch: a FINE line")
                .contains(": A removed plugin failed while it was disposed")
                .contains("\t" + JarOnlyExceptions.EXCEPTION_CLASS_NAME
                        + ": <the message could not be read: java.lang.NoClassDefFoundError>")
                .contains(" SEVERE  [daw-take-storage] com.benesquivelmusic.daw.app.diagnostics.FailureFunnel:"
                        + " Failure thread.uncaught (ERROR) on thread \"daw-take-storage\", occurrence 1, fingerprint"
                        + " thread.uncaught|java.lang.IllegalStateException|"
                        + PackagedLaunchLoggingMain.class.getName() + ".failToStore:")
                .contains("\tjava.lang.IllegalStateException: disk gone");
        assertThat(log.lines()).as("the failure's full stack")
                .anySatisfy(line -> assertThat(line).startsWith("\t\tat ")
                        .contains(PackagedLaunchLoggingMain.class.getName() + ".failToStore("));
        assertThat(log.indexOf("Session started")).as("the banner begins the session")
                .isLessThan(log.indexOf("packaged launch: an INFO line"));
        assertThat(lastRecord(log)).endsWith(": Log closed at application exit.");
    }

    @Test
    void withNoOptionTheLauncherNamesTheApplicationsManagerItself(@TempDir Path work) throws Exception {
        ChildJvm.Result child = ChildJvm.run(work, PackagedLaunchLoggingMain.class, List.of(), List.of());

        assertThat(child.exitValue()).as(child.printed()).isZero();
        assertThat(child.stdout()).contains(APPLICATION_MANAGER);
        assertThat(child.log()).contains(": Exit hold: active");
        assertThat(lastRecord(child.log())).endsWith(": Log closed at application exit.");
    }

    @Test
    void withTheJdksManagerNamedTheBannerSaysTheExitHoldIsInactive(@TempDir Path work) throws Exception {
        ChildJvm.Result child = ChildJvm.run(work, PackagedLaunchLoggingMain.class,
                List.of("-Djava.util.logging.manager=java.util.logging.LogManager"), List.of());

        assertThat(child.exitValue()).as(child.printed()).isZero();
        assertThat(child.stdout()).contains("log manager: java.util.logging.LogManager");
        String log = child.log();
        assertThat(log).contains(": Exit hold: inactive (java.util.logging.manager does not name the application's"
                        + " log manager on this launch path); lines logged while the application exits may be lost")
                .contains(": packaged launch: an INFO line")
                .doesNotContain("Log closed at application exit.");
        assertThat(Files.size(child.logFile())).isPositive();
    }

    /** The last line of {@code log} that begins a record. */
    private static String lastRecord(String log) {
        List<String> records = log.lines().filter(line -> line.matches("^\\d{4}-.*")).toList();
        assertThat(records).isNotEmpty();
        return records.getLast();
    }
}
