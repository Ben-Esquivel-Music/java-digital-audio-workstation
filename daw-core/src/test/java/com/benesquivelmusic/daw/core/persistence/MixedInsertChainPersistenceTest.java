package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.ProcessorRegistry;
import com.benesquivelmusic.daw.core.plugin.AudioFormatPluginContext;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginGraph;
import com.benesquivelmusic.daw.core.plugin.DeEsserPlugin;
import com.benesquivelmusic.daw.core.plugin.LoadedPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginIdentity;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.plugin.PluginResolution;
import com.benesquivelmusic.daw.core.plugin.PluginResolver;
import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;
import com.benesquivelmusic.daw.core.plugin.PluginTestJars;
import com.benesquivelmusic.daw.core.preset.ReflectivePresetSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goals — Tests "Mixed-chain round-trip" (story 334, finding G13): chains of
 * built-in, first-party, external-JAR and CLAP inserts save and reopen
 * identical in content and order — the JAR plugins through the real
 * installed-plugin registry, the CLAP plugin through a resolver standing in
 * for story 034's — with every parameter and the CLAP state; and each
 * reopened strip renders the reference program bit-equal to the strip
 * before the save, from the first sample.
 */
class MixedInsertChainPersistenceTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 256);
    private static final AudioFormatPluginContext CONTEXT = new AudioFormatPluginContext(FORMAT);

    @TempDir
    Path tempDir;

    private ProjectManager manager;
    private PluginRegistry registry;

    @BeforeEach
    void createTheManager() {
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    @AfterEach
    void releaseEverything() {
        manager.closeProject().join();
        if (registry != null) {
            registry.disposeAll();
        }
    }

    @Test
    void aChainOfBuiltInJarAndClapInsertsReopensIdenticalInContentAndOrder() throws Exception {
        registry = new PluginRegistry(new InstalledPluginStore(tempDir.resolve("config")
                .resolve(InstalledPluginStore.FILE_NAME)));
        registry.restore().get(10, TimeUnit.SECONDS);
        PluginTestJars.install(registry, tempDir.resolve("Downloads"), "annotated-gain.jar",
                AnnotatedJarTestPlugin.class);
        PluginTestJars.install(registry, tempDir.resolve("Downloads"), "knob-box.jar",
                StoreParameterJarTestPlugin.class);
        PluginResolver plugins = (identity, context) -> identity.kind() == PluginSourceKind.CLAP
                ? clapPlugin(identity, context)
                : registry.resolve(identity, context);

        DawProject original = new DawProject("Song", FORMAT);
        MixerChannel vox = original.getMixerChannelForTrack(original.createAudioTrack("Vox"));
        vox.addInsert(builtIn(InsertEffectType.COMPRESSOR, Map.of("Threshold", -24.0)));
        vox.addInsert(named(plugins, AnnotatedJarTestPlugin.ID, Map.of("Gain", 0.5)));
        vox.addInsert(clap(plugins, new byte[] {(byte) 200, 1, 2, 3}));
        InsertSlot knobs = PluginTestJars.insertOf(plugins.resolve(jar(StoreParameterJarTestPlugin.ID), CONTEXT));
        knobs.applyParameterNow(StoreParameterJarTestPlugin.LEVEL, 0.3);
        knobs.applyParameterNow(StoreParameterJarTestPlugin.DRIVE, 6.5);
        vox.addInsert(knobs);
        InsertSlot deEsser = new BuiltInPluginGraph(ProcessorRegistry.getInstance()).createSlot(new DeEsserPlugin(),
                CONTEXT, original.getMetronome());
        ReflectivePresetSerializer.restore(deEsser.getProcessor(), Map.of("Frequency", 8000.0));
        vox.addInsert(deEsser);
        MixerChannel bass = original.getMixerChannelForTrack(original.createAudioTrack("Bass"));
        bass.addInsert(clap(plugins, new byte[] {64, 9}));
        bass.addInsert(builtIn(InsertEffectType.PARAMETRIC_EQ, Map.of()));
        bass.addInsert(named(plugins, AnnotatedJarTestPlugin.ID, Map.of("Gain", 0.75)));
        bass.setInsertBypassed(1, true);
        List<List<SlotFacts>> savedFacts = List.of(facts(vox), facts(bass));
        List<float[][]> savedRenders = List.of(StripRenders.render(vox, FORMAT), StripRenders.render(bass, FORMAT));

        Path folder = manager.createProject("Song", tempDir.resolve("projects")).projectPath();
        manager.closeProject().join();
        Files.writeString(folder.resolve("project.daw"), new ProjectSerializer().serialize(original));
        manager.setPluginResolver(plugins);
        manager.openProject(folder);
        DawProject reopened = manager.getCurrentDawProject();

        assertThat(manager.getLastUnreadItems()).isEmpty();
        List<MixerChannel> strips = reopened.getMixer().getChannels();
        assertThat(List.of(facts(strips.get(0)), facts(strips.get(1))))
                .as("every slot, in order: kind, identity, flags, parameters and state")
                .isEqualTo(savedFacts);
        assertThat(savedFacts.getFirst()).extracting(SlotFacts::kind)
                .containsExactly("built-in effect COMPRESSOR", "jar", "clap", "jar", "built-in");
        for (int strip = 0; strip < 2; strip++) {
            assertThat(StripRenders.render(strips.get(strip), FORMAT))
                    .as("strip %d renders bit-equal to the strip before the save (G13)", strip)
                    .isDeepEqualTo(savedRenders.get(strip));
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * What a slot holds, as a test compares it before the save and after the
     * reopen.
     *
     * @param kind        {@code built-in effect <TYPE>}, or the plugin's source kind
     * @param pluginId    the plugin's id, empty for a built-in effect
     * @param version     the plugin's version, empty for a built-in effect
     * @param pluginClass the plugin's class name, empty for a built-in effect
     * @param name        the slot's name
     * @param bypassed    the slot's bypass flag
     * @param expensive   the slot's expensive flag
     * @param named       the processor's named parameters, empty when it declares none
     * @param store       the plugin's parameter store values, by id
     * @param pluginValues the values the plugin itself holds for its store parameters
     * @param state       the CLAP plugin's state bytes
     */
    private record SlotFacts(String kind, String pluginId, String version, String pluginClass, String name,
                             boolean bypassed, boolean expensive, Map<String, Double> named,
                             Map<Integer, Double> store, Map<Integer, Double> pluginValues, List<Byte> state) {
    }

    private static List<SlotFacts> facts(MixerChannel channel) {
        List<SlotFacts> facts = new ArrayList<>();
        for (InsertSlot slot : channel.getInsertSlots()) {
            facts.add(factsOf(slot));
        }
        return facts;
    }

    private static SlotFacts factsOf(InsertSlot slot) {
        assertThat(slot.offline()).as("slot %s is working", slot.getName()).isEmpty();
        DawPlugin plugin = slot.getPlugin();
        Map<String, Double> named = ReflectivePresetSerializer.isSupported(slot.getProcessor())
                ? ReflectivePresetSerializer.snapshot(slot.getProcessor())
                : Map.of();
        if (plugin == null) {
            return new SlotFacts("built-in effect " + slot.getEffectType(), "", "", "", slot.getName(),
                    slot.isBypassed(), slot.isExpensive(), named, Map.of(), Map.of(), List.of());
        }
        Map<Integer, Double> store = new LinkedHashMap<>();
        Map<Integer, Double> pluginValues = new LinkedHashMap<>();
        PluginParameterStore parameters = slot.getParameterStore();
        for (int index = 0; index < parameters.parameterCount(); index++) {
            int id = parameters.parameterIdAt(index);
            store.put(id, parameters.value(index));
            if (plugin instanceof StoreParameterJarTestPlugin knobs) {
                pluginValues.put(id, knobs.value(id));
            }
        }
        List<Byte> state = new ArrayList<>();
        if (plugin instanceof ExternalPluginHost host) {
            for (byte b : host.saveState()) {
                state.add(b);
            }
        }
        return new SlotFacts(PluginSourceKind.of(plugin).orElseThrow().token(), plugin.getDescriptor().id(),
                plugin.getDescriptor().version(), plugin.getClass().getName(), slot.getName(), slot.isBypassed(),
                slot.isExpensive(), named, store, pluginValues, state);
    }

    private static InsertSlot builtIn(InsertEffectType type, Map<String, Double> values) {
        InsertSlot slot = InsertEffectFactory.createSlot(type, FORMAT.channels(), FORMAT.sampleRate());
        if (!values.isEmpty()) {
            ReflectivePresetSerializer.restore(slot.getProcessor(), values);
        }
        return slot;
    }

    private static InsertSlot named(PluginResolver plugins, String id, Map<String, Double> values) {
        InsertSlot slot = PluginTestJars.insertOf(plugins.resolve(jar(id), CONTEXT));
        ReflectivePresetSerializer.restore(slot.getProcessor(), values);
        return slot;
    }

    private static InsertSlot clap(PluginResolver plugins, byte[] state) {
        InsertSlot slot = PluginTestJars.insertOf(plugins.resolve(
                new PluginIdentity(PluginSourceKind.CLAP, FakeClapHost.ID, "1.4.4"), CONTEXT));
        ((FakeClapHost) slot.getPlugin()).setState(state);
        return slot;
    }

    /** The CLAP resolver story 034 will provide, standing in: a fresh {@link FakeClapHost} for its id. */
    private static PluginResolution clapPlugin(PluginIdentity identity,
                                               PluginContext context) {
        if (!identity.id().equals(FakeClapHost.ID)) {
            return new PluginResolution.NotInstalled();
        }
        FakeClapHost host = new FakeClapHost();
        host.initialize(context);
        return new PluginResolution.Resolved(new LoadedPlugin(host, host::dispose));
    }

    private static PluginIdentity jar(String id) {
        return new PluginIdentity(PluginSourceKind.JAR, id, "1.0");
    }
}
