package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.JarOnlyExceptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The rotating log file (story 336, Goals—Tests bullet 3; Failure Surfacing
 * book §4.2): under the settings directory's
 * {@code logs} folder, INFO and above only, rotated at its production cap,
 * at most five files, appended to across sessions, every root handler on the
 * hardened format, and its own failures reported instead of thrown.
 *
 * <p>The root logger is global in the shared surefire fork: each test
 * snapshots its level, its handlers and their formatters, and restores them,
 * closing every handler a sink added so that the temporary folder can be
 * deleted on Windows.</p>
 */
class LogSinkTest {

    private static final String LOGGER = "com.example.logsink";

    /**
     * The line that begins this session's lines in every file the sink writes: a record of {@code LogSink}
     * naming the session.
     */
    private static final String SESSION_LINE = "^\\d{4}-\\d{2}-\\d{2}T\\S+ INFO    \\[[^\\]]+\\] "
            + Pattern.quote(LogSink.class.getName() + ": " + LogSink.SESSION_LINE_PREFIX + "Digital Audio Workstation ")
            + "\\S+ that started at \\d{4}-\\d{2}-\\d{2}T\\S+ as process "
            + ProcessHandle.current().pid() + "$";

    /** Where a record begins: a line that starts with its timestamp (its continuation lines start with a tab). */
    private static final Pattern RECORD_START = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T", Pattern.MULTILINE);

    @TempDir
    Path settings;

    private final Logger root = Logger.getLogger("");
    private Level rootLevel;
    private List<Handler> rootHandlers;
    private final Map<Handler, Formatter> formatters = new IdentityHashMap<>();
    private final List<Logger> touchedLoggers = new ArrayList<>();

    @BeforeEach
    void snapshotTheRootLogger() {
        rootLevel = root.getLevel();
        rootHandlers = List.of(root.getHandlers());
        rootHandlers.forEach(handler -> formatters.put(handler, handler.getFormatter()));
    }

    @AfterEach
    void restoreTheRootLogger() {
        for (Handler handler : root.getHandlers()) {
            if (!rootHandlers.contains(handler)) {
                root.removeHandler(handler);
                handler.close();
            }
        }
        formatters.forEach(Handler::setFormatter);
        root.setLevel(rootLevel);
        touchedLoggers.forEach(logger -> logger.setLevel(null));
    }

    @Test
    void theFileIsDaw0UnderTheSettingsLogsFolderAndHoldsInfoAndAboveOnly() throws IOException {
        Path folder = LogSink.folderUnder(settings);
        LogSink sink = LogSink.install(folder);
        Logger logger = logger(LOGGER);
        logger.setLevel(Level.FINE);

        logger.info("an INFO line reaches the file");
        logger.warning("a WARNING line reaches the file");
        logger.fine("a FINE line does not");

        assertThat(folder).isEqualTo(settings.resolve("logs"));
        assertThat(sink.folder()).isEqualTo(folder.toAbsolutePath());
        assertThat(sink.unavailableReason()).isEmpty();
        String written = read(folder.resolve("daw-0.log"));
        assertThat(written).contains(" INFO    [").contains(LOGGER + ": an INFO line reaches the file")
                .contains(LOGGER + ": a WARNING line reaches the file")
                .doesNotContain("a FINE line does not");
        assertThat(LogSink.FILE_PATTERN).isEqualTo("daw-%g.log");
        assertThat(LogSink.FILE_LIMIT_BYTES).isEqualTo(5_242_880L);
        assertThat(LogSink.FILE_COUNT).isEqualTo(5);
    }

