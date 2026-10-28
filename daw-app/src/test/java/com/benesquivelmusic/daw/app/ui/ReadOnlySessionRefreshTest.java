package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.*;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.session.SessionManager;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(JavaFxToolkitExtension.class)
class ReadOnlySessionRefreshTest {
    @TempDir Path workspace;

    @Test void aStalledManifestWriterFinishesBeforeItsWritableLeaseCanRetire() throws Exception {
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        Path folder = createHead("Stalled");
        manager.openProject(folder);
        SessionManager sessions = spy(new SessionManager());
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            manager.getLockManager().verifyHeld();
            return invocation.callRealMethod();
        }).when(sessions).openSession(any(Path.class), any(Instant.class));
        LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        MainController main = main(manager, queued, sessions);
        java.util.concurrent.CompletableFuture<Void> retired = new java.util.concurrent.CompletableFuture<>();
        ProjectLockManager foreign = new ProjectLockManager();
        try {
            refresh(main);
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
            Thread.ofVirtual().start(() -> {
                try { manager.abandonProject(); retired.complete(null); }
                catch (Throwable failure) { retired.completeExceptionally(failure); }
            });
            assertThatThrownBy(() -> retired.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(folder.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
            release.countDown();
            retired.get(15, TimeUnit.SECONDS);
            Runnable stale = queued.poll(15, TimeUnit.SECONDS);
            assertThat(stale).isNotNull();
            Map<Path, String> published = manifestBytes(folder);
            foreign.forceAcquire(folder);
            manager.setLockConflictHandler((_, _) -> LockConflictResolution.OPEN_READ_ONLY);
            manager.openProject(folder);
            refresh(main);
            Runnable current = queued.poll(15, TimeUnit.SECONDS);
            assertThat(current).isNotNull();
            runOnFx(current);
            runOnFx(stale);
            assertThat(manifestBytes(folder)).isEqualTo(published);
            assertThat(field(main, "activeSession")).isNull();
        } finally {
            release.countDown();
            manager.abandonProject();
            foreign.close();
        }
    }

    @Test void theActualMainControllerRefreshDoesNotWriteOrSealTheLockHoldersOldActiveManifest() throws Exception {
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        Path folder = createHead("Read only");
        SessionManager sessions = new SessionManager();
        sessions.openSession(folder, Instant.now().minusSeconds(6 * 3600));
        Map<Path, String> before = manifestBytes(folder);
        ProjectLockManager foreign = new ProjectLockManager();
        foreign.forceAcquire(folder);
        try {
            manager.setLockConflictHandler((_, _) -> LockConflictResolution.OPEN_READ_ONLY);
            manager.openProject(folder);
            LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
            MainController main = main(manager, queued);
            refresh(main);
            Runnable callback = queued.poll(15, TimeUnit.SECONDS);
            assertThat(callback).isNotNull();
            runOnFx(callback);
            assertThat(manifestBytes(folder)).isEqualTo(before);
            assertThat(sessions.history(folder)).hasSize(1).allMatch(session -> session.isActive());
            assertThat(field(main, "activeSession")).isNull();
            assertThat(field(main, "activeSessionProjectDir")).isNull();
        } finally { manager.abandonProject(); foreign.release(); }
    }

    @Test void anEarlierWritableRefreshCannotReattachItsSessionAfterAReadOnlyOpen() throws Exception {
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        Path writable = manager.createProject("Writable", workspace).projectPath();
        LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        MainController main = main(manager, queued);
        refresh(main);
        Runnable stale = queued.poll(15, TimeUnit.SECONDS);
        assertThat(stale).isNotNull();
        manager.abandonProject();
        Path readOnly = createHead("Read only");
        SessionManager sessions = new SessionManager();
        sessions.openSession(readOnly, Instant.now().minusSeconds(6 * 3600));
        Map<Path, String> before = manifestBytes(readOnly);
        ProjectLockManager foreign = new ProjectLockManager();
        foreign.forceAcquire(readOnly);
        try {
            manager.setLockConflictHandler((_, _) -> LockConflictResolution.OPEN_READ_ONLY);
            manager.openProject(readOnly);
            set(main, "project", manager.getCurrentDawProject());
            refresh(main);
            Runnable current = queued.poll(15, TimeUnit.SECONDS);
            assertThat(current).isNotNull();
            runOnFx(current);
            runOnFx(stale);
            assertThat(field(main, "activeSession")).isNull();
            assertThat(field(main, "activeSessionProjectDir")).isNull();
            assertThat(manifestBytes(readOnly)).isEqualTo(before);
            SessionManagerDock dock = (SessionManagerDock) field(main, "sessionManagerDock");
            assertThat(onFx(() -> dock.getSessions().stream().map(session -> session.id()).toList()))
                    .containsExactly(sessions.history(readOnly).getFirst().id());
        } finally { manager.abandonProject(); foreign.release(); }
    }

    private MainController main(ProjectManager manager, LinkedBlockingQueue<Runnable> queued) throws Exception {
        return main(manager, queued, new SessionManager());
    }

    private MainController main(ProjectManager manager, LinkedBlockingQueue<Runnable> queued, SessionManager sessions) throws Exception {
        MainController main = new MainController(sessions);
        FxDispatcher dispatcher = spy(new FxDispatcher());
        doAnswer(invocation -> { queued.add(invocation.getArgument(0)); return null; })
                .when(dispatcher).onFx(any(Runnable.class));
        main.setFxDispatcher(dispatcher);
        set(main, "projectManager", manager);
        set(main, "project", manager.getCurrentDawProject() != null ? manager.getCurrentDawProject()
                : new DawProject("Writable", AudioFormat.CD_QUALITY));
        set(main, "sessionManagerDock", onFx(SessionManagerDock::new));
        return main;
    }
    private Path createHead(String name) throws Exception {
        Path folder = Files.createDirectories(workspace.resolve(name));
        Files.writeString(folder.resolve("project.daw"), new ProjectSerializer().serialize(new DawProject(name, AudioFormat.CD_QUALITY)));
        return folder;
    }
    private void refresh(MainController main) throws Exception {
        Method method = MainController.class.getDeclaredMethod("refreshSessionManager", boolean.class);
        method.setAccessible(true);
        runOnFx(() -> {
            try { method.invoke(main, true); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }
    private Map<Path, String> manifestBytes(Path folder) throws Exception {
        Map<Path, String> result = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> entries = Files.walk(folder.resolve("sessions"))) {
            for (Path file : entries.filter(Files::isRegularFile).sorted().toList()) result.put(file, Files.readString(file));
        }
        return result;
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = MainController.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = MainController.class.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
}
