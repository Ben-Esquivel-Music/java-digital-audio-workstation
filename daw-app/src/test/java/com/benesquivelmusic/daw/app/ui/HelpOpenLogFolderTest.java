package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.FailureFunnel;
import com.benesquivelmusic.daw.app.diagnostics.LogFolderOpener;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Story 336, Goals — Tests bullet 7 (Failure Surfacing book §2.8):
 * Help ▸ Open Log Folder opens the very directory the log file is written
 * into — the one a marker line logged through the root logger lands in — off
 * the FX thread; a failure toast's action opens the same one; and when the
 * desktop cannot open it, a plain ERROR toast says so and where to find it,
 * without the action, and nothing re-enters the funnel.
 *
 * <p>The menu is the real {@link MenuConstructionService}, built as
 * {@code ProjectInstallationInputFenceTest} builds it, over a host whose
 * {@code onOpenLogFolder} does what {@code MainController.onOpenLogFolder}
 * does: {@code funnel.openLogFolder()} ({@code FailureSpineWiringSourceTest}
 * pins that route through the window's {@code MenuActions}). The toasts reach
 * the {@code MainController} substitute of {@link FailureSpineFixture#armOnFx()}:
 * the {@code FxDispatcher}, {@code NotificationBar} and
 * {@code NotificationHistoryService} built as
 * {@code MainController.initializeNotificationBar} and
 * {@code DawApplication.start} build them, and the production
 * {@code installOnCurrentFxThread} and {@code armToasts} called. The first two
 * cases record what the opener is asked to open; the last drives the
 * production {@link LogFolderOpener#DESKTOP} through
 * {@link FailureSpineFixture#launch} ({@code DawLauncher.prepareLaunch}), with
 * AWT headless as the surefire fork runs it, so the desktop refuses.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class HelpOpenLogFolderTest {

    private static final String OPEN_LOG_FOLDER = "Open Log Folder";

    /** What the recording opener was asked to open, and on which thread. */
    private record Opened(Path folder, String thread) {
        private Opened {
            Objects.requireNonNull(folder, "folder must not be null");
            Objects.requireNonNull(thread, "thread must not be null");
        }
    }

    @Test
    void helpOpenLogFolderOpensTheDirectoryTheLogFileIsWrittenInto(@TempDir Path settings) throws Exception {
        BlockingQueue<Opened> opened = new LinkedBlockingQueue<>();
        try (FailureSpineFixture spine = FailureSpineFixture.withClock(settings, System::nanoTime,
                InstantSource.system(), folder -> opened.add(new Opened(folder, Thread.currentThread().getName())))) {
            spine.armOnFx();
            String marker = "a marker line for the log folder " + UUID.randomUUID();
            Logger.getLogger("").log(Level.INFO, marker);

            fireOnFx(helpItem(spine.funnel()));

            Opened open = opened.poll(GUARD.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(open).as("the folder was opened").isNotNull();
            assertThat(open.thread()).as("off the FX thread").isEqualTo("daw-open-log-folder");
            assertThat(open.folder()).as("the funnel's log folder").isEqualTo(spine.funnel().logFolder());
            assertThat(Files.readString(open.folder().resolve("daw-0.log"), StandardCharsets.UTF_8))
                    .as("the very directory the file handler writes into").contains(marker);
        }
    }

    @Test
    void aFailureToastsActionOpensTheSameDirectory(@TempDir Path settings) throws Exception {
        BlockingQueue<Opened> opened = new LinkedBlockingQueue<>();
        try (FailureSpineFixture spine = FailureSpineFixture.withClock(settings, System::nanoTime,
                InstantSource.system(), folder -> opened.add(new Opened(folder, Thread.currentThread().getName())))) {
            spine.armOnFx();
            Thread worker = Thread.ofVirtual().name("daw-take-storage").start(() -> {
                throw new IllegalStateException("disk gone");
            });
            assertThat(worker.join(GUARD)).isTrue();
            String expected = "Internal error in the background task “take storage”: disk gone. Its work may be"
                    + " incomplete.";

            // In one FX turn: the bar shows this test's own toast, and then its action is fired.
            awaitOnFx("the failure's own toast, its action fired", () -> {
                if (!expected.equals(spine.bar().getMessage())
                        || !OPEN_LOG_FOLDER.equals(spine.bar().getPill().getActionButton().getText())) {
                    return false;
                }
                spine.bar().getPill().getActionButton().fire();
                return true;
            });

            Opened open = opened.poll(GUARD.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(open).as("the toast's action opened a folder").isNotNull();
            assertThat(open.folder()).isEqualTo(spine.funnel().logFolder())
                    .isEqualTo(spine.logFile().getParent().toAbsolutePath());
        }
    }

    @Test
    void whenTheDesktopCannotOpenTheFolderAPlainErrorToastSaysWhereItIs(@TempDir Path settings) throws Exception {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("precondition: AWT is headless, as surefire's argLine sets it, so the desktop refuses to open")
                .isTrue();
        try (FailureSpineFixture spine = FailureSpineFixture.launch(settings)) {
            spine.armOnFx();
            Path folder = spine.funnel().logFolder();

            fireOnFx(helpItem(spine.funnel()));

            Predicate<String> ownToast = message -> message.startsWith("The log folder could not be opened: ")
                    && message.endsWith(". Find it at " + folder.toAbsolutePath());
            FailureSpineFixture.await("the open failure's toast", () -> spine.history().getEntries().stream()
                    .anyMatch(entry -> ownToast.test(entry.message())));
            runOnFx(() -> { });
            assertThat(spine.history().getEntries()).filteredOn(entry -> ownToast.test(entry.message()))
                    .as("this test's own toast, once").singleElement().satisfies(entry -> {
                        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                        assertThat(entry.action())
                                .as("no action: a toast that cannot open the folder does not offer to open it")
                                .isEmpty();
                        assertThat(entry.actionLabel()).isEmpty();
                    });
            assertThat(spine.funnelRecords()).as("one WARNING line")
                    .filteredOn(record -> record.getLevel() == Level.WARNING)
                    .singleElement().satisfies(record -> {
                        assertThat(record.getMessage()).isEqualTo("The log folder " + folder + " could not be opened");
                        assertThat(record.getThrown()).isInstanceOf(java.awt.HeadlessException.class);
                    });
            assertThat(spine.funnel().occurrenceTotals().keySet()).as("nothing re-entered the funnel")
                    .noneMatch(key -> key.contains("HeadlessException"));
        }
    }

    /** The Help ▸ Open Log Folder item of a real menu whose host opens {@code funnel}'s log folder. */
    private static MenuItem helpItem(FailureFunnel funnel) throws Exception {
        return onFx(() -> {
            DawMenuBarController.MenuActions host = mock(DawMenuBarController.MenuActions.class);
            doAnswer(_ -> {
                funnel.openLogFolder();
                return null;
            }).when(host).onOpenLogFolder();
            Preferences preferences = Preferences.userRoot().node("daw-help-log-folder-" + UUID.randomUUID());
            try {
                MenuConstructionService menus = new MenuConstructionService(host, new KeyBindingManager(preferences));
                Menu help = menus.build().menus().stream().filter(menu -> "Help".equals(menu.getText()))
                        .findFirst().orElseThrow();
                MenuItem item = help.getItems().stream().filter(each -> OPEN_LOG_FOLDER.equals(each.getText()))
                        .findFirst().orElseThrow(() -> new AssertionError("Help has no Open Log Folder item"));
                assertThat(item.getAccelerator()).as("no shortcut").isNull();
                return item;
            } finally {
                preferences.removeNode();
            }
        });
    }

    private static void fireOnFx(MenuItem item) throws Exception {
        runOnFx(item::fire);
    }
}
