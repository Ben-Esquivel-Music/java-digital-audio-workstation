package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.plugin.PluginTestJars;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.LevelInsertFixture;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EffectsChain;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OfflineCause;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.plugin.LoadedPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.plugin.PluginResolution;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.snapshot.SnapshotKind;

import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 334 (studio-engineer ruling R1, finding G14's snapshot door) — a
 * snapshot restore reads on the JavaFX application thread, so it looks an
 * external plugin up in the installed-plugin registry and never loads one: an
 * installed JAR plugin's insert restores offline at its place, saying a
 * snapshot restore does not load plugins, counted once in the restore's
 * announcement, and nothing is constructed; saving and reopening loads it with
 * its saved state, and it sounds as it did before. A plugin that is not
 * installed restores offline as not installed. Through this door too the
 * chain never shortens, and the offline insert is written back as saved. A
 * snapshot read only to be compared releases what its read built (critique
 * J11).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SnapshotRestoreOfPluginInsertsTest {

    /** The JAR plugin's saved element: id-keyed level 0.25 (the plugin's default is 0.5). */
    private static final String LEVEL = "<insert bypassed=\"false\" expensive=\"false\" name=\"Level\""
            + " plugin-id=\"test.level\" plugin-source=\"jar\" plugin-version=\"1.0\">"
            + "<parameter id=\"0\" value=\"0.25\"/></insert>";
    private static final String COMPRESSOR =
            "<insert bypassed=\"false\" effect-type=\"COMPRESSOR\" expensive=\"false\" name=\"Compressor\"/>";
    private static final String DELAY =
            "<insert bypassed=\"false\" effect-type=\"DELAY\" expensive=\"false\" name=\"Delay\"/>";
    private static final String SONG = """
            <daw-project version="%d">
              <metadata><name>Song</name></metadata>
              <audio-format sample-rate="48000.0" channels="2" bit-depth="24" buffer-size="256"/>
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000a1" name="Drums" type="AUDIO"/>
                <track id="00000000-0000-0000-0000-0000000000a2" name="Vox" type="AUDIO"/>
              </tracks>
              <mixer>
                <master volume="1.0"/>
                <channels>
                  <channel volume="1.0"/>
                  <channel volume="1.0"><inserts>%s%s%s</inserts></channel>
                </channels>
                <return-buses/>
              </mixer>
            </daw-project>
            """.formatted(MigrationRegistry.CURRENT_VERSION, COMPRESSOR, LEVEL, DELAY);
    private static final String LABEL = "Before the edit";
    private static final int BLOCK_FRAMES = 256;

    @TempDir
    Path workspace;

    private final PluginRegistry registry = new PluginRegistry();
    private final AtomicReference<List<UnreadProjectItem>> restoredItems = new AtomicReference<>();
    /** The projects the plain door opened, which nothing installs: released after the test. */
    private final List<DawProject> opened = new ArrayList<>();
    private OpenProjectFixture fixture;
    private Path directory;

    @BeforeEach
    void writeTheSong() throws Exception {
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        manager.setPluginResolver(registry);
        fixture = new OpenProjectFixture(workspace, manager);
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        directory = other.createProject("Song", workspace).projectPath();
        other.closeProject().join();
        Files.writeString(directory.resolve("project.daw"), SONG);
    }

    @AfterEach
    void closeTheSong() throws Exception {
        try {
            DawProject open = fixture.project.get();
            fixture.close();
            open.disposeInsertsWhenQuiescent().join();
            for (DawProject project : opened) {
                project.disposeInsertsWhenQuiescent().join();
            }
        } finally {
            registry.disposeAll();
        }
    }

    /** Opens the song through the project manager, its plugins resolved through the registry. */
    private void openThePlainWay() throws IOException {
        fixture.projectManager.openProject(directory);
        opened.add(fixture.projectManager.getCurrentDawProject());
    }

    @Test
    void anInstalledPluginRestoresOfflineInPlaceSayingASnapshotRestoreDoesNotLoadIt() throws Exception {
        install();
        openThePlainWay();
        LevelInsertFixture.CONSTRUCTED_ON.clear();

        restoreTheSnapshot();

        List<InsertSlot> chain = voxInserts(fixture.project.get());
        assertThat(chain).extracting(InsertSlot::getName).containsExactly("Compressor", "Level", "Delay");
        assertThat(chain.get(1).offline()).as("offline at its place").hasValueSatisfying(offline ->
                assertThat(offline.cause()).isInstanceOf(OfflineCause.NotLoadedBySnapshotRestore.class));
        assertThat(restoredItems.get()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(UnreadProjectItem.Kind.INSERT_NOT_LOADED);
            assertThat(item.location()).isEqualTo(new UnreadProjectItem.OnTrack(1, "Vox"));
            assertThat(item.detail()).isEqualTo("JAR plugin 'test.level' version '1.0' was not loaded, because"
                    + " restoring a snapshot does not load plugins");
        });
        assertThat(fixture.notifications()).as("one clause").singleElement().satisfies(entry ->
                assertThat(entry.message()).isEqualTo("Restored snapshot: " + LABEL
                        + " — 1 insert not loaded (bypassed, kept as saved)"));
    }

    @Test
    void theSnapshotRestoreConstructsNoPlugin() throws Exception {
        install();
        openThePlainWay();
        assertThat(voxInserts(fixture.projectManager.getCurrentDawProject()).get(1).getPlugin())
                .as("fixture: the plain open loads the installed plugin")
                .isInstanceOf(LevelInsertFixture.class);
        LevelInsertFixture.CONSTRUCTED_ON.clear();

        restoreTheSnapshot();

        assertThat(LevelInsertFixture.CONSTRUCTED_ON).as("the restore constructed no plugin, on any thread")
                .isEmpty();
    }

    @Test
    void aSaveAndReopenLoadsThePluginWithItsSavedStateAndItSoundsAsBefore() throws Exception {
        install();
        openThePlainWay();
        DawProject before = opened.getFirst();
        float[][] beforeRender = render(voxChannel(before), before.getFormat());
        restoreTheSnapshot();

        SaveOutcomes.saved(onFx(() -> fixture.projectManager.save(fixture.project.get())));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(directory))).isTrue();

        DawProject reopened = fixture.project.get();
        InsertSlot level = voxInserts(reopened).get(1);
        assertThat(level.offline()).as("the reopen loads it").isEmpty();
        assertThat(level.getPlugin()).isInstanceOfSatisfying(LevelInsertFixture.class, plugin ->
                assertThat(plugin.level()).as("with its saved state").isEqualTo(0.25));
        assertThat(fixture.projectManager.getLastUnreadItems()).isEmpty();
        assertThat(render(voxChannel(reopened), reopened.getFormat()))
                .as("matched output: the strip sounds as it did before the snapshot round trip")
                .isDeepEqualTo(beforeRender);

        SaveOutcomes.saved(onFx(() -> fixture.projectManager.save(fixture.project.get())));
        assertThat(backupsIn(directory)).as("nothing was degraded: no backup").isEmpty();
    }

    @Test
    void anUninstalledPluginRestoresOfflineAsNotInstalledAndTheChainKeepsItsLength() throws Exception {
        openThePlainWay();

        restoreTheSnapshot();

        List<InsertSlot> chain = voxInserts(fixture.project.get());
        assertThat(chain).as("G14: the snapshot door never shortens the chain").extracting(InsertSlot::getName)
                .containsExactly("Compressor", "Level", "Delay");
        assertThat(chain.get(1).offline()).hasValueSatisfying(offline ->
                assertThat(offline.cause()).isInstanceOf(OfflineCause.NotInstalled.class));
        assertThat(restoredItems.get()).singleElement().satisfies(item ->
                assertThat(item.detail()).isEqualTo("JAR plugin 'test.level' version '1.0' is not installed"));

        SaveOutcomes.saved(onFx(() -> fixture.projectManager.save(fixture.project.get())));
        assertThat(canonical(insertNamed(Files.readString(directory.resolve("project.daw")), "Level")))
                .as("the next save writes the offline insert as saved")
                .isEqualTo(canonical(insertNamed(SONG, "Level")));
    }

    /**
     * Critique J11 — a compared snapshot's read builds its inserts only to be
     * compared: nothing installs that project, so its plugins are released.
     * (The resolver here constructs, to make a plugin to release; the
     * application's never does on this door.)
     */
    @Test
    void aComparedSnapshotReleasesThePluginsItsReadBuilt() throws Exception {
        openThePlainWay();
        AtomicInteger releases = new AtomicInteger();
        SnapshotsController snapshots = snapshotsController();
        snapshots.setPluginResolver((identity, context) -> {
            LevelInsertFixture plugin = new LevelInsertFixture();
            plugin.initialize(context);
            return new PluginResolution.Resolved(new LoadedPlugin(plugin, releases::incrementAndGet));
        });
        try {
            runOnFx(() -> snapshots.compareEntry(snapshotOfTheSong()));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (releases.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(releases).as("the compared project's plugin is released once").hasValue(1);
        } finally {
            runOnFx(() -> {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window instanceof Stage stage && stage.getTitle() != null
                            && stage.getTitle().startsWith("Compare with current")) {
                        stage.close();
                    }
                }
            });
        }
    }

    private void install() throws Exception {
        Path downloads = Files.createDirectories(workspace.resolve("downloads"));
        PluginTestJars.install(registry, PluginTestJars.buildJar(downloads, "level.jar",
                List.of(PluginTestJars.manifest(LevelInsertFixture.class)), List.of(LevelInsertFixture.class)),
                LevelInsertFixture.class);
    }

    /** Restores {@link #SONG} as a snapshot of the open project, as the snapshot browser does, on the FX thread. */
    private void restoreTheSnapshot() throws Exception {
        SnapshotsController snapshots = snapshotsController();
        snapshots.setPluginResolver(registry.withoutLoading());
        SnapshotEntry entry = snapshotOfTheSong();

        runOnFx(() -> snapshots.restoreEntry(entry));
        assertThat(restoredItems.get()).as("the restore installed its project").isNotNull();
    }

    /** The snapshot browser's controller over the fixture, whose restore installs as the application does. */
    private SnapshotsController snapshotsController() {
        return new SnapshotsController(new SnapshotBrowserService(),
                new CheckpointManager(AutoSaveConfig.DEFAULT), fixture.projectManager,
                new SnapshotsController.Deps(() -> null, fixture.projectManager::getCurrentDawProject,
                        Runnable::run, (restored, label, missingAssets, unreadItems) -> {
                            restoredItems.set(List.copyOf(unreadItems));
                            fixture.lifecycle().installRestoredSnapshot(restored, label, missingAssets, unreadItems);
                        }));
    }

    private static SnapshotEntry snapshotOfTheSong() {
        return new SnapshotEntry("edited", Instant.parse("2026-11-08T12:00:00Z"), SnapshotKind.NAMED_SNAPSHOT,
                LABEL, null, () -> SONG);
    }

    private static MixerChannel voxChannel(DawProject project) {
        return project.getMixerChannelForTrack(project.getTracks().get(1));
    }

    private static List<InsertSlot> voxInserts(DawProject project) {
        return voxChannel(project).getInsertSlots();
    }

    /**
     * One second of a reference program — a −12 dBFS 997 Hz sine plus a
     * seeded noise burst, different on each channel — through the strip's
     * inserts from reset (studio finding G13: judge by matched output).
     */
    private static float[][] render(MixerChannel channel, AudioFormat format) {
        int channels = format.channels();
        int frames = (int) format.sampleRate();
        float[][] program = new float[channels][frames];
        for (int c = 0; c < channels; c++) {
            Random noise = new Random(334L + c);
            for (int frame = 0; frame < frames; frame++) {
                double angle = 2.0 * Math.PI * 997.0 * frame / format.sampleRate() + c * Math.PI / 3.0;
                float sample = (float) (Math.pow(10.0, -12.0 / 20.0) * Math.sin(angle));
                if (frame >= frames / 4 && frame < frames / 4 + frames / 20) {
                    sample += (float) (0.25 * (2.0 * noise.nextDouble() - 1.0));
                }
                program[c][frame] = sample;
            }
        }
        channel.prepareEffectsChain(channels, BLOCK_FRAMES);
        EffectsChain chain = channel.getEffectsChain();
        chain.reset();
        float[][] rendered = new float[channels][frames];
        float[][] input = new float[channels][BLOCK_FRAMES];
        float[][] output = new float[channels][BLOCK_FRAMES];
        for (int start = 0; start < frames; start += BLOCK_FRAMES) {
            int block = Math.min(BLOCK_FRAMES, frames - start);
            for (int c = 0; c < channels; c++) {
                System.arraycopy(program[c], start, input[c], 0, block);
            }
            chain.process(input, output, block);
            for (int c = 0; c < channels; c++) {
                System.arraycopy(output[c], 0, rendered[c], start, block);
            }
        }
        return rendered;
    }

    /** The first {@code <insert>} of {@code xml} whose {@code name} is {@code name}. */
    private static Element insertNamed(String xml, String name) throws Exception {
        NodeList inserts = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml))).getElementsByTagName("insert");
        for (int i = 0; i < inserts.getLength(); i++) {
            Element insert = (Element) inserts.item(i);
            if (insert.getAttribute("name").equals(name)) {
                return insert;
            }
        }
        throw new AssertionError("no insert named " + name);
    }

    /** An element as its name, its attributes by name and its child elements, in order — whitespace aside. */
    private static String canonical(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        TreeMap<String, String> sorted = new TreeMap<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            sorted.put(attributes.item(i).getNodeName(), attributes.item(i).getNodeValue());
        }
        List<String> children = new ArrayList<>();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element child) {
                children.add(canonical(child));
            } else if (node.getNodeType() == Node.TEXT_NODE && !node.getNodeValue().isBlank()) {
                children.add("text:" + node.getNodeValue());
            }
        }
        return element.getTagName() + sorted + children;
    }

    private static List<Path> backupsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".bak")).toList();
        }
    }
}
