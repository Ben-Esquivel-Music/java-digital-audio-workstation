package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.diagnostics.FailureFunnel;
import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The child-JVM side of {@code ExitWaitLinesReachTheLogTest} and of the
 * packaged-artifact proof (story 336, Goals bullet 9 and Goals—Tests bullet 8;
 * story 331, ruling N1): it sets logging up exactly as the packaged launcher
 * does ({@link DawLauncher#prepareLaunch} on the production settings
 * directory), then exits in one of three scenarios and lets the JDK's own
 * shutdown path run:
 * <ul>
 *   <li>{@code heldSave <project folder>}: a save held at the project file's
 *       atomic move, and the {@code daw-exit-write-wait} hook — the
 *       production {@code ProjectManager.awaitWritesBeforeExit}, its bound
 *       shortened to 2 s through its package-private seam — handed to the
 *       log manager as {@code MainController.registerExitWriteWait} hands it;</li>
 *   <li>{@code holdExpires}: an exit wait of a 1 s limit that sleeps 8 s and
 *       then logs {@code late line};</li>
 *   <li>{@code noExitWait}: no exit wait registered, and another hook that
 *       logs {@code late line} after 1 s;</li>
 *   <li>{@code failuresDuringTheHold}: the failure funnel {@code prepareLaunch}
 *       installed fails twice on one fingerprint, and its toasts are disarmed
 *       as {@code DawApplication.stop()} disarms them; then an exit wait that
 *       fails twice more on another fingerprint while the hold keeps the log
 *       open, and returns.</li>
 * </ul>
 * It uses only the JDK and {@code daw.*} modules, so it also runs on the jlink
 * image; the seam needs {@code --add-opens daw.core/com.benesquivelmusic.daw.core.persistence=daw.app}.
 */
public final class ExitWaitLogLinesMain {

    /** The exit wait's shortened bound in {@code heldSave}. */
    static final Duration SHORT_EXIT_WAIT = Duration.ofSeconds(2);

    /** The exit wait's limit in {@code holdExpires}. */
    static final Duration EXPIRING_LIMIT = Duration.ofSeconds(1);

    /** What a hook logs after the log should be closed. */
    static final String LATE_LINE = "late line";

    /** The message of the failure before the toasts are disarmed. */
    static final String BEFORE_DISARMING = "the take folder went away";

    /** The message of the failure during the exit wait. */
    static final String DURING_THE_HOLD = "the autosave folder went away";

    /** The logger of the late line; looked up only when it logs, after the launch set logging up. */
    private static final String LATE_LOGGER = "com.benesquivelmusic.daw.app.ExitWaitLogLines";

    private ExitWaitLogLinesMain() {
    }

    /**
     * @param args the scenario, and for {@code heldSave} the folder to create the project in
     * @throws Exception if the child cannot run
     */
    public static void main(String[] args) throws Exception {
        DawLauncher.prepareLaunch(DawLauncher.userSettingsDirectory());
        System.out.println("log manager: " + LogManager.getLogManager().getClass().getName());
        switch (args[0]) {
            case "heldSave" -> holdASaveThenExit(Path.of(args[1]));
            case "holdExpires" -> outliveTheHoldThenExit();
            case "noExitWait" -> exitWithNoExitWait();
            case "failuresDuringTheHold" -> failDuringTheHoldThenExit();
            default -> {
                System.err.println("unknown scenario: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static void holdASaveThenExit(Path projects) throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch reached = new CountDownLatch(1);
        AtomicFileWriter.Mover holdingMover = (temp, target, options) -> holdOnce(armed, reached, temp, target,
                options);
        ProjectManager manager = new ProjectManager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)), null,
                new ProjectLockManager(), new ProjectDeserializer(),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, holdingMover));
        Files.createDirectories(projects);
        manager.createProject("Song", projects);
        armed.set(true);
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Held at exit");
        project.markDirty();
        manager.save(project);
        if (!reached.await(20, TimeUnit.SECONDS)) {
            System.err.println("the save never reached the project file's move");
            System.exit(3);
        }
        Method boundedWait = ProjectManager.class.getDeclaredMethod("awaitWritesBeforeExit", Duration.class);
        boundedWait.setAccessible(true);
        Thread hook = Thread.ofPlatform().name("daw-exit-write-wait")
                .unstarted(() -> invoke(boundedWait, manager, SHORT_EXIT_WAIT));
        Runtime.getRuntime().addShutdownHook(hook);
        ExitHoldingLogManager.holdShutdownResetFor(hook, SHORT_EXIT_WAIT);
        System.out.println("exiting with a save held");
        System.exit(0);
    }

    private static void outliveTheHoldThenExit() {
        Thread hook = Thread.ofPlatform().name("daw-exit-write-wait").unstarted(() -> sleepThenLog(8_000));
        Runtime.getRuntime().addShutdownHook(hook);
        ExitHoldingLogManager.holdShutdownResetFor(hook, EXPIRING_LIMIT);
        System.out.println("exiting with an exit wait that outlives its hold");
        System.exit(0);
    }

    private static void exitWithNoExitWait() {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("daw-late-hook")
                .unstarted(() -> sleepThenLog(1_000)));
        System.out.println("exiting with no exit wait");
        System.exit(0);
    }

    private static void failDuringTheHoldThenExit() throws InterruptedException {
        FailureFunnel funnel = FailureFunnel.installed().orElseThrow();
        failTwice("daw-take-storage", ExitWaitLogLinesMain::loseTheTakeFolder);
        funnel.disarmToasts();
        Thread hook = Thread.ofPlatform().name("daw-exit-write-wait").unstarted(() -> {
            try {
                failTwice("daw-autosave", ExitWaitLogLinesMain::loseTheAutosaveFolder);
                System.out.println("the exit wait returned true");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        ExitHoldingLogManager.holdShutdownResetFor(hook, SHORT_EXIT_WAIT);
        System.out.println("exiting with failures during the hold");
        System.exit(0);
    }

    /** Two uncaught failures thrown from one line, each ending a thread of its own: one fingerprint. */
    private static void failTwice(String threadName, Runnable failing) throws InterruptedException {
        for (int i = 0; i < 2; i++) {
            Thread worker = Thread.ofPlatform().name(threadName).start(failing);
            worker.join();
        }
    }

    private static void loseTheTakeFolder() {
        throw new IllegalStateException(BEFORE_DISARMING);
    }

    private static void loseTheAutosaveFolder() {
        throw new IllegalStateException(DURING_THE_HOLD);
    }

    /** Moves {@code temp} onto {@code target}, but once armed, holds the project file's move until the exit. */
    private static void holdOnce(AtomicBoolean armed, CountDownLatch reached, Path temp, Path target,
                                 CopyOption... options) throws IOException {
        if (armed.get() && target.getFileName().toString().equals("project.daw")) {
            reached.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("the held move was interrupted");
            }
        }
        Files.move(temp, target, options);
    }

    private static void invoke(Method boundedWait, ProjectManager manager, Duration bound) {
        try {
            System.out.println("the exit wait returned " + boundedWait.invoke(manager, bound));
        } catch (ReflectiveOperationException failure) {
            System.err.println("the exit wait could not be called: " + failure);
        }
    }

    private static void sleepThenLog(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Logger.getLogger(LATE_LOGGER).info(LATE_LINE);
    }
}
