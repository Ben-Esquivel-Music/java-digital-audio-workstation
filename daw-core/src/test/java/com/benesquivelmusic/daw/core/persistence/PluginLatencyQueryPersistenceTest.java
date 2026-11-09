package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.PluginDelayCompensation;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OfflineCause;
import com.benesquivelmusic.daw.core.mixer.OfflineInsert;
import com.benesquivelmusic.daw.core.mixer.PassThroughProcessor;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.plugin.PluginResolver;
import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.PluginIdentityKey;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.VOX;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.backupsIn;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.resolving;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plugin whose latency query throws as its insert opens (story 334,
 * ruling-4 Q8): a plugin that cannot state its latency cannot be compensated,
 * so its insert opens offline as failed to load — a pass-through at zero
 * latency at its place, counted once, its plugin disposed once, written back
 * as saved — and the open, and the delay compensation it runs, complete. The
 * query is the load's last step: it runs after the saved parameters, so a
 * plugin whose latency a saved parameter sets opens working.
 */
class PluginLatencyQueryPersistenceTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 256);

    /** The latency of the {@link FixedLatencyPlugin}. */
    private static final int LATENCY = 64;

    private static final String GAIN_DOWN = "<insert bypassed=\"false\" effect-type=\"GAIN_STAGING\""
            + " expensive=\"false\" name=\"Gain Staging\"><parameter id=\"0\" value=\"-6.0\"/></insert>";
    private static final String GAIN_UP = "<insert bypassed=\"false\" effect-type=\"GAIN_STAGING\""
            + " expensive=\"false\" name=\"Gain Staging\"><parameter id=\"0\" value=\"3.0\"/></insert>";
    private static final String COMP_X = "<insert bypassed=\"false\" expensive=\"false\" name=\"Comp X\""
            + " plugin-id=\"com.example.comp\" plugin-source=\"jar\" plugin-version=\"1.2.0\"/>";
    private static final String DELAY_LINE = "<insert bypassed=\"false\" expensive=\"false\" name=\"Delay Line\""
            + " plugin-id=\"" + FixedLatencyPlugin.ID + "\" plugin-source=\"jar\" plugin-version=\"1.0\"/>";

    /** Ruling-4 Q8: the E6 failed-to-load form, naming the exception and the call. */
    private static final String DETAIL = "JAR plugin 'com.example.comp' version '1.2.0' failed to load"
            + " (NullPointerException when asked for its latency)";

    @TempDir
    Path tempDir;

    private ProjectManager manager;

    @BeforeEach
    void createTheManager() {
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    @AfterEach
    void releaseTheManager() {
        manager.closeProject().join();
    }

    /** Ruling-4 Q8 (1). */
    @Test
    void aPluginWhoseLatencyQueryThrowsOpensOfflineInPlaceAndIsWrittenBackAsSaved() throws Exception {
        List<LookAheadJarTestPlugin> made = new CopyOnWriteArrayList<>();
        String xml = file("", GAIN_DOWN + COMP_X + GAIN_UP);

        Opened opened = open(xml, resolver(made));

        List<InsertSlot> slots = vox(opened.project()).getInsertSlots();
        assertThat(slots).extracting(InsertSlot::getName).containsExactly("Gain Staging", "Comp X", "Gain Staging");
        InsertSlot comp = slots.get(1);
        assertThat(comp.offline()).map(OfflineInsert::cause).contains(new OfflineCause.FailedToLoad(
                PluginSourceKind.JAR, LookAheadJarTestPlugin.ID, LookAheadJarTestPlugin.VERSION,
                "NullPointerException when asked for its latency"));
        assertThat(comp.getProcessor()).as("a pass-through").isInstanceOf(PassThroughProcessor.class);
        assertThat(comp.getPlugin()).isNull();
        assertThat(slots.get(0).offline()).isEmpty();
        assertThat(slots.get(2).offline()).isEmpty();
        assertThat(made).singleElement().extracting(LookAheadJarTestPlugin::disposals)
                .as("the plugin is disposed exactly once").isEqualTo(1);
        assertThat(opened.warnings()).containsExactly("Track 2 'Vox': insert 2 'Comp X' not loaded (bypassed,"
                + " kept as saved) — " + DETAIL);
        assertThat(opened.deserializer().getUnreadItems()).as("counted once under the inserts not loaded")
                .containsExactly(UnreadProjectItem.atInsert(UnreadProjectItem.Kind.INSERT_NOT_LOADED, VOX, 1,
                        "Comp X", DETAIL));
        assertThat(opened.deserializer().losesSavedContent()).isFalse();

        Path directory = manager.createProject("Song", tempDir.resolve("written")).projectPath();
        manager.closeProject().join();
        Files.writeString(directory.resolve("project.daw"), xml);
        manager.setPluginResolver(resolver(new CopyOnWriteArrayList<>()));
        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());

        assertThat(backupsIn(directory)).as("nothing the save drops, so no backup").isEmpty();
        assertThat(Files.readString(directory.resolve("project.daw"))).as("written back equal").contains(COMP_X);
    }

    /** Ruling-4 Q8 (2): the compensation completes, and the offline strip is delayed like any 0-latency strip (E1). */
    @Test
    void delayCompensationCompletesAndDelaysTheOfflineStripByTheOtherStripsLatency() throws Exception {
        DawProject project = open(file(DELAY_LINE, GAIN_DOWN + COMP_X + GAIN_UP),
                resolver(new CopyOnWriteArrayList<>())).project();

        PluginDelayCompensation compensation = project.getMixer().getDelayCompensation();
        assertThat(vox(project).getInsertSlots().get(1).offline()).isPresent();
        assertThat(compensation.getChannelLatencySamples(0)).as("the Drums strip's plugin").isEqualTo(LATENCY);
        assertThat(compensation.getChannelLatencySamples(1)).as("the offline strip reports 0").isZero();
        assertThat(compensation.getChannelCompensationSamples(1)).as("delayed by the other strip's latency")
                .isEqualTo(LATENCY);
        assertThat(compensation.getChannelCompensationSamples(0)).isZero();
    }

    /** Ruling-4 Q8 (3): the offline insert is audibly bypassed — the chain renders bit-equal without it (E1). */
    @Test
    void theChainRendersBitEqualToItsWorkingInsertsAlone() throws Exception {
        DawProject withTheOfflineInsert = open(file("", GAIN_DOWN + COMP_X + GAIN_UP),
                resolver(new CopyOnWriteArrayList<>())).project();
        DawProject withoutIt = open(file("", GAIN_DOWN + GAIN_UP), PluginResolver.NONE).project();

        assertThat(StripRenders.render(vox(withTheOfflineInsert), FORMAT))
                .isDeepEqualTo(StripRenders.render(vox(withoutIt), FORMAT));
    }

    /**
     * Ruling-4 Q8 (4): the query is asked after the saved parameters, so a
     * plugin whose latency its saved parameter sets opens working and reports
     * that latency.
     */
    @Test
    void theLatencyIsAskedAfterTheSavedParametersAreApplied() throws Exception {
        List<LookAheadJarTestPlugin> made = new CopyOnWriteArrayList<>();
        String compWithLookAhead = COMP_X.replace("/>", "><parameter id=\"" + LookAheadJarTestPlugin.LOOK_AHEAD
                + "\" value=\"128.0\"/></insert>");

        DawProject project = open(file("", compWithLookAhead), resolver(made)).project();

        InsertSlot comp = vox(project).getInsertSlots().getFirst();
        assertThat(comp.offline()).as("working").isEmpty();
        assertThat(comp.getPlugin()).isSameAs(made.getFirst());
        assertThat(comp.getProcessor().getLatencySamples()).isEqualTo(128);
        assertThat(project.getMixer().getDelayCompensation().getChannelLatencySamples(1)).isEqualTo(128);
        assertThat(made.getFirst().disposals()).isZero();
    }

    /** Ruling-4 Q8 (6): a plugin whose latency query works opens working, and the compensation sees its latency. */
    @Test
    void aPluginWhoseLatencyQueryWorksOpensWorkingAndIsCompensated() throws Exception {
        DawProject project = open(file("", DELAY_LINE), resolver(new CopyOnWriteArrayList<>())).project();

        InsertSlot line = vox(project).getInsertSlots().getFirst();
        assertThat(line.offline()).as("working").isEmpty();
        assertThat(line.getPlugin()).isInstanceOf(FixedLatencyPlugin.class);
        assertThat(project.getMixer().getDelayCompensation().getChannelLatencySamples(1)).isEqualTo(LATENCY);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A project read from a file, the deserializer that read it, and the WARNINGs it logged. */
    private record Opened(DawProject project, ProjectDeserializer deserializer, List<String> warnings) {
    }

    private static Opened open(String xml, PluginResolver resolver) throws IOException {
        ProjectDeserializer deserializer = new ProjectDeserializer();
        deserializer.setPluginResolver(resolver);
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            DawProject project = deserializer.deserialize(xml);
            return new Opened(project, deserializer, log.at(Level.WARNING));
        }
    }

    /** Resolves Comp X to a fresh {@link LookAheadJarTestPlugin}, added to {@code made}, and the delay line. */
    private static PluginResolver resolver(List<LookAheadJarTestPlugin> made) {
        return resolving(Map.of(
                new PluginIdentityKey(PluginSourceKind.JAR, LookAheadJarTestPlugin.ID), () -> {
                    LookAheadJarTestPlugin plugin = new LookAheadJarTestPlugin();
                    made.add(plugin);
                    return plugin;
                },
                new PluginIdentityKey(PluginSourceKind.JAR, FixedLatencyPlugin.ID), FixedLatencyPlugin::new));
    }

    /** The Vox strip. */
    private static MixerChannel vox(DawProject project) {
        return project.getMixer().getChannels().get(1);
    }

    /** A stereo 48 kHz project with the Drums and Vox tracks, whose strips hold the given inserts. */
    private static String file(String drumsInserts, String voxInserts) {
        return """
                <daw-project version="%d">
                  <metadata><name>Song</name></metadata>
                  <audio-format sample-rate="48000.0" channels="2" bit-depth="24" buffer-size="256"/>
                  <tracks>
                    <track id="00000000-0000-0000-0000-0000000000a1" name="Drums" type="AUDIO"/>
                    <track id="00000000-0000-0000-0000-0000000000a2" name="Vox" type="AUDIO"/>
                  </tracks>
                  <mixer>
                    <master volume="1.0"><inserts></inserts></master>
                    <channels>
                      <channel volume="1.0"><inserts>%s</inserts></channel>
                      <channel volume="1.0"><inserts>%s</inserts></channel>
                    </channels>
                    <return-buses/>
                  </mixer>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, drumsInserts, voxInserts);
    }

    /** A JAR plugin that reports a fixed latency of {@link #LATENCY} samples and passes audio through. */
    private static final class FixedLatencyPlugin implements DawPlugin {

        static final String ID = "com.example.delayline";

        private AudioProcessor processor;

        @Override
        public PluginDescriptor getDescriptor() {
            return new PluginDescriptor(ID, "Delay Line", "1.0", "Example Audio", PluginType.EFFECT);
        }

        @Override
        public void initialize(PluginContext context) {
            int channels = context.getAudioChannels();
            processor = new AudioProcessor() {
                @Override
                public void process(float[][] input, float[][] output, int frames) {
                    for (int channel = 0; channel < output.length; channel++) {
                        System.arraycopy(input[channel], 0, output[channel], 0, frames);
                    }
                }

                @Override
                public void reset() {
                }

                @Override
                public int getInputChannelCount() {
                    return channels;
                }

                @Override
                public int getOutputChannelCount() {
                    return channels;
                }

                @Override
                public int getLatencySamples() {
                    return LATENCY;
                }
            };
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void dispose() {
        }

        @Override
        public Optional<AudioProcessor> asAudioProcessor() {
            return Optional.of(processor);
        }
    }
}
