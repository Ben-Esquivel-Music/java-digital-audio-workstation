package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectCard;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHubView;
import com.benesquivelmusic.daw.app.ui.hub.WelcomeView;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.*;
import com.benesquivelmusic.daw.core.persistence.journal.*;
import com.benesquivelmusic.daw.core.project.DawProject;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(JavaFxToolkitExtension.class)
class EveryOpenDoorRunsRecoveryFirstTest {
    enum Door { FILE, HUB, CONTINUE, ARCHIVE, RECOVER }
    @TempDir Path workspace;

    @ParameterizedTest @ValueSource(strings = {"Cancel", "Inspect events…"})
    void cancelledGatedRecoveryPreservesTheOutgoingModelLockAndLiveJournal(String answer) throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            fixture.save(); fixture.settle();
            DawProject priorModel = fixture.project.get();
            ProjectMetadata priorMetadata = fixture.projectManager.getCurrentProject();
            byte[] priorLock = Files.readAllBytes(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME));
            com.benesquivelmusic.daw.core.event.DefaultEventBus bus = new com.benesquivelmusic.daw.core.event.DefaultEventBus();
            JournalWriter outgoing = new JournalWriter(ProjectContext.forProject(fixture.songA), JournalConfig.DEFAULT, true, null);
            outgoing.start();
            JournalEventRecorder recorder = new JournalEventRecorder(bus, outgoing, java.time.InstantSource.system()).start();
            AtomicInteger closes = new AtomicInteger();
            AtomicInteger welcomes = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Path incoming = fixture.anotherProjectOnDisk("Recover me");
            try (JournalWriter crashed = new JournalWriter(ProjectContext.forProject(incoming), JournalConfig.DEFAULT, true, null)) {
                crashed.start(); crashed.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, Instant.now());
            }
            byte[] priorIncoming = Files.readAllBytes(incoming.resolve("journal/segment-000.bin"));
            ListChangeListener<Window> observer = change -> {
                while (change.next()) for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && ("Recover unsaved work".equals(stage.getTitle())
                            || "Captured events".equals(stage.getTitle()))) {
                        Platform.runLater(() -> {
                            try {
                                Node root = stage.getScene().getRoot();
                                DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
                                String label = "Captured events".equals(stage.getTitle()) ? null : answer;
                                javafx.scene.control.ButtonType choice = pane.getButtonTypes().stream()
                                        .filter(type -> label == null || type.getText().equals(label)).findFirst().orElseThrow();
                                ((Button) pane.lookupButton(choice)).fire();
                            } catch (Throwable thrown) { failure.set(thrown); stage.hide(); }
                        });
                    }
                }
            };
            try {
                runOnFx(() -> {
                    Window.getWindows().addListener(observer);
                    fixture.lifecycle().setWelcomePresenter(_ -> welcomes.incrementAndGet());
                    fixture.lifecycle().enableRecoveryGate(() -> true, () -> {
                        closes.incrementAndGet(); recorder.close(); return outgoing::close;
                    }, _ -> { throw new AssertionError("cancelled recovery opened a journal"); });
                    fixture.lifecycle().beginRecovery(incoming);
                });
                fixture.awaitOnFx(() -> welcomes.get() == 1 && !fixture.lifecycle().isProjectInstallationPending(), "cancelled recovery finished");
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertThat(closes).hasValue(0);
                assertThat(fixture.project.get()).isSameAs(priorModel);
                assertThat(fixture.projectManager.getCurrentProject()).isSameAs(priorMetadata);
                assertThat(Files.readAllBytes(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME))).isEqualTo(priorLock);
                assertThat(Files.readAllBytes(incoming.resolve("journal/segment-000.bin"))).isEqualTo(priorIncoming);
                assertThat(incoming.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
                bus.publish(new com.benesquivelmusic.daw.sdk.event.MixerEvent.GainChanged(UUID.randomUUID(), Instant.now()));
                outgoing.forceFlush();
                assertThat(JournalState.records(ProjectContext.forProject(fixture.songA)))
                        .anyMatch(record -> !record.isControl()).noneMatch(JournalRecord::isSessionSeal);
            } finally {
                runOnFx(() -> Window.getWindows().removeListener(observer));
                recorder.close(); outgoing.close(); bus.close();
            }
        }
    }

    @Test void reopeningTheActiveFolderNeverTreatsItsLiveJournalAsACrash() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            AtomicInteger workers = new AtomicInteger();
            runOnFx(() -> {
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> { throw new AssertionError("retired active project"); },
                        _ -> { throw new AssertionError("opened another journal"); });
                fixture.lifecycle().setBeforeRecoveryWork(_ -> workers.incrementAndGet());
                assertThat(fixture.lifecycle().loadProjectFromPath(fixture.songA)).isFalse();
            });
            assertThat(workers).hasValue(0);
            assertThat(fixture.projectManager.getCurrentProject().projectPath()).isEqualTo(fixture.songA);
            assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                    .anyMatch(message -> message.startsWith("Project is already open:"));
        }
    }

    @Test void aRefusedSecondDirectLoadCannotInvalidateTheWorkerThatStillOwnsInstallation() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            Path first = fixture.anotherProjectOnDisk("First");
            Path second = fixture.anotherProjectOnDisk("Second");
            java.util.concurrent.CountDownLatch scanning = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            AtomicInteger journalOpens = new AtomicInteger();
            runOnFx(() -> {
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { }, _ -> journalOpens.incrementAndGet());
                fixture.lifecycle().setBeforeRecoveryWork(_ -> {
                    scanning.countDown();
                    try { assertThat(release.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                });
                assertThat(fixture.lifecycle().loadProjectFromPath(first)).isTrue();
            });
            try {
                assertThat(scanning.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                runOnFx(() -> assertThat(fixture.lifecycle().loadProjectFromPath(second)).isFalse());
            } finally { release.countDown(); }
            fixture.awaitOnFx(() -> journalOpens.get() == 1 && !fixture.lifecycle().isProjectInstallationPending(), "first load retained ownership");
            assertThat(fixture.projectManager.getCurrentProject().projectPath()).isEqualTo(first);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void replayOrAtomicCommitFailureOpensTheLastSavedHeadReadOnlyAndPreservesEvidence(boolean commitFailure) throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            Path incoming = fixture.anotherProjectOnDisk("Recovery failure");
            try (JournalWriter crashed = new JournalWriter(ProjectContext.forProject(incoming), JournalConfig.DEFAULT, true, null)) {
                crashed.start(); crashed.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, Instant.now());
            }
            byte[] records = Files.readAllBytes(incoming.resolve("journal/segment-000.bin"));
            String savedHead = Files.readString(incoming.resolve("project.daw"));
            if (!commitFailure) Files.writeString(incoming.resolve("journal").resolve(JournalState.BASE_FILE), "invalid session baseline");
            AtomicReference<Throwable> answerFailure = new AtomicReference<>();
            AtomicInteger journalOpens = new AtomicInteger();
            ListChangeListener<Window> observer = change -> {
                while (change.next()) for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && "Recover unsaved work".equals(stage.getTitle())) {
                        Platform.runLater(() -> {
                            try {
                                Node root = stage.getScene().getRoot();
                                DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
                                javafx.scene.control.ButtonType choice = pane.getButtonTypes().stream()
                                        .filter(type -> "Recover everything".equals(type.getText())).findFirst().orElseThrow();
                                ((Button) pane.lookupButton(choice)).fire();
                            } catch (Throwable failure) { answerFailure.set(failure); stage.hide(); }
                        });
                    }
                }
            };
            try {
                runOnFx(() -> {
                    Window.getWindows().addListener(observer);
                    fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { }, _ -> journalOpens.incrementAndGet());
                    if (commitFailure) fixture.lifecycle().setFileWriter(new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                            (temp, target, options) -> {
                                if (target.getFileName().toString().equals("project.daw")) throw new IOException("atomic head replacement refused");
                                Files.move(temp, target, options);
                            }));
                    fixture.lifecycle().beginRecovery(incoming);
                });
                fixture.awaitOnFx(() -> fixture.projectManager.getCurrentProject() != null
                        && incoming.equals(fixture.projectManager.getCurrentProject().projectPath())
                        && !fixture.lifecycle().isProjectInstallationPending(), "read-only last-head fallback");
                if (answerFailure.get() != null) throw new AssertionError(answerFailure.get());
                assertThat(fixture.projectManager.isReadOnly()).isTrue();
                assertThat(journalOpens).hasValue(0);
                assertThat(Files.readString(incoming.resolve("project.daw"))).isEqualTo(savedHead);
                assertThat(Files.readAllBytes(incoming.resolve("journal/segment-000.bin"))).isEqualTo(records);
                assertThat(fixture.notifications()).anySatisfy(notification -> {
                    assertThat(notification.level()).isEqualTo(NotificationLevel.ERROR);
                    assertThat(notification.message()).contains("read-only", "preserved");
                });
            } finally { runOnFx(() -> Window.getWindows().removeListener(observer)); }
        }
    }

    @ParameterizedTest @EnumSource(Door.class)
    void crashEvidenceIsUntouchedWhenTheRealDoorPresentsItsRecoveryDialog(Door door) throws Exception {
        Preferences preferences = Preferences.userRoot().node("daw-story332-" + UUID.randomUUID());
        RecentProjectsStore recents = new RecentProjectsStore(preferences);
        AtomicInteger dialogs = new AtomicInteger();
        AtomicInteger journalOpens = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Path> target = new AtomicReference<>();
        AtomicReference<byte[]> original = new AtomicReference<>();
        AtomicReference<JournalWriter> liveJournal = new AtomicReference<>();
        ListChangeListener<Window> observer = change -> {
            while (change.next()) for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && "Recover unsaved work".equals(stage.getTitle())) {
                    Platform.runLater(() -> {
                        try {
                            dialogs.incrementAndGet();
                            assertThat(journalOpens).hasValue(0);
                            assertThat(Files.readAllBytes(target.get().resolve("journal/segment-000.bin"))).isEqualTo(original.get());
                            try (java.util.stream.Stream<Path> segments = Files.list(target.get().resolve("journal"))) {
                                assertThat(segments.filter(path -> path.getFileName().toString().startsWith("segment-")).toList())
                                        .hasSize(1);
                            }
                            DialogPane pane = (DialogPane) stage.getScene().getRoot();
                            Button button = pane.getButtonTypes().stream().map(pane::lookupButton)
                                    .filter(node -> node instanceof Button value && "Restore checkpoint only".equals(value.getText()))
                                    .map(Button.class::cast).findFirst().orElseThrow();
                            button.fire();
                        } catch (Throwable thrown) { failure.set(thrown); stage.hide(); }
                    });
                }
            }
        };
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT, recents)) {
            Path incoming = Files.createDirectories(workspace.resolve("Incoming"));
            Files.writeString(incoming.resolve("project.daw"), new ProjectSerializer().serialize(
                    new DawProject("Incoming", AudioFormat.CD_QUALITY)));
            recents.addRecentProject(incoming);
            target.set(incoming);
            runOnFx(() -> {
                Window.getWindows().addListener(observer);
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { }, path -> {
                    try {
                        assertThat(dialogs).hasValue(1);
                        assertThat(JournalState.records(ProjectContext.forProject(path))).isEmpty();
                        JournalWriter writer = new JournalWriter(ProjectContext.forProject(path), JournalConfig.DEFAULT, true, null);
                        writer.start();
                        liveJournal.set(writer);
                        journalOpens.incrementAndGet();
                    } catch (IOException problem) { throw new java.io.UncheckedIOException(problem); }
                });
            });
            ProjectCard continueCard = null;
            ProjectHubView hub = null;
            if (door == Door.CONTINUE) {
                WelcomeView welcome = onFx(() -> fixture.lifecycle().buildWelcomeView());
                fixture.awaitOnFx(() -> welcome.continuePaths().contains(incoming), "async Welcome classification");
                continueCard = onFx(() -> welcome.continueCards().get(welcome.continuePaths().indexOf(incoming)));
            } else if (door == Door.HUB) {
                hub = onFx(() -> fixture.lifecycle().buildProjectHub());
            }
            try (JournalWriter crashed = new JournalWriter(ProjectContext.forProject(incoming), JournalConfig.DEFAULT, true, null)) {
                crashed.start();
                crashed.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, Instant.now().minusSeconds(30));
            }
            original.set(Files.readAllBytes(incoming.resolve("journal/segment-000.bin")));
            switch (door) {
                case FILE -> runOnFx(() -> {
                    fixture.lifecycle().setOpenProjectPrompt(() -> incoming);
                    fixture.lifecycle().onOpenProject();
                });
                case HUB -> {
                    ProjectHubView selectedHub = hub;
                    ProjectCard card = onFx(() -> selectedHub.recentCards().stream()
                            .filter(value -> incoming.equals(selectedHub.pathFor(value))).findFirst().orElseThrow());
                    runOnFx(() -> enter(card));
                }
                case CONTINUE -> {
                    ProjectCard selected = continueCard;
                    runOnFx(() -> enter(selected));
                }
                case RECOVER -> runOnFx(() -> fixture.lifecycle().beginRecovery(incoming));
                case ARCHIVE -> {
                    Path archive = fixture.archiveOnDisk("Incoming");
                    addJournal(archive, incoming);
                    Path destination = fixture.restoreDestination("Incoming");
                    target.set(destination);
                    runOnFx(() -> fixture.lifecycle().startArchiveRestore(archive, destination));
                }
            }
            fixture.awaitOnFx(() -> journalOpens.get() > 0 || failure.get() != null, "recovery then journal-open");
            if (failure.get() != null) throw new AssertionError("dialog invariant failed", failure.get());
            fixture.awaitOnFx(() -> fixture.projectManager.getCurrentProject() != null
                    && target.get().equals(fixture.projectManager.getCurrentProject().projectPath())
                    && !fixture.lifecycle().isProjectInstallationPending(), "completed UI installation");
            assertThat(dialogs).hasValue(1);
            assertThat(journalOpens).hasValue(1);
        } finally {
            runOnFx(() -> Window.getWindows().removeListener(observer));
            if (liveJournal.get() != null) liveJournal.get().close();
            preferences.removeNode();
        }
    }

    private static void enter(ProjectCard card) {
        card.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", KeyCode.ENTER,
                false, false, false, false));
    }

    private static void addJournal(Path archive, Path incoming) throws IOException {
        Path rewritten = archive.resolveSibling("with-journal.dawz");
        try (ZipFile original = new ZipFile(archive.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(rewritten))) {
            for (ZipEntry entry : original.stream().toList()) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (!entry.isDirectory()) original.getInputStream(entry).transferTo(output);
                output.closeEntry();
            }
            for (String name : List.of("segment-000.bin", JournalState.BASE_FILE)) {
                output.putNextEntry(new ZipEntry("journal/" + name));
                Files.copy(incoming.resolve("journal").resolve(name), output);
                output.closeEntry();
            }
        }
        Files.move(rewritten, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