    @Test
    void theFileRotatesAtItsProductionCapAndEveryFileBeginsByNamingItsSession() throws IOException {
        Path folder = LogSink.folderUnder(settings);
        LogSink.install(folder);
        Logger logger = logger(LOGGER);
        String payload = "x".repeat(1_000);
        Path rotated = folder.resolve("daw-1.log");
        int records = 0;
        while (!Files.exists(rotated) && records < 20_000) {
            logger.info(payload);
            records++;
        }
        logger.info("the first line after the rotation");

        assertThat(rotated).as("rotated after %d records", records).exists();
        String launchFile = read(rotated);
        assertThat((long) launchFile.getBytes(StandardCharsets.UTF_8).length).as("the file that reached the cap")
                .isGreaterThanOrEqualTo(LogSink.FILE_LIMIT_BYTES);
        assertThat(bytesBeforeItsLastRecord(launchFile))
                .as("rotated by the very record that reached the cap, whichever thread wrote it")
                .isLessThan(LogSink.FILE_LIMIT_BYTES);
        List<String> current = read(folder.resolve("daw-0.log")).lines().toList();
        assertThat(current).as("generation 0 started again, in the middle of the session").isNotEmpty();
        assertThat(current.getFirst()).as("and begins by naming the session").matches(SESSION_LINE);
        assertThat(current.subList(1, current.size())).as("this test's own line follows it")
                .anyMatch(line -> line.endsWith(LOGGER + ": the first line after the rotation"));
        String launchFirst = launchFile.lines().findFirst().orElseThrow();
        assertThat(launchFirst).as("so did the file the sink opened at launch").matches(SESSION_LINE);
        assertThat(sessionNamed(launchFirst))
                .as("both name the same session, whichever thread wrote each line and whenever")
                .isEqualTo(sessionNamed(current.getFirst()));
    }

    @Test
    void theSessionLineNamesTheVersionTheStartAndTheProcess() {
        assertThat(LogSink.sessionLine()).startsWith(LogSink.SESSION_LINE_PREFIX + "Digital Audio Workstation "
                        + moduleVersion() + " that started at ")
                .endsWith(" as process " + ProcessHandle.current().pid());
    }

    @Test
    void theVersionIsTheBuildsOwnAndTheInstallersOnlyLabelledAsSuch() {
        assertThat(LogSink.applicationVersion(Optional.of("0.1.0-SNAPSHOT"), Optional.empty()))
                .isEqualTo("0.1.0-SNAPSHOT");
        assertThat(LogSink.applicationVersion(Optional.of("0.1.0-SNAPSHOT"), Optional.of("1.0.0")))
                .as("the installed application: the installer's version never stands in for the build's"
                        + " (story 336, studio-engineer ruling R-V4)")
                .isEqualTo("0.1.0-SNAPSHOT (installer 1.0.0)");
        assertThat(LogSink.applicationVersion(Optional.empty(), Optional.of("1.0.0")))
                .isEqualTo("version unknown (installer 1.0.0)");
        assertThat(LogSink.applicationVersion(Optional.empty(), Optional.of(" "))).isEqualTo("version unknown");
    }

    @Test
    void theInstalledApplicationsBannerNamesTheModuleVersionAndTheInstallersLabelled() throws IOException {
        String before = System.getProperty("jpackage.app-version");
        System.setProperty("jpackage.app-version", "1.0.0");
        try {
            Path folder = LogSink.folderUnder(settings);
            LogSink sink = LogSink.install(folder);

            sink.logSessionStart();

            assertThat(read(folder.resolve("daw-0.log")))
                    .as("as the jpackage launcher runs it: the build's own version, the installer's labelled")
                    .contains(": Session started: Digital Audio Workstation " + moduleVersion()
                            + " (installer 1.0.0), Java ")
                    .contains(LogSink.SESSION_LINE_PREFIX + "Digital Audio Workstation " + moduleVersion()
                            + " (installer 1.0.0) that started at ")
                    .doesNotContain("Digital Audio Workstation 1.0.0");
        } finally {
            if (before == null) {
                System.clearProperty("jpackage.app-version");
            } else {
                System.setProperty("jpackage.app-version", before);
            }
        }
    }

