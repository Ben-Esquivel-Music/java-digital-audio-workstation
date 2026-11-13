package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.JarOnlyExceptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
 *       application's manager through the module's qualified export, and a
 *       first launch, with no {@code zgc.conf} yet, writes one with the
 *       default heap size;</li>
 *   <li>with no option — {@code DawLauncher} names the manager itself, and
 *       writes {@code zgc.conf} with the default heap size over one an earlier
 *       launch wrote with another;</li>
 *   <li>with the JDK's own manager named — the banner records that the exit
 *       hold is inactive, and the session ends without the closing line;</li>
 *   <li>with a session-memory setting that is not a heap size
 *       ({@code -Ddaw.sessionMem=4GB}) — the launch carries on, the log holds
 *       the WARNING that names the setting and its value, without a stack, and
 *       a {@code zgc.conf} from an earlier launch is left as it was (story 336,
 *       studio-engineer ruling R992-8);</li>
 *   <li>with {@code zgc.conf} a folder the launcher cannot replace — the
 *       write's WARNING, with its exception, and the launch carries on.</li>
 * </ul>
 */
class PackagedLaunchLoggingTest {

    private static final String APPLICATION_MANAGER = "log manager: " + ExitHoldingLogManager.class.getName();
    private static final String LAUNCHER_WARNING = " WARNING [main] " + DawLauncher.class.getName() + ": ";
    private static final String REJECTED_PROPERTY = "The system property daw.sessionMem holds \"4GB\", which is not a"
            + " heap size this setting accepts: use a whole number, optionally followed by k, m or g in either case,"
            + " such as 16G. zgc.conf was not written; a zgc.conf from an earlier launch is left as it was.";
    private static final String UNWRITABLE =
            "Could not write zgc.conf; a zgc.conf from an earlier launch is left as it was";

    @Test
    void thePackagedOptionSelectsTheApplicationsManagerAndTheLogIsWrittenFromCode(@TempDir Path work)
            throws Exception {
        Path jar = JarOnlyExceptions.buildJar(work.resolve("plugins"));
        Path zgcConf = ChildJvm.settingsDirectory(work.resolve("child")).resolve("zgc.conf");
        assertThat(zgcConf).as("no zgc.conf before the first launch").doesNotExist();

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
        assertThat(zgcConf).as("the first launch writes zgc.conf with the default heap size").isRegularFile();
        assertThat(Files.readString(zgcConf, StandardCharsets.UTF_8))
                .as("the first launch writes zgc.conf with the default heap size")
                .contains("-Xms4G").contains("-Xmx4G");
    }

    @Test
    void withNoOptionTheLauncherNamesTheApplicationsManagerItself(@TempDir Path work) throws Exception {
        DawLauncher.installZgcConfig(ChildJvm.settingsDirectory(work), "16G");

        ChildJvm.Result child = ChildJvm.run(work, PackagedLaunchLoggingMain.class, List.of(), List.of());

        assertThat(child.exitValue()).as(child.printed()).isZero();
        assertThat(child.stdout()).contains(APPLICATION_MANAGER);
        assertThat(child.log()).contains(": Exit hold: active");
        assertThat(lastRecord(child.log())).endsWith(": Log closed at application exit.");
        assertThat(Files.readString(child.settingsDirectory().resolve("zgc.conf"), StandardCharsets.UTF_8))
                .as("a zgc.conf an earlier launch wrote with 16G is written again with the default heap size")
                .contains("-Xms4G").contains("-Xmx4G").doesNotContain("-Xms16G").doesNotContain("-Xmx16G");
    }

    @Test
    void aSessionMemorySettingThatIsNotAHeapSizeIsAWarningAndLeavesAnEarlierZgcConfAsItWas(@TempDir Path work)
            throws Exception {
        Path settings = ChildJvm.settingsDirectory(work);
        Path earlier = DawLauncher.installZgcConfig(settings, "16G");
        byte[] earlierBytes = Files.readAllBytes(earlier);

        ChildJvm.Result child = ChildJvm.run(work, PackagedLaunchLoggingMain.class, List.of("-Ddaw.sessionMem=4GB"),
                List.of());

        assertThat(child.exited()).as("the child ended; %s", child.printed()).isTrue();
        assertThat(child.exitValue()).as("the launch carries on; %s", child.printed()).isZero();
        assertThat(child.stdout()).contains("done");
        String log = child.log();
        assertThat(log.lines().filter(line -> line.endsWith(LAUNCHER_WARNING + REJECTED_PROPERTY)))
                .as("one WARNING naming the setting and its value, on one line").hasSize(1);
        List<String> lines = log.lines().toList();
        int warning = IntStream.range(0, lines.size())
                .filter(at -> lines.get(at).endsWith(LAUNCHER_WARNING + REJECTED_PROPERTY)).findFirst().orElse(-1);
        assertThat(warning + 1 == lines.size() || !lines.get(warning + 1).startsWith("\t"))
                .as("the user's setting is a WARNING without a stack").isTrue();
        assertThat(log).as("no failure of the launch").doesNotContain("app.launch");
        assertThat(Files.readAllBytes(earlier)).as("the zgc.conf an earlier launch wrote is left as it was")
                .isEqualTo(earlierBytes);
        try (Stream<Path> files = Files.list(settings)) {
            assertThat(files.map(file -> file.getFileName().toString()))
                    .as("no temporary file is written").noneMatch(name -> name.endsWith(".conf.tmp"));
        }
    }

    @Test
    void aZgcConfThatCannotBeWrittenIsAWarningWithItsExceptionAndTheLaunchCarriesOn(@TempDir Path work)
            throws Exception {
        Path folder = Files.createDirectories(ChildJvm.settingsDirectory(work).resolve("zgc.conf"));
        Path kept = Files.writeString(folder.resolve("kept.txt"), "a file that keeps the folder from being replaced",
                StandardCharsets.UTF_8);

        ChildJvm.Result child = ChildJvm.run(work, PackagedLaunchLoggingMain.class, List.of(), List.of());

        assertThat(child.exitValue()).as("the launch carries on; %s", child.printed()).isZero();
        assertThat(child.stdout()).contains("done");
        List<String> lines = child.log().lines().toList();
        int warning = IntStream.range(0, lines.size())
                .filter(at -> lines.get(at).endsWith(LAUNCHER_WARNING + UNWRITABLE)).findFirst().orElse(-1);
        assertThat(warning).as("the write's WARNING in %s", lines).isNotNegative();
        assertThat(lines).hasSizeGreaterThan(warning + 1);
        assertThat(lines.get(warning + 1)).as("the WARNING carries its exception").startsWith("\t");
        assertThat(folder).as("the folder is left as it was").isDirectory();
        assertThat(kept).exists();
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
