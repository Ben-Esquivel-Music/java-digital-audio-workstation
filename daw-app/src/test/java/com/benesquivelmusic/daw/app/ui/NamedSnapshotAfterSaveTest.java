package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.snapshot.SnapshotStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, the named snapshot (ruling G13 #10): it saves first through the
 * one save request and copies {@code project.daw} only once that save ended
 * {@code Saved}, on the project manager's save writer behind it — so the
 * snapshot holds exactly what the save wrote — and a failed save never
 * snapshots the old head under the user's name. A door asked while the copy
 * is still pending waits for it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NamedSnapshotAfterSaveTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        runOnFx(() -> fixture.project.get().createAudioTrack("Bass"));
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void theSnapshotCopiesTheHeadItsSaveWrote() throws Exception {
        CountDownLatch created = new CountDownLatch(1);
        runOnFx(() -> {
            fixture.lifecycle().setNamedSnapshotCreatedHook(created::countDown);
            fixture.lifecycle().createNamedSnapshot("Mix A");
        });

        assertThat(created.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the snapshot was made").isTrue();
        List<Path> snapshots = snapshots();
        assertThat(snapshots).singleElement().satisfies(snapshot ->
                assertThat(Files.readString(snapshot)).as("exactly the head the save wrote")
                        .isEqualTo(Files.readString(fixture.songA.resolve("project.daw")))
                        .contains("Bass"));
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .containsExactly("Snapshot saved: Mix A");
        assertThat(fixture.operationNames()).isEmpty();
    }

    @Test
    void aFailedSaveTakesNoSnapshot() throws Exception {
        fixture.publishes.failNext(SaveFlowFixture::isHead, new IOException("injected head write failure"));
        String before = Files.readString(fixture.songA.resolve("project.daw"));

        runOnFx(() -> fixture.lifecycle().createNamedSnapshot("Mix B"));
        fixture.settle();

        assertThat(snapshots()).as("no snapshot of the old head under the user's name").isEmpty();
        assertThat(fixture.notifications()).extracting(NotificationEntry::message).singleElement()
                .satisfies(message -> assertThat(message).startsWith("Save failed: injected head write failure."));
        assertThat(Files.readString(fixture.songA.resolve("project.daw"))).isEqualTo(before);
    }

    /**
     * Ruling K4: the copy is queued right behind the snapshot's own save, in
     * the FX step that named it, so a Ctrl+S captured while that save is
     * still being written lands after the copy: the snapshot holds the
     * project as it was when the user named it, and the head ends as the
     * later save.
     */
    @Test
    void aSaveCapturedWhileTheSnapshotsSaveIsHeldLandsAfterTheCopy() throws Exception {
        CountDownLatch created = new CountDownLatch(1);
        SaveFlowFixture.Publishes.Hold snapshotsSave = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        runOnFx(() -> {
            fixture.lifecycle().setNamedSnapshotCreatedHook(created::countDown);
            fixture.lifecycle().createNamedSnapshot("Mix D");
        });
        snapshotsSave.awaitHeld();

        runOnFx(() -> {
            fixture.project.get().createAudioTrack("Added after the name");
            fixture.project.get().markDirty();
            fixture.lifecycle().onSaveProject();
        });
        snapshotsSave.release();

        assertThat(created.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the snapshot was made").isTrue();
        SaveOutcomes.awaitIdle(fixture.projectManager);
        assertThat(fixture.publishes.done(SaveFlowFixture::isHead)).as("both saves were written").hasSize(2);
        assertThat(snapshots()).singleElement().satisfies(snapshot ->
                assertThat(Files.readString(snapshot)).as("the project as it was named")
                        .contains("Bass").doesNotContain("Added after the name"));
        assertThat(Files.readString(fixture.songA.resolve("project.daw"))).as("the head is the later save")
                .contains("Added after the name");
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .containsExactly("Snapshot saved: Mix D");
    }

    @Test
    void aDoorAskedBeforeTheCopyIsMadeWaitsForIt() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        runOnFx(() -> fixture.lifecycle().createNamedSnapshot("Mix C"));
        head.awaitHeld();
        CountDownLatch proceeded = onFx(() -> DoorGate.whenItProceeds(fixture.lifecycle()));

        head.release();
        assertThat(proceeded.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(snapshots()).as("the copy was made before the door went ahead").hasSize(1);
    }

    private List<Path> snapshots() throws IOException {
        Path folder = fixture.songA.resolve(SnapshotStore.SNAPSHOTS_DIR);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith("."))
                    .toList();
        }
    }
}
