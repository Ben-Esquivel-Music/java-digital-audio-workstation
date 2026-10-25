package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.analysis.AnalyzerProcessor;
import com.benesquivelmusic.daw.core.analysis.AnalyzerSnapshot;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, File ▸ Create Checkpoint (rulings G8, G13 #9; Persistence
 * Integrity book §4.2): the project is captured and rendered once, on the FX
 * thread — the browser entry holds that document — and the same document is
 * requested as a user checkpoint on the FX thread, so it belongs to the
 * project the user asked it for, and is written off it. A switch right after
 * it never sends that document into the next project's {@code checkpoints/}
 * and leaves the next project's strip alone. A checkpoint that writes nothing
 * is reported every time — an ERROR naming the cause and the checkpoint
 * failure state — and never counts toward the autosave streak.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CreateCheckpointRequestTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void theDocumentIsCapturedOnceOnTheFxThreadAndTheSameDocumentIsWrittenOffIt() throws Exception {
        SnapshotEntry entry = onFx(() -> fixture.snapshots().createCheckpointWithLabel("Before the comp"));
        fixture.settle();
        awaitCheckpointWritten();

        List<SaveFlowFixture.Publish> written = fixture.publishes.done(SaveFlowFixture::isCheckpoint);
        assertThat(written).singleElement().satisfies(publish -> {
            assertThat(publish.thread()).as("written off the FX thread").isNotSameAs(fixture.fxThread());
            assertThat(publish.target().getParent().getParent()).isEqualTo(fixture.songA);
            assertThat(Files.readString(publish.target())).as("the document the browser entry holds")
                    .isEqualTo(entry.loadContent());
        });
        assertThat(onFx(fixture.progress::isLastCheckpointSucceeded)).isTrue();
        assertThat(onFx(fixture.progress::getLastCheckpointFile)).isEqualTo(written.getFirst().target());
        assertThat(fixture.notifications()).isEmpty();
    }

    @Test
    void aCreateCheckpointFollowedAtOnceByASwitchNeverWritesIntoTheNextProjectNorTouchesItsStrip()
            throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        String songADocument = new com.benesquivelmusic.daw.core.persistence.ProjectSerializer()
                .serialize(fixture.project.get());

        runOnFx(() -> {
            fixture.snapshots().createCheckpointWithLabel("Right before the switch");
            assertThat(fixture.lifecycle().loadProjectFromPath(songB)).isTrue();
        });
        fixture.settle();
        runOnFx(() -> { });
        fixture.pulse();

        List<Path> songACheckpoints = SaveFlowFixture.checkpointFiles(fixture.songA);
        assertThat(songACheckpoints).as("written into the project it was asked for, or dropped with it")
                .hasSizeLessThanOrEqualTo(1);
        for (Path checkpoint : songACheckpoints) {
            assertThat(Files.readString(checkpoint)).isEqualTo(songADocument);
        }
        for (Path checkpoint : SaveFlowFixture.checkpointFiles(songB)) {
            assertThat(Files.readString(checkpoint)).as("never Song A's document in Song B's folder")
                    .doesNotContain("Song A");
        }
        assertThat(onFx(fixture.progress::isLastCheckpointSucceeded)).as("Song B's strip is untouched").isTrue();
        assertThat(onFx(fixture.progress::getCheckpointFailureCause)).isNull();
        Path songBNewest = SaveFlowFixture.checkpointFiles(songB).getLast();
        fixture.awaitOnFx(() -> songBNewest.equals(fixture.progress.getLastCheckpointFile()),
                "Song B's newest checkpoint on disk as its last good one (ruling K1)");
        if (songACheckpoints.isEmpty()) {
            assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                    .as("a dropped checkpoint is still said to have failed")
                    .anyMatch(message -> message.startsWith("Checkpoint failed: "));
        }
    }

    @Test
    void aCheckpointThatFailsIsReportedEveryTimeAndDoesNotCountTowardTheStreak() throws Exception {
        IOException first = new IOException("injected checkpoint failure 1");
        IOException second = new IOException("injected checkpoint failure 2");
        fixture.publishes.failNext(SaveFlowFixture::isCheckpoint, first);
        fixture.publishes.failNext(SaveFlowFixture::isCheckpoint, second);

        runOnFx(() -> fixture.snapshots().createCheckpointWithLabel("One"));
        awaitNotifications(1);
        runOnFx(() -> fixture.snapshots().createCheckpointWithLabel("Two"));
        awaitNotifications(2);
        fixture.pulse();

        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .as("an ERROR each time, never the autosave WARNING")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR,
                                "Checkpoint failed: injected checkpoint failure 1"),
                        org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR,
                                "Checkpoint failed: injected checkpoint failure 2"));
        assertThat(onFx(fixture.progress::isLastCheckpointSucceeded)).as("the checkpoint failure state").isFalse();
        assertThat(onFx(fixture.progress::getCheckpointFailureCause)).isSameAs(second);
        assertThat(onFx(fixture.progress::isLastSaveSucceeded)).as("not the head's").isTrue();
        assertThat(SaveFlowFixture.checkpointFiles(fixture.songA)).isEmpty();
    }

    @Test
    void aCaptureThatThrowsWritesNothingAndIsReported() throws Exception {
        FailingAnalyzer analyzer = new FailingAnalyzer();
        runOnFx(() -> {
            DawProject project = fixture.project.get();
            Track vox = project.getTracks().getFirst();
            project.getMixerChannelForTrack(vox).addInsert(new InsertSlot(
                    "Analyzer", new LiveAnalyzerPlugin.TransparentProcessor(), null, analyzer));
            analyzer.armed = true;
        });

        SnapshotEntry entry = onFx(() -> fixture.snapshots().createCheckpointWithLabel("Broken"));
        runOnFx(() -> { });
        fixture.pulse();

        assertThat(entry).as("nothing to put in the browser").isNull();
        assertThat(analyzer.threads).as("the capture ran on the FX thread").containsOnly(fixture.fxThread());
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .containsExactly("Checkpoint failed: the analyzer broke");
        assertThat(onFx(fixture.progress::isLastCheckpointSucceeded)).isFalse();
        assertThat(fixture.publishes.done()).as("nothing was written").isEmpty();
    }

    @Test
    void aCaptureThatThrowsAnErrorWritesNothingAndIsReported() throws Exception {
        FailingAnalyzer analyzer = new FailingAnalyzer();
        AssertionError broken = new AssertionError("the analyzer's state is corrupt");
        runOnFx(() -> {
            DawProject project = fixture.project.get();
            Track vox = project.getTracks().getFirst();
            project.getMixerChannelForTrack(vox).addInsert(new InsertSlot(
                    "Analyzer", new LiveAnalyzerPlugin.TransparentProcessor(), null, analyzer));
            analyzer.error = broken;
            analyzer.armed = true;
        });

        SnapshotEntry entry = onFx(() -> fixture.snapshots().createCheckpointWithLabel("Broken"));
        runOnFx(() -> { });
        fixture.pulse();

        assertThat(entry).as("nothing to put in the browser").isNull();
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .as("an Error is reported like any failure: never silent")
                .containsExactly("Checkpoint failed: the analyzer's state is corrupt");
        assertThat(onFx(fixture.progress::isLastCheckpointSucceeded)).isFalse();
        assertThat(onFx(fixture.progress::getCheckpointFailureCause)).isSameAs(broken);
        assertThat(fixture.publishes.done()).as("nothing was written").isEmpty();
    }

    private void awaitCheckpointWritten() throws Exception {
        fixture.awaitOnFx(() -> fixture.progress.getLastCheckpointFile() != null, "the Written event");
    }

    private void awaitNotifications(int count) throws Exception {
        fixture.awaitOnFx(() -> fixture.shown.getEntries().size() >= count, count + " notifications");
    }

    /**
     * An analyzer insert whose descriptor read — made by the capture — fails
     * once armed, with {@link #error} when one is set, and records its thread.
     */
    private static final class FailingAnalyzer implements LiveAnalyzerPlugin {

        private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
                "test.failing-analyzer", "Failing Analyzer", "1.0", "Test", PluginType.ANALYZER);

        volatile boolean armed;
        volatile Error error;
        final List<Thread> threads = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public PluginDescriptor getDescriptor() {
            if (armed) {
                threads.add(Thread.currentThread());
                if (error != null) {
                    throw error;
                }
                throw new IllegalStateException("the analyzer broke");
            }
            return DESCRIPTOR;
        }

        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
        @Override public AnalyzerProcessor createAnalysisConsumer(Consumer<AnalyzerSnapshot> publish) { return null; }
        @Override public void acceptAnalysis(AnalyzerSnapshot snapshot) { }
        @Override public boolean isActive() { return true; }
    }
}