    @Test
    void atMostFiveFilesAreKept() throws IOException {
        Path folder = LogSink.folderUnder(settings);
        LogSink.install(folder, 4_096, LogSink.FILE_COUNT);
        Logger logger = logger(LOGGER);
        for (int i = 0; i < 400; i++) {
            logger.info("record " + i + " " + "y".repeat(200));
        }

        try (Stream<Path> files = Files.list(folder)) {
            List<String> rotation = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.matches("daw-\\d+\\.log")).sorted().toList();
            assertThat(rotation).containsExactly("daw-0.log", "daw-1.log", "daw-2.log", "daw-3.log", "daw-4.log");
        }
    }

    @Test
    void aSecondSessionAppendsToTheFirstSessionsFileItsLinesBeginningWithItsSessionLine() throws IOException {
        Path folder = LogSink.folderUnder(settings);
        LogSink first = LogSink.install(folder);
        logger(LOGGER).info("the first session's line");
        Handler firstHandler = first.handler().orElseThrow();
        root.removeHandler(firstHandler);
        firstHandler.close();

        LogSink.install(folder);
        logger(LOGGER).info("the second session's line");

        String written = read(folder.resolve("daw-0.log"));
        assertThat(written).contains("the first session's line").contains("the second session's line");
        assertThat(written.indexOf("the first session's line")).isLessThan(written.indexOf("the second session's line"));
        List<String> lines = written.lines().toList();
        List<Integer> firstOwn = indexesOfLinesMatching(lines,
                ".*" + Pattern.quote(LOGGER + ": the first session's line"));
        List<Integer> secondOwn = indexesOfLinesMatching(lines,
                ".*" + Pattern.quote(LOGGER + ": the second session's line"));
        List<Integer> sessionLines = indexesOfLinesMatching(lines, SESSION_LINE);
        assertThat(firstOwn).hasSize(1);
        assertThat(secondOwn).hasSize(1);
        assertThat(sessionLines).as("one session line per launch").hasSize(2);
        assertThat(sessionLines.get(0)).as("the first session's lines begin with its session line")
                .isLessThan(firstOwn.getFirst());
        assertThat(sessionLines.get(1))
                .as("and so do the second's, after the first session's lines, in the middle of the file")
                .isGreaterThan(firstOwn.getFirst()).isLessThan(secondOwn.getFirst());
    }

    @Test
    void aFolderWhoseNameHoldsPatternTokensIsWrittenLiterally() throws IOException {
        Path folder = settings.resolve("take %g of %t and %u").resolve("logs");
        LogSink sink = LogSink.install(folder);
        logger(LOGGER).info("written where the folder says");

        assertThat(sink.unavailableReason()).isEmpty();
        assertThat(read(folder.resolve("daw-0.log"))).contains("written where the folder says");
    }

    @Test
    void everyRootHandlerFormatsWithTheHardenedFormatter() {
        LogSink sink = LogSink.install(LogSink.folderUnder(settings));

        assertThat(root.getHandlers()).as("the file handler and the JDK's console handler")
                .contains(sink.handler().orElseThrow()).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(handler -> assertThat(handler.getFormatter()).isInstanceOf(LogLineFormatter.class));
        assertThat(sink.handler().orElseThrow().getLevel()).isEqualTo(Level.INFO);
        assertThat(sink.handler().orElseThrow().getEncoding()).isEqualTo("UTF-8");
        assertThat(root.getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void aPluginExceptionOnlyItsClosedJarCanFormatIsLoggedWithoutThrowing(@TempDir Path plugins) throws Exception {
        Path folder = LogSink.folderUnder(settings);
        LogSink.install(folder);
        RuntimeException jarOnly = JarOnlyExceptions.loadThenClose(plugins);

        assertThatCode(() -> logger("com.example.retire").log(Level.WARNING,
                "A removed plugin failed while it was disposed", jarOnly)).doesNotThrowAnyException();

        assertThat(read(folder.resolve("daw-0.log")))
                .contains("com.example.retire: A removed plugin failed while it was disposed")
                .contains("\t" + JarOnlyExceptions.EXCEPTION_CLASS_NAME
                        + ": <the message could not be read: java.lang.NoClassDefFoundError>");
    }

    @Test
    void aFolderThatCannotBeCreatedGivesAnUnavailableSinkAndNothingThrows() throws IOException {
        Path blocked = settings.resolve("logs");
        Files.writeString(blocked, "a file where the folder should be");
        int handlersBefore = root.getHandlers().length;

        LogSink sink = LogSink.install(blocked);

        assertThat(sink.unavailableReason()).isPresent();
        assertThat(sink.handler()).isEmpty();
        assertThat(sink.folder()).isEqualTo(blocked.toAbsolutePath());
        assertThat(root.getHandlers()).hasSize(handlersBefore)
                .as("every root handler still formats with the hardened formatter: a console handler's formatter"
                        + " would otherwise throw a plugin's Error at whoever logs")
                .allSatisfy(handler -> assertThat(handler.getFormatter()).isInstanceOf(LogLineFormatter.class));
    }

    @Test
    void theFirstWriteFailureIsToldOnceEvenToAListenerSetAfterIt() {
        LogSink sink = LogSink.install(LogSink.folderUnder(settings));
        ErrorManager errors = sink.handler().orElseThrow().getErrorManager();
        errors.error("write failed", new IOException("There is not enough space on the disk"),
                ErrorManager.WRITE_FAILURE);
        errors.error("write failed again", new IOException("still full"), ErrorManager.WRITE_FAILURE);
        List<String> told = new CopyOnWriteArrayList<>();

        sink.onFirstWriteFailure(told::add);
        sink.onFirstWriteFailure(told::add);

        assertThat(told).containsExactly("There is not enough space on the disk");
    }

    @Test
    void theSessionBannerIsTwoInfoLinesNamingTheExitHold() throws IOException {
        assertThat(ExitHoldingLogManager.isActive()).as("precondition: surefire runs the JDK's own manager")
                .isFalse();
        Path folder = LogSink.folderUnder(settings);
        LogSink sink = LogSink.install(folder);

        sink.logSessionStart();

        List<String> banner = read(folder.resolve("daw-0.log")).lines()
                .filter(line -> line.contains(LogSink.class.getName() + ": ")).toList();
        assertThat(banner).as("the file's session line, then the two banner lines")
                .hasSize(3);
        assertThat(banner.get(0)).as("the session line comes first at launch, and is not a second banner")
                .matches(SESSION_LINE).doesNotContain("Session started");
        assertThat(banner.get(1)).contains(" INFO    [")
                .contains(": Session started: Digital Audio Workstation " + moduleVersion() + ", Java "
                        + System.getProperty("java.runtime.version") + " (")
                .contains(", process " + ProcessHandle.current().pid() + ", maximum heap ")
                .endsWith(" MiB");
        assertThat(banner.get(2)).contains(" INFO    [").endsWith(": Exit hold: inactive (java.util.logging.manager"
                + " does not name the application's log manager on this launch path); lines logged while the"
                + " application exits may be lost");
    }

    /** The module's own version, which the surefire JVM's module descriptor of {@code daw.app} carries. */
    private static String moduleVersion() {
        ModuleDescriptor descriptor = LogSink.class.getModule().getDescriptor();
        assertThat(descriptor).as("precondition: daw.app runs as a named module").isNotNull();
        return descriptor.rawVersion().orElseThrow(() -> new AssertionError("daw.app carries no version"));
    }

    /** A session line from its logger's name on: without the timestamp and the thread that wrote it. */
    private static String sessionNamed(String sessionLine) {
        return sessionLine.substring(sessionLine.indexOf(LogSink.class.getName() + ": "));
    }

    /** How many bytes of {@code file} come before its last record, which begins with a timestamp. */
    private static long bytesBeforeItsLastRecord(String file) {
        Matcher recordStart = RECORD_START.matcher(file);
        int last = -1;
        while (recordStart.find()) {
            last = recordStart.start();
        }
        assertThat(last).as("the file holds a record").isNotNegative();
        return file.substring(0, last).getBytes(StandardCharsets.UTF_8).length;
    }

    /** The indexes of the lines that match {@code regex}, in order. */
    private static List<Integer> indexesOfLinesMatching(List<String> lines, String regex) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches(regex)) {
                found.add(i);
            }
        }
        return found;
    }

    private Logger logger(String name) {
        Logger logger = Logger.getLogger(name);
        touchedLoggers.add(logger);
        return logger;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
