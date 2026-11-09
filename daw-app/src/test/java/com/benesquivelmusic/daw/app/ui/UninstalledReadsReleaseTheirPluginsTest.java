package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.LevelInsertFixture;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveHeader;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.plugin.LoadedPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginIdentity;
import com.benesquivelmusic.daw.core.plugin.PluginResolution;
import com.benesquivelmusic.daw.core.plugin.PluginResolver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.snapshot.SnapshotKind;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 334 (critique J11; Java review C2) — a project the application reads
 * and then does not install releases the plugins its read built: the gated
 * open's read, when the open is refused after it; the archive restore's own
 * read, once it is written back as {@code project.daw}; a snapshot restore's
 * read, when the restore fails before its install. A project the application
 * installed keeps its plugins, even when the open fails after the install.
 * Each read here builds a Level plugin for the saved JAR insert through a
 * resolver that counts the plugins it built and the ones released.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class UninstalledReadsReleaseTheirPluginsTest {

    /** One track whose strip holds the Level JAR plugin. */
    private static final String SONG = """
            <daw-project version="%d">
              <metadata><name>Song</name></metadata>
              <audio-format sample-rate="48000.0" channels="2" bit-depth="24" buffer-size="256"/>
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000a1" name="Vox" type="AUDIO"/>
              </tracks>
              <mixer>
                <master volume="1.0"/>
                <channels>
                  <channel volume="1.0"><inserts><insert bypassed="false" expensive="false" name="Level" plugin-id="test.level" plugin-source="jar" plugin-version="1.0"/></inserts></channel>
                </channels>
                <return-buses/>
              </mixer>
            </daw-project>
            """.formatted(MigrationRegistry.CURRENT_VERSION);

    /** The bound of every wait for a release, which runs on a retirement thread. */
    private static final long RELEASE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10);

    @TempDir
    Path workspace;

    @Test
    void aGatedOpenRefusedAfterItsReadReleasesThePluginsTheReadBuilt() throws Exception {
        CountingResolver resolver = new CountingResolver();
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false), null,
                new FxDispatcher(), readerThrough(resolver))) {
            Path incoming = projectFolderHolding("Incoming", SONG);
            DawProject original = fixture.project.get();
            AtomicBoolean recording = new AtomicBoolean();
            // A recording starts while the open reads: after its checks before the read, before its install.
            resolver.whileResolving = () -> recording.set(true);
            runOnFx(() -> {
                fixture.lifecycle().setRecordingInFlightCheck(recording::get);
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { },
                        _ -> { throw new AssertionError("a refused open opened a journal"); });
                assertThat(fixture.lifecycle().loadProjectFromPath(incoming)).isTrue();
            });
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "the refused open ended");

            assertThat(resolver.built).as("fixture: the open's read built the plugin").hasValue(1);
            assertThat(fixture.project.get()).as("the refused open installed nothing").isSameAs(original);
            assertThat(fixture.notifications()).as("fixture: the open was refused")
                    .anySatisfy(entry -> assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING));
            assertReleased(resolver, 1, "the refused open's read released the plugin it built, once");
        }
    }

    @ParameterizedTest(name = "the journal fails to open: {0}")
    @ValueSource(booleans = {false, true})
    void aGatedOpenThatInstalledItsProjectKeepsThatProjectsPlugins(boolean journalOpenFails) throws Exception {
        CountingResolver resolver = new CountingResolver();
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false), null,
                new FxDispatcher(), readerThrough(resolver))) {
            Path incoming = projectFolderHolding("Incoming", SONG);
            DawProject original = fixture.project.get();
            runOnFx(() -> {
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { }, _ -> {
                    if (journalOpenFails) {
                        throw new IllegalStateException("journal unavailable");
                    }
                });
                assertThat(fixture.lifecycle().loadProjectFromPath(incoming)).isTrue();
            });
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "the open ended");
            // The FX steps the open's worker posted after it ended have run.
            runOnFx(() -> { });
            runOnFx(() -> { });

            DawProject installed = fixture.project.get();
            try {
                assertThat(installed).as("fixture: the open installed the project it read").isNotSameAs(original);
                MixerChannel vox = installed.getMixerChannelForTrack(installed.getTracks().getFirst());
                assertThat(vox.getInsertSlots()).as("the installed project keeps its plugin insert").singleElement()
                        .extracting(InsertSlot::getPlugin).isInstanceOf(LevelInsertFixture.class);
                assertThat(vox.getEffectsChain().isRetired()).as("its chain was not retired").isFalse();
                assertThat(resolver.built).hasValue(1);
                assertThat(resolver.released).as("nothing released the installed project's plugin").hasValue(0);
            } finally {
                installed.disposeInsertsWhenQuiescent().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void anArchiveRestoreReleasesThePluginsItsOwnReadBuiltAndTheReopenKeepsItsOwn() throws Exception {
        CountingResolver archiveRead = new CountingResolver();
        CountingResolver reopen = new CountingResolver();
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        manager.setPluginResolver(reopen);
        OpenProjectFixture fixture = new OpenProjectFixture(workspace, manager,
                new ProjectArchiver(new ProjectSerializer(), readerThrough(archiveRead), null));
        try {
            Path archive = archiveHolding("Song", SONG);
            Path destination = Files.createDirectories(workspace.resolve("restored")).resolve("Song-20261108-120000");

            // The worker half runs off the FX thread, as the restore's virtual thread runs it,
            // and posts the FX half, the reopen; the barrier lets it run.
            fixture.lifecycle().restoreArchive(archive, destination, () -> { });
            runOnFx(() -> { });

            DawProject opened = fixture.project.get();
            MixerChannel vox = opened.getMixerChannelForTrack(opened.getTracks().getFirst());
            assertThat(vox.getInsertSlots()).as("fixture: the reopen loaded the plugin").singleElement()
                    .extracting(InsertSlot::getPlugin).isInstanceOf(LevelInsertFixture.class);
            assertThat(archiveRead.built).as("fixture: the archive's own read built the plugin").hasValue(1);
            assertReleased(archiveRead, 1, "the archive's read, written back and dropped, released its plugin once");
            assertThat(reopen.built).hasValue(1);
            assertThat(reopen.released).as("the reopened, installed project keeps its plugin").hasValue(0);
            assertThat(vox.getEffectsChain().isRetired()).isFalse();
        } finally {
            try {
                fixture.project.get().disposeInsertsWhenQuiescent().get(10, TimeUnit.SECONDS);
            } finally {
                fixture.close();
            }
        }
    }

    @Test
    void aSnapshotRestoreThatFailsBeforeItsInstallReleasesThePluginsItsReadBuilt() throws Exception {
        CountingResolver resolver = new CountingResolver();
        OpenProjectFixture fixture = new OpenProjectFixture(workspace);
        AtomicInteger errorsShown = new AtomicInteger();
        ListChangeListener<Window> answerTheError = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && "Snapshot".equals(stage.getTitle())) {
                        errorsShown.incrementAndGet();
                        Platform.runLater(() -> pressOk(stage));
                    }
                }
            }
        };
        try {
            SnapshotsController snapshots = new SnapshotsController(new SnapshotBrowserService(),
                    new CheckpointManager(AutoSaveConfig.DEFAULT), fixture.projectManager,
                    new SnapshotsController.Deps(() -> null, fixture.projectManager::getCurrentDawProject,
                            Runnable::run, (restored, label, missingAssets, unreadItems) -> {
                                throw new IllegalStateException("the install failed before it began");
                            }));
            snapshots.setPluginResolver(resolver);
            SnapshotEntry entry = new SnapshotEntry("edited", Instant.parse("2026-11-08T12:00:00Z"),
                    SnapshotKind.NAMED_SNAPSHOT, "Before the edit", null, () -> SONG);

            runOnFx(() -> {
                Window.getWindows().addListener(answerTheError);
                snapshots.restoreEntry(entry);
            });

            assertThat(errorsShown).as("fixture: the restore failed and said so").hasValue(1);
            assertThat(resolver.built).as("fixture: the restore's read built the plugin").hasValue(1);
            assertReleased(resolver, 1, "the failed restore's read released the plugin it built, once");
        } finally {
            runOnFx(() -> Window.getWindows().removeListener(answerTheError));
            fixture.close();
        }
    }

    /** Waits, bounded, until {@code resolver} saw {@code expected} releases, then asserts exactly that many. */
    private static void assertReleased(CountingResolver resolver, int expected, String what) throws Exception {
        long deadline = System.nanoTime() + RELEASE_WAIT_NANOS;
        while (resolver.released.get() < expected && System.nanoTime() - deadline < 0) {
            Thread.sleep(10);
        }
        assertThat(resolver.released).as(what).hasValue(expected);
    }

    private static void pressOk(Stage stage) {
        Node root = stage.getScene().getRoot();
        DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
        ((Button) pane.lookupButton(ButtonType.OK)).fire();
    }

    /** A project reader whose plugin identities resolve through {@code resolver}. */
    private static ProjectDeserializer readerThrough(PluginResolver resolver) {
        ProjectDeserializer reader = new ProjectDeserializer();
        reader.setPluginResolver(resolver);
        return reader;
    }

    /** A project folder whose {@code project.daw} holds {@code xml}, made by a manager of its own. */
    private Path projectFolderHolding(String name, String xml) throws IOException {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = other.createProject(name, workspace).projectPath();
        other.closeProject().join();
        Files.writeString(directory.resolve("project.daw"), xml);
        return directory;
    }

    /** A {@code .dawz} whose project document is {@code xml}, its header naming {@code projectName}. */
    private Path archiveHolding(String projectName, String xml) throws IOException {
        Path archive = workspace.resolve(projectName + ".dawz");
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(ArchiveHeader.FILE_NAME));
            zip.write(("projectName=" + projectName + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(ArchiveHeader.PROJECT_DOC_NAME));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }

    /**
     * Builds a Level plugin for every identity it is asked for, after running
     * {@link #whileResolving} on the reading thread, and counts the plugins
     * it built and the ones released.
     */
    private static final class CountingResolver implements PluginResolver {
        final AtomicInteger built = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        volatile Runnable whileResolving = () -> { };

        @Override
        public PluginResolution resolve(PluginIdentity identity, PluginContext context) {
            whileResolving.run();
            LevelInsertFixture plugin = new LevelInsertFixture();
            plugin.initialize(context);
            built.incrementAndGet();
            return new PluginResolution.Resolved(new LoadedPlugin(plugin, released::incrementAndGet));
        }
    }
}
